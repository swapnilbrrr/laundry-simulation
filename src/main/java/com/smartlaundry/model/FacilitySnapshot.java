package com.smartlaundry.model;

import com.smartlaundry.simulation.FailureMode;
import com.smartlaundry.simulation.OwnerStatus;
import com.smartlaundry.simulation.SimulationMode;
import com.smartlaundry.simulation.SimulationState;

import java.util.List;

/** Complete state of the facility at one instant; this is all the browser ever receives. */
public record FacilitySnapshot(
        SimulationState state, SimulationMode mode, FailureMode failureMode, long elapsedMs, int totalCustomers,
        List<MachineView> washers, List<MachineView> dryers, List<MachineView> kiosks,
        List<Integer> washerQueue, List<Integer> dryerQueue, List<Integer> paymentQueue,
        PipelineCounts pipeline, List<CustomerSnapshot> customers, SimulationStatistics statistics,
        OwnerStatus ownerStatus, int congestionThreshold) {
}
