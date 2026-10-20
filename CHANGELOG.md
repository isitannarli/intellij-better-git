# Changelog

Release history for Better Git.

## 1.1.0 — 2026-10-21

### Added

- Compose and create commits directly in Changes, with a multiline message, keyboard shortcut, staged/all options, AI draft action, and repository-specific drafts.
- Show right-aligned green additions and red deletions beside changed files, with separate staged and working totals and explicit binary-file labels.
- Stage, unstage, discard, delete, and stash selected files or folders directly from the Changes tree.
- Refresh working changes automatically from native IDE file, change-list, and Git events while preserving selection and collapsed folders.
- Add regression coverage for commit operations, line statistics, centralized settings, event coalescing, large change trees, and graph cancellation.

### Changed

- Suspend workspace queries while hidden, retain history on file-only updates, debounce file filtering, prepare large change trees in the background, and reduce graph layout and row-rendering work.
- Extend the minimum supported platform to 2024.3 (build 243), compile against its APIs with Java 21 and Kotlin 2.0 targets, and include older platform generations in the optional CI verification matrix. Preserve bounded avatar downloads, editor save callbacks, and native save dialogs through compatible APIs.
- Update Kotlin to 2.4.21, Gson to 2.14.0, core tests to JUnit 6.1.3, and Plugin Verifier to 1.411. Refresh build-only libraries and Jackson test compile dependencies while retaining the supported IDE runtime and legacy JAXB namespace compatibility.
- Centralize general, commit, remote, and AI preferences in Settings → Version Control → Better Git, including provider-specific models and password-store keys applied on Apply/OK. Keep the AI composer focused on diff preview and draft editing, with a shortcut to the shared settings page.
- Update the Gradle wrapper to 9.8.2 with distribution checksum verification, explicitly enable parallel tooling models, and document the IntelliJ IDEA 2026.2 sync workaround. Automatically provision the Java 21 build toolchain when missing while allowing Gradle to run on JDK 25.
- Compact the Changes tree with 8-pixel indentation and tighter rows, remove hierarchy guide lines, and align its left edge with the file filter.
- Use native file icons and IDE filename associations in Changes, including icons for deleted files, while keeping disk lookups out of row painting.
- Use the IDE's native context menu for changed files, with theme-aware icons, standard diff/source shortcuts, and grouped file actions.
- Use a native, theme-aware split button for Commit and its options, with a faint green border while inactive and a solid green fill when ready to commit.
- Merge Overview and Changes into the first Changes tab, with compact folder trees for staged changes, working changes, and conflicts.
- Replace the large repository header with a compact branch and repository bar, move secondary actions into a menu, and allow tabs to wrap in narrow layouts.
- Give the Changes tool window a document icon with plus/minus marks and light and dark theme variants, keeping the branch graph icon for Commit Graph.
- Remove persistent help text below the Changes tree and collapse the empty footer while retaining progress, error, and file-limit messages.

### Fixed

- Open changed files and diff previews through the stable navigation API to avoid deprecated editor-opening calls on IntelliJ IDEA 2026.3 EAP while retaining 2024.3 support.
- Keep hovered Changes rows inside the panel and wrap file tooltips to the available width.

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
