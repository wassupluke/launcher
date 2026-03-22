# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Gesture detection algorithm documentation (`docs/gesture-detection.md`)
- Architecture documentation with gesture dispatch pipeline and extension guides (`docs/architecture.md`)

## [0.2.11] - 2026-03-09

### Added
- Option to enable/disable diagonal swipe gestures

### Changed
- Improved German translation

## [0.2.10] - 2026-03-01

### Added
- Diagonal gestures
- Per-app language support
- Animation toggle
- AI policy for contributions

### Fixed
- Serif font rendering
- Shifty/mispositioned widgets
- AM/PM detection with localized date formats
- Dark mode issues in expanded state

### Changed
- No-animation mode now correctly suppresses transitions
- Improved app list performance and structure
- Updated translations (multiple languages)

## [0.2.9] - 2026-01-18

### Added
- Amber and green color themes
- Grid layout without labels
- Galician, Czech, and Spanish translations

### Fixed
- Crash when launching an invalid shortcut

### Changed
- "Choose action" toast replaced with snackbar where possible
- Reduced gap size in grid icon layout
- Upgraded Android Gradle Plugin

## [0.2.8] - 2025-11-03

### Added
- Torch mode fallback using `CameraManager#setTorchMode` when only one strength level is supported
- Camera access failure logging
- Debug info: git commit hash

### Fixed
- Crash on `IllegalStateException` when accepting a `PinItemRequest` twice
- Crash when `PackageManager#queryIntentActivities` throws `DeadSystemException`
- String format warning

### Changed
- Replaced `ViewPager` with `ViewPager2`
- Split `ListActivity` into `AppListActivity` and `SelectActionActivity`
- Reworked activity animations
- Unified `UIObject` activity boilerplate
- Refactored `LauncherAppWidgetProvider`

## [0.2.7] - 2025-09-27

### Fixed
- Crash when pinning a shortcut

## [0.2.6] - 2025-09-25

### Changed
- Downgraded Android Gradle Plugin to 8.11.1 (stability fix)

## [0.2.5] - 2025-09-23

### Added
- Automatic debug pre-release for every commit to `master`
- Certificate fingerprints in documentation
- `docs/declined-features.md`
- Git commit hash shown in debug info

### Fixed
- Crash when `LauncherApps#getShortcutBadgedIconDrawable` returns null

### Changed
- Updated to SDK 36
- Gesture display order in settings
- Improved accessibility content descriptions throughout the app
- Migrated build configuration from Groovy to Kotlin DSL

## [0.2.4] - 2025-09-02

### Added
- Setting for app name capitalization
- `docs/known-issues.md`

### Fixed
- Crash in `ManageWidgetsActivity#onActivityResult`
- Crash from `LauncherApps#getActivityList()`
- Widget resizing logic bugs
- App rotation handling in list layout
- `SecurityException` when launching an app

## [0.2.3] - 2025-06-24

### Added
- `docs/security.md`
- Documentation button in settings
- App drawer documentation

### Fixed
- Long-press handler not stopped when activity finishes
- Card shadow clipping in light theme

### Changed
- Visual improvements to settings fragment
- Improved widget list layout
- Updated and expanded documentation

## [0.2.2] - 2025-05-17

### Added
- Crash handler routing to `ReportCrashActivity`
- Widget interaction enabled by default on widget panels

### Fixed
- Widget-related crashes and stability issues

### Changed
- Updated translations (Italian, Arabic, Portuguese (Brazil), Dutch, Chinese Simplified)

## [0.2.1] - 2025-05-12

### Added
- Debug info widget (debug builds only)
- Clipboard copy for version number

### Fixed
- New widgets placed in free area if possible
- Consistent widget container position
- Shadow drawn around widget control elements
- Popup shown over widget
- Close button not working in `SelectWidgetActivity`
- `IllegalArgumentException` when accessing torch

### Changed
- Widget panel shows number of widgets

## [0.2.0] - 2025-05-10

### Added
- Widget panels (home screen and custom panels)
- Support for Android app widgets
- Option to hide keyboard when scrolling in app list
- Polish, Dutch, Arabic, Italian, Chinese (Simplified) translations

### Fixed
- Multiple `WidgetOverlayView` conflicts
- Alpha slider position clarified (ARGB not RGBA)

### Changed
- Migrated wiki to `docs/` directory

## [0.1.4] - 2025-04-15

### Added
- Action: launch other launchers
- Action: show recent apps (accessibility service required)
- Arabic, Lithuanian, Chinese (Simplified), Portuguese (Brazil), Spanish translations

### Fixed
- Tab renamed from "Apps" to "Actions" for clarity
- Volume key labels corrected

## [0.1.3] - 2025-03-20

### Fixed
- Crash fix (#133)

## [0.1.2] - 2025-03-20

### Fixed
- Reverted `ViewPager2` migration that caused keyboard opening issues

## [0.1.1] - 2025-03-19

### Added
- Improved tutorial
- Japanese translation

### Fixed
- Gesture detection on `ACTION_CANCEL` events
- Grayscale icon mode switching back to normal
- Various stability fixes

### Changed
- Migrated `ViewPager` to `ViewPager2` / `FragmentStateAdapter`
- Upgraded Android Gradle Plugin

## [0.1.0] - 2025-03-14

### Added
- Action: adjust volume
- Action: show pinned shortcuts in app list
- Option to reverse app list order
- Option to hide navigation bar on home screen
- Donate button
- Japanese, Portuguese (Brazil) translations

### Fixed
- Private space lock icon hidden when "hide when locked" setting is active
- Blurred text in dialogs
- Various layout and accessibility fixes

## [0.0.23] - 2025-03-02

### Added
- Tap-swipe combo gestures
- Basic support for pinned shortcuts
- Action: media play/pause

## [0.0.22] - 2025-02-17

### Added
- Triangle-shaped gestures (`<`, `>`, `V`, `Λ`)
- Turkish and Portuguese (Brazil) translations

### Fixed
- Gesture detection logic bug

---

[Unreleased]: https://github.com/jrpie/Launcher/compare/v0.2.11...HEAD
[0.2.11]: https://github.com/jrpie/Launcher/compare/v0.2.10...v0.2.11
[0.2.10]: https://github.com/jrpie/Launcher/compare/v0.2.9...v0.2.10
[0.2.9]: https://github.com/jrpie/Launcher/compare/v0.2.8...v0.2.9
[0.2.8]: https://github.com/jrpie/Launcher/compare/v0.2.7...v0.2.8
[0.2.7]: https://github.com/jrpie/Launcher/compare/v0.2.6...v0.2.7
[0.2.6]: https://github.com/jrpie/Launcher/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/jrpie/Launcher/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/jrpie/Launcher/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/jrpie/Launcher/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/jrpie/Launcher/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/jrpie/Launcher/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/jrpie/Launcher/compare/v0.1.4...v0.2.0
[0.1.4]: https://github.com/jrpie/Launcher/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/jrpie/Launcher/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/jrpie/Launcher/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/jrpie/Launcher/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/jrpie/Launcher/compare/v0.0.23...v0.1.0
[0.0.23]: https://github.com/jrpie/Launcher/compare/v0.0.22...v0.0.23
[0.0.22]: https://github.com/jrpie/Launcher/releases/tag/v0.0.22
