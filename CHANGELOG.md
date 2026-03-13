<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# PDE-VCS Exporter Changelog

## [Unreleased]

### Added

- Export Java changes as compiled class files.
- Export web resources using a web-package-style directory layout.
- Create a timestamped export root directory for each export.

## [1.0.1] - 2026-03-12

### Changed

- Refined export behavior for web-package-style output.
- Added timestamped export root directory creation.
- Updated plugin metadata and packaging information.

## [1.0.0] - 2026-03-12

### Added

- Initial release of PDE-VCS Exporter.
- Support exporting selected files or directories from local VCS changes.
- Preserve web deployment structure including `WEB-INF` and `WEB-INF/classes`.

