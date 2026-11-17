# Draft: ADR-27 (the graph's view choices in `state.json`)

Status: **accepted by the author (2026-11-16)**, from G1 Task 13. It goes into `docs/design/03 - Project/02 - Decisions.md` and Storage Formats gets the field list. The data change is applied in Task 13; the notes are the author's to edit.

---

## The ADR (text for `02 - Decisions.md`)

### ADR-27 The graph's view choices are saved in `state.json` v1 as optional fields (Accepted 2026-11-16; extends Storage Formats' "Repo UI state")

- **Context:**
  - The graph screen (G1 Task 13, S4) has view choices that Commit Graph and UI Design expect to persist per repository ("the window remembers its layout per repository"). They are: the branch filter (all branches or the current branch), hide remotes, first parent, the author types shown, and the table's column widths and hidden columns.
  - D8 (G1 plan) offers to write a commit-graph file once per repository ("never silent"). "Once" has to survive a restart, or the offer comes back every time the repository opens.
  - `$GIT_COMMON_DIR/graft/state.json` v1 already holds the left panel's `hiddenRefs`, `soloRefs` and `pinnedRefs`, shared by every worktree of the repository. Storage Formats' rule 2 says readers ignore unknown fields. Rule 6 says format changes need an ADR.
- **Decision:**
  - `state.json` stays at **`"version": 1`**. Two optional objects are added; a missing object or field means its default:

    ```json
    {
      "version": 1,
      "hiddenRefs": [], "soloRefs": [], "pinnedRefs": [],
      "graph": {
        "branches": "all",
        "hideRemotes": false,
        "firstParent": false,
        "authorTypes": ["person", "aiAssisted", "bot"],
        "commitGraphOffered": false
      },
      "columns": {
        "refs": 160, "graph": null, "author": 140, "date": 90, "sha": 70
      }
    }
    ```

  - `graph.branches` is `"all"` or `"current"`; non-empty `soloRefs` overrides it (the menu then reads "{n} selected"). `authorTypes` lists the types shown; leaving one out dims its commits (the author's 2026-11-16 ruling). An unknown value in `branches` or `authorTypes` is ignored, and its default is used.
  - `columns` maps a column id to its width in dp, or `null` when it's hidden. Ids the screen doesn't know are ignored (`TableColumnsState.restore`). The message column fills the rest, so it's never saved.
  - `commitGraphOffered` turns true when the offer is shown and answered, either way. Writing the file needs no record, since git keeps the file.
  - A worktree shares these choices with the main working tree, as it already shares hide, solo and pin.
- **Consequences:**
  - No migration and no version bump: older Graft builds ignore the new objects, and a file without them reads as the defaults. A fixture of a v1 file without `graph` and `columns` pins that it still reads.
  - When a v1 file is rewritten after an older build read it, the new objects survive only if that build kept unknown fields. Graft's `JsonStore` rewrites from its own record, so an older Graft drops them. That's acceptable: they're view choices, and the defaults are safe.
  - Later additions to the graph's choices (column order, density per repository) extend these objects under the same rule, recorded as a ruling rather than a new ADR, as long as they're optional view choices.

---

## Changes after acceptance

- `docs/design/03 - Project/02 - Decisions.md`: ADR-27 as above (the author edits the notes, then `scripts/sync-design-docs.py`).
- `docs/design/04 - Development/06 - Storage Formats.md`: the "Repo UI state" row points to ADR-27 for its fields.
- `:module:data:app`:
  - `RepositoryUiState` gains `graph: GraphViewState` and `columns: Map<String, Float?>`.
  - `RepositoryStateRecord` gains the two optional objects.
  - A fixture of today's v1 file, without them, pins that it reads as the defaults.
  - A round-trip test.
