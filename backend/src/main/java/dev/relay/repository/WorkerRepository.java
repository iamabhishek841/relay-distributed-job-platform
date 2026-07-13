package dev.relay.repository;

import dev.relay.domain.WorkerView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
public class WorkerRepository {
    private final JdbcTemplate jdbc;

    public WorkerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String workerId, String status) {
        jdbc.update("""
                INSERT INTO workers(id, status, last_heartbeat, started_at, updated_at)
                VALUES (?, ?, NOW(), NOW(), NOW())
                ON CONFLICT (id) DO UPDATE
                SET status = EXCLUDED.status,
                    last_heartbeat = NOW(),
                    started_at = NOW(),
                    updated_at = NOW()
                """, workerId, status);
    }

    public void heartbeat(String workerId) {
        jdbc.update("""
                UPDATE workers
                SET status = 'ACTIVE',
                    last_heartbeat = NOW(),
                    updated_at = NOW()
                WHERE id = ?
                """, workerId);
    }

    public void markDead(String workerId) {
        jdbc.update("""
                UPDATE workers
                SET status = 'DEAD',
                    updated_at = NOW()
                WHERE id = ?
                """, workerId);
    }

    public int markStaleWorkersDead(Duration staleAfter) {
        return jdbc.update("""
                UPDATE workers
                SET status = 'DEAD',
                    updated_at = NOW()
                WHERE status = 'ACTIVE'
                  AND last_heartbeat < NOW() - (? * INTERVAL '1 millisecond')
                """, staleAfter.toMillis());
    }

    public int pruneDeadWorkers(Duration retention) {
        return jdbc.update("""
                DELETE FROM workers
                WHERE status = 'DEAD'
                  AND updated_at < NOW() - (? * INTERVAL '1 millisecond')
                """, retention.toMillis());
    }

    public List<WorkerView> listWorkers() {
        return jdbc.query("""
                SELECT w.id,
                       w.status,
                       w.last_heartbeat,
                       w.started_at,
                       COUNT(j.id)::INT AS active_jobs
                FROM workers w
                LEFT JOIN jobs j
                  ON j.lease_owner = w.id
                 AND j.status = 'RUNNING'
                GROUP BY w.id, w.status, w.last_heartbeat, w.started_at
                ORDER BY w.status DESC, w.id
                """, (rs, rowNum) -> new WorkerView(
                rs.getString("id"),
                rs.getString("status"),
                timestamp(rs.getTimestamp("last_heartbeat")),
                timestamp(rs.getTimestamp("started_at")),
                rs.getInt("active_jobs")
        ));
    }

    private static Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
