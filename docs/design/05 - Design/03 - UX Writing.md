---
tags: [graft, design, writing]
status: seedling
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 03 - UX Writing

How Graft talks. Terms come from the [Glossary](../01%20-%20Foundations/09%20-%20Glossary.md).

## Voice

- **Plain and short.** Say what happened and what to do. No jokes in errors, no exclamation marks.
- **Git words where they're the right words** (commit, branch, rebase), explained once in a tooltip for beginners. Never invent synonyms for Git terms.
- **The user is in charge.** Graft suggests ("Pull first, then push"), never scolds.
- **Sentence case** everywhere: "Create pull request", not "Create Pull Request".

## Buttons and menus

| Rule | Example |
|---|---|
| Verb first, specific | **Shelve 3 files**, not "OK" |
| The button repeats the dialog's action | dialog "Delete branch feature/login?" → buttons **Delete branch** / **Cancel** |
| Ellipsis when more input follows | **Rename branch…** |
| Destructive actions are named, never "Yes" | **Discard changes**, **Force push** |
| Counts when they matter | **Push 2 commits** |

## Errors

Pattern: **what happened**, then **why** if known, then **what to do**, then the **code**.

> Push was rejected because `origin/main` has commits you don't have.
> Pull first, then push again. · `GIT-E-PUSH-REJ`
> **Pull and push** · **Show output**

- Git's own output is one click away (**Show output**), never the main message.
- No blame: "Couldn't reach github.com", not "You are offline".
- Codes always shown, so support reports can find the cause ([Conventions](../04%20-%20Development/02%20-%20Conventions.md)).

## Confirmations for destructive actions

Ask only when work can be lost and can't be undone from Graft. Otherwise, do it and offer **Undo**.

| Situation | Behavior |
|---|---|
| Undoable through the op log or a snapshot (reset, discard, branch delete with reflog) | no dialog; a toast with **Undo** for 10 s |
| Not undoable (force push, clean of ignored files, history rewrite, delete remote branch) | a dialog that **lists what will be lost** ("4 commits not on any other branch") and names the action on the button |
| Very destructive (history rewrite, remove worktree with changes) | the dialog also shows the backup being made |

## Empty states

One sentence on why it's empty, plus the action. Examples:
- Shelves: "No shelves yet. Shelve changes from the changes panel to set them aside." **Shelve changes**
- Pull requests: "No open pull requests in this repository." **Create pull request**
- Agents: "No agent tasks. Start one to let an agent work in its own worktree." **New agent task**

## Numbers, dates, names

- Relative times under a week ("2 h ago"), then dates in the OS format; the exact time in a tooltip.
- Hashes: 7 characters in the UI, full on copy.
- Branch names, paths and commands in monospace.
- Names as Git has them; never "corrected".

## Localization (English, Indonesian)

- All strings in resource files with keys (`shelf.empty.title`). No sentences built from pieces; use placeholders (`{count} files`) with plural forms.
- Git terms stay in English in Indonesian text, the way Indonesian developers use them: "**Commit** 3 file", "**Push** ditolak". UI-only words are translated: "Simpan ke shelf", "Batalkan".
- A glossary column for Indonesian is added to the Glossary when translation starts (G8).
