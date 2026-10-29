# Batch File Names Implementation Plan

> **For agentic workers:** Use the established MediaLoader worktree and run the focused test before a release build.

## Design

The existing download confirmation dialog edits stems of selected media. Add one optional stem field above the individual rows. Applying it assigns deterministic numbered stems in selected item order when more than one item is selected. Each row gets a reset icon that restores only its original stem. A separate reset action restores every selected original stem. The existing per-row text fields remain editable after either action. Extensions remain controlled by `StorageNaming.customName` in the download path.

## Tasks

1. Add a pure naming helper and tests for numbering, single selection and reset to original names. Run the focused test red, then green.
2. Wire the dialog to the helper, add an accessible per-row reset icon and a batch name field with apply/reset actions. Keep the actions inside the scrollable content for narrow screens.
3. Run unit tests and arm64 release build, bump version to 1.7.27 (89), sign with the existing local key, verify and archive sources plus Git history.
