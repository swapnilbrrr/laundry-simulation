# Testing Evidence

Environment: Windows, JDK 25. Each scenario was compiled with
`javac -Xlint:all -d out src/laundry/*.java` (0 errors, 0 warnings) and run to
completion. Full transcripts are in `logs/`.

## Test plan

| # | Test | Method | Expected | Result |
|---|---|---|---|---|
| T1 | Compile cleanly | `javac -Xlint:all` | no errors/warnings | PASS |
| T2 | All customers served | normal run, final report | served = 50 | PASS (50) |
| T3 | Bounded concurrency respected | inspect log timestamps | never >6 washers, >4 dryers, >2 kiosks busy at once | PASS (peaks 6 / 4 / 2) |
| T4 | Simultaneity (add. req. 4) | inspect log | overlapping wash/dry/pay lines from different threads | PASS |
| T5 | Washer failure + retry | normal run | `MACHINE FAILURE mid-cycle` then customer retries and later succeeds | PASS |
| T6 | Kiosk failure + 2s retry | normal run | `payment failed; retrying in 2000ms.` then `(attempt 2)` | PASS |
| T7 | Statistics correctness | cross-check report vs log | avg time plausible (service ≈ 5–14s + queueing), peaks match log | PASS (avg 11.17s, peaks 6/4) |
| T8 | Bonus congested scenario | `--congested` | kiosks fail from opening, owner triggered exactly at 30 queued, queue drains, run ends | PASS (trigger 59.89s, repaired 63.89s, closed 106.6s, 50/50) |
| T9 | GUI | `--gui` | window renders live device colours; no EDT exceptions | PASS |
| T10 | Termination / no deadlock | both modes | process exits by itself, no hang | PASS |

## Defect found and fixed during testing

**D1 — livelock in the bonus scenario.**
The payment-queue counter was incremented and decremented *per attempt*, so a
customer that failed and slept 2s left the queue while sleeping. Because a
kiosk failure in congested mode is instantaneous, the permit was never held
long enough for a queue to build: the observed queue size hovered around 2–3,
the "owner called in at 30" trigger never fired, and the 50 threads retried
forever (run exceeded 15 minutes, >6500 identical failure lines).

*Fix:* queue membership now spans the whole retry sequence — `Stats.joinPaymentQueue()`
is called once on entering `Laundry.pay()` and `leavePaymentQueue()` in the
surrounding `finally`, so sleeping customers still count as waiting. A separate
cumulative `paymentArrivals` counter distinguishes "how many have queued" from
"how many are queuing right now". Verified by T8.

**D2 — failed washer stayed in the idle pool.**
Initially the failed device was returned to the pool immediately, so the next
customer could take a machine that was still marked `failed`.
*Fix:* on failure the machine is withdrawn (permit held) and the repair thread
returns both device and permit, so effective capacity really drops and later
recovers.

## How to re-run the tests

```bat
run.bat                       :: T1-T7, T10
run.bat --congested           :: T8
run.bat --gui                 :: T9
```

## Known limitations (honest list for the report's "requirements not met")

- Arrival gaps are drawn per customer, so two customers may arrive at the same
  instant (no global arrival ordering guarantee is required by the brief).
- The GUI is a status display, not an interactive control panel.
- No persistence: statistics are printed, not written to file.
- Failures are modelled as timing/probability events, not as real I/O faults.
