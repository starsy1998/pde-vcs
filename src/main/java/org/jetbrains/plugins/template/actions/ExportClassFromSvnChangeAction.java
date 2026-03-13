package org.jetbrains.plugins.template.actions;

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
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

/**
 * 从 IDEA 的本地变更视图或项目视图中收集用户选中的文件，
 * 并按“可部署的 Web 包目录结构”导出到指定目录。
 *
 * 规则大致分为两类：
 * 1. Java/Kotlin 源文件：导出其编译后的 class 文件。
 * 2. 前端资源、配置文件等普通文件：按 Web 目录结构导出原文件或编译输出中的资源副本。
 */
public class ExportClassFromSvnChangeAction extends AnAction {
    /**
     * Web 项目中 class 的标准部署目录前缀。
     */
    private static final String WEB_INF_CLASSES = "WEB-INF/classes/";

    /**
     * 导出目录名中的时间格式，例如：20260313_153000。
     */
    private static final DateTimeFormatter EXPORT_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * 指定 action 的更新线程。
     *
     * 这里返回后台线程，避免在 UI 线程中做文件和 VCS 数据判断。
     */
    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * 控制右键菜单是否显示。
     *
     * 只要当前上下文里能收集到至少一个可处理文件，就显示这个动作。
     */
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

    /**
     * 导出动作的主入口。
     *
     * 整体流程：
     * 1. 收集用户选中的文件。
     * 2. 让用户选择导出基目录。
     * 3. 在基目录下创建“项目名+时间戳”的新根目录。
     * 4. 在读锁中分析每个文件应该导出成什么。
     * 5. 执行复制，并在完成后打开目标目录。
     */
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

        // 这里会访问 PSI 和项目索引，所以放在读锁里执行更安全。
        ExportPlan plan = ApplicationManager.getApplication()
                .runReadAction((com.intellij.openapi.util.Computable<ExportPlan>) () -> buildExportPlan(project, files));
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

    /**
     * 从当前上下文中收集需要导出的文件。
     *
     * 这里同时兼容两类来源：
     * 1. `VcsDataKeys.CHANGES`：适合从变更视图中取“真正发生变化的文件”。
     * 2. `CommonDataKeys.VIRTUAL_FILE_ARRAY`：适合项目视图里的普通文件/目录选择。
     *
     * 如果当前上下文同时提供了变更集合和目录选择，目录会被限定到“该目录下的变更文件”，
     * 不会把目录里所有文件都导出来。
     */
    private List<VirtualFile> collectSelectedFiles(AnActionEvent e) {
        Set<VirtualFile> results = new LinkedHashSet<>();
        Set<VirtualFile> changedFiles = new LinkedHashSet<>();

        // 优先从 VCS 变更对象里取文件，这样选中目录时只会导出本次变更真正涉及的文件。
        var changes = e.getData(VcsDataKeys.CHANGES);
        if (changes != null) {
            for (var change : changes) {
                var filePath = change.getAfterRevision() != null
                        ? change.getAfterRevision().getFile()
                        : change.getBeforeRevision() != null ? change.getBeforeRevision().getFile() : null;
                VirtualFile file = resolveVirtualFile(
                        filePath == null ? null : filePath.getPath(),
                        filePath == null ? null : filePath.getVirtualFile()
                );
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

    /**
     * 把 VCS 提供的文件路径转换成可操作的 VirtualFile。
     *
     * 有些场景下 VCS 已经直接给了 VirtualFile，优先复用；
     * 如果没有，再根据路径从本地文件系统里查找。
     */
    private VirtualFile resolveVirtualFile(String path, VirtualFile existing) {
        if (existing != null) {
            return existing;
        }
        if (path == null) {
            return null;
        }
        return LocalFileSystem.getInstance().findFileByPath(path);
    }

    /**
     * 把一个目录递归展开成所有文件。
     *
     * 这是项目视图下的兜底逻辑：如果用户直接在项目树上选目录，就把目录下全部文件加入导出集合。
     */
    private void addFileOrChildren(Set<VirtualFile> results, VirtualFile file) {
        if (file.isDirectory()) {
            // 在项目视图里，如果直接选中目录，就递归展开目录下的所有文件。
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

    /**
     * 只把“本次变更里、同时位于选中范围内”的文件加入结果。
     *
     * 这个方法是为了配合 Changes 视图或类似场景：
     * 用户可能选了一个目录，但真正需要导出的只应该是该目录下的变更文件，而不是全部子文件。
     */
    private void addChangedFilesUnderSelection(Set<VirtualFile> results, Set<VirtualFile> changedFiles, VirtualFile selection) {
        if (selection.isDirectory()) {
            // 在变更视图里选中目录时，只保留该目录下且确实属于本次变更的文件。
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

    /**
     * 弹出目录选择框，让用户选择导出的基目录。
     *
     * 注意：这里返回的是“基目录”，真正导出时还会在其下面创建一个新的时间戳子目录。
     */
    private VirtualFile chooseTargetDirectory(Project project) {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Select Export Directory");
        return FileChooser.chooseFile(descriptor, project, null);
    }

    /**
     * 在用户选中的基目录下创建本次导出的根目录。
     *
     * 目录名格式：项目名_时间戳
     * 例如：MyProject_20260313_154500
     */
    private VirtualFile createExportRootDirectory(Project project, VirtualFile targetBaseDir) {
        // 每次导出都创建一个新的根目录，避免覆盖之前的导出结果。
        String directoryName = sanitizeFileName(project.getName()) + "_" + LocalDateTime.now().format(EXPORT_DIR_FORMATTER);
        Path exportRootPath = targetBaseDir.toNioPath().resolve(directoryName);
        try {
            Files.createDirectories(exportRootPath);
        } catch (Exception ignored) {
            return null;
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(exportRootPath);
    }

    /**
     * 清理文件名中的非法字符，避免项目名直接作为目录名时创建失败。
     */
    private String sanitizeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * 为所有待导出文件构建导出计划。
     *
     * 这里不直接复制文件，而是先把“应该导出什么文件、导到什么相对路径”计算出来，
     * 这样后续复制阶段会更简单，也更方便统一统计未命中的文件。
     */
    private ExportPlan buildExportPlan(Project project, List<VirtualFile> files) {
        List<ExportEntry> classEntries = new ArrayList<>();
        List<ExportEntry> regularEntries = new ArrayList<>();
        List<VirtualFile> missingSources = new ArrayList<>();

        for (VirtualFile file : files) {
            // Java/Kotlin 文件导出编译后的 class，其他文件按资源文件导出。
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

    /**
     * 为 Java/Kotlin 文件生成 class 导出项。
     *
     * 关键点：
     * 1. 先找到模块输出目录。
     * 2. 推导该源文件对应的一个或多个 class 相对路径。
     * 3. 如果项目是 Web 结构，则把导出路径前缀补成 WEB-INF/classes/。
     */
    private List<ExportEntry> buildClassEntries(Project project, VirtualFile file) {
        VirtualFile outputRoot = findOutputRoot(project, file);
        if (outputRoot == null) {
            return List.of();
        }

        // Web 工程里的 class 需要放到 WEB-INF/classes 下，普通模块则保持原始输出结构。
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

    /**
     * 为普通资源文件生成导出项。
     *
     * 规则如下：
     * 1. 如果文件位于 webapp/WebRoot 等站点根目录下，则保持站点目录结构导出。
     * 2. 如果文件位于 source root 下，则按 source root 的相对路径导出。
     * 3. 对于 source root 资源，若编译输出目录已有对应资源副本，优先导出编译后的副本。
     * 4. 如果整个模块属于 Web 工程，则 source root 资源会被导到 WEB-INF/classes 下。
     */
    private ExportEntry buildRegularEntry(Project project, VirtualFile file) {
        VirtualFile webRoot = findWebResourceRoot(project, file);
        if (webRoot != null) {
            // webapp/WebRoot 下的文件直接保持站点目录结构，不再额外套一层 classes。
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
        // 如果编译输出目录里已经生成了对应资源文件，优先导出编译后的副本。
        VirtualFile compiledFile = outputRoot != null ? outputRoot.findFileByRelativePath(relativePath) : null;
        VirtualFile fileToCopy = compiledFile != null && !compiledFile.isDirectory() ? compiledFile : file;
        String exportPath = hasWebLayout(project, file) ? WEB_INF_CLASSES + relativePath : relativePath;
        return new ExportEntry(fileToCopy, exportPath);
    }

    /**
     * 判断当前文件是否应该按“导出 class”的路径处理。
     */
    private boolean isJavaLike(VirtualFile file) {
        String extension = file.getExtension();
        if (extension == null) {
            return false;
        }
        String lower = extension.toLowerCase();
        return "java".equals(lower) || "kt".equals(lower);
    }

    /**
     * 查找文件所属模块的编译输出目录。
     *
     * 如果文件属于测试源码，则返回测试输出目录；
     * 否则返回主源码输出目录。
     */
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

    /**
     * 判断当前文件是否处于 Web 工程布局中。
     *
     * 只要能识别到明确的 Web 根目录，就认为该文件需要按 Web 包结构导出。
     */
    private boolean hasWebLayout(Project project, VirtualFile file) {
        return findWebResourceRoot(project, file) != null || findModuleWebRoot(project, file) != null;
    }

    /**
     * 在模块内容根目录下查找常见的 Web 根路径。
     *
     * 这个方法主要用于那些当前文件不在 webapp 下、但整个模块本身是 Web 工程的情况，
     * 比如 Java class 或 resources 仍然应该进入 WEB-INF/classes。
     */
    private VirtualFile findModuleWebRoot(Project project, VirtualFile file) {
        VirtualFile contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file);
        if (contentRoot == null) {
            return null;
        }

        // 兼容常见的 Java Web 工程目录名称。
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

    /**
     * 从当前文件向上回溯，查找它直接所在的 Web 资源根目录。
     *
     * 这个方法用于识别前端文件、页面文件、WEB-INF 下配置等是否位于真正的站点根目录下。
     */
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

    /**
     * 根据源文件推导其对应的 class 相对路径列表。
     *
     * Java 文件可能生成多个 class：
     * - 主类
     * - 内部类
     *
     * Kotlin 这里采用常见命名规则做简单推断，覆盖多数普通文件场景。
     */
    private List<String> buildClassRelativePaths(Project project, VirtualFile file) {
        String extension = file.getExtension();
        if (extension == null) {
            return List.of();
        }

        String packagePath = resolvePackagePath(project, file);
        if ("java".equalsIgnoreCase(extension)) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            // 一个 Java 文件可能因为内部类而生成多个 class 文件。
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
            // Kotlin 这里按最常见的编译产物命名规则来推断输出文件名。
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

    /**
     * 解析源码文件对应的包路径。
     *
     * 对 Java 文件优先读 package 声明；
     * 如果读不到，则回退到 source root 下的目录相对路径。
     */
    private String resolvePackagePath(Project project, VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile instanceof PsiJavaFile psiJavaFile && !psiJavaFile.getPackageName().isBlank()) {
            return psiJavaFile.getPackageName().replace('.', '/');
        }

        VirtualFile sourceRoot = ProjectFileIndex.getInstance(project).getSourceRootForFile(file);
        String relativeDir = sourceRoot != null ? VfsUtilCore.getRelativePath(file.getParent(), sourceRoot) : null;
        return relativeDir != null ? relativeDir : "";
    }

    /**
     * 从一个 Java 文件里提取所有需要导出的 class 基名。
     *
     * 这里会过滤匿名类和局部类，只保留真正有稳定 class 文件名的类。
     */
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

    /**
     * 递归生成 class 文件名。
     *
     * 例如：
     * - 外部类：Outer
     * - 内部类：Outer$Inner
     */
    private String buildClassFileBaseName(PsiClass psiClass) {
        String name = psiClass.getName();
        if (name == null) {
            return null;
        }
        PsiClass outer = psiClass.getContainingClass();
        if (outer == null) {
            return name;
        }
        // 内部类编译后通常会变成 Outer$Inner.class 这种命名形式。
        String outerName = buildClassFileBaseName(outer);
        return outerName == null ? null : outerName + "$" + name;
    }

    /**
     * 执行真正的复制动作。
     *
     * 这里不区分 class 和资源，统一按照 ExportEntry 描述的“源文件 + 导出相对路径”来复制。
     */
    private int copyEntries(List<ExportEntry> entries, VirtualFile targetDir) {
        int copied = 0;
        Path targetRootPath = Path.of(targetDir.getPath());
        for (ExportEntry entry : entries) {
            // 导出路径内部统一用 / 表示，真正落盘时再转换成当前系统的分隔符。
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

    /**
     * 导出完成后在系统文件管理器中打开目标目录。
     */
    private void revealInExplorer(VirtualFile targetDir) {
        try {
            RevealFileAction.openDirectory(targetDir.toNioPath());
        } catch (Exception ignored) {
            // Ignore failures to open file explorer.
        }
    }

    /**
     * 统一弹出插件通知。
     */
    private void notify(Project project, @NlsContexts.NotificationContent String message) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Export Class Files")
                .createNotification(message, NotificationType.INFORMATION)
                .notify(project);
    }

    /**
     * 一次导出动作的分析结果。
     *
     * - classEntries：需要导出的 class 文件
     * - regularEntries：需要导出的普通资源文件
     * - missingSources：没有成功匹配到导出目标的源文件
     */
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

    /**
     * 描述一个最终要复制的导出项。
     *
     * sourceFile 是实际复制来源；
     * exportRelativePath 是相对于本次导出根目录的目标路径。
     */
    private static class ExportEntry {
        private final VirtualFile sourceFile;
        private final String exportRelativePath;

        private ExportEntry(VirtualFile sourceFile, String exportRelativePath) {
            this.sourceFile = sourceFile;
            this.exportRelativePath = exportRelativePath;
        }
    }
}
