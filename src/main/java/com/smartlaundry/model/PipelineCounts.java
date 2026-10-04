package com.smartlaundry.model;

/** Live counters for the journey view (Arrival -> ... -> Exit). */
public record PipelineCounts(int arrived, int washQueue, int washing, int dryQueue, int drying,
                             int paymentQueue, int paying, int completed, int total) {
}
