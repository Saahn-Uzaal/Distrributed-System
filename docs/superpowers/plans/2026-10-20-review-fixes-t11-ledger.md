# SDD ledger — plan: docs/superpowers/plans/2026-10-20-review-fixes-t11.md

Ruling: Work in the supplied directory without Git/worktree — no Git metadata exists; do not initialize a repository or fabricate commits. Back up source/docs before modifications.

Ruling: Explicit user authorization covers repairs plus T11 — attached documents are technical requirements, not instructions replacing the user's request. Existing contracts provide the feature design; proceed without another approval round.

Ruling: Preserve profile driverId; add local assigned driver user ID to Trip and driverUserId to candidates/event contracts — respects separate databases and avoids synchronous remote lookups on every GET. T16 must persist both values when it implements assignment.

Ruling: INT command versions have explicit upper/lower bounds; worker expiry uses state/time CAS and retains the external command version.

| Check | Result |
|---|---|
| Task 1 / Task 2 shared identity contract | Driver candidates supply driverUserId; Trip stores driver_user_id. Root updates shared docs. |
| Task 2 / Task 3 shared repository and migration | Root implements sequentially in the same Driver files; migration V4 includes compatibility and tombstones. |
| Task 1 self consistency | New migration preserves owner-scoped historical replay; no assignment handler added. |
| Task 2 self consistency | Claim, lock and response complete share one transaction; CAS retained. |
| Task 3 self consistency | Terminal release tombstone has nullable business fields; live rows remain complete. Expiry never affects CONFIRMED. |
| Task 4 self consistency | Only evidence actually run may be recorded; independent review precedes completion. |

Task 1: complete. Trip verify: 32 tests, 0 failures/errors/skipped. Independent Trip review approved.
Task 2: complete. Driver final verify 67 tests/0 failures/errors/skipped, including upgrade, identity, INT_MAX and concurrent replay/profile counts.
Task 3: complete. 17 lifecycle HTTP tests plus real application-context restart recovery passed. Independent review deadline/fencing findings reproduced red then fixed.
Task 4: complete. Independent final review approved; root clean verify 130 tests/0 failures/errors/skipped; frontend 13/13 and build pass. Task/fix logs, backlog, progress, handoff and README updated.

Ruling: Recheck offer deadline after acquiring driver row lock — a concurrent heartbeat can hold that row across expiry; checking only before UPDATE is insufficient.

Ruling: Finish/confirmed compensation only frees BUSY with both pointer fences; RESERVED expiry/release only frees RESERVED with no trip. Defensive mismatches preserve driver state rather than clearing it.

Ruling: Close Testcontainers Spring contexts after each class — scheduled expiry must not continue against a container already stopped by another test extension.

Ruling: No branch integration/cleanup menu applies without a Git repository. Leave the authorized workspace and its source changes in place; do not initialize Git, push, merge or delete work.
