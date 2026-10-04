# Smart Laundry Facility Simulation

![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)
![Concurrency](https://img.shields.io/badge/Thread%20Concurrency-Semaphore%20%7C%20synchronized%20%7C%20BlockingQueue-blue)
![No dependencies](https://img.shields.io/badge/Dependencies-none%20—%20pure%20JDK-green)
![Swing](https://img.shields.io/badge/GUI-Java%20Swing-lightgrey)
![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20macOS%20%7C%20Linux-informational)
![License](https://img.shields.io/badge/License-Educational%20—%20assignment-red)

<!-- tags: java, multithreading, concurrency, semaphore, synchronized, reentrantlock-free, blockingqueue, atomic-variables, swing, gui, simulation, thread-safety, deadlock-prevention, producer-consumer, university-assignment, ct074, concurrent-programming, apo -->

A concurrent-programming assignment (CT074-3-2, Asia Pacific University):
a **smart self-service laundromat** simulated in pure Java. **50 customer
threads** compete for **6 washing machines, 4 tumble dryers and 2 payment
kiosks**, under random arrivals, random device failures and live statistics
collection — plus a bonus congestion scenario and a live Swing visualisation.

## Requirements

- **JDK 17 or newer** (`javac` and `java` on your `PATH`) — developed and verified on JDK 25.
- **No libraries, no Maven/Gradle** — plain JDK, runs anywhere Java runs.

## Simulation modes

**Normal mode** starts the laundromat normally. Customers arrive every 0–3 seconds,
compete for the six washers, four dryers and two payment kiosks, and recover from
the required random failures.

**Congested mode** starts with both payment kiosks out of service. Customers still
complete washing and drying, then build a payment queue. When 30 customers are
waiting, the owner is called and repairs both kiosks so the queue can drain. This
is the assignment's congestion bonus scenario.

The `--gui` flag can be combined with either mode. The GUI is a live Swing dashboard
showing resource state, customer count, queue size, average time, failures and peak
resource usage.

## How to run

**Windows**

```bat
run.bat                      :: console simulation
run.bat --gui                :: console + live Swing floor plan
run.bat --congested          :: bonus: both kiosks broken, owner called at 30 in queue
run.bat --congested --gui    :: bonus scenario with the GUI
```

**macOS / Linux**

```sh
sh run.sh                    # console simulation
sh run.sh --gui              # with live GUI
sh run.sh --congested        # bonus congestion scenario
sh run.sh --congested --gui  # bonus scenario + GUI
```

**Any OS, manually** (from this folder):

```sh
javac -d out src/laundry/*.java
java -cp out laundry.Main            # add --gui / --congested as desired
```

A run completes all 50 customers and is kept open for a minimum of **90 seconds**.
The final statistics then show customers served, average time, peak concurrent
washers/dryers and failures. Normal mode and the bonus congested mode are both
supported from the same entry point.

## Source layout

```
laundry-simulation/
├── run.bat                      Windows compile + run wrapper
├── run.sh                       macOS/Linux compile + run wrapper
├── PLAN.md                      design decisions + to-do/done checklist
├── README.md                    this file
├── src/laundry/
│   ├── Main.java                CLI entry point, flags, GUI window bootstrap
│   ├── Simulation.java          arrival generator, join(), bonus owner trigger
│   ├── Laundry.java             shared resource: semaphores + device pools + stages
│   ├── Machine.java             one device; synchronized cycle, failure/repair state
│   ├── Customer.java            one thread per customer; wash -> dry -> pay
│   ├── Stats.java               atomic counters, peak tracking, final report
│   ├── Logger.java              thread-safe console output with elapsed clock
│   └── LaundromatPanel.java     Swing visualisation (EDT-polled)
├── docs/
│   ├── REPORT-DRAFT.md          skeleton of the written report (20% mark)
│   └── TESTING.md               test cases, evidence, bug found while testing
└── logs/
    ├── run-normal.log           captured console output of a verified run
    └── run-congested.log        captured output of the bonus scenario
```

## Where each requirement is implemented

| Requirement | File · symbol |
|---|---|
| Customer as thread, arrival 0–3s | `Simulation.run()` |
| Washing 4–6s, queue when full | `Laundry.wash()`, `Laundry.WASH_MIN/MAX` |
| Drying 3–5s, queue when full | `Laundry.dry()` |
| Payment 1–2s at 2 kiosks | `Laundry.pay()` |
| Mutual exclusion | `Laundry.acquire()`, `Semaphore`, `Machine.runCycle` (`synchronized`) |
| Washer mid-cycle failure + retry | `Laundry.wash()` loop, `Machine.failureScheduled`, `Laundry.scheduleRepair()` |
| Kiosk failure + 2s retry | `Laundry.pay()` loop |
| Statistics | `Stats` (atomics), printed by `Simulation.run()` |
| Simultaneous machines | `Semaphore(6)` / `Semaphore(4)` permits — visible in `logs/run-normal.log` |
| Bonus: congested scenario | `Laundry.kiosksDown`, `Simulation.installOwnerTrigger()` |
| Bonus: GUI | `LaundromatPanel` |

## Concurrency facilities used

| Facility | Where | Why |
|---|---|---|
| `Semaphore` (fair) | one per device class in `Laundry` | bounded multi-slot concurrency = queueing without busy-wait |
| `BlockingQueue` (`ArrayBlockingQueue`) | idle-device pools | exclusive, thread-safe hand-off of machine objects |
| `synchronized` | `Machine.runCycle()`, `Logger` | small critical sections; uncorrupted output |
| `AtomicInteger` / `AtomicLong` | `Stats`, owner-trigger CAS | lock-free counters, race-free peak tracking |
| `volatile` | machine `busy`/`failed`, kiosk outage flag | safe cross-thread visibility for GUI/monitors |
| `Thread.join()` | `Simulation.run()` | structured wait for all 50 customers |

## Assumptions (stated deliberately — the report is marked on this)

1. A customer holds exactly one device per stage and never acts for another customer.
2. Failed washers are withdrawn from service and self-repair after 1.5s; the customer
   who was using them rejoins the washer queue.
3. A kiosk glitch clears on the next attempt (5% again per attempt); the "out of order
   all day" case is only the bonus congested scenario.
4. "Total time per customer" = arrival instant → payment completed.
5. The 50-arrival stream (0–3s gaps ⇒ ~75s) plus service tail places the run inside the
   brief's "1–2 minutes"; the "about 60 seconds" figure is satisfied by the active
   simulation window after the last arrival.
6. No queue-priority fairness guarantee beyond the fair semaphore mode used.
