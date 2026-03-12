package org.jetbrains.plugins.template.actions;

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ExportClassFromSvnChangeAction extends AnAction {
    private static final String WEB_INF_CLASSES = "WEB-INF/classes/";
    private static final DateTimeFormatter EXPORT_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }

        List<VirtualFile> files = collectSelectedFiles(e);
        e.getPresentation().setEnabledAndVisible(!files.isEmpty());
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        List<VirtualFile> files = collectSelectedFiles(e);
        if (files.isEmpty()) {
            notify(project, "No files selected.");
            return;
        }

        VirtualFile targetBaseDir = chooseTargetDirectory(project);
        if (targetBaseDir == null) {
            return;
        }

        VirtualFile exportRootDir = createExportRootDirectory(project, targetBaseDir);
        if (exportRootDir == null) {
            notify(project, "Failed to create export directory.");
            return;
        }

        ExportPlan plan = ReadAction.compute(() -> buildExportPlan(project, files));
        int classCopied = copyEntries(plan.classEntries, exportRootDir);
        int fileCopied = copyEntries(plan.regularEntries, exportRootDir);

        if (classCopied == 0 && fileCopied == 0) {
            notify(project, "No files were exported. Build the project first if exporting class files.");
            return;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Exported ").append(classCopied).append(" class file(s) and ")
                .append(fileCopied).append(" file(s) to ").append(exportRootDir.getPath());
        if (!plan.missingSources.isEmpty()) {
            summary.append(". ").append(plan.missingSources.size())
                    .append(" source file(s) had no matching export path.");
        }

        notify(project, summary.toString());
        revealInExplorer(exportRootDir);
    }

    private List<VirtualFile> collectSelectedFiles(AnActionEvent e) {
        Set<VirtualFile> results = new LinkedHashSet<>();
        Set<VirtualFile> changedFiles = new LinkedHashSet<>();

        var changes = e.getData(VcsDataKeys.CHANGES);
        if (changes != null) {
            for (var change : changes) {
                var filePath = change.getAfterRevision() != null
                        ? change.getAfterRevision().getFile()
                        : change.getBeforeRevision() != null ? change.getBeforeRevision().getFile() : null;
                VirtualFile file = resolveVirtualFile(filePath == null ? null : filePath.getPath(), filePath == null ? null : filePath.getVirtualFile());
                if (file != null && !file.isDirectory()) {
                    changedFiles.add(file);
                }
            }
        }

        VirtualFile[] selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (selected != null && selected.length > 0) {
            if (!changedFiles.isEmpty()) {
                for (VirtualFile selection : selected) {
                    addChangedFilesUnderSelection(results, changedFiles, selection);
                }
            } else {
                for (VirtualFile selection : selected) {
                    addFileOrChildren(results, selection);
                }
            }
        } else {
            results.addAll(changedFiles);
        }

        return new ArrayList<>(results);
    }

    private VirtualFile resolveVirtualFile(String path, VirtualFile existing) {
        if (existing != null) {
            return existing;
        }
        if (path == null) {
            return null;
        }
        return LocalFileSystem.getInstance().findFileByPath(path);
    }

    private void addFileOrChildren(Set<VirtualFile> results, VirtualFile file) {
        if (file.isDirectory()) {
            VfsUtilCore.iterateChildrenRecursively(file, null, child -> {
                if (!child.isDirectory()) {
                    results.add(child);
                }
                return true;
            });
            return;
        }
        results.add(file);
    }

    private void addChangedFilesUnderSelection(Set<VirtualFile> results, Set<VirtualFile> changedFiles, VirtualFile selection) {
        if (selection.isDirectory()) {
            for (VirtualFile file : changedFiles) {
                if (VfsUtilCore.isAncestor(selection, file, false)) {
                    results.add(file);
                }
            }
            return;
        }
        if (changedFiles.contains(selection)) {
            results.add(selection);
        }
    }

    private VirtualFile chooseTargetDirectory(Project project) {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Select Export Directory");
        return FileChooser.chooseFile(descriptor, project, null);
    }

    private VirtualFile createExportRootDirectory(Project project, VirtualFile targetBaseDir) {
        String directoryName = sanitizeFileName(project.getName()) + "_" + LocalDateTime.now().format(EXPORT_DIR_FORMATTER);
        Path exportRootPath = targetBaseDir.toNioPath().resolve(directoryName);
        try {
            Files.createDirectories(exportRootPath);
        } catch (Exception ignored) {
            return null;
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(exportRootPath);
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private ExportPlan buildExportPlan(Project project, List<VirtualFile> files) {
        List<ExportEntry> classEntries = new ArrayList<>();
        List<ExportEntry> regularEntries = new ArrayList<>();
        List<VirtualFile> missingSources = new ArrayList<>();

        for (VirtualFile file : files) {
            if (isJavaLike(file)) {
                List<ExportEntry> entries = buildClassEntries(project, file);
                if (entries.isEmpty()) {
                    missingSources.add(file);
                } else {
                    classEntries.addAll(entries);
                }
            } else {
                ExportEntry entry = buildRegularEntry(project, file);
                if (entry == null) {
                    missingSources.add(file);
                } else {
                    regularEntries.add(entry);
                }
            }
        }

        return new ExportPlan(classEntries, regularEntries, missingSources);
    }

    private List<ExportEntry> buildClassEntries(Project project, VirtualFile file) {
        VirtualFile outputRoot = findOutputRoot(project, file);
        if (outputRoot == null) {
            return List.of();
        }

        String exportPrefix = hasWebLayout(project, file) ? WEB_INF_CLASSES : "";
        List<ExportEntry> entries = new ArrayList<>();
        for (String relativeClassPath : buildClassRelativePaths(project, file)) {
            VirtualFile classFile = outputRoot.findFileByRelativePath(relativeClassPath);
            if (classFile != null && !classFile.isDirectory()) {
                entries.add(new ExportEntry(classFile, exportPrefix + relativeClassPath));
            }
        }
        return entries;
    }

    private ExportEntry buildRegularEntry(Project project, VirtualFile file) {
        VirtualFile webRoot = findWebResourceRoot(project, file);
        if (webRoot != null) {
            String relativePath = VfsUtilCore.getRelativePath(file, webRoot);
            if (relativePath == null || relativePath.isBlank()) {
                return null;
            }
            return new ExportEntry(file, relativePath);
        }

        VirtualFile sourceRoot = ProjectFileIndex.getInstance(project).getSourceRootForFile(file);
        if (sourceRoot == null) {
            return null;
        }

        String relativePath = VfsUtilCore.getRelativePath(file, sourceRoot);
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }

        VirtualFile outputRoot = findOutputRoot(project, file);
        VirtualFile compiledFile = outputRoot != null ? outputRoot.findFileByRelativePath(relativePath) : null;
        VirtualFile fileToCopy = compiledFile != null && !compiledFile.isDirectory() ? compiledFile : file;
        String exportPath = hasWebLayout(project, file) ? WEB_INF_CLASSES + relativePath : relativePath;
        return new ExportEntry(fileToCopy, exportPath);
    }

    private boolean isJavaLike(VirtualFile file) {
        String extension = file.getExtension();
        if (extension == null) {
            return false;
        }
        String lower = extension.toLowerCase();
        return "java".equals(lower) || "kt".equals(lower);
    }

    private VirtualFile findOutputRoot(Project project, VirtualFile file) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        var module = index.getModuleForFile(file);
        if (module == null) {
            return null;
        }
        CompilerModuleExtension extension = CompilerModuleExtension.getInstance(module);
        if (extension == null) {
            return null;
        }
        boolean isTest = index.isInTestSourceContent(file);
        return isTest ? extension.getCompilerOutputPathForTests() : extension.getCompilerOutputPath();
    }

    private boolean hasWebLayout(Project project, VirtualFile file) {
        return findWebResourceRoot(project, file) != null || findModuleWebRoot(project, file) != null;
    }

    private VirtualFile findModuleWebRoot(Project project, VirtualFile file) {
        VirtualFile contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file);
        if (contentRoot == null) {
            return null;
        }

        String[] candidates = {
                "src/main/webapp",
                "src/main/web",
                "src/webapp",
                "webapp",
                "web",
                "WebRoot"
        };
        for (String candidate : candidates) {
            VirtualFile webRoot = contentRoot.findFileByRelativePath(candidate);
            if (webRoot != null && webRoot.isDirectory()) {
                return webRoot;
            }
        }
        return null;
    }

    private VirtualFile findWebResourceRoot(Project project, VirtualFile file) {
        VirtualFile current = file.isDirectory() ? file : file.getParent();
        VirtualFile contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file);
        while (current != null) {
            String name = current.getName();
            if ("webapp".equalsIgnoreCase(name)
                    || "web".equalsIgnoreCase(name)
                    || "WebRoot".equalsIgnoreCase(name)) {
                return current;
            }
            if (current.equals(contentRoot)) {
                break;
            }
            current = current.getParent();
        }
        return null;
    }

    private List<String> buildClassRelativePaths(Project project, VirtualFile file) {
        String extension = file.getExtension();
        if (extension == null) {
            return List.of();
        }

        String packagePath = resolvePackagePath(project, file);
        if ("java".equalsIgnoreCase(extension)) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            List<String> classNames = psiFile instanceof PsiJavaFile
                    ? collectJavaClassNames((PsiJavaFile) psiFile)
                    : List.of(file.getNameWithoutExtension());

            List<String> results = new ArrayList<>();
            for (String className : classNames) {
                results.add(packagePath.isBlank() ? className + ".class" : packagePath + "/" + className + ".class");
            }
            return results;
        }

        if ("kt".equalsIgnoreCase(extension)) {
            String baseName = file.getNameWithoutExtension();
            List<String> candidates = List.of(baseName + ".class", baseName + "Kt.class");
            List<String> results = new ArrayList<>();
            for (String candidate : candidates) {
                results.add(packagePath.isBlank() ? candidate : packagePath + "/" + candidate);
            }
            return results;
        }

        return List.of();
    }

    private String resolvePackagePath(Project project, VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile instanceof PsiJavaFile psiJavaFile && !psiJavaFile.getPackageName().isBlank()) {
            return psiJavaFile.getPackageName().replace('.', '/');
        }

        VirtualFile sourceRoot = ProjectFileIndex.getInstance(project).getSourceRootForFile(file);
        String relativeDir = sourceRoot != null ? VfsUtilCore.getRelativePath(file.getParent(), sourceRoot) : null;
        return relativeDir != null ? relativeDir : "";
    }

    private List<String> collectJavaClassNames(PsiJavaFile psiFile) {
        List<String> result = new ArrayList<>();
        java.util.Collection<PsiClass> classes = PsiTreeUtil.collectElementsOfType(psiFile, PsiClass.class);
        for (PsiClass psiClass : classes) {
            if (psiClass instanceof PsiAnonymousClass || PsiUtil.isLocalClass(psiClass)) {
                continue;
            }
            String className = buildClassFileBaseName(psiClass);
            if (className != null) {
                result.add(className);
            }
        }

        if (result.isEmpty() && psiFile.getClasses().length > 0 && psiFile.getClasses()[0].getName() != null) {
            result.add(psiFile.getClasses()[0].getName());
        }
        return result;
    }

    private String buildClassFileBaseName(PsiClass psiClass) {
        String name = psiClass.getName();
        if (name == null) {
            return null;
        }
        PsiClass outer = psiClass.getContainingClass();
        if (outer == null) {
            return name;
        }
        String outerName = buildClassFileBaseName(outer);
        return outerName == null ? null : outerName + "$" + name;
    }

    private int copyEntries(List<ExportEntry> entries, VirtualFile targetDir) {
        int copied = 0;
        Path targetRootPath = Path.of(targetDir.getPath());
        for (ExportEntry entry : entries) {
            Path destination = targetRootPath.resolve(entry.exportRelativePath.replace('/', java.io.File.separatorChar));
            try {
                Path parent = destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(entry.sourceFile.toNioPath(), destination, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (Exception ignored) {
                // Keep processing remaining files.
            }
        }
        return copied;
    }

    private void revealInExplorer(VirtualFile targetDir) {
        try {
            RevealFileAction.openDirectory(targetDir.toNioPath());
        } catch (Exception ignored) {
            // Ignore failures to open file explorer.
        }
    }

    private void notify(Project project, @NlsContexts.NotificationContent String message) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Export Class Files")
                .createNotification(message, NotificationType.INFORMATION)
                .notify(project);
    }

    private static class ExportPlan {
        private final List<ExportEntry> classEntries;
        private final List<ExportEntry> regularEntries;
        private final List<VirtualFile> missingSources;

        private ExportPlan(List<ExportEntry> classEntries, List<ExportEntry> regularEntries, List<VirtualFile> missingSources) {
            this.classEntries = classEntries;
            this.regularEntries = regularEntries;
            this.missingSources = missingSources;
        }
    }

    private static class ExportEntry {
        private final VirtualFile sourceFile;
        private final String exportRelativePath;

        private ExportEntry(VirtualFile sourceFile, String exportRelativePath) {
            this.sourceFile = sourceFile;
            this.exportRelativePath = exportRelativePath;
        }
    }
}



