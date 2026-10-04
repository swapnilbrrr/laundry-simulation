package com.smartlaundry.model;

/** Immutable log entry shared by the console, the REST history and the SSE stream. customerId 0 = system event. */
public record SimulationEvent(long id, long epochMillis, String time, int customerId, String threadName,
                              EventType type, String message, String resourceType, String resourceId) {
}
