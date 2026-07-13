import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 100,
  iterations: 100,
};

const base = __ENV.RELAY_URL || 'http://localhost:8080';
const idempotencyKey = __ENV.IDEMPOTENCY_KEY || 'order-2026-991';

export default function () {
  const response = http.post(
    `${base}/api/jobs`,
    JSON.stringify({
      tenantId: 'idempotency-test',
      jobType: 'SIMULATED_SIDE_EFFECT',
      payload: {
        durationMs: 250,
        failureMode: 'NONE',
        effectKey: idempotencyKey,
      },
      idempotencyKey,
    }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  check(response, {
    'accepted': (r) => r.status === 202,
  });
}
