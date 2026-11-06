# RACE.md

One section per milestone. Fill each one in order as you go.

The prediction in milestone 1 is written BEFORE you run your race test. A TA
may ask about it. Commit it on its own so the order is visible.

Keep it short and specific. Point at methods, call sites, and the invariant.

---

## Milestone 1: from the ticket to a test that fails every time

### Suspect list

Everything your review turned up that touches shared state, with a one-line
verdict for each. Add or remove rows as needed.

| # | What you found (method, field, or class) | Verdict, in one line |
|---|---|---|
| 1 | `InMemoryBookingStore.bookings` | Safe within each store method: the list is private, all access is synchronized on the store, and snapshots do not expose it. |
| 2 | `BookingService.book`: `store.hasOverlap(...)` then `store.insert(...)` | Broken compound operation: two callers can both observe a free slot before either inserts its confirmed booking. |
| 3 | `BookingService.catalog` | Safe: it is constructed once, never modified afterward, and contains immutable `Room` values. |
| 4 | `BookingService.sequence` | Safe: `AtomicLong.incrementAndGet()` assigns unique sequence values to concurrent callers of this service instance. |
| 5 | `BookingStats` counters | Deliberately racy but acceptable: counts may be low or stale, but they are approximate and never drive booking decisions. |
| 6 | `InMemoryBookingStore.cancelAndPromote` | Safe as one store operation: cancellation, overlap checking, and promotion all occur while holding the store monitor. |
| 7 | `BookingService.joinWaitlist` | Safe for the invariant: store insertion is serialized, and WAITLISTED entries do not hold a room. |
| 8 | `BookingService.listBookings` / `bookingsForRoom` | Safe: the snapshot is built under the store lock and returned as an immutable copy. |
| 9 | `BookingLog` / standard output | Safe for the invariant: logging does not determine booking state, and each event is emitted by one `println`. |

**Which one explains the ticket, and how you know.** Tie it to the invariant
the ticket names.

The check-then-insert sequence in `BookingService.book` explains the ticket.
Although `hasOverlap` and `insert` are each atomic, they are separate store
calls. Two threads can both get `false` from `hasOverlap` before either calls
`insert`, then each inserts a `CONFIRMED` booking for the same room and range.
The end state directly violates the invariant that a room has at most one
`CONFIRMED` booking covering any given minute.

### Predicted interleaving (write this before you run your race test)

Two threads, one row per step, in the form from lecture. Describe what each
thread does at that step and leave the other column empty. Add or remove rows
as your prediction needs.

| Step | Thread A | Thread B |
|---|---|---|
| t1 | Calls `book`; `hasOverlap` checks the store and computes `false`, then pauses before returning. | |
| t2 | | Calls `book`; `hasOverlap` returns `false`. |
| t3 | | Creates booking B as `CONFIRMED` and `insert` stores it. |
| t4 | Resumes with the already-computed `false` result. | |
| t5 | Creates booking A as `CONFIRMED` and `insert` stores it. | |
| | **End state:** two `CONFIRMED` bookings cover the same room and minutes. | |

If the run turned out different from your prediction, add one line on what
you missed. Leave the table as you first wrote it.

### Your race test

**How you aimed `PausingStore`, and the result.** Method name and call index, why
that pair pins the schedule you predicted, and the output of two separate
runs (assertion failure and log lines).

I aimed `PausingStore` at `hasOverlap`, call 1. The wrapper lets the real first
overlap check compute `false`, then parks Thread A before returning that answer
to `BookingService.book`. Thread B is started only after `awaitPaused` confirms
that Thread A is parked. Because the gate is one-shot, Thread B's overlap check
returns `false` without pausing and Thread B inserts its confirmed booking.
After Thread B finishes, `release` lets Thread A continue with its stale
`false` answer and insert a second confirmed booking. This pins the predicted
check-check-insert-insert schedule without sleeps.

The two runs both matched the prediction and failed on the invariant.

Run 1:

```text
2026-11-06T16:59:59.843Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T16:59:59.847Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
RaceTest.twoCustomersCannotConfirmTheSameSlot:59 a room may have at most one confirmed booking covering a minute ==> expected: <1> but was: <2>
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

Run 2:

```text
2026-11-06T17:00:23.437Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T17:00:23.440Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
RaceTest.twoCustomersCannotConfirmTheSameSlot:59 a room may have at most one confirmed booking covering a minute ==> expected: <1> but was: <2>
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

---

## Milestone 2: the patch a teammate sent

**Your milestone-1 test on the patched code.** Green or red. Paste the result.

Green. With the original gate aimed at `hasOverlap`, call 1, the second
customer inserted while the first customer was paused. After release, the
patch's new second check saw that booking and rejected the first customer.

```text
2026-11-06T17:02:57.129Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T17:02:57.133Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**The re-aimed run.** Method name, call index, the pasted result, and one line
on why that aim tests the patch rather than sidestepping it.

I re-aimed `PausingStore` at `hasOverlap`, call 2, which is the first
customer's defensive recheck added by the patch. That check computes `false`
and pauses before returning. The second customer then completes both of its
checks and inserts; after release, the first customer inserts using the stale
result of the patched recheck. This exercises the new code directly rather
than bypassing it.

```text
2026-11-06T17:06:28.679Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
2026-11-06T17:06:28.682Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
RaceTest.defensiveRecheckStillLeavesAWindowBeforeInsert:93 the defensive recheck and insert must be one atomic operation ==> expected: <1> but was: <2>
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
BUILD FAILURE
```

**Your verdict.** Accept or reject, and what your milestone-1 result does and
does not show about the patch.

Reject. The deterministic re-aimed run proves that the patch still permits
two overlapping `CONFIRMED` bookings. The milestone-1 test turning green shows
only that the extra check closes the particular window where the first check
was paused; it does not prove the invariant. The patch merely moves the same
check-then-insert race to the gap between its defensive recheck and `insert`.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Name the lock or the atomic operation
and the two operations it now covers. Paste the diff of your change and the
passing run of your milestone-1 test and the full suite.

I chose option 2 and added the plain `BookingStore.insertIfNoOverlap` method.
`InMemoryBookingStore` implements it as a `synchronized` method, so the store's
monitor now covers both checking for an overlapping confirmed booking and
adding the new confirmed booking as one atomic operation. `BookingService.book`
uses that result instead of making separate `hasOverlap` and `insert` calls.
`PausingStore` passes the new call through to its delegate.

```diff
diff --git a/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java b/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
@@
+    /** Atomically stores a CONFIRMED booking only when its range is free. */
+    boolean insertIfNoOverlap(Booking booking);

diff --git a/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java b/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
@@
+    @Override
+    public synchronized boolean insertIfNoOverlap(Booking booking) {
+        if (hasOverlap(booking.roomId(), booking.startMinute(), booking.endMinute())) {
+            return false;
+        }
+        bookings.add(booking);
+        return true;
+    }

diff --git a/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java b/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
@@
+    @Override
+    public boolean insertIfNoOverlap(Booking booking) {
+        return gate("insertIfNoOverlap", () -> delegate.insertIfNoOverlap(booking));
+    }

diff --git a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
@@
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
-            return reject(roomId, startMinute, endMinute, "slot already held");
-        }
         Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
-        store.insert(booking);
+        if (!store.insertIfNoOverlap(booking)) {
+            return reject(roomId, startMinute, endMinute, "slot already held");
+        }
```

Milestone-1 race test after the fix:

```text
2026-11-06T17:12:57.579Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
2026-11-06T17:12:57.585Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Full suite after the fix:

```text
RaceTest:
2026-11-06T17:13:19.322Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
2026-11-06T17:13:19.328Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**Is your milestone-1 test still testing anything?** After your fix, describe
what that test now exercises. Did `PausingStore` pause a call during the green run? How do
you know?

I adjusted what the test can observe. Before the fix, it aimed at
`hasOverlap`, call 1, so it could pause between the separate overlap check and
`insert`. The shipped code removes that observable boundary by replacing both
calls with `insertIfNoOverlap`. I therefore re-aimed `PausingStore` at
`insertIfNoOverlap`, call 1. The wrapper now pauses Thread A after the real
store has atomically checked and inserted, but before that result returns to
`BookingService.book`. While Thread A is paused, Thread B calls the same atomic
operation and is rejected, so the test still exercises two competing callers
and checks the one-confirmed-booking invariant. `awaitPaused` returned `true`
in the green run (the test asserts this), proving that the new gate actually
fired rather than the test passing through a timeout or sequential execution.

**What runs one at a time now that ran in parallel before.** Name concretely which callers
queue behind which. "Nothing, it is just correct now" does not count as an
answer.

Two callers of `BookingService.book` that share an `InMemoryBookingStore` now
queue at `insertIfNoOverlap`: one caller performs the overlap scan and list
insertion while holding the store monitor, and the other cannot begin its own
scan until that monitor is released. Because the store uses one monitor, a
booking for a different room also waits briefly behind that atomic operation,
as do concurrent calls to the store's other synchronized methods. The pause in
`PausingStore` happens after the delegate has returned and released that
monitor, so it does not artificially hold the store lock while Thread B runs.

**What you left unfixed.** For anything your review flagged that you chose not
to fix, one line each on why.

I left `BookingStats` unchanged. Its plain `long` increments can lose updates
or be read stale, but the counters are explicitly approximate, feed only
reporting, and never participate in a booking decision. The other reviewed
shared state was already safe and needed no change.
