---
tags: [graft, feature, ssh]
status: seedling
milestone: G3
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 12 - SSH

Set up SSH for Git without the terminal: create a key, add it to GitHub, trust the host, test the connection. Graft uses the system OpenSSH (`ssh`, `ssh-keygen`, `ssh-agent`) and never reimplements SSH ([ADR-12](../03%20-%20Project/02%20-%20Decisions.md)).

## Two modes per profile

| Mode | How Git connects | For |
|---|---|---|
| **System SSH** (default) | plain `ssh`: the user's `~/.ssh/config`, agent and keys, untouched | people who already have SSH set up |
| **Managed key** | the profile's git config sets `core.sshCommand = ssh -i <key> -o IdentitiesOnly=yes`, so Graft, the terminal and AI agents all use it ([Terminal and AI Agents](13%20-%20Terminal%20and%20AI%20Agents.md)) | one key per profile, e.g. separate Personal and Work GitHub accounts on the same `github.com` |

Managed mode is what makes several accounts on one host work without host aliases (`github.com-work`) in `~/.ssh/config`.

**Non-interactive use (AI agents):** tools like Claude Code can't answer SSH prompts. The wizard therefore stores the passphrase in the agent/keychain and trusts the host key up front. An optional "never prompt" setting adds `-o BatchMode=yes`, so an agent gets a clear error instead of hanging.

## Setup wizard

1. **Find existing keys** in `~/.ssh` (`*.pub`) and the agent (`ssh-add -L`). Offer to use one.
2. **Or generate a key:**
   - `ssh-keygen -t ed25519 -C "<profile email>" -f ~/.ssh/graft_<profile>_ed25519`;
   - RSA 4096 only when a server needs it;
   - passphrase recommended; it can be stored in the OS keychain.
3. **Add to the agent:**
   - macOS: `ssh-add --apple-use-keychain`;
   - Windows: the OpenSSH Authentication Agent service (Graft offers to start it);
   - Linux: the running agent or gnome-keyring.
4. **Add to GitHub:** one click with the profile's account (`POST /user/keys`, needs the `write:public_key` scope, asked only now). Optionally also as a **signing key** (`POST /user/ssh_signing_keys`). Without an account: copy the public key and open the GitHub settings page.
5. **Test:** `ssh -T git@github.com`, then show "Authenticated as <login>". Warn if that login isn't the profile's account.
6. **Use it:** offer to switch the repo's `origin` from HTTPS to SSH (`git remote set-url`).

## Host keys

- On a first connection, `ssh` asks to trust the host key. The question arrives through `SSH_ASKPASS` as a Graft dialog with the fingerprint.
- For known providers Graft compares it with the provider's published fingerprints (GitHub: `api.github.com/meta`) and shows **verified** or a red **mismatch** warning.
- Graft never sets `StrictHostKeyChecking=no`. Accepted keys go to the normal `~/.ssh/known_hosts`.

## Commit signing with SSH

Profile setting "Sign commits with this SSH key" sets, on Graft's commands, `gpg.format=ssh`, `user.signingkey=<pub key>`, `commit.gpgsign=true`. To show "Verified" for others' commits, Graft keeps an `allowed_signers` file built from the profile's own keys (and, later, from provider-published keys).

## Key management screen

List keys (type, fingerprint, comment, where used: which profiles, whether loaded in the agent, whether on GitHub), copy the public key, change passphrase (`ssh-keygen -p`), remove from agent, delete from GitHub, delete files (asked, with the profiles that use the key listed).

## Edge cases

| Case | Decided behavior |
|---|---|
| Windows has two OpenSSH copies (Windows' and Git for Windows') | Graft uses the one `git` uses (`core.sshCommand`, else Git's bundled ssh), so agent and known_hosts are consistent, and shows which in settings |
| Key with passphrase, no agent | the passphrase prompt comes through askpass; "remember in keychain" offered |
| Hardware keys (`ed25519-sk`, YubiKey) | supported through OpenSSH; Graft shows "touch your security key" while waiting |
| Corporate SSH config with `ProxyJump` / custom ports | system mode respects it; managed mode only adds `-i` and `IdentitiesOnly`, so the rest of `~/.ssh/config` still applies |
| Key file permissions too open | `ssh` refuses it; Graft offers to fix (`chmod 600`, or the Windows ACL equivalent) |
| Existing file at the generated path | never overwritten; a numbered name is used |
