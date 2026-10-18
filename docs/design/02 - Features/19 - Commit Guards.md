---
tags: [graft, feature, security, safety]
status: seedling
milestone: G2
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 19 - Commit Guards

Checks before commit and push that catch things nobody wants in Git history: tokens, private keys, `.env` files, huge binaries. AI agents commit these by accident more often than people, so the guards also cover commits Graft didn't make ([ADR-17](../03%20-%20Project/02%20-%20Decisions.md)).

## Checks

| Check | Detects | Default |
|---|---|---|
| **Secrets** | known token formats (`ghp_`, `github_pat_`, `glpat-`, `AKIA…`, `sk-ant-`, `sk-`, `xox[bp]-`, Stripe, Google API keys), private key headers (`-----BEGIN … PRIVATE KEY-----`), high-entropy strings next to `password`/`secret`/`token` | block |
| **Sensitive files** | `.env*` (except `.env.example`), `id_rsa`, `id_ed25519`, `*.pem`, `*.p12`, `*.keystore`, `*.jks`, `credentials.json`, `google-services.json` (warn only) | block |
| **Large files** | files over 10 MB; suggest Git LFS | warn; block over 100 MB (GitHub's hard limit) |
| **Conflict markers** | `<<<<<<<`, `>>>>>>>` left in text files | block |
| **Case collisions** | `Readme.md` and `README.md`, which break checkouts on macOS and Windows | warn |

Rules are local regexes and file patterns. Content never leaves the machine.

## Where they run

| Point | Covers |
|---|---|
| Commit in Graft | the staged changes or changelist being committed |
| **Push from Graft** | every commit in the push range, so commits made by agents or the terminal are checked before they leave |
| `graft guard` CLI | any tool: `graft guard --staged` (pre-commit), `graft guard --range A..B` (pre-push) |
| Git hooks (opt-in) | Graft offers to add `graft guard` to an existing hook manager (pre-commit, lefthook, husky). It installs its own `.git/hooks/pre-commit` only if no hook exists, and never replaces one |
| Claude Code hook (opt-in) | a `PreToolUse` hook on `git commit` / `git push` commands runs `graft guard` |

## When a check fires

- The dialog shows the file, line and rule, with the secret masked (`ghp_****…a91F`).
- **Fix options:** unstage the file, add it to `.gitignore`, move it to LFS, or open it at the line.
- **Allow once:** needs an explicit click and is recorded in the op log.
- **Allow always for this finding:** stores the finding's fingerprint in `.git/graft/guard-allow.json` (local), or in a committed `.graft/guard.toml` if the team wants shared exceptions.
- **Already pushed?** If a secret is found in commits already on the remote, the message says to **rotate the secret first**. Removing it from history comes second ([History Rewriting](30%20-%20History%20Rewriting.md)).

## Edge cases

| Case | Decided behavior |
|---|---|
| Test fixtures with fake keys | allow by path pattern in `.graft/guard.toml` |
| Binary files | only the size and file-name checks apply |
| Push of hundreds of commits | the range is scanned once as a combined diff, streamed |
| User disables guards | per repo; the status bar shows "guards off" |
