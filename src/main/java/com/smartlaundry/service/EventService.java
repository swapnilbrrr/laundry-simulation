package com.smartlaundry.service;

import com.smartlaundry.model.EventType;
import com.smartlaundry.model.SimulationEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe activity history plus a small SSE broadcaster.
 * Customer threads publish events; network delivery happens on the dispatcher thread.
 */
@Service
public class EventService {
    private static final int MAX_HISTORY = 1500;
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Deque<SimulationEvent> history = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-dispatcher");
        t.setDaemon(true);
        return t;
    });

    public SimulationEvent publish(EventType type, int customerId,
                                    String resourceType, String resourceId, String message) {
        String threadName = Thread.currentThread().getName();
        String time = FORMAT.format(LocalTime.now());
        SimulationEvent event;

        synchronized (history) {
            event = new SimulationEvent(
                    sequence.incrementAndGet(),
                    System.currentTimeMillis(),
                    time.substring(0, 8),
                    customerId,
                    threadName,
                    type,
                    message,
                    resourceType,
                    resourceId);
            history.addLast(event);
            if (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
            enqueue("log", event);
        }

        System.out.println("[" + time + "] [" + threadName + "] [" + resourceId + "] " + message);
        return event;
    }

    public List<SimulationEvent> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public void clear() {
        synchronized (history) {
            history.clear();
            sequence.set(0);
            enqueue("reset", Map.of("reset", true));
        }
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcast(String eventName, Object data) {
        if (!emitters.isEmpty()) {
            enqueue(eventName, data);
        }
    }

    private void enqueue(String eventName, Object data) {
        try {
            dispatcher.execute(() -> send(eventName, data));
        } catch (RejectedExecutionException ignored) {
            // Application shutdown.
        }
    }

    private void send(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (Exception ignored) {
                emitters.remove(emitter);
            }
        }
    }

    @PreDestroy
    public void close() {
        dispatcher.shutdownNow();
    }
}
