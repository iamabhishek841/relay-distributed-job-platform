# ADR-004: Server-Sent Events for the operational control plane

**Status:** Accepted

## Context

The browser needs low-latency state-transition events from the backend. The primary traffic direction is server to browser; bidirectional application messaging is not required because controls already use REST.

## Decision

Relay uses Server-Sent Events for sampled execution transitions and important failure/recovery events. The UI polls the durable system snapshot for authoritative counts and uses SSE for movement and operator context.

## Consequences

The live architecture view reflects backend events without introducing a WebSocket protocol layer. Event sampling prevents burst workloads from trying to render every one of thousands of job transitions.
