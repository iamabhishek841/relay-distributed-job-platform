package dev.relay.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class EventRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EventRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void persist(String type, String entityType, String entityId, Map<String, Object> payload) {
        try {
            jdbc.update("""
                    INSERT INTO system_events(event_type, entity_type, entity_id, payload)
                    VALUES (?, ?, ?, ?::jsonb)
                    """, type, entityType, entityId, objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Could not serialize event payload", e);
        }
    }

    public List<Map<String, Object>> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                SELECT id, event_type, entity_type, entity_id, payload::text, created_at
                FROM system_events
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> Map.of(
                "id", rs.getLong("id"),
                "eventType", rs.getString("event_type"),
                "entityType", rs.getString("entity_type"),
                "entityId", rs.getString("entity_id") == null ? "" : rs.getString("entity_id"),
                "payload", parseJson(rs.getString("payload")),
                "createdAt", rs.getTimestamp("created_at").toInstant()
        ), safeLimit);
    }

    public long countByType(String type) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM system_events
                WHERE event_type = ?
                """, Long.class, type);
        return value == null ? 0 : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String value) {
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (JacksonException e) {
            return Map.of("raw", value);
        }
    }
}
