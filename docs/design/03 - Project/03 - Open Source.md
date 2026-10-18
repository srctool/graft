---
tags: [graft, project, open-source]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 03 - Open Source

## License: GPL-3.0-or-later (decided 2026-10-18, [ADR-10](02%20-%20Decisions.md))

> [!WARNING]
> **Not legal advice**
> This is a working summary to decide with. Read the license texts (choosealicense.com is a good start) before publishing.

### The two families

- **Permissive** (MIT, BSD, Apache-2.0): anyone may use, change and redistribute the code, also inside closed-source products. Their only duty is to keep the copyright and license notices.
- **Copyleft** (GPL, AGPL, MPL): anyone may do the same, but whoever **distributes** a changed version must publish its source under the same license.
  - *Strong* copyleft (GPL): the whole program.
  - *Weak*, file-level copyleft (MPL): only the changed files.

### The candidates

| | Apache-2.0 | MIT | GPL-3.0 | MPL-2.0 |
|---|---|---|---|---|
| Someone sells a closed fork of Graft | allowed | allowed | not allowed: the fork must ship its source | allowed, but changes to Graft's own files must be published |
| Patent grant from contributors | **yes, explicit**, and it ends for anyone who sues over patents | no | yes | yes |
| Can include Apache-2.0 libraries (Kotlin, Compose, Ktor, SQLDelight) | yes | yes | yes | yes |
| Others can reuse Graft code (the graph layout, git parsers) in their projects | anywhere | anywhere | only in GPL-3.0 projects | anywhere, those files stay MPL |
| Mac App Store / Microsoft Store | fine | fine | **conflicts** with Apple's App Store terms (VLC was removed for this) | fine |
| Changing license later | easy to move to GPL-3.0 later (Apache code can go into a GPL-3.0 project) | easy | hard: needs every contributor's consent | medium |
| Duties when shipping | keep `LICENSE` + `NOTICE`, mark changed files | keep the notice | provide source, keep notices, no extra restrictions | provide source of changed MPL files |

Two licenses to **avoid**:
- **GPL-2.0-only** is incompatible with Apache-2.0, and almost every Kotlin library is Apache-2.0.
- **AGPL** only adds rules for software offered over a network. That's irrelevant for a desktop app and only scares contributors.

### What other Git clients chose

| License | Clients |
|---|---|
| MIT | GitHub Desktop, Gittyup, SourceGit, lazygit |
| GPL-2.0 | Git itself, git-cola |
| Fair-source (source available, not OSI open source) | GitButler |
| Closed source | GitKraken, Fork, Tower, Sublime Merge |

Most open-source Git GUIs are permissive, and closed clones of them have not been a real problem.

### Things a license does not decide

- **The name.** No license gives rights to the name "Graft". A `TRADEMARKS.md` policy decides who may call a build "Graft". A fork may use the code but must rename itself. This is the real protection against a confusing copycat under a permissive license (Rust and Firefox work this way).
- **Calling `git`.** Graft runs `git` as a separate program. That doesn't make Graft a derivative of Git, so Git's GPL-2.0 doesn't apply to Graft's code. If Graft **bundles** a Git binary (Windows, macOS), it's shipped next to Graft as a separate program ("mere aggregation"). Graft must then include Git's license and point to its source, for example Git for Windows' release page.
- **Paid extras.** Under Apache-2.0, a paid plugin or a hosted service later stays possible. Under GPL-3.0 that only works if you hold all the copyright, which means a CLA instead of the DCO.

### Decision: GPL-3.0-or-later

Chosen because a closed copy of Graft would bother the author, and the App Store isn't a goal (too expensive for an open-source project).

What GPL-3.0 does and doesn't do:

| | |
|---|---|
| **Does** | every distributed copy of Graft, changed or not, comes with its source under GPL-3.0. Improvements made by others stay open and can come back to Graft. |
| **Doesn't** | stop people from forking, changing or even selling Graft. That's what makes it open source. It only makes sure they can't close it. |
| **Doesn't** | apply to private changes nobody else receives. |
| **Doesn't** | protect the name. `TRADEMARKS.md` does that: forks must not call themselves "Graft". |

"-or-later" lets Graft move to a future GPL version without asking every contributor. It's the FSF's recommended form.

Consequences:
- **Contributors:** DCO sign-off, no CLA. Every contributor keeps their copyright, so changing the license later needs all of them. Accepted: GPL-3.0 is meant to be permanent. Dual licensing (selling non-GPL licenses) is given up.
- **Plugins:** plugins that load into Graft's process must be GPL-3.0-compatible. External tools that talk to Graft over a CLI or a socket can use any license.
- **Dependencies:** must be GPL-3.0-compatible. Apache-2.0, MIT, BSD, LGPL, MPL-2.0 and EPL-2.0 (with its GPL secondary license) are fine; **GPL-2.0-only, EPL-1.0, SSPL, "non-commercial" and proprietary SDKs are not**. CI fails on them.
- **Distribution:** no Mac App Store. macOS builds still need Apple notarization (same paid developer account) to open without a Gatekeeper warning; until then, document "right-click → Open". Windows signing via SignPath's free OSS program.

### Name policy (`TRADEMARKS.md`)

The code is free under GPL-3.0; the **name** is not. Anyone may fork, change and share Graft, but a version they distribute as their own must use a different name and logo.

| Allowed | Not allowed |
|---|---|
| Distributing unchanged official builds as "Graft" | Distributing a changed version as "Graft" or a confusingly similar name ("Graft Pro", "GraftX") |
| Saying a fork is "based on Graft" or "a fork of Graft" | Using the Graft logo or icon for a fork |
| Linux distributions packaging Graft with small build or packaging patches | Presenting a fork as official or endorsed |
| Writing about Graft, tutorials, talks | Domains or app-store listings that look official |

- GPL-3.0 section 7(e) explicitly allows refusing trademark rights, so this policy sits alongside the license without conflict.
- Registering "Graft" as a trademark makes the policy enforceable; in Indonesia (DJKI) protection depends on registration, since it's first-to-file. Optional at first, worth doing before 1.0 if forks appear.

### Setting it up

- [ ] `LICENSE`: the full GPL-3.0 text, unchanged.
- [ ] `// SPDX-License-Identifier: GPL-3.0-or-later` plus `// Copyright (C) <year> Graft contributors` in every source file; CI checks it.
- [ ] README states the license, and the About dialog shows it with "no warranty" (GPL section 15).
- [ ] `TRADEMARKS.md`: forks must rename; "based on Graft" is fine.
- [ ] Dependency license check in CI (Gradle license report) against the list above.
- [ ] About → Licenses lists every third-party component, including the bundled Git (GPL-2.0, shipped as a separate program, with a link to its source).
- [ ] Each release is a tag with its source, which satisfies the "provide source" duty.

## Repository

```
graft/
├─ app-desktop/          core-*/          provider-github/      feature-*/
├─ docs/                 (user + contributor docs site, Docusaurus)
├─ fixtures/             (recorded git repos and API responses for tests)
├─ .github/              (workflows, issue forms, PR template, CODEOWNERS)
├─ README.md  CONTRIBUTING.md  CODE_OF_CONDUCT.md  SECURITY.md  GOVERNANCE.md  CHANGELOG.md  LICENSE
```

Hosted on GitHub (organization `srctool`, the same org as Sinew and Camouflage). Dogfood: Graft's own PRs reviewed in Graft once G6 lands.

## Contribution rules

Roles, decisions and triage: [Governance](06%20-%20Governance.md). Privacy policy: [Privacy](07%20-%20Privacy.md).

- DCO sign-off (`git commit -s`) instead of a CLA.
- Conventional commits; changelog generated from them.
- Every Git command added needs a parser fixture test; every provider call needs a recorded-response test.
- "good first issue" labels from G7 on; ADR required for changes to module boundaries, storage formats (shelf format version) or provider SPI.

## CI

Details: [CI](../04%20-%20Development/04%20-%20CI.md); releases: [Release and Updates](../04%20-%20Development/05%20-%20Release%20and%20Updates.md).

| Job | Runs on |
|---|---|
| Build + unit tests | macOS, Windows, Ubuntu |
| Git integration tests against real `git` (min 2.38 and latest) | all three |
| UI screenshot tests (Compose) | Ubuntu |
| Dependency graph check | Ubuntu |
| Packaging (dmg, msi, deb, rpm, AppImage) | on tags |

## Releases

- GitHub Releases with checksums and SBOM; Homebrew cask, winget, Scoop, Flathub, AUR later.
- **CLI-only `graft-cli`** on the same channels (Homebrew formula, winget `Graft.CLI`, Scoop, AUR, archives), released together with the app at the same version.
- **Claude Code plugin** `graft` from the repo's own plugin marketplace (`/plugin marketplace add srctool/graft`), versioned with the app ([MCP Server](../02%20-%20Features/32%20-%20MCP%20Server.md)).
- **macOS signing + notarization** needs an Apple Developer account (paid yearly).
- **Windows signing**: SignPath Foundation offers free code signing for open-source projects; otherwise an OV certificate.
- Auto-update: Conveyor's updater, or Sparkle (macOS) / WinSparkle (Windows) with a signed appcast.
- Version scheme: SemVer; shelf storage format versioned separately (`Graft-Shelf-Version`).

## Funding (optional)

GitHub Sponsors / Open Collective. No paid tier for features in the core.
