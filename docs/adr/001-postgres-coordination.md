# ADR-001: PostgreSQL as the coordination boundary

**Status:** Accepted

## Context

Relay needs durable job state, worker ownership, idempotency, attempt history, retries, and workflow dependency state. Adding a broker before measuring a queue bottleneck would split the correctness model across more infrastructure.

## Decision

PostgreSQL is the first coordination boundary. Runnable jobs are claimed with an atomic CTE using `FOR UPDATE SKIP LOCKED`. Job state and attempt history remain queryable in one transactional system.

## Consequences

The design is easy to reason about and exposes strong SQL-level invariants. The expected scaling limit is database claim contention and connection pressure as worker count rises. That is a measured future migration trigger, not a reason to add Kafka by default.
