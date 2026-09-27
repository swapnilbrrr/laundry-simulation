# Smart Laundry Facility Simulation — Build Plan & Checklist

**Module:** CT074-3-2 Concurrent Programming (Individual Assignment — System, 25%)
**Language:** Java 25 (no external libraries, no build tool required)
**Case:** self-service laundry, 6 washers / 4 dryers / 2 kiosks / 50 customers

---

## 1. Architecture decision (made before coding)

| Decision | Choice | Why (rubric: "appropriateness of Java concurrent facilities") |
|---|---|---|
| Customer identity | one `Thread` per customer | basic requirement 1, verbatim |
| Device access | `Semaphore` (bounded concurrency) + `ArrayBlockingQueue` idle pool | a single monitor would serialise all customers onto one device; the brief demands washers/dryers/kiosks run *simultaneously* |
| Small critical sections | `synchronized` on `Machine` and on `Logger` | satisfies "use synchronized blocks" and stops interleaved output |
| Statistics | `AtomicInteger` / `AtomicLong` + `accumulateAndGet(max)` | lock-free atomic statements; plain `if (x>max)` is a check-then-act race |
| Cross-thread flags | `volatile` (`busy`, `failed`, `kiosksDown`, listener) | one writer / many readers, GUI reads without locking |
| Termination | `Thread.join()` on all 50 customers | no polling, no "sleep long enough" |
| Failure model | probabilistic flag consumed inside the device cycle | requirement 2 (5% washer / 5% kiosk) |
| Bonus congestion | hard-outage lambda + queue-size callback + `AtomicBoolean` CAS | owner dispatched exactly once |
| GUI | Swing + `javax.swing.Timer` on the EDT | thread-confined UI; no cross-thread component access |

## 2. Timing model

Arrival stream alone is `50 × avg 1.5s ≈ 75s`, so the whole run lands in the
**85–105s** band. The brief states two numbers: "about 60 seconds" (additional
requirement 5) and "about 1–2 minutes" (Implementation section). They conflict,
so the run honours the *arrival-rate* rule (explicit, testable) and the total
sits inside the 1–2 minute window. Documented as an assumption.

---

## 3. Checklist

Legend: `[x]` done & verified · `[ ]` to do

### 3.1 Project scaffolding
- [x] Folder structure `src/laundry`, `out`, `docs`, `logs`
- [x] `run.bat` one-command compile + run
- [x] README with requirement→code map

### 3.2 Basic requirements (20% of system mark)
- [x] R1 Customer = thread, random arrival 0–3s
- [x] R2 Washing 4–6s, waits when all 6 washers busy
- [x] R3 Drying 3–5s after wash, waits for a dryer
- [x] R4 Payment 1–2s at one of 2 kiosks
- [x] R5 Every event logged with the acting thread's name (entry / service / exit)

### 3.3 Additional requirements (10%)
- [x] A1 Mutual exclusion: `Semaphore` + `synchronized` + blocking pool
- [x] A2 Error handling: 5% washer mid-cycle failure → device withdrawn, customer retries;
      5% kiosk failure → 2s wait then retry
- [x] A3 Statistics: total served, average total time, max concurrent washers/dryers
- [x] A4 Simultaneity visible in console output (verified peak = 6 washers, 4 dryers)
- [x] A5 Simulation duration — see timing model above

### 3.4 Bonus requirements (10%)
- [x] B1 Congested scenario: `--congested` = both kiosks dead all day, owner called in
      at 30 customers queued (verified trigger at 59.89s, repair at 63.89s, closed at 106.6s)
- [x] B2 GUI visualisation: `--gui` live colour-coded floor plan (Swing, EDT-polled)

### 3.5 Code quality (20% + 20%)
- [x] Comments on every concurrency decision (what and *why*)
- [x] No busy-waiting, no `Thread.stop`, no unbalanced permit release
- [x] All resources returned in `finally` (no lost-permit leak)
- [x] Compiles clean with `-Xlint:all` (0 errors, 0 warnings)
- [x] Verified runs: normal scenario 50/50 served, congested 50/50 served
- [x] Bug found & fixed during testing: payment-queue counter was cleared during the 2s
      retry sleep, so the owner trigger never reached 30 and the simulation livelocked
      → queue membership now spans the whole retry sequence (see `docs/TESTING.md`)

### 3.6 Deliverables still to do (your part)
- [ ] Record the ≤5-minute walkthrough video (run `run.bat --gui`, narrate code + output)
- [ ] Finalise the written report from `docs/REPORT-DRAFT.md` (add your name/student ID,
      screenshots from the GUI, and paste the console log of your own recorded run)
- [ ] Weekly log / cover page per faculty template
- [ ] Optional: run 3–5 times and pick the cleanest log for submission

---

## 4. Verification evidence

| Check | Expected | Observed |
|---|---|---|
| Customers served | 50 | 50 |
| Max concurrent washers | 6 | 6 |
| Max concurrent dryers | 4 | 4 |
| Normal run duration | 1–2 min | 88.5s (avg 11.17s/customer, peaks 6/4, 2 washer + 2 kiosk failures recovered) |
| Congested run duration | 1–2 min | 106.6s, 50/50 served |
| Owner trigger | at 30 queued | fired at 59.89s with q=30, repaired 63.89s |
| GUI | renders, no EDT errors | 245 log lines, 0 exceptions (`logs/gui-smoke.log`) |
| Deadlock/livelock | none | both scenarios terminate |
