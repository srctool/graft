@AGENTS.md

## Claude Code specifics

- Read the design in `docs/design/`. The Obsidian source path is in `CLAUDE.local.md`; sync with `GRAFT_DESIGN_DOCS="<that path>" scripts/sync-design-docs.py` when the user asks, or when `--check` reports it's out of date. Paths contain spaces, so quote them.
- Starting a milestone with no plan: draft `plans/<milestone>.md` from the Roadmap and the notes it names, then stop for review.
- Use the task list for the current plan task's steps, not for the whole milestone.
- After G0 creates code, index the repo with GitNexus (`npx gitnexus analyze`), as in Sinew and Camouflage. Its block is then added below; follow it.
- Never edit files in the design docs folder unless the user asks.
