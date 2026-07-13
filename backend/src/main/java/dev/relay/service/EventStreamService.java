package dev.relay.service;

import dev.relay.config.RelayProperties;
import dev.relay.repository.EventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class EventStreamService {
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final EventRepository eventRepository;
    private final RelayProperties properties;

    public EventStreamService(EventRepository eventRepository, RelayProperties properties) {
        this.eventRepository = eventRepository;
        this.properties = properties;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        send(emitter, "CONNECTED", "SYSTEM", "relay", Map.of("message", "event stream connected"));
        return emitter;
    }

    public void publish(String type, String entityType, String entityId, Map<String, Object> payload, boolean persist) {
        if (persist) {
            eventRepository.persist(type, entityType, entityId, payload);
        }

        Map<String, Object> envelope = Map.of(
                "eventType", type,
                "entityType", entityType,
                "entityId", entityId == null ? "" : entityId,
                "payload", payload,
                "timestamp", Instant.now().toString()
        );

        for (SseEmitter emitter : emitters) {
            sendEnvelope(emitter, type, envelope);
        }
    }

    public void publishSampled(String type, String entityType, String entityId, Map<String, Object> payload) {
        if (ThreadLocalRandom.current().nextDouble() <= properties.eventSampleRate()) {
            publish(type, entityType, entityId, payload, false);
        }
    }

    @Scheduled(fixedDelay = 15_000)
    void keepAlive() {
        for (SseEmitter emitter : emitters) {
            send(emitter, "KEEPALIVE", "SYSTEM", "relay", Map.of("at", Instant.now().toString()));
        }
    }

    private void send(SseEmitter emitter, String type, String entityType, String entityId, Map<String, Object> payload) {
        Map<String, Object> envelope = Map.of(
                "eventType", type,
                "entityType", entityType,
                "entityId", entityId,
                "payload", payload,
                "timestamp", Instant.now().toString()
        );
        sendEnvelope(emitter, type, envelope);
    }

    private void sendEnvelope(SseEmitter emitter, String type, Map<String, Object> envelope) {
        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data(envelope));
        } catch (IOException | IllegalStateException error) {
            emitter.complete();
            emitters.remove(emitter);
        }
    }
}
