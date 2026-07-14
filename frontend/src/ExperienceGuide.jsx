import React, { useMemo, useState } from 'react';
import { api } from './api.js';
import './experience.css';
import './polish.css';

const scenarioSteps = [
  {
    title: 'Create durable backlog',
    copy: 'Submit 320 jobs that are persisted before workers can execute them.'
  },
  {
    title: 'Terminate a busy worker',
    copy: 'Stop a worker while it owns work and watch its heartbeat disappear.'
  },
  {
    title: 'Recover the expired lease',
    copy: 'Wait for ownership to expire, requeue the abandoned job, and replace the worker.'
  },
  {
    title: 'Inject transient dependency failure',
    copy: 'Return HTTP 503-style failures so retries use exponential backoff and jitter.'
  },
  {
    title: 'Prove idempotent submission',
    copy: 'Send 100 concurrent requests with one idempotency key and retain one durable job.'
  }
];

const sleep = (milliseconds) => new Promise((resolve) => window.setTimeout(resolve, milliseconds));

async function waitForRecovery(baselineRecovered, timeoutMs = 18_000) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const snapshot = await api.snapshot();
    if (Number(snapshot.recoveredJobs || 0) > baselineRecovered) {
      return snapshot;
    }
    await sleep(900);
  }
  throw new Error('Lease recovery did not become visible before the scenario timeout.');
}

function scrollToControlPlane() {
  document.querySelector('.app-shell')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

export default function ExperienceGuide() {
  const [state, setState] = useState('IDLE');
  const [activeStep, setActiveStep] = useState(-1);
  const [completed, setCompleted] = useState([]);
  const [results, setResults] = useState({});
  const [error, setError] = useState('');

  const progress = useMemo(() => {
    if (state === 'COMPLETE') return 100;
    if (state !== 'RUNNING') return 0;
    return Math.max(5, Math.round(((completed.length + 0.35) / scenarioSteps.length) * 100));
  }, [completed.length, state]);

  const runScenario = async () => {
    if (state === 'RUNNING') return;

    setState('RUNNING');
    setActiveStep(0);
    setCompleted([]);
    setResults({});
    setError('');

    try {
      const baseline = await api.snapshot();
      const baselineRecovered = Number(baseline.recoveredJobs || 0);

      const burst = await api.burst(320, 450);
      setResults((current) => ({ ...current, accepted: burst.accepted }));
      setCompleted([0]);
      await sleep(800);

      setActiveStep(1);
      const killed = await api.killWorker();
      setResults((current) => ({ ...current, killedWorker: killed.workerId }));
      setCompleted([0, 1]);

      setActiveStep(2);
      const recoveredSnapshot = await waitForRecovery(baselineRecovered);
      const recoveredDelta = Number(recoveredSnapshot.recoveredJobs || 0) - baselineRecovered;
      const replacement = await api.startWorker();
      setResults((current) => ({
        ...current,
        recovered: recoveredDelta,
        replacementWorker: replacement.workerId
      }));
      setCompleted([0, 1, 2]);

      setActiveStep(3);
      const failure = await api.inject503();
      setResults((current) => ({ ...current, retryJobs: failure.accepted }));
      setCompleted([0, 1, 2, 3]);
      await sleep(700);

      setActiveStep(4);
      const duplicate = await api.duplicate();
      const finalSnapshot = await api.snapshot();
      const activeWorkers = (finalSnapshot.workers || []).filter((worker) => worker.status === 'ACTIVE').length;
      setResults((current) => ({
        ...current,
        duplicateRequests: duplicate.requests,
        duplicateResponses: duplicate.duplicateResponses,
        uniqueJobs: duplicate.uniqueJobs,
        activeWorkers
      }));
      setCompleted([0, 1, 2, 3, 4]);
      setActiveStep(-1);
      setState('COMPLETE');
    } catch (scenarioError) {
      setError(scenarioError.message || 'The guided scenario could not complete.');
      setState('ERROR');
    }
  };

  return (
    <section className="experience-shell">
      <div className="experience-grid">
        <div className="experience-copy">
          <div className="experience-kicker"><span /> INTERACTIVE DISTRIBUTED SYSTEM</div>
          <h1>Watch a background job survive the failures most systems hide.</h1>
          <p className="experience-lede">
            Relay durably accepts backend work, assigns it to competing workers, recovers abandoned jobs after a crash,
            retries transient dependencies, and suppresses duplicate submissions. The control plane below visualises real
            database state and backend events.
          </p>

          <div className="experience-actions">
            <button className="scenario-primary" onClick={runScenario} disabled={state === 'RUNNING'}>
              {state === 'RUNNING' ? 'SCENARIO RUNNING' : state === 'COMPLETE' ? 'RUN SCENARIO AGAIN' : 'RUN GUIDED FAILURE SCENARIO'}
              <span>{state === 'RUNNING' ? `${progress}%` : '→'}</span>
            </button>
            <button className="scenario-secondary" onClick={scrollToControlPlane}>OPEN CONTROL PLANE</button>
          </div>

          <div className="experience-proof">
            <span>≈15 seconds</span>
            <span>real backend state</span>
            <span>live SSE events</span>
            <span>no scripted animation</span>
          </div>

          <div className="problem-strip">
            <ProblemCard number="01" title="Worker dies" copy="Leases expire and abandoned work becomes runnable again." />
            <ProblemCard number="02" title="Dependency fails" copy="Transient errors retry with capped backoff and jitter." />
            <ProblemCard number="03" title="Request repeats" copy="Idempotency collapses concurrent duplicates to one job." />
          </div>
        </div>

        <aside className={`scenario-console ${state.toLowerCase()}`}>
          <div className="scenario-console-head">
            <div>
              <span>GUIDED FAILURE SCENARIO</span>
              <strong>{state === 'IDLE' ? 'Ready to run' : state === 'RUNNING' ? 'Observing live system' : state === 'COMPLETE' ? 'Scenario complete' : 'Scenario interrupted'}</strong>
            </div>
            <div className={`scenario-state ${state.toLowerCase()}`}>{state}</div>
          </div>

          <div className="scenario-progress"><i style={{ width: `${progress}%` }} /></div>

          <div className="scenario-steps">
            {scenarioSteps.map((step, index) => {
              const isDone = completed.includes(index);
              const isActive = activeStep === index;
              return (
                <div className={`scenario-step ${isDone ? 'done' : ''} ${isActive ? 'active' : ''}`} key={step.title}>
                  <div className="step-marker">{isDone ? '✓' : String(index + 1).padStart(2, '0')}</div>
                  <div>
                    <strong>{step.title}</strong>
                    <span>{step.copy}</span>
                  </div>
                  <small>{isDone ? 'DONE' : isActive ? 'RUNNING' : 'WAITING'}</small>
                </div>
              );
            })}
          </div>

          {state === 'COMPLETE' && (
            <div className="scenario-result-grid">
              <Result label="Accepted" value={results.accepted} />
              <Result label="Recovered" value={results.recovered} />
              <Result label="503 jobs" value={results.retryJobs} />
              <Result label="Duplicates" value={results.duplicateResponses} />
              <Result label="Unique jobs" value={results.uniqueJobs} />
              <Result label="Active workers" value={results.activeWorkers} />
            </div>
          )}

          {error && <div className="scenario-error">{error}</div>}

          <div className="scenario-footer">
            <span>Watch next:</span>
            <b>queue depth → worker state → lease recovery → retry events → idempotency result</b>
          </div>
        </aside>
      </div>
      <button className="control-plane-jump" onClick={scrollToControlPlane}>
        <span className="jump-copy">
          <strong>EXPLORE LIVE CONTROL PLANE</strong>
          <small>Topology · workers · queue · execution events</small>
        </span>
        <span className="jump-arrow">↓</span>
      </button>
    </section>
  );
}

function ProblemCard({ number, title, copy }) {
  return (
    <div className="problem-card">
      <span>{number}</span>
      <strong>{title}</strong>
      <p>{copy}</p>
    </div>
  );
}

function Result({ label, value }) {
  return (
    <div className="scenario-result">
      <span>{label}</span>
      <strong>{value ?? '—'}</strong>
    </div>
  );
}
