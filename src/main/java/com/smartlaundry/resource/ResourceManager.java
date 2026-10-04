package com.smartlaundry.resource;

import java.util.stream.IntStream;

/** Owns the three bounded resource pools for one simulation run. */
public final class ResourceManager {
    private final ResourcePool<WashingMachine> washers;
    private final ResourcePool<Dryer> dryers;
    private final ResourcePool<PaymentKiosk> kiosks;

    public ResourceManager(int washerCount, int dryerCount, int kioskCount) {
        washers = new ResourcePool<>(
                IntStream.rangeClosed(1, washerCount).mapToObj(WashingMachine::new).toList());
        dryers = new ResourcePool<>(
                IntStream.rangeClosed(1, dryerCount).mapToObj(Dryer::new).toList());
        kiosks = new ResourcePool<>(
                IntStream.rangeClosed(1, kioskCount).mapToObj(PaymentKiosk::new).toList());
    }

    public ResourcePool<WashingMachine> washers() { return washers; }
    public ResourcePool<Dryer> dryers() { return dryers; }
    public ResourcePool<PaymentKiosk> kiosks() { return kiosks; }

    public void restoreAll() {
        washers.restoreAll();
        dryers.restoreAll();
        kiosks.restoreAll();
    }
}
