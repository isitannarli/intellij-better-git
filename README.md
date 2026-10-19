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
  Review staged, unstaged, untracked, and conflicting files, then continue through the native IDE commit workflow. The sidebar icon shows the number of changed Git files across the project, even when the panel is closed.

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

Binary compatibility verified with Plugin Verifier:

| IDE | Version |
| --- | --- |
| WebStorm | 2026.2.3 |
| GoLand | 2026.2.3 |
| Rider | 2026.2.3.1 |

## Installation

Git must be available to your IDE. Build the plugin or obtain its distribution ZIP, then open **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select `intellij-better-git-<version>.zip` from `build/distributions/`.

## Usage

- Open **View → Tool Windows → Better Git** and select a repository.
- Open **Better Git Graph** from the same menu for the bottom-docked graph.
- In the graph, **Changes** shows changed files, added lines in green, and deleted lines in red. The labeled sidebar counts local branches, configured remotes, stashes, tags, and worktrees; click a category to browse and jump to its history.
- The **Minimap** summarizes loaded commits by time. Switch between commit counts and lines changed, then click or drag to navigate. Green marks HEAD, yellow marks search matches, blue squares mark branches, amber squares mark tags, and the shaded band covers visible dates. Minimap totals and markers cover only the loaded history; use **Load older commits** to extend it.
- Right-click selected code → **Better Git → Selection History** to inspect its changes.
- Use the sparkle action beside the native commit message field to generate an AI draft.
- Configure blame, author photos, history limits, and optional auto-fetch under **Settings → Version Control → Better Git**.

Local Git features need no hosting account. Private PR/MR access requires existing API permissions. Ollama models are installed separately; cloud AI requires a provider API key and follows that provider's billing terms.

## Development

Open the repository as a Gradle project and use **JDK 25** as the Gradle JVM. Run from the repository root; use `gradlew.bat` on Windows.

```bash
./gradlew :core:test :test                             # Run core and IDE integration tests
./gradlew verifyPluginProjectConfiguration buildPlugin # Build the plugin ZIP
./gradlew runIde                                       # Launch the development IDE
./gradlew verifyPlugin                                 # Check target IDE compatibility
```

Git logic, providers, and calculations live in `core/`. IDE actions, editor integration, services, and UI live in `src/main/kotlin/dev/bettergit/`. Platform integration tests in `src/test/kotlin/` cover settings, registered IDE actions, tool-window disposal, and Git operations with unsaved documents. Add focused regression tests for behavior changes and check UI changes in the development IDE.

Bug reports and pull requests are welcome. Include reproduction steps for bugs and a brief explanation of changes and verification for pull requests.

## License

[MIT](LICENSE)
