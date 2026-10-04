package com.smartlaundry.simulation;

import com.smartlaundry.model.FacilitySnapshot;
import com.smartlaundry.model.MachineState;
import com.smartlaundry.model.SimulationEvent;
import com.smartlaundry.model.SimulationStatistics;
import com.smartlaundry.resource.ResourcePool;
import com.smartlaundry.resource.WashingMachine;
import com.smartlaundry.service.EventService;
import com.smartlaundry.service.SimulationService;
import com.smartlaundry.service.StatisticsService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Fast, deterministic concurrency checks. Test timing is shortened; production timing is unchanged. */
class LaundrySimulationTest {

    private static final SimulationConfig FAST = SimulationConfig.defaults().scaled(0.03)
            .withArrivalMaxMs(8)
            .withFailureProbabilities(0, 0);

    private record Run(LaundrySimulation sim, StatisticsService stats, EventService events) {
        SimulationStatistics statistics() {
            return sim.snapshot(SimulationState.COMPLETED).statistics();
        }
    }

    private static Run runToCompletion(SimulationConfig config, SimulationMode mode) throws Exception {
        EventService events = new EventService();
        StatisticsService stats = new StatisticsService();
        LaundrySimulation sim = new LaundrySimulation(config, mode, events, stats);
        sim.start();
        sim.completion().get(60, TimeUnit.SECONDS);
        sim.finish();
        Thread.sleep(200);
        return new Run(sim, stats, events);
    }

    private static int maxOverlap(List<Customer> customers, Function<Customer.Timeline, long[]> interval) {
        List<long[]> points = new ArrayList<>();
        for (Customer c : customers) {
            long[] iv = interval.apply(c.timeline());
            points.add(new long[]{iv[0], 1});
            points.add(new long[]{iv[1], -1});
        }
        points.sort(Comparator.<long[]>comparingLong(p -> p[0]).thenComparingLong(p -> p[1]));
        int current = 0, max = 0;
        for (long[] point : points) {
            current += (int) point[1];
            max = Math.max(max, current);
        }
        return max;
    }

    @Test
    void test01_fiftyCustomersAreGenerated() throws Exception {
        Run run = runToCompletion(FAST, SimulationMode.NORMAL);
        assertEquals(50, run.sim().customers().size());
        assertEquals(50, run.statistics().customersArrived());
    }

    @Test
    void test02_washerCapacityNeverExceedsSix() throws Exception {
        Run run = runToCompletion(FAST, SimulationMode.NORMAL);
        int overlap = maxOverlap(run.sim().customers(), t -> new long[]{t.washStart(), t.washEnd()});
        assertTrue(overlap <= 6);
        assertTrue(run.statistics().peakWashers() <= 6);
        assertTrue(run.statistics().peakWashers() >= 5);
    }

    @Test
    void test03_dryerCapacityNeverExceedsFour() throws Exception {
        Run run = runToCompletion(FAST, SimulationMode.NORMAL);
        int overlap = maxOverlap(run.sim().customers(), t -> new long[]{t.dryStart(), t.dryEnd()});
        assertTrue(overlap <= 4);
        assertTrue(run.statistics().peakDryers() <= 4);
    }

    @Test
    void test04_kioskCapacityNeverExceedsTwo() throws Exception {
        Run run = runToCompletion(FAST, SimulationMode.NORMAL);
        int overlap = maxOverlap(run.sim().customers(), t -> new long[]{t.payStart(), t.payEnd()});
        assertTrue(overlap <= 2);
        assertTrue(run.statistics().peakKiosks() <= 2);
    }

    @Test
    void test05_workflowOrderIsRespected() throws Exception {
        Run run = runToCompletion(FAST, SimulationMode.NORMAL);
        for (Customer c : run.sim().customers()) {
            Customer.Timeline t = c.timeline();
            long[] order = {t.arrival(), t.washWaitStart(), t.washStart(), t.washEnd(),
                    t.dryWaitStart(), t.dryStart(), t.dryEnd(),
                    t.payWaitStart(), t.payStart(), t.payEnd(), t.completed()};
            for (int i = 0; i < order.length; i++) {
                assertTrue(order[i] >= 0, c.name() + " skipped stage " + i);
                if (i > 0) assertTrue(order[i] >= order[i - 1], c.name() + " stage order violated");
            }
            assertEquals(com.smartlaundry.model.CustomerState.COMPLETED, c.state());
        }
    }

    @Test
    void test06_washerFailureCausesRetryWithoutLeakingPermits() throws Exception {
        Run run = runToCompletion(FAST.withFailureMode(FailureMode.FORCE_WASH), SimulationMode.NORMAL);
        SimulationStatistics s = run.statistics();
        assertEquals(5, s.washerFailures());
        assertEquals(5, s.washerRetries());
        assertEquals(50, s.customersServed());
        assertEquals(6, run.sim().resources().washers().availablePermits());
        assertEquals(6, run.sim().resources().washers().idleCount());
        assertTrue(run.events().history().stream().anyMatch(e -> e.message().contains("failed during washing")));
    }

    @Test
    void test07_paymentFailureRetriesAfterConfiguredDelay() throws Exception {
        SimulationConfig cfg = FAST.withFailureMode(FailureMode.FORCE_PAYMENT).withPayRetryMs(200);
        Run run = runToCompletion(cfg, SimulationMode.NORMAL);
        assertEquals(5, run.statistics().paymentFailures());
        assertEquals(50, run.statistics().customersServed());
        long delay = run.sim().customers().get(9).paymentRetryDelayMillis();
        assertTrue(delay >= 195 && delay < 500, "retry delay was " + delay + " ms");
        assertEquals(2, run.sim().resources().kiosks().availablePermits());
        assertEquals(2, run.sim().resources().kiosks().idleCount());
    }

    @Test
    void test08_statisticsAreConsistent() throws Exception {
        Run run = runToCompletion(FAST, SimulationMode.NORMAL);
        SimulationStatistics s = run.statistics();
        assertEquals(50, s.customersServed());
        assertEquals(0, s.customersInSystem());
        double manual = run.sim().customers().stream()
                .mapToDouble(c -> (c.timeline().completed() - c.timeline().arrival()) / 1e9)
                .average().orElseThrow();
        assertEquals(manual, s.avgTotalTimeSec(), 0.03);
        assertEquals(0, s.currentWashers());
        assertEquals(0, s.currentDryers());
        assertEquals(0, s.currentKiosks());
    }

    @Test
    void test09_resourceAllocationsAreUniqueUnderContention() throws Exception {
        ResourcePool<WashingMachine> pool = new ResourcePool<>(
                java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(WashingMachine::new).toList());
        Set<String> inUse = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(40);
        CountDownLatch done = new CountDownLatch(40);

        for (int i = 1; i <= 40; i++) {
            final int id = i;
            executor.execute(() -> {
                try {
                    for (int k = 0; k < 20; k++) {
                        WashingMachine machine = pool.acquire(id);
                        try {
                            if (!inUse.add(machine.getName())) duplicates.incrementAndGet();
                            Thread.sleep(1);
                        } finally {
                            inUse.remove(machine.getName());
                            pool.release(machine);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertEquals(0, duplicates.get());
        assertEquals(0, pool.violations());
        assertTrue(pool.peakInUse() <= 6);
        assertEquals(6, pool.availablePermits());
    }

    @Test
    void test10_stopCancelsRunButServerLogicSurvives() throws Exception {
        SimulationService service = new SimulationService(
                new EventService(), new StatisticsService(), SimulationConfig.defaults().scaled(0.1));
        service.start(SimulationMode.NORMAL, FailureMode.RANDOM, null);
        Thread.sleep(800);
        FacilitySnapshot snap = service.stop();

        assertEquals(SimulationState.STOPPED, snap.state());
        assertTrue(snap.washers().stream().allMatch(m -> m.state() == MachineState.AVAILABLE));
        assertTrue(snap.dryers().stream().allMatch(m -> m.state() == MachineState.AVAILABLE));
        assertTrue(snap.kiosks().stream().allMatch(m -> m.state() == MachineState.AVAILABLE));
        assertTrue(snap.washerQueue().isEmpty());
        assertTrue(snap.dryerQueue().isEmpty());
        assertTrue(snap.paymentQueue().isEmpty());
        assertTrue(service.currentSimulation().isTerminated());
        assertEquals(SimulationState.STOPPED, service.state());
    }

    @Test
    void test11_simulationCanRunAgainAfterStopAndReset() throws Exception {
        SimulationService service = new SimulationService(
                new EventService(), new StatisticsService(), SimulationConfig.defaults().scaled(0.05));

        service.start(SimulationMode.NORMAL, FailureMode.RANDOM, 50);
        Thread.sleep(300);
        service.stop();

        service.start(SimulationMode.NORMAL, FailureMode.RANDOM, 50);
        service.currentSimulation().completion().get(60, TimeUnit.SECONDS);
        waitForState(service, SimulationState.COMPLETED);
        assertEquals(50, service.statistics().customersServed());
        assertThrows(RuntimeException.class,
                () -> service.start(SimulationMode.NORMAL, FailureMode.RANDOM, 50));

        service.reset();
        assertEquals(SimulationState.IDLE, service.state());
        assertEquals(0, service.statistics().customersServed());

        service.start(SimulationMode.NORMAL, FailureMode.RANDOM, 50);
        service.currentSimulation().completion().get(60, TimeUnit.SECONDS);
        waitForState(service, SimulationState.COMPLETED);
        assertEquals(50, service.statistics().customersServed());
    }

    @Test
    void test12_congestedModeCallsOwnerAtThirtyWaiting() throws Exception {
        Run run = runToCompletion(FAST, SimulationMode.CONGESTED);
        SimulationStatistics s = run.statistics();
        assertTrue(s.ownerCalled());
        assertEquals(1, s.congestionEvents());
        assertTrue(s.maxPaymentQueue() >= 30);
        assertEquals(OwnerStatus.RESTORED, run.sim().ownerStatus());
        assertEquals(50, s.customersServed());
        assertTrue(run.events().history().stream()
                .anyMatch(e -> e.message().contains("OWNER HAS BEEN CALLED")));
    }

    @Test
    void test13_everyCustomerEventComesFromItsOwnThread() throws Exception {
        Run run = runToCompletion(FAST.withFailureProbabilities(0.05, 0.05), SimulationMode.NORMAL);
        for (SimulationEvent event : run.events().history()) {
            if (event.customerId() > 0) {
                assertEquals(String.format("Customer-%02d", event.customerId()), event.threadName());
            }
        }
    }

    private static void waitForState(SimulationService service, SimulationState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (service.state() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(expected, service.state());
    }
}
