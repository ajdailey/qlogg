package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.Iterator;
import java.util.SplittableRandom;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class MutableLineSetTest {
  private static final long B = MutableLineSet.BUCKET_LINES;

  /** Directory bytes once the first bucket exists. */
  private static final long DIRECTORY =
      MutableLineSet.FIRST_DIRECTORY * MutableLineSet.DIRECTORY_SLOT_BYTES;

  /** Checks every query against the reference at probes around each member and bucket edge. */
  private static void assertSame(TreeSet<Long> reference, LineSet set) {
    assertEquals(reference.size(), set.size());
    long index = 0;
    for (long line : reference) {
      assertEquals(line, set.select(index), "select " + index);
      assertEquals(index, set.rank(line), "rank " + line);
      index++;
    }
    long[] sorted = new long[reference.size()];
    int n = 0;
    for (long line : reference) {
      sorted[n++] = line;
    }
    TreeSet<Long> probes = new TreeSet<Long>();
    probes.add(-1L);
    probes.add(0L);
    for (long line : reference) {
      probes.add(line - 1);
      probes.add(line);
      probes.add(line + 1);
      long bucket = line / B * B;
      probes.add(bucket);
      probes.add(bucket - 1);
      probes.add(bucket + B - 1);
      probes.add(bucket + B);
    }
    for (long probe : probes) {
      Long ceiling = reference.ceiling(probe);
      Long floor = reference.floor(probe);
      assertEquals(ceiling == null ? -1 : ceiling, set.ceiling(probe), "ceiling");
      assertEquals(floor == null ? -1 : floor, set.floor(probe), "floor");
      assertEquals(reference.contains(probe), set.contains(probe), "contains");
      int at = Arrays.binarySearch(sorted, probe);
      assertEquals(at >= 0 ? at : -at - 1, set.rank(probe), "rank");
    }
  }

  @Test
  void randomOperationsMatchATreeSet() {
    SplittableRandom random = new SplittableRandom(7);
    for (int round = 0; round < 40; round++) {
      TreeSet<Long> reference = new TreeSet<Long>();
      MutableLineSet set = new MutableLineSet();
      long span = round % 2 == 0 ? 4 * B : 1L << 40;
      for (int op = 0; op < 300; op++) {
        long line = random.nextLong(span);
        switch (random.nextInt(5)) {
          case 0 -> {
            set.add(line);
            reference.add(line);
          }
          case 1 -> assertEquals(reference.add(line) || !reference.remove(line), set.toggle(line));
          case 2 -> {
            long[] batch = batch(random, line, span);
            assertTrue(set.addSorted(batch, batch.length));
            for (long value : batch) {
              reference.add(value);
            }
          }
          case 3 -> {
            if (random.nextInt(10) == 0) {
              set.removeFrom(line);
              reference.tailSet(line, true).clear();
            }
          }
          default -> assertEquals(reference.contains(line), set.contains(line));
        }
      }
      assertSame(reference, set);
    }
  }

  private static long[] batch(SplittableRandom random, long from, long span) {
    TreeSet<Long> values = new TreeSet<Long>();
    int count = random.nextInt(1, 200);
    for (int i = 0; i < count; i++) {
      values.add(Math.min(span - 1, from + random.nextLong(3 * B)));
    }
    long[] batch = new long[values.size()];
    int i = 0;
    for (long value : values) {
      batch[i++] = value;
    }
    return batch;
  }

  @Test
  void bucketsTurnDenseAndBackToSparse() {
    MutableLineSet set = new MutableLineSet();
    TreeSet<Long> reference = new TreeSet<Long>();
    long base = 3 * B;
    for (int i = 0; i < MutableLineSet.SPARSE_MAX; i++) {
      set.add(base + 2L * i);
      reference.add(base + 2L * i);
    }
    long sparse = set.retainedBytes();
    assertEquals(
        DIRECTORY + MutableLineSet.BUCKET_OVERHEAD + 2L * MutableLineSet.SPARSE_MAX, sparse);
    set.add(base + 1);
    reference.add(base + 1);
    assertEquals(
        DIRECTORY + MutableLineSet.BUCKET_OVERHEAD + 8L * MutableLineSet.BITMAP_WORDS,
        set.retainedBytes());
    assertSame(reference, set);
    Iterator<Long> values = reference.iterator();
    while (reference.size() > MutableLineSet.SPARSE_RETURN) {
      long line = values.next();
      values.remove();
      assertFalse(set.toggle(line));
    }
    assertEquals(
        DIRECTORY + MutableLineSet.BUCKET_OVERHEAD + 2L * MutableLineSet.SPARSE_RETURN,
        set.retainedBytes());
    assertSame(reference, set);
  }

  @Test
  void denseBatchesAndRemoveFrom() {
    MutableLineSet set = new MutableLineSet();
    TreeSet<Long> reference = new TreeSet<Long>();
    long[] all = new long[(int) (2 * B)];
    for (int i = 0; i < all.length; i++) {
      all[i] = B + i;
      reference.add(B + i);
    }
    assertTrue(set.addSorted(all, all.length));
    assertEquals(
        DIRECTORY + 2 * (MutableLineSet.BUCKET_OVERHEAD + 8L * MutableLineSet.BITMAP_WORDS),
        set.retainedBytes());
    assertEquals(B + B / 2, set.select(B / 2));
    set.removeFrom(B + B + 100);
    reference.tailSet(B + B + 100, true).clear();
    assertSame(reference, set);
    set.removeFrom(B + 10);
    reference.tailSet(B + 10, true).clear();
    assertSame(reference, set);
    set.clear();
    assertEquals(0, set.size());
    assertEquals(0, set.retainedBytes());
    assertEquals(-1, set.ceiling(0));
    assertEquals(-1, set.floor(Long.MAX_VALUE));
  }

  @Test
  void retainedBytesFollowTheFormula() {
    MutableLineSet set = new MutableLineSet();
    long[] lines = {1, 2, 3, 4, 5};
    assertTrue(set.addSorted(lines, 5));
    assertEquals(5, MutableLineSet.capacityFor(0, 5));
    assertEquals(4, MutableLineSet.capacityFor(0, 1));
    // 5 chars pad to 16 bytes, 4 chars fill 8.
    assertEquals(DIRECTORY + MutableLineSet.BUCKET_OVERHEAD + 16, set.retainedBytes());
    set.add(B * 7);
    assertEquals(DIRECTORY + 2 * MutableLineSet.BUCKET_OVERHEAD + 16 + 8, set.retainedBytes());
    for (int i = 2; i <= MutableLineSet.FIRST_DIRECTORY; i++) {
      set.add(B * 7 * i);
    }
    assertEquals(
        2 * DIRECTORY + 9 * MutableLineSet.BUCKET_OVERHEAD + 16 + 8 * 8, set.retainedBytes());
    assertEquals(6, MutableLineSet.capacityFor(4, 5));
    assertEquals(MutableLineSet.SPARSE_MAX, MutableLineSet.capacityFor(4000, 4001));
  }

  @Test
  void budgetHoldsExactlyTheRetainedBytes() {
    MemoryBudget budget = MemoryBudget.of(1_000_000);
    MutableLineSet set = new MutableLineSet(budget);
    long[] lines = new long[5000];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = 3L * i;
    }
    assertTrue(set.addSorted(lines, lines.length));
    assertEquals(set.retainedBytes(), budget.reserved(MemoryShare.RESULTS));
    set.toggle(1);
    set.add(10 * B);
    assertEquals(set.retainedBytes(), budget.reserved(MemoryShare.RESULTS));
    set.removeFrom(B);
    assertEquals(set.retainedBytes(), budget.reserved(MemoryShare.RESULTS));
    set.clear();
    assertEquals(0, budget.reserved(MemoryShare.RESULTS));
  }

  @Test
  void refusedBatchAddsNothing() {
    MemoryBudget budget = MemoryBudget.of(100_000);
    long limit = budget.limit(MemoryShare.RESULTS);
    MutableLineSet set = new MutableLineSet(budget);
    long added = 0;
    long bucket = 0;
    while (true) {
      long[] lines = new long[MutableLineSet.SPARSE_MAX + 1];
      for (int i = 0; i < lines.length; i++) {
        lines[i] = bucket * B + i;
      }
      bucket++;
      if (!set.addSorted(lines, lines.length)) {
        break;
      }
      added += lines.length;
    }
    assertEquals(added, set.size());
    assertTrue(budget.reserved(MemoryShare.RESULTS) <= limit);
    assertEquals(set.retainedBytes(), budget.reserved(MemoryShare.RESULTS));
  }

  @Test
  void unionMergesBothSets() {
    MutableLineSet a = new MutableLineSet();
    MutableLineSet b = new MutableLineSet();
    TreeSet<Long> reference = new TreeSet<Long>();
    SplittableRandom random = new SplittableRandom(3);
    for (int i = 0; i < 3000; i++) {
      long line = random.nextLong(5 * B);
      (i % 2 == 0 ? a : b).add(line);
      reference.add(line);
    }
    a.add(42);
    b.add(42);
    reference.add(42L);
    assertSame(reference, MutableLineSet.union(a, b));
  }

  @Test
  void rejectsBadInput() {
    final MutableLineSet set = new MutableLineSet();
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            set.addSorted(new long[] {5, 5}, 2);
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            set.add(-1);
          }
        });
    assertThrows(
        IndexOutOfBoundsException.class,
        new Executable() {
          @Override
          public void execute() {
            set.select(0);
          }
        });
  }

  @Test
  void fillCopiesAscendingAcrossBuckets() {
    MutableLineSet set = new MutableLineSet();
    for (long line = 0; line < 300_000; line += 3) {
      set.add(line);
    }
    long[] out = new long[10];
    assertEquals(10, set.fill(65_534, out));
    assertEquals(65_535, out[0]);
    assertEquals(65_535 + 27, out[9]);
    long[] all = new long[200_000];
    assertEquals(100_000, set.fill(0, all));
    assertEquals(0, set.fill(300_000, out));
  }

  @Test
  void removeSortedReleasesStorage() {
    MemoryBudget budget = MemoryBudget.of(1L << 26);
    MutableLineSet set = new MutableLineSet(budget);
    long[] lines = new long[5000];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = 2L * i;
    }
    assertTrue(set.addSorted(lines, lines.length));
    long version = set.version();
    assertEquals(5000, set.removeSorted(lines, lines.length));
    assertEquals(0, set.removeSorted(lines, 10));
    assertEquals(0, set.size());
    assertTrue(set.version() > version);
    assertEquals(0, set.retainedBytes());
    assertEquals(0, budget.reserved(MemoryShare.RESULTS));
  }

  /** Heap in use after a full collection, the least of three readings. */
  private static long heapAfterGc() {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    long used = Long.MAX_VALUE;
    for (int i = 0; i < 3; i++) {
      System.gc();
      used = Math.min(used, memory.getHeapMemoryUsage().getUsed());
    }
    return used;
  }

  /**
   * One match per bucket is the costliest shape per entry: the bucket object, the array header and
   * the directory slots outweigh the entry. The charge must cover the heap the set holds, measured
   * after a full collection, within 5%: the reading exceeds the layout by 3 KB when the test runs
   * alone and by 41 KB after other tests in the same JVM (Linux, G1), and other lanes run other
   * collectors and heap sizes. The undercharge this pins was 57%.
   */
  @Test
  void retainedBytesCoverTheMeasuredHeapOfSparseBuckets() {
    int buckets = 100_000;
    long[] lines = new long[buckets];
    for (int i = 0; i < buckets; i++) {
      lines[i] = i * B + 17;
    }
    long before = heapAfterGc();
    MutableLineSet set = new MutableLineSet();
    for (int i = 0; i < buckets; i += MutableLineSet.SPARSE_MAX) {
      int n = Math.min(MutableLineSet.SPARSE_MAX, buckets - i);
      assertTrue(set.addSorted(Arrays.copyOfRange(lines, i, i + n), n));
    }
    long measured = heapAfterGc() - before;
    long retained = set.retainedBytes();
    Reference.reachabilityFence(set);
    Reference.reachabilityFence(lines);
    String report = "retained " + retained + " B, measured " + measured + " B";
    assertTrue(retained >= measured - measured / 20, report);
    assertTrue(retained <= measured + measured / 4, report);
  }
}
