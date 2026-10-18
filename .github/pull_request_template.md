## What and why

<!-- What this changes and why. Link the issue: "Closes #123". -->

## How it was tested

<!-- Tests added or changed, and what you ran. Paste real output for anything that failed. -->

## Checklist

- [ ] `./gradlew check` passes
- [ ] Every commit is signed off (`git commit -s`)
- [ ] The PR title is a Conventional Commit with the module as scope (`feat(core-git): …`)
- [ ] New git commands have parser fixture tests; new provider calls have recorded-response tests
- [ ] Tests that run git sandbox `HOME`, git config, SSH and the keychain
- [ ] No tokens, keys or local paths in code, fixtures or logs
- [ ] A change to a contract, storage format, module edge or the provider SPI has an accepted ADR: <!-- link -->
- [ ] User-facing text follows the UX Writing note
