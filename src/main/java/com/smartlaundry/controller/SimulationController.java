package com.smartlaundry.controller;

import com.smartlaundry.exception.SimulationException;
import com.smartlaundry.model.FacilitySnapshot;
import com.smartlaundry.model.SimulationEvent;
import com.smartlaundry.model.SimulationStatistics;
import com.smartlaundry.service.EventService;
import com.smartlaundry.service.SimulationService;
import com.smartlaundry.simulation.FailureMode;
import com.smartlaundry.simulation.SimulationMode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** REST + SSE API. Every endpoint returns immediately; the simulation runs on its own threads. */
@RestController
@RequestMapping("/api/simulation")
public class SimulationController {
    private final SimulationService service;
    private final EventService events;

    public SimulationController(SimulationService service, EventService events) {
        this.service = service;
        this.events = events;
    }

    @GetMapping("/state")
    public FacilitySnapshot state() {
        return service.snapshot();
    }

    @GetMapping("/statistics")
    public SimulationStatistics statistics() {
        return service.statistics();
    }

    @GetMapping("/log")
    public List<SimulationEvent> log() {
        return service.log();
    }

    /** Live stream: log = SimulationEvent, snapshot = FacilitySnapshot, reset = marker. */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return events.subscribe();
    }

    @PostMapping("/start")
    public FacilitySnapshot start(@RequestParam(defaultValue = "NORMAL") SimulationMode mode,
                                  @RequestParam(defaultValue = "RANDOM") FailureMode failureMode) {
        return service.start(mode, failureMode);
    }

    @PostMapping("/pause")
    public FacilitySnapshot pause() {
        return service.pause();
    }

    @PostMapping("/resume")
    public FacilitySnapshot resume() {
        return service.resume();
    }

    @PostMapping("/stop")
    public FacilitySnapshot stop() {
        return service.stop();
    }

    @PostMapping("/reset")
    public FacilitySnapshot reset() {
        return service.reset();
    }

    @ExceptionHandler(SimulationException.class)
    public ResponseEntity<Map<String, String>> conflict(SimulationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
