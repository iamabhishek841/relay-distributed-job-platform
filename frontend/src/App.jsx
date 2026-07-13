import React, { useEffect, useMemo, useRef, useState } from 'react';
import { api, API_BASE, subscribeToEvents } from './api.js';

const tabs = [
  ['system', 'Live System'],
  ['jobs', 'Job Explorer'],
  ['metrics', 'System Metrics'],
  ['notes', 'Engineering Notes']
];

const statusTone = {
  ACTIVE: 'good',
  SUCCEEDED: 'good',
  RUNNING: 'live',
  QUEUED: 'neutral',
  RETRY_WAIT: 'warn',
  DEAD: 'bad',
  DEAD_LETTER: 'bad',
  CANCELLED: 'muted',
  BLOCKED: 'muted'
};

function formatNumber(value, digits = 0) {
  const number = Number(value || 0);
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: digits }).format(number);
}

function shortId(value) {
  if (!value) return '—';
  return value.length > 14 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
}

function timeAgo(value) {
  if (!value) return '—';
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000));
  if (seconds < 2) return 'now';
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m`;
}

function App() {
  const [tab, setTab] = useState('system');
  const [snapshot, setSnapshot] = useState(null);
  const [history, setHistory] = useState([]);
  const [events, setEvents] = useState([]);
  const [streamStatus, setStreamStatus] = useState('CONNECTING');
  const [busyAction, setBusyAction] = useState('');
  const [toast, setToast] = useState(null);
  const [jobs, setJobs] = useState([]);
  const [jobFilter, setJobFilter] = useState('');
  const [selectedJob, setSelectedJob] = useState(null);
  const [attempts, setAttempts] = useState([]);
  const eventCounter = useRef(0);

  const refreshSnapshot = async () => {
    try {
      const next = await api.snapshot();
      setSnapshot(next);
      setHistory((current) => [
        ...current.slice(-59),
        {
          at: new Date(next.timestamp).getTime(),
          queue: next.queueDepth,
          throughput: next.throughputPerSecond,
          running: next.jobCounts?.RUNNING || 0
        }
      ]);
    } catch (error) {
      setToast({ type: 'bad', message: `Backend unavailable: ${error.message}` });
    }
  };

  const refreshJobs = async () => {
    try {
      setJobs(await api.jobs(jobFilter));
    } catch (error) {
      setToast({ type: 'bad', message: error.message });
    }
  };

  useEffect(() => {
    refreshSnapshot();
    const timer = window.setInterval(refreshSnapshot, 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const unsubscribe = subscribeToEvents(
      (event) => {
        if (event.eventType === 'KEEPALIVE' || event.eventType === 'CONNECTED') return;
        eventCounter.current += 1;
        setEvents((current) => [
          { ...event, localId: eventCounter.current },
          ...current
        ].slice(0, 60));
      },
      setStreamStatus
    );
    return unsubscribe;
  }, []);

  useEffect(() => {
    if (tab === 'jobs') {
      refreshJobs();
      const timer = window.setInterval(refreshJobs, 2000);
      return () => window.clearInterval(timer);
    }
  }, [tab, jobFilter]);

  const runAction = async (name, action) => {
    setBusyAction(name);
    setToast(null);
    try {
      const result = await action();
      setToast({ type: 'good', message: `${name}: ${JSON.stringify(result)}` });
      await refreshSnapshot();
      if (tab === 'jobs') await refreshJobs();
    } catch (error) {
      setToast({ type: 'bad', message: `${name}: ${error.message}` });
    } finally {
      setBusyAction('');
    }
  };

  const openJob = async (job) => {
    setSelectedJob(job);
    setAttempts([]);
    try {
      setAttempts(await api.attempts(job.id));
    } catch (error) {
      setToast({ type: 'bad', message: error.message });
    }
  };

  const activeWorkers = snapshot?.workers?.filter((worker) => worker.status === 'ACTIVE').length || 0;

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark">R</div>
          <div>
            <div className="brand-name">RELAY</div>
            <div className="brand-subtitle">Distributed Job Execution &amp; Recovery Platform</div>
          </div>
        </div>
        <div className="system-strip">
          <span className={`live-pill ${streamStatus === 'LIVE' ? 'online' : ''}`}>
            <span className="pulse-dot" />
            SYSTEM {streamStatus}
          </span>
          <span><strong>{activeWorkers}</strong> workers</span>
          <span>queue <strong>{formatNumber(snapshot?.queueDepth)}</strong></span>
          <span><strong>{formatNumber(snapshot?.throughputPerSecond, 1)}</strong> jobs/s</span>
        </div>
      </header>

      <nav className="tabs">
        {tabs.map(([id, label]) => (
          <button
            key={id}
            className={tab === id ? 'tab active' : 'tab'}
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
        <div className="api-origin">API · {API_BASE.replace(/^https?:\/\//, '')}</div>
      </nav>

      <main>
        {toast && (
          <button className={`toast ${toast.type}`} onClick={() => setToast(null)}>
            {toast.message}
          </button>
        )}

        {tab === 'system' && (
          <ControlRoom
            snapshot={snapshot}
            events={events}
            history={history}
            busyAction={busyAction}
            runAction={runAction}
          />
        )}

        {tab === 'jobs' && (
          <JobExplorer
            jobs={jobs}
            filter={jobFilter}
            setFilter={setJobFilter}
            selectedJob={selectedJob}
            attempts={attempts}
            openJob={openJob}
            replay={(id) => runAction('REPLAY JOB', () => api.replay(id))}
          />
        )}

        {tab === 'metrics' && <Metrics snapshot={snapshot} history={history} />}
        {tab === 'notes' && <EngineeringNotes />}
      </main>
    </div>
  );
}

function ControlRoom({ snapshot, events, history, busyAction, runAction }) {
  const recentFlow = events.slice(0, 8);
  const counts = snapshot?.jobCounts || {};

  return (
    <div className="control-layout">
      <section className="architecture-panel panel">
        <div className="panel-heading">
          <div>
            <div className="eyebrow">LIVE CONTROL PLANE</div>
            <h1>Execution topology</h1>
          </div>
          <div className="invariant">DURABILITY INVARIANT · ACCEPTED JOBS HAVE DURABLE STATE</div>
        </div>

        <div className="architecture">
          <div className="arch-row ingress">
            <ArchNode title="Clients" subtitle="REST / workflow submissions" metric={`${formatNumber(counts.QUEUED || 0)} queued`} kind="client" />
            <FlowLine active={recentFlow.some((event) => event.eventType.includes('SUBMITTED') || event.eventType === 'BURST_ACCEPTED')} />
            <ArchNode title="Job API" subtitle="idempotency + durable submit" metric={`${formatNumber(snapshot?.throughputPerSecond, 1)} jobs/s`} kind="api" />
            <FlowLine active={recentFlow.some((event) => event.eventType === 'BURST_ACCEPTED')} />
            <ArchNode title="PostgreSQL" subtitle="durable queue + leases" metric={`depth ${formatNumber(snapshot?.queueDepth)}`} kind="db" />
          </div>

          <div className="fanout-line" />

          <div className="worker-grid">
            {(snapshot?.workers || []).map((worker) => (
              <WorkerNode key={worker.id} worker={worker} events={recentFlow} />
            ))}
            {!snapshot?.workers?.length && <div className="empty-state">Waiting for worker fleet…</div>}
          </div>

          <div className="recovery-row">
            <ArchNode
              title="Lease Reaper"
              subtitle="expired ownership recovery"
              metric={`${formatNumber(snapshot?.recoveredJobs)} recovered`}
              kind="reaper"
            />
            <div className="recovery-copy">
              <span>Heartbeat stops</span>
              <span>→</span>
              <span>Lease expires</span>
              <span>→</span>
              <span>Job requeued</span>
              <span>→</span>
              <span>Healthy worker reclaims</span>
            </div>
          </div>
        </div>

        <div className="control-bar">
          <ActionButton label="RUN 1K BURST" busy={busyAction} onClick={() => runAction('BURST LOAD', () => api.burst(1000))} />
          <ActionButton label="TERMINATE WORKER" busy={busyAction} onClick={() => runAction('TERMINATE WORKER', api.killWorker)} danger />
          <ActionButton label="INJECT HTTP 503" busy={busyAction} onClick={() => runAction('INJECT 503', api.inject503)} />
          <ActionButton label="SUBMIT DUPLICATES" busy={busyAction} onClick={() => runAction('IDEMPOTENCY STRESS', api.duplicate)} />
          <ActionButton label="CRASH AFTER SIDE EFFECT" busy={busyAction} onClick={() => runAction('CRASH WINDOW', api.crashWindow)} danger />
          <ActionButton label="RUN DAG WORKFLOW" busy={busyAction} onClick={() => runAction('DAG WORKFLOW', api.submitWorkflow)} />
          <ActionButton label="START REPLACEMENT" busy={busyAction} onClick={() => runAction('START WORKER', api.startWorker)} />
          <ActionButton label="RESET SYSTEM" busy={busyAction} onClick={() => runAction('RESET', api.reset)} muted />
        </div>
      </section>

      <aside className="event-panel panel">
        <div className="panel-heading compact">
          <div>
            <div className="eyebrow">BACKEND EVENT STREAM</div>
            <h2>Execution events</h2>
          </div>
        </div>
        <div className="event-list">
          {events.length === 0 && <div className="empty-state">Waiting for state transitions…</div>}
          {events.map((event) => (
            <div key={event.localId} className="event-row">
              <span className={`event-dot ${eventTone(event.eventType)}`} />
              <div className="event-body">
                <strong>{event.eventType.replaceAll('_', ' ')}</strong>
                <span>{shortId(event.entityId)} · {new Date(event.timestamp).toLocaleTimeString()}</span>
              </div>
            </div>
          ))}
        </div>
      </aside>

      <section className="metric-grid full-width">
        <MetricCard label="Queue Depth" value={formatNumber(snapshot?.queueDepth)} detail={trend(history, 'queue')} />
        <MetricCard label="Throughput" value={`${formatNumber(snapshot?.throughputPerSecond, 1)}/s`} detail="successful jobs · last 60s" />
        <MetricCard label="P95 Execution" value={`${formatNumber(snapshot?.p95ExecutionMs)} ms`} detail="successful attempts · 1h" />
        <MetricCard label="P99 Execution" value={`${formatNumber(snapshot?.p99ExecutionMs)} ms`} detail="successful attempts · 1h" />
        <MetricCard label="Retries" value={formatNumber(snapshot?.retriesLastHour)} detail="repeat attempts · 1h" />
        <MetricCard label="Recovered Jobs" value={formatNumber(snapshot?.recoveredJobs)} detail="expired leases reclaimed" />
        <MetricCard label="Dead Letter" value={formatNumber(counts.DEAD_LETTER)} detail="operator review required" bad={counts.DEAD_LETTER > 0} />
        <MetricCard label="Side Effects" value={formatNumber(snapshot?.sideEffects)} detail="unique idempotent effects" />
      </section>
    </div>
  );
}

function ArchNode({ title, subtitle, metric, kind }) {
  return (
    <div className={`arch-node ${kind}`}>
      <div className="node-icon">{kind === 'db' ? 'DB' : kind === 'reaper' ? '↻' : kind === 'client' ? '→' : 'API'}</div>
      <strong>{title}</strong>
      <span>{subtitle}</span>
      <b>{metric}</b>
    </div>
  );
}

function FlowLine({ active }) {
  return (
    <div className={`flow-line ${active ? 'active' : ''}`}>
      <span className="flow-particle p1" />
      <span className="flow-particle p2" />
      <span className="arrow">›</span>
    </div>
  );
}

function WorkerNode({ worker, events }) {
  const relevant = events.find((event) => event.payload?.workerId === worker.id || event.entityId === worker.id);
  return (
    <div className={`worker-node ${worker.status === 'ACTIVE' ? 'active' : 'dead'} ${relevant ? 'recent' : ''}`}>
      <div className="worker-top">
        <span className={`worker-status ${worker.status === 'ACTIVE' ? 'good' : 'bad'}`} />
        <strong>{worker.id.split('-').slice(-2).join('-')}</strong>
        <span>{worker.status}</span>
      </div>
      <div className="worker-visual">
        <span className="worker-core" />
        <span className="worker-ring" />
      </div>
      <div className="worker-stats">
        <span>{worker.activeJobs} active</span>
        <span>hb {timeAgo(worker.lastHeartbeat)}</span>
      </div>
    </div>
  );
}

function ActionButton({ label, busy, onClick, danger, muted }) {
  return (
    <button
      className={`action-button ${danger ? 'danger' : ''} ${muted ? 'muted' : ''}`}
      onClick={onClick}
      disabled={Boolean(busy)}
    >
      {busy === label ? 'RUNNING…' : label}
    </button>
  );
}

function MetricCard({ label, value, detail, bad }) {
  return (
    <div className={`metric-card ${bad ? 'bad' : ''}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{detail}</small>
    </div>
  );
}

function JobExplorer({ jobs, filter, setFilter, selectedJob, attempts, openJob, replay }) {
  const statuses = ['', 'RUNNING', 'RETRY_WAIT', 'DEAD_LETTER', 'SUCCEEDED', 'BLOCKED'];
  return (
    <div className="jobs-layout">
      <section className="panel jobs-panel">
        <div className="panel-heading">
          <div>
            <div className="eyebrow">DURABLE EXECUTION RECORDS</div>
            <h1>Job Explorer</h1>
          </div>
          <div className="filter-row">
            {statuses.map((status) => (
              <button
                key={status || 'ALL'}
                className={filter === status ? 'filter active' : 'filter'}
                onClick={() => setFilter(status)}
              >
                {status || 'ALL'}
              </button>
            ))}
          </div>
        </div>
        <div className="job-table-wrap">
          <table className="job-table">
            <thead>
              <tr>
                <th>Job</th>
                <th>Type</th>
                <th>Status</th>
                <th>Attempt</th>
                <th>Owner</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => (
                <tr key={job.id} onClick={() => openJob(job)} className={selectedJob?.id === job.id ? 'selected' : ''}>
                  <td className="mono">{shortId(job.id)}</td>
                  <td>{job.jobType}</td>
                  <td><StatusPill value={job.status} /></td>
                  <td>{job.attemptCount}/{job.maxAttempts}</td>
                  <td className="mono">{shortId(job.leaseOwner)}</td>
                  <td>{timeAgo(job.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <aside className="panel job-detail">
        {!selectedJob && <div className="empty-state tall">Select a job to inspect its execution history.</div>}
        {selectedJob && (
          <>
            <div className="detail-header">
              <div>
                <div className="eyebrow">JOB LIFECYCLE</div>
                <h2>{shortId(selectedJob.id)}</h2>
              </div>
              <StatusPill value={selectedJob.status} />
            </div>
            <dl className="detail-grid">
              <div><dt>Tenant</dt><dd>{selectedJob.tenantId}</dd></div>
              <div><dt>Type</dt><dd>{selectedJob.jobType}</dd></div>
              <div><dt>Owner</dt><dd className="mono">{shortId(selectedJob.leaseOwner)}</dd></div>
              <div><dt>Workflow</dt><dd className="mono">{shortId(selectedJob.workflowId)}</dd></div>
            </dl>
            {selectedJob.lastErrorCode && (
              <div className="error-box">
                <strong>{selectedJob.lastErrorCode}</strong>
                <span>{selectedJob.lastErrorMessage}</span>
              </div>
            )}
            <div className="timeline">
              {attempts.map((attempt) => (
                <div className="timeline-item" key={attempt.id}>
                  <span className={`timeline-marker ${statusTone[attempt.status] || 'neutral'}`} />
                  <div>
                    <strong>Attempt {attempt.attemptNumber} · {attempt.status}</strong>
                    <span>{shortId(attempt.workerId)} · {attempt.durationMs == null ? 'running' : `${attempt.durationMs} ms`}</span>
                    {attempt.errorCode && <small>{attempt.errorCode} — {attempt.errorMessage}</small>}
                  </div>
                </div>
              ))}
              {!attempts.length && <div className="empty-state">No execution attempts yet.</div>}
            </div>
            {['DEAD_LETTER', 'CANCELLED'].includes(selectedJob.status) && (
              <button className="action-button" onClick={() => replay(selectedJob.id)}>REPLAY JOB</button>
            )}
          </>
        )}
      </aside>
    </div>
  );
}

function Metrics({ snapshot, history }) {
  const counts = snapshot?.jobCounts || {};
  return (
    <div className="metrics-page">
      <section className="metric-grid">
        {Object.entries(counts).map(([status, value]) => (
          <MetricCard key={status} label={status.replaceAll('_', ' ')} value={formatNumber(value)} detail="current durable state" bad={status === 'DEAD_LETTER' && value > 0} />
        ))}
      </section>
      <section className="charts-grid">
        <LineChart title="Queue depth · last 60 snapshots" data={history.map((point) => point.queue)} />
        <LineChart title="Throughput · jobs/s" data={history.map((point) => point.throughput)} />
        <LineChart title="Running jobs" data={history.map((point) => point.running)} />
      </section>
      <section className="panel metrics-note">
        <div className="eyebrow">PROMETHEUS SCRAPE</div>
        <h2>/actuator/prometheus</h2>
        <p>Spring Boot Actuator and Micrometer expose runtime and JVM metrics for the local Prometheus/Grafana stack. The control plane above reads Relay's durable state directly through the system snapshot API.</p>
      </section>
    </div>
  );
}

function LineChart({ title, data }) {
  const width = 640;
  const height = 180;
  const safe = data.length ? data : [0];
  const max = Math.max(...safe, 1);
  const points = safe.map((value, index) => {
    const x = safe.length === 1 ? 0 : (index / (safe.length - 1)) * width;
    const y = height - (Number(value) / max) * (height - 18);
    return `${x},${y}`;
  }).join(' ');

  return (
    <div className="panel chart-card">
      <div className="chart-title">{title}</div>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={title}>
        <polyline points={points} fill="none" stroke="currentColor" strokeWidth="3" />
      </svg>
      <div className="chart-max">peak {formatNumber(max, 1)}</div>
    </div>
  );
}

function EngineeringNotes() {
  const notes = [
    {
      title: 'Why PostgreSQL is the coordination boundary',
      tag: 'ADR-001',
      text: 'Job state, lease ownership, attempt history, idempotency and workflow dependencies share one transactional boundary. Workers claim runnable rows with SELECT … FOR UPDATE SKIP LOCKED instead of introducing a broker before a measured bottleneck exists.'
    },
    {
      title: 'At-least-once, not fake exactly-once',
      tag: 'CORRECTNESS',
      text: 'A worker can complete an external side effect and crash before persisting SUCCEEDED. Relay deliberately models that crash window, recovers the expired lease, and relies on an idempotency key at the side-effect boundary.'
    },
    {
      title: 'Lease-based crash recovery',
      tag: 'FAILURE MODEL',
      text: 'RUNNING is temporary ownership, not permanent truth. Heartbeats extend leases. If ownership expires, the reaper atomically requeues abandoned work and marks the incomplete attempt ABANDONED.'
    },
    {
      title: 'Backoff with jitter',
      tag: 'ALGORITHM',
      text: 'Transient failures use capped exponential backoff plus jitter. Permanent failures stop immediately. This avoids blind retries and reduces synchronized retry storms when a dependency recovers.'
    },
    {
      title: 'DAG validation with Kahn’s algorithm',
      tag: 'DSA',
      text: 'Workflow submission validates dependencies before persistence. Relay computes indegrees and processes zero-indegree nodes; if processed nodes are fewer than total nodes, the workflow contains a cycle and is rejected.'
    },
    {
      title: 'Token-bucket guard for public controls',
      tag: 'ALGORITHM',
      text: 'Failure injection and burst controls share a synchronized token bucket. Expensive actions consume more tokens, the bucket refills continuously, and abusive public traffic is rejected before it can repeatedly reset or overload the live system.'
    },
    {
      title: 'Bounded worker fleet',
      tag: 'CAPACITY',
      text: 'The public deployment runs a bounded in-process fleet to stay within constrained cloud resources. The same database claim protocol permits multiple Relay instances to compete safely for work against one durable queue.'
    }
  ];

  return (
    <div className="notes-page">
      <section className="notes-hero panel">
        <div className="eyebrow">ENGINEERING DECISIONS</div>
        <h1>Failure semantics are part of the product.</h1>
        <p>Relay is intentionally built around the questions that appear after background work leaves the synchronous request path: who owns the job, what happens after a crash, when should a failure retry, and how an operator explains a job's history.</p>
      </section>
      <section className="notes-grid">
        {notes.map((note) => (
          <article className="note-card panel" key={note.title}>
            <span>{note.tag}</span>
            <h2>{note.title}</h2>
            <p>{note.text}</p>
          </article>
        ))}
      </section>
    </div>
  );
}

function StatusPill({ value }) {
  return <span className={`status-pill ${statusTone[value] || 'neutral'}`}>{value}</span>;
}

function eventTone(type) {
  if (type.includes('LOST') || type.includes('DEAD') || type.includes('FAILURE') || type.includes('STALE')) return 'bad';
  if (type.includes('RETRY') || type.includes('EXPIRED')) return 'warn';
  if (type.includes('SUCCEEDED') || type.includes('RECOVERED') || type.includes('STARTED')) return 'good';
  return 'live';
}

function trend(history, key) {
  if (history.length < 2) return 'collecting live samples';
  const current = Number(history.at(-1)?.[key] || 0);
  const previous = Number(history.at(-2)?.[key] || 0);
  if (current === previous) return 'stable';
  return current > previous ? `↑ ${formatNumber(current - previous)}` : `↓ ${formatNumber(previous - current)}`;
}

export default App;
