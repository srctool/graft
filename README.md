# Graft - SRC Tool

> Building Graft needs **JDK 21** (Temurin recommended) and **Git 2.38** or newer. See [Build](#build).

**Graft** is an open-source desktop Git client for macOS, Windows and Linux, part of the **SRC Tool** family alongside [Sinew](https://github.com/srctool/sinew) and [Camouflage](https://github.com/srctool/camouflage). It has a GitKraken-style commit graph, GitHub pull requests and issues, worktrees, submodules, JetBrains-style shelves and changelists, profiles that keep work and personal identities apart, and a CLI and MCP server for AI agents. It runs your own `git` executable; there's no Git library inside.

**Status: pre-alpha.** Graft is being built milestone by milestone (G0 … G10). Nothing is usable yet. The [Roadmap](docs/design/03%20-%20Project/01%20-%20Roadmap.md) and the [project board](https://github.com/orgs/srctool/projects/9) show where it stands.

## Build

Install JDK 21 and Git first:

| OS | JDK 21 | Git |
|---|---|---|
| macOS | `brew install --cask temurin@21` | Xcode Command Line Tools or Homebrew |
| Windows | `winget install EclipseAdoptium.Temurin.21.JDK` | Git for Windows, with long paths on (`git config --system core.longpaths true`) |
| Linux | your distribution's OpenJDK 21, or Temurin | your distribution's Git (2.38 or newer) |

Then, from the repository root:

```bash
./gradlew build
```

The first run downloads Gradle and the dependencies. Other commands:

| Task | Command |
|---|---|
| All checks (tests, formatting, module graph, imports, license headers) | `./gradlew check` |
| Run the app | `./gradlew :app:desktop:run` |
| Format the code | `./gradlew spotlessApply` |

## Documentation

- [`ARCHITECTURE.md`](ARCHITECTURE.md): the module map and the rules that never bend.
- [`docs/design/`](docs/design/00%20-%20Overview.md): the full design, written before the code. It's generated from the author's notes; don't edit it by hand.
- [`CONTRIBUTING.md`](CONTRIBUTING.md): how to contribute. [`GOVERNANCE.md`](GOVERNANCE.md): how decisions are made.
- [`PRIVACY.md`](PRIVACY.md): Graft collects nothing about you. [`SECURITY.md`](SECURITY.md): how to report a vulnerability.

## License

Graft is free software under the [GNU General Public License v3.0 or later](LICENSE) (`GPL-3.0-or-later`). It comes with **no warranty**, to the extent permitted by law.

The name "Graft" and its logo aren't covered by the license. Forks are welcome under another name: see [`TRADEMARKS.md`](TRADEMARKS.md).
