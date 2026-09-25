<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# RepoLeap Changelog

## [Unreleased]

## [1.0.0] - 2026-09-25

### Added

- Search popup for local Git repositories: Option+Command+I (macOS) / Alt+Shift+P (Windows/Linux),
  also available via File | Open Repository… and the Welcome screen
- Settings page (Settings | Tools | RepoLeap): any number of root folders, search depth, ignored folder names,
  skipping of hidden folders
- Free-text search with multiple words in any order, fuzzy matching on repository names and highlighted matches
- Fixed order: pinned repositories, current window, other windows, rest - inside each group best match and
  recently opened first
- Open repositories are marked with colored tags (green: current window, blue: other window); choosing one of
  them brings its window (or macOS window tab) to the front instead of opening it again
- Open mode: new window, current window or IDE setting; Shift+Enter uses the alternative
- macOS window tabs: bring a repository from another window into the current window as a tab (Shift+Enter or
  Merge into This Window); the actions only offer what fits the state of the repository
- Open Remote in Browser shows the complete web page it will open
- Actions per repository via Tab or right click: close project, info dialog with git and working tree state,
  show in the system file manager, copy path, open remote in browser, pin to top, hide (restorable in the settings), rename and
  move to Trash with a warning about uncommitted changes, untracked files, unpushed commits and stashes
- Web pages of remotes for GitHub, GitLab, Gitea/Forgejo, Bitbucket Cloud, Bitbucket Server / Data Center,
  Azure DevOps, AWS CodeCommit and SourceHut; self-hosted Bitbucket Servers with a neutral host name are
  recognized by asking the server once (can be switched off), custom rules per host in the settings

[Unreleased]: https://github.com/ryoga86/repoleap/compare/1.0.0...HEAD
[1.0.0]: https://github.com/ryoga86/repoleap/commits/1.0.0
