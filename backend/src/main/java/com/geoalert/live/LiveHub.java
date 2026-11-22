package com.geoalert.live;

import com.geoalert.AppProperties;
import com.geoalert.common.ApiException;
import com.geoalert.common.ErrorCode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Server-sent-event subscribers connected to this instance. */
@Component
public class LiveHub {

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_SUBSCRIBERS = 200;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final String instanceId;

    public LiveHub(AppProperties props, MeterRegistry meters) {
        this.instanceId = props.instanceId();
        Gauge.builder("live.subscribers", emitters, Set::size).register(meters);
    }

    public boolean hasSubscribers() {
        return !emitters.isEmpty();
    }

    /**
     * Registers first, then says hello: the client loads its REST snapshot only after the hello,
     * so every change committed after that point reaches it through the stream.
     */
    public SseEmitter subscribe() {
        if (emitters.size() >= MAX_SUBSCRIBERS) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Too many live subscribers on this instance");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        send(emitter, "hello", "{\"instance\":\"" + instanceId + "\"}");
        return emitter;
    }

    public void broadcast(String event, String json) {
        for (SseEmitter emitter : emitters) {
            send(emitter, event, json);
        }
    }

    private void send(SseEmitter emitter, String event, String json) {
        try {
            emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }
    }

    /** Keeps proxies from closing idle streams and lets clients detect a dead connection. */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /** Ends the streams on shutdown so graceful shutdown does not wait for them. */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
    }
}
