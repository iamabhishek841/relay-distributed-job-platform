import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 5,
  duration: '20s',
};

const base = __ENV.RELAY_URL || 'http://localhost:8080';

export default function () {
  const payload = JSON.stringify({
    tenantId: `tenant-${__VU % 3}`,
    jobType: 'SIMULATED',
    payload: {
      durationMs: 120,
      failureMode: 'NONE',
    },
    maxAttempts: 5,
  });

  const response = http.post(`${base}/api/jobs`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(response, {
    'accepted': (r) => r.status === 202,
  });

  sleep(0.01);
}
