# Better Git Privacy Policy

Last updated: October 19, 2026

**We do not collect or store your source code, repository history, credentials, or usage data through Better Git. The plugin does not send this information to us.**

Better Git runs locally in your IDE. When a feature connects to a Git host, avatar service, or AI provider, it communicates directly with that service, without passing through a Better Git server. Those services handle the data under their own policies. Settings, caches, saved API keys, and exported reports use local storage as described below. Author photos are enabled by default and can be disabled in settings.

This policy explains how Better Git, a JetBrains IDE plugin maintained by **isitannarli**, processes information. In this policy, "we" and "us" refer to the maintainer. It covers the plugin itself; your IDE, Git hosting services, and AI providers have their own privacy practices.

Better Git requires no Better Git account and includes no advertising, usage analytics, or automatic crash-reporting service.

## Local repository data

Better Git reads repository and editor information to provide commit graphs, blame, file and selection history, diffs, working changes, branches, stashes, insights, and reports. This information can include source code, unsaved editor content, file paths, remote URLs, branch and tag names, commit hashes and messages, author names and email addresses, and timestamps.

This processing takes place locally through the IDE and your Git installation. Repository content is sent outside your computer only through the external features and Git operations described below.

## Author photos

**Author photos are enabled by default.** When the plugin displays an author photo, it may send the commit author's email address to GitHub's avatar service or the relevant GitLab or GitHub Enterprise server. For supported GitHub noreply addresses, it can use the numeric user ID instead of the email address.

GitHub images may be loaded from `avatars.githubusercontent.com`. GitLab may return an image hosted on the GitLab server or on Gravatar, in which case the plugin requests that image from the returned service. The email address belongs to the commit author and may identify someone other than the person using the plugin.

Avatar requests do not include your hosting access token. Receiving services can observe normal network information, such as your IP address and request headers.

You can disable author photos under **Settings → Version Control → Better Git** by turning off **Load author photos from GitHub/GitLab when a blame hover opens**. This setting controls the plugin's author photo requests.

## Pull and merge requests

When you load pull requests, merge requests, related reviews, or their diffs, Better Git contacts the selected repository's GitHub or GitLab API, including supported self-hosted installations. Requests identify the repository and, as applicable, a commit hash, review number, filter, or page.

For authenticated access, the plugin can retrieve existing credentials from `gh`, `glab`, or supported global Git credential helpers. It sends the credential to the selected hosting API. Better Git does not create a separate hosting account or persist a new copy of these credentials in its settings.

Review titles, descriptions, authors, branches, and diffs returned by the service are displayed in the IDE. Opening a commit or review in your browser visits the corresponding hosting website, where the website's privacy and cookie policies apply.

## Git network operations

Manual fetch and optional automatic fetch contact your configured Git remotes using your existing Git configuration and authentication. **Automatic fetch is disabled by default** and can be enabled or disabled in Better Git settings.

Actions that open the IDE's native Git workflows, such as Push, use the IDE and Git's normal behavior. Completing a push sends the selected commits, including their code and author metadata, to the destination remote.

## AI commit messages

AI generation is optional. Opening the commit message dialog prepares a local diff preview; a generation request is sent only when you click **Generate draft**.

The request contains the previewed diff, your chosen commit message style, model selection, and instructions for generating a message. Depending on the workflow, the diff comes from staged changes or changes included in the IDE's Commit panel, which may include new files and unsaved edits. Diffs can contain file paths, source code, personal information, or secrets present in those changes. Review the preview before sending it; Better Git does not automatically redact sensitive content.

### Local Ollama

Ollama is the default provider. Better Git accepts only local loopback endpoints and checks model metadata for an installed local model before sending the diff. The default endpoint is `http://127.0.0.1:11434`.

Better Git sends these requests to your local Ollama installation. That installation and its configuration remain under your control.

### Cloud providers

If you choose OpenAI, Google Gemini, or Anthropic Claude, Better Git sends the request directly to that provider over HTTPS, together with the API key needed to authenticate it. If configured, an Anthropic workspace ID is also included. The provider receives the submitted content and normal network information.

Provider retention, use for model improvement, processing locations, and deletion options depend on the provider's applicable terms and your account settings. Better Git cannot determine or guarantee those practices. Review the relevant provider information before sending code:

- [OpenAI Privacy Policy](https://openai.com/policies/privacy-policy/)
- [Google Gemini API Terms](https://ai.google.dev/gemini-api/terms)
- [Anthropic Privacy Policy](https://www.anthropic.com/legal/privacy)

## Credentials and local storage

Cloud AI keys can be entered for a request, read from supported environment variables, or retrieved from the IDE's PasswordSafe. **Remember this key in the IDE password store** is unchecked by default. If you select it, the key is saved through PasswordSafe rather than in Better Git's ordinary settings files. PasswordSafe protection depends on your IDE and operating system configuration.

Better Git stores preferences such as feature toggles, history limits, provider and model choices, local endpoint, message style, and optional Anthropic workspace ID in the IDE's configuration. It also stores the selected workspace tab in project workspace settings. IDE settings synchronization or backups may handle these files according to your configuration.

Working data is held in memory while features are in use. Author photos are cached in memory, and the cache is cleared when the project service is disposed. Selection history can create temporary local files containing repository and editor snapshots; the plugin attempts to delete them after the comparison.

Exported HTML reports are saved to the location you choose and may contain the repository name, contributor names, and activity statistics. They remain there until you delete them and are shared only if you or another application shares that location or file.

## Your controls and deletion

You can disable author photos and automatic fetch, avoid hosting queries, and use local Ollama or skip AI generation. Local Git views do not require a hosting or cloud AI account.

Use **Forget saved key** in the AI dialog to remove the selected provider's key from PasswordSafe. This does not remove environment variables, revoke the key at the provider, or change credentials managed by Git, `gh`, or `glab`; manage those through their respective tools or services.

Settings and saved credentials may remain after uninstalling the plugin. To remove persistent plugin preferences, remove `betterGit.xml` and `betterGitAi.xml` from your IDE's configuration with the IDE closed. Project layout preferences are stored in the project's workspace settings. Delete exported reports separately. Your Git repositories and commit history remain under your control.

Requests concerning information held by a hosting or AI provider should be directed to that provider. Disabling a feature stops future use of that feature; it does not delete information already received by an external service.

## Other services and support

Hosting and avatar services process requests under their own policies: [GitHub](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement), [GitLab](https://about.gitlab.com/privacy/), and [Automattic, including Gravatar](https://automattic.com/privacy/). For a self-hosted service, consult its operator. IDE and Marketplace data handling is covered separately by [JetBrains' Privacy Notice](https://www.jetbrains.com/legal/docs/privacy/privacy/).

If you submit an issue or support request, the maintainers receive the information you choose to include. Public GitHub issues can be read by others. Do not post API keys, private code, or other sensitive information in an issue.

## Changes and contact

This policy may be updated as the plugin changes. Updates will be published in this repository with a revised date.

For questions about this policy, contact the maintainer through the [Better Git issue tracker](https://github.com/isitannarli/intellij-better-git/issues). For a privacy matter involving personal information, first request a private contact method without posting the information publicly.
