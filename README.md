<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" width="80" height="80" alt="Better Git logo">
</p>

# Better Git

Better Git brings your code’s history into focus with visual commit graphs, inline blame, pull and merge requests, and AI commit messages in your JetBrains IDE. Explore changes and review your work in one place, without breaking your flow.

## Features

- **Inline blame and hover cards**
  See who changed a line and when. Hover for the commit message, syntax-colored diff preview, and quick actions.

- **Commit graph**
  Explore colored branch and merge lanes, author avatars, branch/tag labels, change statistics, and searchable commits.

- **Commit inspector**
  Browse changed files beside their native diff, filter by path or status, and open commits in your browser.

- **File and selection history**
  Follow a file across renames or select code to inspect the commits that changed it.

- **Working changes**
  Review staged, unstaged, untracked, and conflicting files in a compact tree with green/red line counts. Write a message above the tree and commit staged changes with **Commit** or **⌘Enter / Ctrl+Enter**. With nothing staged, confirm **Stage All and Commit** to include all saved changes. The menu also opens the native IDE commit workflow. The sidebar icon shows the number of changed Git files across the project, even when the panel is closed.

  Inline commits run Git hooks and respect signing and Better Git's Conventional Commit setting. Use **Open IDE Commit…** for the IDE's additional pre-commit checks.

- **Workflow visualization**
  Navigate branch and merge relationships with zoom, Fit, search highlighting, and commit selection.

- **Insights and reports**
  Explore activity calendars, contributors, streaks, and trends. Export summaries of loaded history as HTML.

- **File age heatmap**
  Color source lines by their last change date to see recent and older code at a glance.

- **Pull and merge requests**
  Browse PRs/MRs, read descriptions and diffs, and open related reviews in your browser using existing credentials.

- **Branches and stashes**
  Create or switch local branches, create/apply stashes, and list tags and worktrees.

- **AI commit messages**
  Generate editable drafts with local Ollama models or optional OpenAI, Gemini, and Claude APIs.

- **Theme-aware interface**
  Native IDE components, compact layouts, and colors that adapt to your theme.

- **Privacy**
  We do not collect or store your source code, repository history, credentials, or usage data through Better Git. The plugin runs locally in your IDE, and external integrations communicate directly with the relevant service. No Better Git account is required. Author photos are enabled by default and can send author email addresses to GitHub/GitLab; you can disable them in settings. Reviews use hosting APIs, and cloud AI sends the previewed diff only when you generate. API keys can be stored in IDE PasswordSafe. See the [Privacy Policy](PRIVACY.md) for details.

## Compatibility

Requires **IntelliJ Platform 2024.3 (build 243) or newer** with the Git plugin enabled. The distribution targets Java 21 and the Kotlin 2.0 API, so it also runs on newer IDEs using Java 25. IDEs from 2024.2 and earlier are outside the supported range.

Binary compatibility verified with Plugin Verifier for the current build:

| IDE | Version |
| --- | --- |
| WebStorm | 2024.3, 2025.1, 2025.2, 2025.3, 2026.1, 2026.2.3 |
| IntelliJ IDEA | 2026.2.3, 2026.3 EAP (263.7031.23) |

The optional CI compatibility matrix also includes GoLand 2026.2.3 and Rider 2026.2.3.1. Binary verification checks API compatibility; it does not replace interactive testing of every IDE/product combination.

## Installation

Git must be available to your IDE. Install [Better Git from JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35267-better-git), or open **Settings → Plugins → Marketplace**, search for **Better Git**, and click **Install**.

For a local build, open **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select `intellij-better-git-<version>.zip` from `build/distributions/`.

## Usage

- Open **View → Tool Windows → Better Git** and select a repository.
- Open **Better Git Graph** from the same menu for the bottom-docked graph.
- In the graph, **Changes** shows changed files, added lines in green, and deleted lines in red. The labeled sidebar counts local branches, configured remotes, stashes, tags, and worktrees; click a category to browse and jump to its history.
- The **Minimap** summarizes loaded commits by time. Switch between commit counts and lines changed, then click or drag to navigate. Green marks HEAD, yellow marks search matches, blue squares mark branches, amber squares mark tags, and the shaded band covers visible dates. Minimap totals and markers cover only the loaded history; use **Load older commits** to extend it.
- Right-click selected code → **Better Git → Selection History** to inspect its changes.
- Use the sparkle action beside the native commit message field to generate an AI draft.
- Configure all plugin preferences under **Settings → Version Control → Better Git**: blame, author photos, history limits, auto-fetch, commit checks, and AI providers/models, message style, local endpoint, workspace, and saved API keys. Existing preferences are preserved. Settings buttons in Changes, Commit Graph, and the AI composer open this same page.
- AI credential edits take effect on **Apply/OK** and use the IDE password store. Leave a key blank to keep it, or select **Remove saved key on Apply** to delete it. Provider environment variables still take precedence over saved keys.

Local Git features need no hosting account. Private PR/MR access requires existing API permissions. Ollama models are installed separately; cloud AI requires a provider API key and follows that provider's billing terms.

## Development

Open the repository as a Gradle project and use **JDK 25** as the Gradle JVM with the checked-in **Gradle 9.8.2 wrapper**. Compilation and tests use **JDK 21**. If it is not installed, Gradle downloads it automatically through the Foojay toolchain resolver on the first sync or build; this requires an internet connection. For offline builds, install JDK 21 beforehand or reuse a previously provisioned toolchain. CI installs both JDKs. Run from the repository root; use `gradlew.bat` on Windows.

```bash
./gradlew :core:test :test                             # Run core and IDE integration tests
./gradlew verifyPluginProjectConfiguration buildPlugin # Build the plugin ZIP
./gradlew runIde                                       # Launch the development IDE
./gradlew verifyPlugin                                 # Check target IDE compatibility
./gradlew verifyPlugin -PverificationIdes=WS-2024.3     # Check one IDE (comma-separated IDs are supported)
```

The build uses the Kotlin 2.4.21 compiler and Gson 2.14.0, while explicitly targeting the Kotlin 2.0 language/API and Java 21 bytecode. Core compile/test dependencies use Kotlin stdlib 2.0.21 to match the oldest supported IDE; stdlib and coroutines are supplied by the host IDE and are not bundled. Build against the configured 2024.3 SDK and verify newer platforms with Plugin Verifier. Do not raise the JVM/language/API target without reviewing the minimum IDE version and compatibility matrix. The initial WebStorm 2024.3 build predates the first published 243 test/instrumentation artifacts, so those helpers are pinned to 243.21565.193.

Core tests use JUnit 6.1.3; IDE integration tests retain JUnit 4.13.2 as required by the platform test framework. Build-only dependency updates are scoped to `buildscript` in `build.gradle.kts`, so they are not bundled into the plugin ZIP. Keep IDE-provided runtime libraries and compiler-internal dependencies aligned with their owning tools; the Marketplace client still requires the `javax.xml.bind` JAXB 2.x implementation alongside the build tools' Jakarta JAXB 4.x implementation.

If IntelliJ IDEA 2026.2 reports a failed sync while Gradle itself succeeds, the IDE's Compose/Kotlin Multiplatform support can request unavailable `IdeaComposeHotReloadModel` or `XcodeCocoaPodsModel` models. As a temporary IDE-local workaround, open **Find Action → Registry**, turn off `gradle.use.resilient.model.fetch`, and sync again. This uses the previous model-fetch API without disabling plugins or changing the build. Restore the registry default after upgrading to an IDE/plugin version with the fix; see [KTIJ-40132](https://youtrack.jetbrains.com/issue/KTIJ-40132).

Git logic, providers, and calculations live in `core/`. IDE actions, editor integration, services, and UI live in `src/main/kotlin/dev/bettergit/`. Platform integration tests in `src/test/kotlin/` cover settings, registered IDE actions, tool-window disposal, and Git operations with unsaved documents. Add focused regression tests for behavior changes and check UI changes in the development IDE.

Bug reports and pull requests are welcome. Include reproduction steps for bugs and a brief explanation of changes and verification for pull requests.

## License

[MIT](LICENSE)
