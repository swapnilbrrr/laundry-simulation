package com.smartlaundry.service;

import com.smartlaundry.exception.SimulationException;
import com.smartlaundry.model.EventType;
import com.smartlaundry.model.FacilitySnapshot;
import com.smartlaundry.model.SimulationEvent;
import com.smartlaundry.model.SimulationStatistics;
import com.smartlaundry.simulation.FailureMode;
import com.smartlaundry.simulation.LaundrySimulation;
import com.smartlaundry.simulation.SimulationConfig;
import com.smartlaundry.simulation.SimulationMode;
import com.smartlaundry.simulation.SimulationState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Application-level simulation state machine:
 * IDLE -> STARTING -> RUNNING <-> PAUSED -> STOPPING -> STOPPED / COMPLETED.
 */
@Service
public class SimulationService {
    private final EventService events;
    private final StatisticsService stats;
    private final SimulationConfig baseConfig;

    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService control = daemonSingle("sim-control");
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "sim-ticker");
        thread.setDaemon(true);
        return thread;
    });

    private volatile SimulationState state = SimulationState.IDLE;
    private volatile LaundrySimulation current;

    @Autowired
    public SimulationService(EventService events, StatisticsService stats) {
        this(events, stats, SimulationConfig.defaults());
    }

    public SimulationService(EventService events, StatisticsService stats, SimulationConfig baseConfig) {
        this.events = events;
        this.stats = stats;
        this.baseConfig = baseConfig;
        this.current = new LaundrySimulation(baseConfig, SimulationMode.NORMAL, events, stats);
    }

    private static ExecutorService daemonSingle(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void startTicker() {
        ticker.scheduleAtFixedRate(() -> {
            try {
                events.broadcast("snapshot", snapshot());
            } catch (RuntimeException ignored) {
                // A telemetry failure must not kill the ticker.
            }
        }, 500, 250, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        ticker.shutdownNow();
        control.shutdownNow();
        LaundrySimulation sim = current;
        if (state == SimulationState.RUNNING || state == SimulationState.PAUSED) {
            sim.stop();
        }
    }

    // ------------------------------------------------------------------ controls
    public FacilitySnapshot start(SimulationMode mode, FailureMode failureMode) {
        return startInternal(mode, failureMode, null);
    }

    /**
     * Test-only overload: production requests use the assignment's fixed 0–3 second
     * arrival window. Tests may shorten it so concurrency behaviour runs quickly.
     */
    public FacilitySnapshot start(SimulationMode mode, FailureMode failureMode, Integer arrivalMaxMs) {
        return startInternal(mode, failureMode, arrivalMaxMs);
    }

    private FacilitySnapshot startInternal(
            SimulationMode mode, FailureMode failureMode, Integer arrivalMaxMs) {
        lock.lock();
        try {
            if (state != SimulationState.IDLE && state != SimulationState.STOPPED) {
                throw new SimulationException("Cannot start while simulation is " + state
                        + (state == SimulationState.COMPLETED ? " - press Reset first" : ""));
            }

            state = SimulationState.STARTING;
            SimulationConfig config =
                    baseConfig.withFailureMode(failureMode == null ? FailureMode.RANDOM : failureMode);

            if (arrivalMaxMs != null) {
                config = config.withArrivalMaxMs(Math.max(0, Math.min(3000, arrivalMaxMs)));
            }

            stats.reset();
            events.clear();

            LaundrySimulation sim = new LaundrySimulation(
                    config,
                    mode == null ? SimulationMode.NORMAL : mode,
                    events,
                    stats);

            current = sim;
            sim.start();
            state = SimulationState.RUNNING;

            sim.completion().thenRunAsync(() -> onCompleted(sim), control);
            return snapshot();
        } finally {
            lock.unlock();
        }
    }

    public FacilitySnapshot pause() {
        lock.lock();
        try {
            if (state != SimulationState.RUNNING) {
                throw new SimulationException("Cannot pause while simulation is " + state);
            }
            current.pauseRun();
            state = SimulationState.PAUSED;
            events.publish(EventType.SYSTEM, 0, "Facility", "-", "Simulation paused");
            return snapshot();
        } finally {
            lock.unlock();
        }
    }

    public FacilitySnapshot resume() {
        lock.lock();
        try {
            if (state != SimulationState.PAUSED) {
                throw new SimulationException("Cannot resume while simulation is " + state);
            }
            current.resumeRun();
            state = SimulationState.RUNNING;
            events.publish(EventType.SYSTEM, 0, "Facility", "-", "Simulation resumed");
            return snapshot();
        } finally {
            lock.unlock();
        }
    }

    public FacilitySnapshot stop() {
        lock.lock();
        try {
            if (state != SimulationState.RUNNING && state != SimulationState.PAUSED) {
                throw new SimulationException("No running simulation to stop (state " + state + ")");
            }
            stopCurrent();
            return snapshot();
        } finally {
            lock.unlock();
        }
    }

    public FacilitySnapshot reset() {
        lock.lock();
        try {
            if (state == SimulationState.RUNNING || state == SimulationState.PAUSED) {
                stopCurrent();
            }
            stats.reset();
            events.clear();
            current = new LaundrySimulation(baseConfig, SimulationMode.NORMAL, events, stats);
            state = SimulationState.IDLE;
            return snapshot();
        } finally {
            lock.unlock();
        }
    }

    private void stopCurrent() {
        state = SimulationState.STOPPING;
        current.stop();
        state = SimulationState.STOPPED;
        events.publish(
                EventType.SYSTEM,
                0,
                "Facility",
                "-",
                "Simulation STOPPED - customer threads cancelled, resources restored, web server still running");
    }

    private void onCompleted(LaundrySimulation sim) {
        lock.lock();
        try {
            if (current != sim || state != SimulationState.RUNNING) {
                return;
            }
            sim.finish();
            state = SimulationState.COMPLETED;
            events.publish(
                    EventType.SUCCESS,
                    0,
                    "Facility",
                    "-",
                    "SIMULATION COMPLETED - all customers served");
            events.broadcast("snapshot", snapshot());
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ queries
    public FacilitySnapshot snapshot() {
        return current.snapshot(state);
    }

    public SimulationStatistics statistics() {
        return snapshot().statistics();
    }

    public SimulationState state() {
        return state;
    }

    public LaundrySimulation currentSimulation() {
        return current;
    }

    public List<SimulationEvent> log() {
        return events.history();
    }
