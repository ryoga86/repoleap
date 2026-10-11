<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# RepoLeap Changelog

## [Unreleased]

### Changed

- Order of the actions (Tab): *Switch Branch…* and *Pin to Top* come right after *Close*, *Show Info…* moved behind
  *Open Remote in Browser*

### Fixed

- *Switch Branch…* did nothing after picking a branch: the choice was read too early and the action list simply
  reopened
- Esc in the branch list goes back to the actions of the repository instead of the repository list; after a
  successful switch the popup stays closed
- The title of the popup is centered again; it is now called “RepoLeap”

## [1.1.0] - 2026-10-11

### Added

- The popup shows the checked out branch of every repository (the short commit hash for a detached HEAD)
- *Switch Branch…* in the actions (Tab): pick a local branch, or a remote one to create a local tracking branch.
  Runs `git switch`, so nothing is forced: if local changes would be overwritten, git refuses and says why
- Sort order for the popup: smart (as before), by name, recently opened first, or by path. Choose the default in
  the settings or switch with the sort button in the popup; pinned repositories always stay on top
- Choose what is shown next to a repository's name in the popup: branch, path and window state (*Settings | Tools |
  RepoLeap | Popup*)

### Fixed

- Finding `git` no longer uses an IDE API that is scheduled for removal (future-proofing for IDE 2026.3 and later;
  no change in behavior)
- Windows: repository paths are shown in full (`C:\Users\...`) instead of the Unix-style `~\...` abbreviation

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

[Unreleased]: https://github.com/ryoga86/repoleap/compare/1.1.0...HEAD
[1.1.0]: https://github.com/ryoga86/repoleap/compare/1.0.0...1.1.0
[1.0.0]: https://github.com/ryoga86/repoleap/commits/1.0.0
