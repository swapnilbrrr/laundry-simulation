# Smart Laundry Facility Simulation

**Module:** Concurrent Programming – CT074-3-2  
**Student:** Swapnil Katuwal (NP070613)

This project implements the Smart Laundry Facility case study as a Spring Boot + Maven application. The simulation contains **50 customers, 6 washing machines, 4 dryers and 2 payment kiosks**. Each customer follows the same sequence: arrival, washing, drying, payment and exit.

The simulation is available in **two required modes**:

- **Normal mode:** customers arrive every 0–3 seconds and use the resources normally. Washer and payment failures are injected with the required 5% probability and customers retry when needed.
- **Congested mode:** both payment kiosks are offline at the start. Customers continue through washing and drying, then build a payment queue. At 30 waiting customers the owner is called, waits 5 seconds, repairs both kiosks and payment resumes.

## Main features

- Spring Boot 3.2 + Maven project
- Java 17
- One Java `Thread` per customer
- Fair `Semaphore` based resource pools
- `BlockingQueue` for physical resource hand-off
- CAS ownership check on every physical resource
- `synchronized` machine state updates
- `AtomicInteger`, `AtomicBoolean` and `LongAdder` statistics
- Pause / Resume / Stop / Reset controls
- Normal and Congested simulation modes
- Random and forced failure modes for demonstration/testing
- Live REST + Server-Sent Events dashboard
- JUnit 5 concurrency tests

## Project structure

```text
smart-laundry-simulation/
├── pom.xml
├── README.md
├── docs/
│   └── IMPLEMENTATION.md
├── src/
│   ├── main/
│   │   ├── java/com/smartlaundry/
│   │   │   ├── SmartLaundryApplication.java
│   │   │   ├── controller/SimulationController.java
│   │   │   ├── service/
│   │   │   │   ├── SimulationService.java
│   │   │   │   ├── StatisticsService.java
│   │   │   │   └── EventService.java
│   │   │   ├── simulation/
│   │   │   │   ├── Customer.java
│   │   │   │   ├── LaundrySimulation.java
│   │   │   │   ├── SimulationConfig.java
│   │   │   │   ├── SimulationMode.java
│   │   │   │   ├── SimulationState.java
│   │   │   │   ├── FailureMode.java
│   │   │   │   ├── FailureInjector.java
│   │   │   │   └── OwnerStatus.java
│   │   │   ├── resource/
│   │   │   │   ├── LaundryResource.java
│   │   │   │   ├── ResourcePool.java
│   │   │   │   ├── ResourceManager.java
│   │   │   │   ├── WashingMachine.java
│   │   │   │   ├── Dryer.java
│   │   │   │   └── PaymentKiosk.java
│   │   │   ├── concurrency/PauseController.java
│   │   │   ├── model/*.java
│   │   │   └── exception/SimulationException.java
│   │   └── resources/
│   │       ├── application.properties
│   │       └── static/
│   │           ├── index.html
│   │           ├── css/style.css
│   │           └── js/app.js
│   └── test/java/com/smartlaundry/
│       ├── SmartLaundryApplicationTests.java
│       └── simulation/LaundrySimulationTest.java
```

The repository intentionally does not contain generated `target/` files, IDE metadata, old console wrappers or captured run logs. Those are build/runtime artifacts rather than part of the submission source tree.

## Run the application

Requirements:

- JDK 17 or newer
- Maven 3.8 or newer

From the project directory:

```bash
mvn clean test
mvn spring-boot:run
```

Open:

```text
http://localhost:8080
```

Choose **Normal** or **Congested**, select the failure behaviour if needed, then press **Start**.

The dashboard also provides Pause, Resume, Stop and Reset. Stopping a run only stops the simulation executor. The Spring Boot application stays available.

## API

```text
GET  /api/simulation/state
GET  /api/simulation/statistics
GET  /api/simulation/log
GET  /api/simulation/events
POST /api/simulation/start?mode=NORMAL&failureMode=RANDOM
POST /api/simulation/pause
POST /api/simulation/resume
POST /api/simulation/stop
POST /api/simulation/reset
```

## Concurrency design

`ResourcePool<T>` is the main resource-management abstraction. A fair semaphore represents the available capacity and a blocking queue stores the actual idle resources. The customer releases the current resource before requesting the next one, so the simulation does not create a hold-and-wait cycle.

The dashboard receives immutable snapshots and live events from the backend. Customer threads never update browser state directly. The web layer only displays the simulation state.

## Verification

Run:

```bash
mvn test
```

The test suite uses scaled timing values so concurrency logic can be checked quickly without waiting for the real-time assignment delays. It checks customer count, resource limits, lifecycle ordering, forced failures, retry delays, statistics, unique resource allocation, stop/restart/reset, congestion handling and thread attribution.
