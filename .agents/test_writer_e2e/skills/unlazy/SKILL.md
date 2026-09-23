---
name: unlazy
description: Enforces completion discipline for substantial autonomous work by writing acceptance gates before execution, decomposing work with the Depth Tree, running approved checks, and re-verifying evidence before reporting. Use when an agent faces a long or multi-part task, work that has returned half-done, an exhaustive audit or build, parallel leaves or pipelines, or explicit triggers such as /unlazy, $unlazy, "tree N", "gates", and "do not stop until it is done".
---

# Unlazy

Make incomplete work visible and make completion testable. Prove outcomes against a ledger instead of relying on a confident done report.

## Write gates before real work
For solo work, create GATES.md from the local file templates/gates-leaf.md before implementing.
State one observable outcome per gate.
Treat CHECK: as code.
Work each leaf in four passes:
1. Implement complete deliverable.
2. Replace cheap version of each part.
3. Hunt correctness, integration, portability, performance, evidence defects.
4. Apply low-cost polish.
Audit final report.
