# Security policy

Graft handles credentials, SSH keys and your Git configuration, so security reports get priority.

## Report a vulnerability

**Don't open a public issue.** Report it privately through GitHub: [open a private report](https://github.com/srctool/graft/security/advisories/new) (Security tab → Report a vulnerability). Only the maintainers can see it.

Include what you can: the Graft version (or commit), OS, Git version, steps to reproduce, and the impact you see. Leave tokens, passwords and private keys out of the report; if one is needed to show the problem, use a throwaway.

| | Within |
|---|---|
| Acknowledgement | 7 days |
| Fix or mitigation plan, for high severity | 30 days |

We'll keep you updated, agree on a disclosure date with you, and credit you in the advisory unless you'd rather not be named.

## Supported versions

Graft is pre-alpha: only the latest commit on `main` gets fixes. Once releases start, this section will list the supported versions.

## In scope

Anything that could expose or misuse your credentials, keys or repositories, for example:

- tokens or passphrases leaking into files, logs, crash reports or command-line arguments;
- Graft running a command, hook or shell it shouldn't (argument or path injection);
- writes to `~/.gitconfig`, `~/.ssh` or a repository's config outside what you approved;
- the CLI or MCP server doing more than its documented commands allow;
- untrusted content (Markdown, diffs, remote data) executing or loading anything.

The [Threat Model](docs/design/01%20-%20Foundations/07%20-%20Threat%20Model.md) lists the threats Graft is designed against.

## A leaked secret in this repository

If you find a token or key committed here or printed in CI logs, report it the same way. The maintainers rotate the secret first, then clean up.
