---
tags: [graft, development, platform]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 08 - Platform Integration

## 1. Why

Desktop apps break on the details each OS does differently: file watching, processes, paths, secrets, windows. These are written down here, so the code handles them on purpose instead of discovering them in bug reports. Each one lives behind a Graft interface with one implementation per OS.

## 2. Shape

| Interface | Module | macOS | Windows | Linux |
|---|---|---|---|---|
| `FileWatcher` | `core-repo` | FSEvents (via `directory-watcher`) | `ReadDirectoryChangesW` (JDK `WatchService`) | inotify (JDK `WatchService`) |
| `SecretStore` | `core-data` | Keychain | Credential Manager | Secret Service (gnome-keyring, KWallet) |
| `ProcessControl` | `core-git` | `ProcessHandle` tree | `ProcessHandle` tree + `destroyForcibly` | `ProcessHandle` tree |
| `AppInstance` (single instance + IPC) | `app-desktop`, `app-cli` | Unix domain socket | Unix domain socket (Windows 10+, via JDK 16+ `AF_UNIX`) | Unix domain socket |
| `PowerState` | `app-desktop` | OSHI | OSHI | OSHI |
| `Launcher` (editor, terminal, file manager, URL) | `app-desktop` | `open -a` | `start`, `wt.exe` | `xdg-open`, `$TERMINAL` |
| `DesktopNotifier` | `app-desktop` | Compose `Tray` notification first, native later | same | same (libnotify via D-Bus later) |

## 3. API and details

### 3.1 File watching

- One watcher per repository session, on the working tree and `.git/`, with Graft's ignore rules (`.git/objects`, ignored build folders).
- Events are **debounced** (300 ms for status, immediate for `HEAD` and refs) and coalesced into "refresh status / refs / operation state" ([Architecture](../01%20-%20Foundations/02%20-%20Architecture.md)).
- On overflow (too many events) or wake from sleep, Graft does a full refresh instead of trusting events.
- Linux inotify watch limits: when the limit is hit, Graft falls back to polling for that repo and shows how to raise `fs.inotify.max_user_watches`.

### 3.2 Processes

- `ProcessBuilder` with argv only; the environment is built explicitly ([Git Engine](../01%20-%20Foundations/03%20-%20Git%20Engine.md)).
- **Cancel** = destroy every `ProcessHandle.descendants()`, then the process itself: git spawns `ssh`, hooks, `git-remote-https`. On Windows, `destroyForcibly`.
- stdout and stderr are read concurrently (never one after the other: a full pipe would deadlock).
- Windows: `git.exe` is resolved from `PATH`, then Git for Windows' default location; `.cmd`/`.bat` hooks need no special handling because git runs them.

### 3.3 Paths and file names

| Issue | Handling |
|---|---|
| Spaces and non-ASCII | always argv; tests run from a path with spaces and Unicode ([CI](04%20-%20CI.md)) |
| macOS Unicode normalization (NFD on disk) | rely on `core.precomposeUnicode=true` (Git's default on macOS); paths compared after NFC normalization |
| Case-insensitive file systems (macOS, Windows) | case-only renames done with `git mv`; case collisions warned ([Commit Guards](../02%20-%20Features/19%20-%20Commit%20Guards.md)) |
| Windows long paths | `core.longpaths=true` suggested; Graft uses `\\?\` paths for its own file I/O |
| Windows reserved names (`CON`, `aux.c`) | shown with a warning; checkout errors explained |
| Line endings | never converted by Graft; Git's `core.autocrlf` and `.gitattributes` decide |

### 3.4 Secrets

- Entries are named `Graft/<profileId>/<host>/<login>` (tokens) and `Graft/ssh/<key fingerprint>` (passphrases).
- **Linux without a Secret Service** (headless or minimal desktop): no plain-text fallback. Graft explains it and offers to keep the token only in memory for the session.
- The keychain may prompt the user (macOS "allow access"); Graft asks for the secret once per session and caches it in memory only.

### 3.5 Single instance and IPC

- The app listens on a Unix domain socket in a user-only directory (mode 0700; on Windows, the user's local app data, which only that user can read).
- `graft open`, `graft clone` and the CLI's "tell the app what changed" messages go through it.
- Each request carries a token read from a user-only file ([Threat Model](../01%20-%20Foundations/07%20-%20Threat%20Model.md) T13).
- A second app launch hands its arguments to the first and exits.

### 3.6 Windows and title bar

- macOS: transparent title bar with full-size content (`apple.awt.fullWindowContent`, `apple.awt.transparentTitleBar` on the root pane), traffic lights native ([UI Design](../01%20-%20Foundations/06%20-%20UI%20Design.md)).
- Windows and Linux: native title bar in 1.0.
- Window position, size and maximized state remembered per window, and clamped to visible screens when a monitor is disconnected.

### 3.7 Desktop shell integration

| Feature | macOS | Windows | Linux |
|---|---|---|---|
| Dock/taskbar badge (review requests) | `Taskbar.setIconBadge` | overlay icon (later) | n/a |
| Progress in dock/taskbar | n/a | `Taskbar.setWindowProgressValue` | n/a |
| Recent repos in dock/jump list | later | later | n/a |
| `graft` on `PATH` | symlink in `/usr/local/bin` (asks for admin) or `~/.local/bin` | PATH entry for the user | `~/.local/bin` |
| URL scheme `graft://open?path=…` | later | later | later |

## 4. Build steps

1. `ProcessControl` with tree kill and concurrent stream reading (G0 Task 5).
2. `FileWatcher` with debounce and overflow handling (G1).
3. `SecretStore` on all three OSes plus `InMemorySecretStore` for tests (G3).
4. `AppInstance` socket and CLI hand-off (G1 for `graft open`).
5. Shell integration items as listed, by milestone.

## 5. Edge cases

| Case | Decided behavior |
|---|---|
| Repo on a network drive (SMB) | file events unreliable: polling every 5 s, and a note in the status bar |
| Repo inside iCloud/OneDrive/Dropbox folder | warn once that syncing `.git/` can corrupt it |
| System sleeps during a git operation | the process resumes or fails normally; Graft refreshes on wake |
| Antivirus locks files on Windows | retried briefly for Graft's own writes; git errors shown as is |
