# ADR-005: DAG workflow validation and dependency release

**Status:** Accepted

## Context

Staged backend work often has dependencies: `ingest -> validate -> {score, enrich} -> publish`. Accepting cyclic graphs would create workflows that can never become runnable.

## Decision

Relay validates workflow dependencies with Kahn's algorithm. Nodes with indegree zero enter `QUEUED`; the rest enter `BLOCKED`. Successful jobs atomically evaluate direct children and queue a child only after every parent is `SUCCEEDED`.

## Consequences

The dependency model makes graph correctness explicit and allows independent branches to execute concurrently. A dead-lettered node marks the workflow failed and leaves blocked descendants with `DEPENDENCY_FAILED` context.
