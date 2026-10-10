![RepoLeap – Stop digging through folders like it’s 1999.](docs/banner-github.png)

# RepoLeap

[![Version](https://img.shields.io/jetbrains/plugin/v/34545)](https://plugins.jetbrains.com/plugin/34545-repoleap)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/34545)](https://plugins.jetbrains.com/plugin/34545-repoleap)
[![Build](https://github.com/ryoga86/repoleap/actions/workflows/build.yml/badge.svg)](https://github.com/ryoga86/repoleap/actions/workflows/build.yml)

Leap to any of your local Git repositories in a few keystrokes – in IntelliJ IDEA and all other JetBrains IDEs.

Press **⌥⌘I** (macOS) or **Alt+Shift+P** (Windows/Linux), type a few letters of the repository name or
path, press **Enter** – the repository opens as a project.

<!-- TODO: screenshot / GIF of the popup -->

## Features

- **Any number of root folders** that contain your repositories (*Settings | Tools | RepoLeap*)
- **Recursive discovery** of Git repositories (folders containing `.git`, including worktrees)
  - configurable search depth (default: 3 levels)
  - repositories are not searched any further (submodules are not listed separately)
  - ignored folder names (default: `node_modules, build, target, out, dist, vendor, …`) are not searched,
    but a repository with such a name is still listed
  - hidden folders can be skipped
  - symlinks are followed, every physical folder is visited only once
- **Free-text search**: multiple words in any order, each has to match the name or the path
  (`platform api`), fuzzy matching on names (`bilserv` → `billing-service`), highlighted matches
- **Fixed order** that no search result breaks: pinned repositories, then the one in the **current window**,
  then those open in **other windows**, then the rest; inside each group the best match and recently opened
  repositories come first
- Open repositories are marked (🟢 **current window**, 🔵 **other window**) – choosing one brings exactly
  that window to the front
- **macOS window tabs**: bring a repository that is open in another window into the current window as a tab
  (**Shift+Enter** or *Merge into This Window*). If it is the only other window, IntelliJ's *Merge All Windows* is
  used (nothing is reloaded); otherwise the project is reopened as a tab, which needs the macOS setting
  *Prefer tabs when opening documents: Always*. To take a tab out again, use the tab's context menu in the IDE.
- **Open mode**: new window (default), current window, or the IDE setting *Open project in*;
  **Shift+Enter** uses the alternative
- **Actions per repository** (**Tab** or right click): close the project, show info, show the folder in the system
  file manager (Finder / Explorer / …), copy path, open the remote in the browser, pin to top, hide from the list,
  rename the folder, move it to the Trash
  - **Remote in browser** knows GitHub, GitLab (incl. subgroups), Gitea/Forgejo, Bitbucket Cloud,
    self-hosted Bitbucket Server / Data Center (incl. context paths and personal `~user` repos), Azure DevOps,
    AWS CodeCommit and SourceHut; for anything else, add a rule (host → type or URL template) in the settings
  - **Info**: branch, upstream with ahead/behind, last commit, remotes, uncommitted/untracked files,
    commits that are not pushed anywhere, local branches, stashes
  - **Move to Trash** warns about everything that would only be left in the Trash (uncommitted changes, untracked
    files, unpushed commits, stashes, missing remote); rename and trash are disabled while the project is open
  - hidden repositories can be shown again in the settings
- Scanning runs in the background – the popup opens instantly with the cached list and refreshes itself

## Usage

| Key                        | Action                              |
|----------------------------|-------------------------------------|
| ⌥⌘I / Alt+Shift+P          | open the popup                      |
| typing                     | filter                              |
| ↑ ↓ Page Up / Page Down    | move the selection                  |
| Enter / click              | open with the configured mode       |
| Shift+Enter / Shift+click  | open with the alternative mode; on macOS a repository open in another window is merged into this window as a tab |
| Tab / right click          | actions for the selected repository |
| Esc                        | close (in the actions: back)        |

In the actions: ↑ ↓ select, **Enter** runs the action, **Esc**, **Tab** or **←** go back, typing searches again.

Git information is read with the `git` executable found on the `PATH` (read-only commands, no network access).
Without git, branch and remotes are read from the `.git` folder.

The action is also available via *File | Open Repository…*, on the Welcome screen and in *Find Action*.
The shortcut can be changed in *Settings | Keymap* (search for “Open Repository”).

## Installation

[![Get from JetBrains Marketplace](https://img.shields.io/badge/Get_from-JetBrains_Marketplace-000000?style=for-the-badge&logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34545-repoleap)

- **JetBrains Marketplace**: click *Install* on the [plugin page](https://plugins.jetbrains.com/plugin/34545-repoleap)
  (installs it into a running IDE), or in the IDE: *Settings | Plugins | Marketplace*, search for **RepoLeap**
- **Manually**: download the ZIP from [Releases](https://github.com/ryoga86/repoleap/releases) and use
  *Settings | Plugins | ⚙ | Install Plugin from Disk…*

Requires IntelliJ Platform **2025.2** or newer. The plugin only depends on the IntelliJ Platform, so it can be
installed in every IntelliJ-based IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, CLion, …).

## Privacy

RepoLeap works locally and does not collect or transmit any data about you or your code.

The only network request: when you open a remote in the browser (or the info dialog) and the type of a
self-hosted git server can't be told from its URL, RepoLeap asks **that git server** once
(`https://<host>/rest/api/1.0/application-properties`) whether it is a Bitbucket Server. The answer is kept until
the IDE is restarted. This can be switched off in *Settings | Tools | RepoLeap | Open Remote in Browser*.

## Building from source

Requirements: JDK 21 (Gradle toolchain). Everything else is downloaded by Gradle.

```bash
./gradlew check          # unit tests + headless IDE tests
./gradlew runIde         # start a sandbox IDE (IntelliJ IDEA 2025.2) with the plugin
./gradlew buildPlugin    # -> build/distributions/repoleap-<version>.zip
./gradlew verifyPlugin   # IntelliJ Plugin Verifier against the recommended IDE versions
```

Run or verify against a locally installed IDE instead (no downloads):

```bash
./gradlew runLocalIde  -PlocalIdePath="$HOME/Applications/IntelliJ IDEA.app"
./gradlew verifyPlugin -PlocalIdePath="$HOME/Applications/IntelliJ IDEA.app"
```

`-PbuildSearchableOptions=false` speeds up local builds.

### Project structure

```
src/main/kotlin/de/pdenis/repoleap/
├── actions/   OpenRepositoryAction – the shortcut / menu action
├── ui/        RepoLeapPopup (search field + list), RepoListCellRenderer
├── search/    RepoMatcher – tokenizing, substring/fuzzy scoring, highlight ranges
├── scan/      RepoScanner (file system), RepoIndexService (cache + background scan)
├── open/      RepoOpener – opens a repository according to the open mode
└── settings/  RepoLeapSettings (stored in repoLeap.xml, not synced), RepoLeapConfigurable (UI)
```

The plugin is compiled against the lowest supported platform version (2025.2) so that newer APIs cannot be used by
accident. Releases are described in [RELEASING.md](RELEASING.md).

## Support

RepoLeap is free and open source. If it saves you time, I'd be happy about a coffee:

<a href="https://ko-fi.com/ryoga86"><img src="https://storage.ko-fi.com/cdn/kofi5.png?v=6" alt="Buy me a coffee at ko-fi.com" height="36"></a>

Bugs and ideas: [GitHub issues](https://github.com/ryoga86/repoleap/issues)

## License

[MIT](LICENSE) © Peter Denis
