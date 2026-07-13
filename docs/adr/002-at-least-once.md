# ADR-002: At-least-once execution

**Status:** Accepted

## Context

A worker can perform an external side effect, then crash before the `SUCCEEDED` transition is committed. No local database transaction can atomically commit an arbitrary external side effect.

## Decision

Relay explicitly provides at-least-once execution. Duplicate execution is possible after lease recovery. Side-effecting handlers must provide an idempotency key at the business-action boundary.

## Consequences

The guarantee matches the real crash window. Relay never markets fake exactly-once semantics. The control plane includes a failure injection that creates a side effect, terminates the worker before acknowledgement, and lets the recovered attempt prove duplicate suppression.
