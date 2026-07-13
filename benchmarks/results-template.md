# Relay benchmark record

Record the environment before using any number in a README, resume, or interview.

## Environment

- Date:
- Commit:
- CPU:
- Memory:
- PostgreSQL version / plan:
- Relay instances:
- Workers per instance:
- Database pool size:
- Lease duration:
- Job duration / failure mode:

## Worker scaling

| Workers | Accepted jobs | Completion jobs/s | P95 execution | P99 execution | DB active conns | Peak queue |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | | | | | | |
| 2 | | | | | | |
| 4 | | | | | | |
| 8 | | | | | | |
| 16 | | | | | | |

## Burst load

- Submitted:
- Submission duration:
- Peak arrival rate:
- Peak queue depth:
- Queue drain time:
- Completed:
- Dead-letter:
- Unaccounted durable states:

## Worker crash recovery

- Worker:
- Jobs owned at termination:
- Last heartbeat:
- Lease expiry:
- Reaper recovery:
- First healthy reclaim:
- Recovery latency:
- Duplicate side effects:

## Idempotency

- Concurrent requests:
- Idempotency key:
- Unique job IDs:
- Duplicate responses:
- Side effects created:

## Notes

Describe the bottleneck and the technical mechanism behind any before/after number.
