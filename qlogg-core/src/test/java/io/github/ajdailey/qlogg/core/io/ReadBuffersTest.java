package io.github.ajdailey.qlogg.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ReadBuffersTest {
  @Test
  void minimumBuffersExistEvenWithAnEmptyShare() throws Exception {
    MemoryBudget budget = MemoryBudget.of(0);
    ReadBuffers buffers = new ReadBuffers(1024, 8, budget);
    ByteBuffer first = buffers.tryAcquire();
    ByteBuffer second = buffers.tryAcquire();
    assertNotNull(first);
    assertNotNull(second);
    assertTrue(first.isDirect());
    assertEquals(ByteOrder.LITTLE_ENDIAN, first.order());
    assertNull(buffers.tryAcquire());
    assertEquals(2048, budget.reserved(MemoryShare.IO_BUFFERS));
    buffers.release(first);
    assertSame(first, buffers.acquire(new CancellationToken()));
    assertEquals(2, buffers.allocated());
  }

  @Test
  void shareAndCapBoundAllocation() {
    MemoryBudget budget = MemoryBudget.of(100 * 1024);
    ReadBuffers buffers = new ReadBuffers(1024, 6, budget);
    int taken = 0;
    while (buffers.tryAcquire() != null) {
      taken++;
    }
    assertEquals(6, taken);
    ReadBuffers small = new ReadBuffers(4096, 50, budget);
    taken = 0;
    while (small.tryAcquire() != null) {
      taken++;
    }
    assertEquals(ReadBuffers.MIN_BUFFERS, taken);
  }

  @Test
  void waitingAcquireEndsOnCancel() {
    final ReadBuffers buffers = new ReadBuffers(64, 2, MemoryBudget.of(0));
    buffers.tryAcquire();
    buffers.tryAcquire();
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws InterruptedException {
            buffers.acquire(token);
          }
        });
  }
}
