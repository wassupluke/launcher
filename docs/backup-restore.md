# Backup & Restore

µLauncher can export all your settings to a JSON file and restore them on a new device or after a reset.

## Exporting

Go to **Settings → About → Export settings**. Choose where to save the file — it is saved as `ulauncher-backup.json`.

## Importing

Go to **Settings → About → Import settings**. Select a previously exported file. A summary shows how many items were skipped.

## What is included

- All gesture bindings
- Theme, clock, display, and functionality settings
- App list preferences (favorites, hidden apps, custom names)
- Enabled gesture flags
- Custom widget panel layouts

## What is not included

- **Widgets** — Android widget IDs are device-local and cannot be transferred
- **Pinned shortcuts** — Android prevents restoring shortcuts from other devices
- **Apps not installed** on the target device will appear as unbound gestures

## File format

The backup is a versioned JSON file (`backupVersion: 1`). Backups from newer app versions may not be importable by older versions.
