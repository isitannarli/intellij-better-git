# Changelog

Release history for Better Git.

## 1.0.1 — 2026-10-19

### Fixed

- Size the sidebar change-count badge icon from its base icon so scaled badges keep the expected dimensions, keeping the crisp SVG rendering wherever the platform scales it correctly.

## 1.0.0 — 2026-10-19

### Added

- Labeled graph navigation for branches, remotes, stashes, tags, and worktrees, plus a minimap with commit/line metrics, reference and search markers, visible dates, and click/drag navigation.
- Live changed-file count on the Better Git sidebar icon, including untracked files; the badge hides when the working tree is clean.
- Initial commit.
- IntelliJ Platform integration tests for settings, action registration, tool-window disposal, and Git state synchronization.

### Fixed

- Use a monochrome Commit Graph tool-window icon with light and dark theme variants.
- Replace ambiguous graph glyphs with theme-aware icons and explicit file labels; count configured remotes correctly and exclude symbolic remote HEAD aliases from branch counts.
- Save IDE documents before branch and stash operations, and refresh file and repository state after successful or partially failed Git commands.
- Serialize branch and stash operations to prevent overlapping changes from the plugin.
- Create Settings UI components only when the settings page opens, and release them when it closes.
