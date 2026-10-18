---
tags: [graft, project, governance]
status: seedling
milestone: G7
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 06 - Governance

How decisions are made and how issues and PRs are handled once Graft is public. The repository's `GOVERNANCE.md` and `CONTRIBUTING.md` are written from this note.

## Roles

| Role | Who | Can |
|---|---|---|
| **Lead maintainer** | the author | final say on scope, design and ADRs; release; trademark (the name policy) |
| **Maintainer** | trusted contributors, invited by the lead | review and merge PRs, triage, cut releases with the lead's OK |
| **Contributor** | anyone | issues, PRs, discussions, translations |

Becoming a maintainer: sustained good contributions (roughly 10 merged PRs over 3 months) and an invitation. Inactive maintainers (6 months) move to emeritus.

## How decisions are made

| Decision | Process |
|---|---|
| Bug fixes, small features inside the docs | PR with one maintainer review |
| A change to a contract, a storage format, the module graph or the provider SPI | an ADR first (as a design-doc PR or a GitHub Discussion), accepted by the lead maintainer |
| New feature not in the docs | a Discussion ("Ideas"), then a feature note before code |
| License, name, governance | lead maintainer only |

## Issues

**Labels:**
- type: `bug`, `feature`, `docs`, `question`;
- area: `area/graph`, `area/shelves`, `area/github`, `area/cli`, …;
- priority: `p0` (data loss or security), `p1` (broken feature), `p2`, `p3`;
- status: `needs-repro`, `good first issue`, `help wanted`.

**Triage targets:**

| Kind | First response | Note |
|---|---|---|
| Security report (private advisory) | 7 days | [Threat Model](../01%20-%20Foundations/07%20-%20Threat%20Model.md), `SECURITY.md` |
| Data loss (`p0`) | 2 days | fixed before anything else; release as a patch |
| Other bugs | 14 days | needs a repro: Graft version, OS, Git version, steps, `graft doctor` output |
| Features | best effort | redirected to Discussions unless already in the roadmap |

Issues inactive for 60 days with `needs-repro` are closed with a note; they can be reopened anytime.

## Pull requests

- CI green, DCO sign-off, one approving maintainer review ([Conventions](../04%20-%20Development/02%20-%20Conventions.md)).
- PRs made with AI agents are welcome. The author is responsible for every line, the same as for hand-written code.
- Large PRs without a prior issue or Discussion may be asked to split.

## Communication

GitHub Issues (bugs), Discussions (questions, ideas, ADR proposals), and release notes. No private channels for project decisions.

## Code of conduct

Contributor Covenant 2.1; reports to the lead maintainer by email; enforcement per its guidelines.
