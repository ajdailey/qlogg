package io.github.ajdailey.qlogg.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class MemoryBudgetTest {
  @Test
  void sharesDivideTheMaximum() {
    MemoryBudget budget = MemoryBudget.of(256L * 1024 * 1024);
    assertEquals(256L * 1024 * 1024 / 100 * 40, budget.limit(MemoryShare.LINE_INDEX));
    assertEquals(256L * 1024 * 1024 / 100 * 20, budget.limit(MemoryShare.RESULTS));
    long assigned = 0;
    for (MemoryShare share : MemoryShare.values()) {
      assigned += share.percent();
    }
    assertEquals(92, assigned);
    MemoryBudget runtime = MemoryBudget.fromRuntime();
    assertEquals(Runtime.getRuntime().maxMemory(), runtime.maxBytes());
  }

  @Test
  void reserveRefusesPastTheShareAndReleaseReturns() {
    MemoryBudget budget = MemoryBudget.of(1000);
    assertTrue(budget.reserve(MemoryShare.LINE_INDEX, 300));
    assertTrue(budget.reserve(MemoryShare.LINE_INDEX, 100));
    assertFalse(budget.reserve(MemoryShare.LINE_INDEX, 1));
    assertEquals(400, budget.reserved(MemoryShare.LINE_INDEX));
    assertEquals(0, budget.available(MemoryShare.LINE_INDEX));
    assertEquals(200, budget.available(MemoryShare.RESULTS));
    budget.release(MemoryShare.LINE_INDEX, 300);
    assertEquals(300, budget.available(MemoryShare.LINE_INDEX));
    assertTrue(budget.reserve(MemoryShare.LINE_INDEX, 300));
  }

  @Test
  void chargeOverdrawsUntilReleased() {
    MemoryBudget budget = MemoryBudget.of(1000);
    budget.charge(MemoryShare.IO_BUFFERS, 150);
    assertEquals(0, budget.available(MemoryShare.IO_BUFFERS));
    assertFalse(budget.reserve(MemoryShare.IO_BUFFERS, 1));
    budget.release(MemoryShare.IO_BUFFERS, 150);
    assertTrue(budget.reserve(MemoryShare.IO_BUFFERS, 100));
  }

  @Test
  void negativeAmountsAreRejected() {
    final MemoryBudget budget = MemoryBudget.of(1000);
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            budget.reserve(MemoryShare.FOLDS, -1);
          }
        });
  }

  @Test
  void tokenThrowsOnceCanceled() {
    final CancellationToken token = new CancellationToken();
    token.check();
    assertFalse(token.isCanceled());
    token.cancel();
    assertTrue(token.isCanceled());
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            token.check();
          }
        });
  }
}
