# ADR-003: Lease-based recovery

**Status:** Accepted

## Context

A permanent `RUNNING` flag cannot distinguish active work from an abandoned job after process failure.

## Decision

Worker ownership is time-limited. Claiming a job records `lease_owner` and `lease_expires_at`. Active workers heartbeat and extend leases. A scheduled reaper atomically requeues expired jobs and marks unfinished attempts `ABANDONED`.

## Consequences

Crash recovery time is bounded by heartbeat and lease configuration. A temporarily partitioned worker can lose ownership and later continue an external side effect, which is another reason the execution model requires idempotent handlers.
