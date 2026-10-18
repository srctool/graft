# Contributing to Graft

Thanks for helping. Graft is pre-alpha, so the code changes quickly; the design in [`docs/design/`](docs/design/00%20-%20Overview.md) changes slowly, and it decides. Read [`ARCHITECTURE.md`](ARCHITECTURE.md) first: one page with the module map and the rules that never bend.

How decisions are made, roles and triage: [`GOVERNANCE.md`](GOVERNANCE.md). Be kind: [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

## Before you start

| You want to | Do this |
|---|---|
| Report a bug | open an issue with the bug form; include `graft doctor` output once the CLI has it |
| Fix a bug, or build something already in the [Roadmap](docs/design/03%20-%20Project/01%20-%20Roadmap.md) | comment on its issue first, so work isn't done twice |
| Propose a new feature | start a [Discussion](https://github.com/srctool/graft/discussions) under Ideas. A feature needs a design note before code |
| Change a contract, a storage format, the module graph or the provider SPI | propose an **ADR first**, as a design-doc PR or a Discussion. The lead maintainer accepts it before any code |
| Report a vulnerability | don't open an issue: see [`SECURITY.md`](SECURITY.md) |

`docs/design/` is generated from the author's design notes, so a PR that edits it is a proposal: the maintainer copies the change into the notes and regenerates the folder.

## Set up

You need JDK 21 and Git 2.38 or newer (see the [README](README.md#build)). Then:

```bash
./gradlew build
```

## Rules for code

- **Git only through `GitRunner`** in `core-git`: the `git` executable, argv lists, never a shell, never JGit or libgit2. Machine-readable output only (`-z`, `--porcelain=v2`, `--format` with `%x00`).
- **Module graph:** only the edges in [`config/module-graph.txt`](config/module-graph.txt). `core-*` and `app-cli` never import Compose; only `ui-kit` imports Material 3; features never import each other. `./gradlew check` enforces this.
- **Secrets** only in the OS keychain: never in files, logs, exceptions or test output.
- **Libraries** only from the [Libraries](docs/design/04%20-%20Development/07%20-%20Libraries.md) note, through `gradle/libs.versions.toml`, and only with GPL-3.0-compatible licenses (Apache-2.0, MIT, BSD, LGPL, MPL-2.0, EPL-2.0 with its GPL secondary license).
- **Every source file** starts with:
  ```kotlin
  // SPDX-License-Identifier: GPL-3.0-or-later
  // Copyright (C) <year> Graft contributors
  ```
- Kotlin official style, enforced by Spotless + ktlint: run `./gradlew spotlessApply`. Comments say *why*, not *what*. Public APIs in `core-*` get KDoc.

## Rules for tests

- **Real `git`, in temporary directories.** Integration tests create throwaway repositories. Never run git from a test against this repository or any of your own.
- **Sandbox the environment** in every test that runs git or touches config: `HOME` and `XDG_CONFIG_HOME` set to temp dirs, `GIT_CONFIG_GLOBAL` to a temp file, `GIT_CONFIG_NOSYSTEM=1`, an in-memory fake keychain, and `SSH_AUTH_SOCK` unset.
- **Every git command you add** needs a parser test with fixtures recorded from real repositories (non-UTF-8 paths and messages, renames, binary files, submodules).
- **Every provider call** needs a recorded-response test. No network in unit tests, and no real tokens in fixtures.
- Integration tests pass on Git 2.38 and on the latest Git.

Run everything before you push:

```bash
./gradlew check
```

## Commits and pull requests

- **Branches:** `feat/…`, `fix/…`, `docs/…`. `main` stays green.
- **Conventional Commits** with the module as scope: `feat(core-git): stream git log`, `fix(feature-graph): …`, `docs: …`.
- **Sign off every commit** with the [Developer Certificate of Origin](https://developercertificate.org/): `git commit -s`. It adds `Signed-off-by: Your Name <you@example.com>`, which says you have the right to submit the change under the project's license. There's no CLA, and you keep your copyright. CI rejects commits without a sign-off.
- **AI-assisted work is welcome.** Keep the tool's `Co-Authored-By` trailer. You're responsible for every line, the same as for code you typed.
- **Pull requests** are small, ideally one plan task each, with CI green and one approving maintainer review. Large PRs without an issue or Discussion first may be asked to split.
- **Merging** is a squash merge; the PR title becomes the commit subject, so write it as a Conventional Commit.

## License

By contributing, you agree that your contribution is licensed under [GPL-3.0-or-later](LICENSE), as certified by your sign-off.
