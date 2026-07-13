const configuredBase = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '');
const localDevBase = window.location.port === '5173' ? 'http://localhost:8080' : window.location.origin;
export const API_BASE = configuredBase || localDevBase;

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    },
    ...options
  });

  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      message = body.message || message;
    } catch {
      // Keep the HTTP error.
    }
    throw new Error(message);
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

export const api = {
  snapshot: () => request('/api/system/snapshot'),
  jobs: (status = '') =>
    request(`/api/jobs?limit=80${status ? `&status=${encodeURIComponent(status)}` : ''}`),
  attempts: (jobId) => request(`/api/jobs/${jobId}/attempts`),
  burst: (count = 1000, durationMs = 120) =>
    request('/api/control/burst', {
      method: 'POST',
      body: JSON.stringify({ count, durationMs, tenantId: 'burst-tenant' })
    }),
  inject503: () =>
    request('/api/control/inject-503', {
      method: 'POST',
      body: JSON.stringify({ count: 20, failAttempts: 3, durationMs: 180 })
    }),
  duplicate: () =>
    request('/api/control/duplicate', {
      method: 'POST',
      body: JSON.stringify({ requests: 100 })
    }),
  crashWindow: () =>
    request('/api/control/crash-window', { method: 'POST' }),
  killWorker: () =>
    request('/api/control/kill-worker', { method: 'POST' }),
  startWorker: () =>
    request('/api/control/start-worker', { method: 'POST' }),
  reset: () =>
    request('/api/control/reset', { method: 'DELETE' }),
  replay: (jobId) =>
    request(`/api/jobs/${jobId}/replay`, { method: 'POST' }),
  submitWorkflow: () =>
    request('/api/workflows', {
      method: 'POST',
      body: JSON.stringify({
        name: `customer-processing-${Date.now()}`,
        tenantId: 'workflow-tenant',
        nodes: [
          { key: 'ingest', jobType: 'INGEST', payload: { durationMs: 500, failureMode: 'NONE' }, dependsOn: [] },
          { key: 'validate', jobType: 'VALIDATE', payload: { durationMs: 450, failureMode: 'NONE' }, dependsOn: ['ingest'] },
          { key: 'score', jobType: 'SCORE', payload: { durationMs: 900, failureMode: 'NONE' }, dependsOn: ['validate'] },
          { key: 'enrich', jobType: 'ENRICH', payload: { durationMs: 700, failureMode: 'NONE' }, dependsOn: ['validate'] },
          { key: 'publish', jobType: 'PUBLISH', payload: { durationMs: 400, failureMode: 'NONE' }, dependsOn: ['score', 'enrich'] }
        ]
      })
    })
};

export function subscribeToEvents(onEvent, onStatus) {
  const source = new EventSource(`${API_BASE}/api/events`);

  source.onopen = () => onStatus?.('LIVE');
  source.onerror = () => onStatus?.('RECONNECTING');
  source.onmessage = (event) => {
    try {
      onEvent(JSON.parse(event.data));
    } catch {
      // Ignore malformed messages.
    }
  };

  const eventNames = [
    'CONNECTED',
    'JOB_SUBMITTED',
    'JOB_CLAIMED',
    'JOB_SUCCEEDED',
    'JOB_RETRY_SCHEDULED',
    'JOB_DEAD_LETTERED',
    'WORKER_LOST',
    'WORKER_STARTED',
    'LEASE_EXPIRED_JOB_RECOVERED',
    'DUPLICATE_SUPPRESSED',
    'IDEMPOTENCY_STRESS_COMPLETED',
    'DEPENDENCY_FAILURE_INJECTED',
    'BURST_ACCEPTED',
    'CRASH_WINDOW_INJECTED',
    'WORKFLOW_SUBMITTED',
    'WORKFLOW_JOB_UNBLOCKED',
    'STALE_COMPLETION_REJECTED',
    'SYSTEM_RESET',
    'KEEPALIVE'
  ];

  for (const name of eventNames) {
    source.addEventListener(name, (event) => {
      try {
        onEvent(JSON.parse(event.data));
      } catch {
        // Ignore malformed messages.
      }
    });
  }

  return () => source.close();
}
