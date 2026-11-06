package edu.cmu.cs214.lab10.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.lab10.domain.Booking;
import edu.cmu.cs214.lab10.domain.BookingStatus;
import edu.cmu.cs214.lab10.domain.Room;
import edu.cmu.cs214.lab10.service.BookingService;
import edu.cmu.cs214.lab10.store.InMemoryBookingStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where your concurrency tests go.
 *
 * <p>Empty on purpose. The wiring you need is written out below; what to aim it
 * at, and what scenario to drive through it, is yours to work out.
 */
class RaceTest {

    private static final String WEAN = "WEH-5409";
    private static final int START_MINUTE = 540;
    private static final int END_MINUTE = 600;
    private static final List<Room> ROOMS = List.of(
            new Room(WEAN, "Wean 5409", 12),
            new Room("GHC-4301", "Gates 4301", 30));

    @Test
    void twoCustomersCannotConfirmTheSameSlot() throws InterruptedException {
        InMemoryBookingStore real = new InMemoryBookingStore();
        PausingStore gated = new PausingStore(real, "insertIfNoOverlap", 1);
        BookingService service = new BookingService(gated, ROOMS);

        Thread first = new Thread(
                () -> service.book(WEAN, START_MINUTE, END_MINUTE),
                "customer-1");
        first.start();

        assertTrue(gated.awaitPaused(), "the first atomic check-and-insert reached the gate");

        Thread second = new Thread(
                () -> service.book(WEAN, START_MINUTE, END_MINUTE),
                "customer-2");
        second.start();
        second.join(PausingStore.TIMEOUT_MILLIS);

        gated.release();
        first.join(PausingStore.TIMEOUT_MILLIS);
        second.join(PausingStore.TIMEOUT_MILLIS);

        int confirmed = 0;
        for (Booking booking : real.bookingsForRoom(WEAN)) {
            if (booking.status() == BookingStatus.CONFIRMED
                    && booking.overlaps(START_MINUTE, END_MINUTE)) {
                confirmed++;
            }
        }
        assertEquals(1, confirmed,
                "a room may have at most one confirmed booking covering a minute");
    }

    // ------------------------------------------------------------------
    // Wiring example 1: wrap the store
    //
    // PausingStore is a BookingStore that wraps another BookingStore. Build the
    // real store, wrap it, and hand the wrapper to the service. The service
    // cannot tell the difference.
    //
    //   InMemoryBookingStore real = new InMemoryBookingStore();
    //   PausingStore gated = new PausingStore(real, "bookingsForRoom", 1);
    //   BookingService service = new BookingService(gated, ROOMS);
    //
    // (ROOMS is the room list, as in BookingServiceTest.)
    //
    // Assert against `real`, not `gated`: you want the end state of the store,
    // and reading through the wrapper counts as a call.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 2: aim the gate
    //
    // The two constructor arguments after the delegate are a method name and a
    // call index counting from 1 across all threads:
    //
    //   new PausingStore(real, "bookingsForRoom", 1)   // park call 1
    //   new PausingStore(real, "insert", 3)            // park call 3 of insert
    //
    // Valid method names are the methods on BookingStore: hasOverlap,
    // insertIfNoOverlap, insert, bookingsForRoom, and cancelAndPromote. On the chosen call the wrapper
    // delegates, then parks the calling thread before returning the answer it
    // already computed. The gate fires once and once only.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 3: the two-thread pattern
    //
    // Every wait below is bounded, so this pattern cannot hang no matter what
    // the code under test does. Do not add a sleep to it; a sleep is a guess,
    // and the latches are not. (Your test method needs
    // `throws InterruptedException` for the joins.)
    //
    // The scenario here is deliberately harmless: it parks a reader in
    // bookingsForRoom (the aim from example 1) while a writer books a
    // different room. It exists to show the choreography, not a bug.
    //
    //   Thread reader = new Thread(() -> service.listBookings("WEH-5409"));
    //   reader.start();
    //
    //   gated.awaitPaused();          // returns when a thread is parked, or
    //                                 // false after PausingStore.TIMEOUT_MILLIS
    //                                 // if the gated call never happened
    //
    //   Thread writer = new Thread(() -> service.book("GHC-4301", 720, 780));
    //   writer.start();
    //   writer.join(PausingStore.TIMEOUT_MILLIS);
    //
    //   gated.release();              // the parked reader returns and finishes
    //   reader.join(PausingStore.TIMEOUT_MILLIS);
    //                                 // note the writer needed no release: it
    //                                 // was never blocked by the parked reader
    //
    // Then assert on the end state, never on the order things happened in. For
    // the harmless scenario above, the writer's booking is stored whatever the
    // reader saw:
    //
    //   assertEquals(1, real.bookingsForRoom("GHC-4301").size());
    //
    // An end-state assertion still means something after you change the code
    // underneath it. A schedule assertion does not.
    // ------------------------------------------------------------------
}
