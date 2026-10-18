---
tags: [graft, feature, ci]
status: seedling
milestone: G9
---
<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->

# 24 - CI and Actions

GitHub Actions in Graft. The provider-neutral name is `Pipeline`, which GitLab pipelines and Azure Pipelines fit later. `PipelineApi` is a capability in the [Provider Model](../01%20-%20Foundations/04%20-%20Provider%20Model.md).

## Where CI shows

- **Graph:** a status dot on branch labels and commits (success, failure, running, pending), from check suites on the commit.
- **PR detail:** the Checks tab ([Pull Requests](09%20-%20Pull%20Requests.md)).
- **Actions pane** per repository: workflow runs, filterable by workflow, branch, status, actor, event.

## Run detail

| Part | Content |
|---|---|
| Jobs | list with status and duration; the dependency graph for `needs:` |
| Steps and logs | logs streamed while running; search; ANSI colors rendered; folded groups (`::group::`) |
| Annotations | errors and warnings mapped to file:line, and shown in Graft's diff and file views |
| Artifacts | list and download (asks where to save) |

## Actions

Re-run all jobs, re-run failed jobs, re-run with debug logging, cancel, approve a pending deployment (if permitted), and **run a workflow** (`workflow_dispatch`) with an inputs form generated from the workflow's `inputs:`.

## Polling

Runs in progress refresh every 10 s while visible, and every 60 s otherwise for your own PRs (feeding "CI failed" notifications in [My Work](18%20-%20My%20Work%20and%20Notifications.md)). Logs are fetched only when opened.

## Edge cases

| Case | Decided behavior |
|---|---|
| Fork PR runs waiting for approval | shown as "awaiting approval" with an Approve button for maintainers |
| Very long logs | streamed and virtualized; "download full log" |
| Required checks vs optional | required ones marked; merge button explains which are missing |
