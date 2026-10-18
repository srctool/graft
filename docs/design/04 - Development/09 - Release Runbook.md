---
tags: [graft, development, release, runbook]
status: seedling
milestone: G7
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 09 - Release Runbook

Step-by-step procedures. The mechanisms behind them are in [Release and Updates](05%20-%20Release%20and%20Updates.md).

## R1: Cut a release

1. **Freeze:** `main` green on all required checks; no open `p0` issues.
2. **Changelog:** generate from Conventional Commits since the last tag; edit for humans (group by feature, call out breaking changes and format migrations).
3. **Version:** bump in `gradle.properties` (`graft.version=X.Y.Z`); PR "chore(release): vX.Y.Z"; merge.
4. **Tag:** `git tag -s vX.Y.Z -m "Graft X.Y.Z"` on the merge commit; push the tag.
5. **Build:** `release.yml` runs; check every artifact exists (app per OS and arch, `graft-cli` per OS and arch, `SHA256SUMS` + signature, SBOM).
6. **Smoke test** on each OS, from the draft release's artifacts:
   - install over the previous version (the migration runs);
   - open a test repo; graph, commit, shelve and unshelve, push to a test remote;
   - `graft whoami` and `graft mcp` start.
7. **Publish** the GitHub Release (beta first for minor versions; stable after 7 days without `p0`).
8. **Channels:** update the Homebrew cask and formula, winget and Scoop manifests, Flathub, AUR, and the Claude Code plugin's version.
9. **Feed:** publish the signed appcast for the channel.
10. **Announce:** release notes in Discussions.

## R2: Pull a bad release

When a release corrupts data, crashes on start, or has a security hole:
1. **Stop the spread:** remove the version from the update feed (the appcast points back to the previous version). Mark the GitHub Release as pre-release with a warning at the top.
2. **Channels:** revert the Homebrew, winget, Scoop, Flathub and AUR updates where possible.
3. **Tell users:** a pinned issue and a Discussions post: what's affected, what to do (downgrade steps; whether data needs recovery, and how: the migration backups, [Storage Formats](06%20-%20Storage%20Formats.md)).
4. **Fix** on a `release/X.Y` branch, then release `X.Y.(Z+1)` through R1 with a shortened beta.
5. **Postmortem** in Discussions within 14 days: cause, why tests missed it, the test added.

## R3: A signing key leaks or is lost

1. **Revoke:**
   - Apple: revoke the Developer ID certificate in the developer portal.
   - Windows: ask SignPath or the CA to revoke the certificate.
   - Update-feed key: rotate it.
   - GPG: publish a revocation certificate.
2. **New keys:** generate them, store them in the GitHub environment secrets, and remove the old ones.
3. **Update-feed trust:** ship a release signed with the **old** feed key (if still trusted) that carries the **new** feed public key. If the old key is compromised, tell users to reinstall manually from the website.
4. **Re-sign** the current release with the new keys and republish its checksums.
5. **Disclose:** a security advisory, saying which releases are affected and how to verify a download.

## R4: Security report

1. Acknowledge in the private advisory within 7 days ([Governance](../03%20-%20Project/06%20-%20Governance.md)).
2. Reproduce; score its impact using the [Threat Model](../01%20-%20Foundations/07%20-%20Threat%20Model.md) entries it touches.
3. Fix in the private advisory fork; add a regression test.
4. Release through R1 with the advisory published at the same time; credit the reporter if they want it.
5. Add or update the threat in the Threat Model.

## R5: Leaked token in the repository or CI logs

1. Revoke the token at the provider immediately.
2. Remove it from logs (re-run or delete the CI logs) and, if it was committed, see [History Rewriting](../02%20-%20Features/30%20-%20History%20Rewriting.md). Rotation comes first; rewriting doesn't un-leak it.
3. Find how it got there; add a fixture-scanner rule or a redaction pattern so it can't happen again.
