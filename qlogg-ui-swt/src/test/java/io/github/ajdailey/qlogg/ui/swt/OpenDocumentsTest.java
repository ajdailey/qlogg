package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.eclipse.swt.SWT;
import org.junit.jupiter.api.Test;

/** {@link OpenDocuments#collect} over a scripted event source and a fake clock, with no display. */
class OpenDocumentsTest {
  private static final long MS = 1_000_000L;

  /** A source whose steps advance the clock and deliver files at given times. */
  private static final class Script implements OpenDocuments.Source, LongSupplier {
    final long[] at;
    final String[] files;
    final List<Long> waits = new ArrayList<Long>();
    long now;
    int next;
    OpenDocuments.Receiver receiver;

    Script(long[] at, String... files) {
      this.at = at;
      this.files = files;
    }

    @Override
    public long getAsLong() {
      return now;
    }

    @Override
    public void start(OpenDocuments.Receiver receiver) {
      this.receiver = receiver;
    }

    @Override
    public void step(long maxNanos) {
      waits.add(maxNanos);
      if (next < at.length && at[next] <= now + maxNanos) {
        now = Math.max(now, at[next]);
        receiver.received(files[next++]);
      } else {
        now += maxNanos;
      }
    }
  }

  @Test
  void noEventEndsAfterTheBound() {
    Script script = new Script(new long[0]);
    assertEquals(List.of(), OpenDocuments.collect(script, script));
    assertEquals(OpenDocuments.BOUND_NANOS, script.now);
  }

  @Test
  void eventsEndAfterTheQuietTime() {
    Script script = new Script(new long[] {300 * MS, 450 * MS, 600 * MS}, "a.log", "b.log", "c");
    assertEquals(List.of("a.log", "b.log", "c"), OpenDocuments.collect(script, script));
    assertEquals(600 * MS + OpenDocuments.QUIET_NANOS, script.now);
  }

  @Test
  void anEventAfterTheBoundIsNotWaitedFor() {
    Script script = new Script(new long[] {1500 * MS}, "late.log");
    assertEquals(List.of(), OpenDocuments.collect(script, script));
    assertEquals(OpenDocuments.BOUND_NANOS, script.now);
  }

  @Test
  void aStepNeverWaitsPastTheEnd() {
    Script script = new Script(new long[] {100 * MS}, "a.log");
    OpenDocuments.collect(script, script);
    assertEquals(OpenDocuments.BOUND_NANOS, script.waits.get(0).longValue());
    assertEquals(OpenDocuments.QUIET_NANOS, script.waits.get(1).longValue());
    assertEquals(2, script.waits.size(), script.waits.toString());
  }

  @Test
  void outsideCocoaAwaitReturnsAtOnceWithNoFile() {
    assumeFalse("cocoa".equals(SWT.getPlatform()), "cocoa runs the event loop");
    assertEquals(List.of(), OpenDocuments.await());
  }
}
