package com.smartlaundry.model;

public record SimulationStatistics(
        int customersArrived, int customersServed, int totalCustomers, int customersInSystem,
        double avgTotalTimeSec, double avgWasherWaitSec, double avgDryerWaitSec, double avgPaymentWaitSec,
        int currentWashers, int peakWashers, int currentDryers, int peakDryers, int currentKiosks, int peakKiosks,
        int washerQueue, int dryerQueue, int paymentQueue, int maxPaymentQueue,
        int washerFailures, int paymentFailures, int washerRetries, int paymentRetries,
        int congestionEvents, boolean ownerCalled, double throughputPerMin, double runtimeSec) {
}
