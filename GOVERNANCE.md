# Governance

How decisions are made and how issues and pull requests are handled. Contribution rules are in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Roles

| Role | Who | Can |
|---|---|---|
| **Lead maintainer** | the author ([@dwikiriyadi](https://github.com/dwikiriyadi)) | final say on scope, design and ADRs; releases; the [name policy](TRADEMARKS.md) |
| **Maintainer** | trusted contributors, invited by the lead | review and merge PRs, triage, cut releases with the lead's OK |
| **Contributor** | anyone | issues, PRs, discussions, translations |

Becoming a maintainer takes sustained good contributions (roughly 10 merged PRs over 3 months) and an invitation. Maintainers inactive for 6 months move to emeritus.

## How decisions are made

| Decision | Process |
|---|---|
| Bug fixes, small features already in the design docs | a PR with one maintainer review |
| A change to a contract, a storage format, the module graph or the provider SPI | an ADR first (a design-doc PR or a Discussion), accepted by the lead maintainer |
| A new feature not in the design docs | a Discussion under Ideas, then a design note before code |
| License, name, governance | the lead maintainer only |

## Issues

**Labels**

- Type: `bug`, `feature`, `docs`, `question`.
- Area: `area/graph`, `area/shelves`, `area/github`, `area/cli`, and so on.
- Priority: `p0` (data loss or security), `p1` (broken feature), `p2`, `p3`.
- Status: `needs-repro`, `good first issue`, `help wanted`.

**Triage targets**

| Kind | First response | Note |
|---|---|---|
| Security report (private) | 7 days | see [`SECURITY.md`](SECURITY.md) |
| Data loss (`p0`) | 2 days | fixed before anything else, released as a patch |
| Other bugs | 14 days | needs a repro: Graft version, OS, Git version, steps, `graft doctor` output |
| Features | best effort | redirected to Discussions unless already in the roadmap |

Issues with `needs-repro` and no activity for 60 days are closed with a note. They can be reopened anytime.

## Pull requests

- CI green, DCO sign-off, one approving maintainer review.
- PRs made with AI agents are welcome. The author is responsible for every line, the same as for hand-written code.
- Large PRs without an issue or Discussion first may be asked to split.

## Communication

GitHub Issues for bugs, [Discussions](https://github.com/srctool/graft/discussions) for questions, ideas and ADR proposals, and release notes. There are no private channels for project decisions.

## Code of conduct

Graft follows the [Contributor Covenant 2.1](CODE_OF_CONDUCT.md). Reports go privately to the maintainers as described there, and are enforced per its guidelines.
