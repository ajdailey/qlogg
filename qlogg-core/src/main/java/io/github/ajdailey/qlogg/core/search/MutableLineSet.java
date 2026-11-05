package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A {@link LineSet} in buckets of {@link #BUCKET_LINES} lines. A bucket holds its low 16 bits as a
 * sorted {@code char[]} up to {@link #SPARSE_MAX} entries, else as a bitmap of {@link
 * #BITMAP_WORDS} words; a bitmap returns to an array once it holds {@link #SPARSE_RETURN} entries
 * or fewer. Per-bucket counts of the entries before each bucket make {@code rank} and {@code
 * select} a binary search over buckets.
 *
 * <p>Cost ({@link #retainedBytes}): {@link #BUCKET_OVERHEAD} bytes per bucket, plus 2 bytes per
 * array slot padded to 8 bytes or {@code 8 * BITMAP_WORDS} bytes per bitmap, plus {@link
 * #DIRECTORY_SLOT_BYTES} per slot of the bucket directory, which grows by doubling. The sizes are
 * HotSpot's with compressed references (heaps under 32 GiB). With a budget, the set holds exactly
 * that much of {@link MemoryShare#RESULTS}: {@link #addSorted} reserves first and adds nothing when
 * the share refuses; {@link #add} and {@link #toggle} charge without the limit check; removals
 * release. {@link #clear} returns everything.
 *
 * <p>Thread-safe. A {@link ReentrantReadWriteLock} guards the set; a writer holds it for one batch
 * and never across I/O.
 */
public final class MutableLineSet implements LineSet {
  /** Lines per bucket. */
  public static final int BUCKET_LINES = 1 << 16;

  /** Most entries a bucket holds as an array. */
  public static final int SPARSE_MAX = 4096;

  /** A bitmap bucket with this many entries or fewer returns to an array. */
  public static final int SPARSE_RETURN = 2048;

  /** Words of a bitmap bucket. */
  public static final int BITMAP_WORDS = BUCKET_LINES / 64;

  /** Bytes charged per bucket for the bucket object and its array header. */
  public static final long BUCKET_OVERHEAD = 40;

  /** Bytes charged per directory slot: a key, a bucket reference and a count before. */
  public static final long DIRECTORY_SLOT_BYTES = 20;

  /** Directory slots allocated by the first bucket. */
  static final int FIRST_DIRECTORY = 8;

  private static final int BUCKET_SHIFT = 16;
  private static final int LOW_MASK = BUCKET_LINES - 1;
  private static final int MIN_CAPACITY = 4;

  private final MemoryBudget budget;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private long[] keys = new long[0];
  private Bucket[] buckets = new Bucket[0];
  private long[] before = new long[0];
  private int bucketCount;
  private long size;
  private long retained;
  private long reserved;
  private long version;

  /** Creates an empty set that charges no budget. */
  public MutableLineSet() {
    this(null);
  }

  /**
   * Creates an empty set.
   *
   * @param budget charged for the set's storage, or null
   */
  public MutableLineSet(MemoryBudget budget) {
    this.budget = budget;
  }

  /** One bucket: an array of low bits, or a bitmap. */
  private static final class Bucket {
    char[] values;
    long[] bits;
    int count;

    long cost() {
      return bits != null ? 8L * BITMAP_WORDS : arrayBytes(values.length);
    }

    boolean contains(int low) {
      if (bits != null) {
        return (bits[low >>> 6] & (1L << low)) != 0;
      }
      return Arrays.binarySearch(values, 0, count, (char) low) >= 0;
    }

    /** Entries below low. */
    int rank(int low) {
      if (bits != null) {
        int word = low >>> 6;
        int n = 0;
        for (int w = 0; w < word; w++) {
          n += Long.bitCount(bits[w]);
        }
        return n + Long.bitCount(bits[word] & ((1L << low) - 1));
      }
      return lowerBound(values, count, low);
    }

    int select(int k) {
      if (bits == null) {
        return values[k];
      }
      int left = k;
      for (int w = 0; w < BITMAP_WORDS; w++) {
        int c = Long.bitCount(bits[w]);
        if (left < c) {
          long word = bits[w];
          for (int i = 0; i < left; i++) {
            word &= word - 1;
          }
          return (w << 6) + Long.numberOfTrailingZeros(word);
        }
        left -= c;
      }
      throw new IllegalStateException("bucket count out of step");
    }

    /** Smallest entry at or after low, or -1. */
    int ceiling(int low) {
      if (bits != null) {
        int w = low >>> 6;
        long word = bits[w] & (-1L << low);
        while (word == 0) {
          if (++w == BITMAP_WORDS) {
            return -1;
          }
          word = bits[w];
        }
        return (w << 6) + Long.numberOfTrailingZeros(word);
      }
      int i = lowerBound(values, count, low);
      return i < count ? values[i] : -1;
    }

    /** Largest entry at or before low, or -1. */
    int floor(int low) {
      if (bits != null) {
        int w = low >>> 6;
        long word = bits[w] & (-1L >>> (63 - (low & 63)));
        while (word == 0) {
          if (--w < 0) {
            return -1;
          }
          word = bits[w];
        }
        return (w << 6) + 63 - Long.numberOfLeadingZeros(word);
      }
      int i = lowerBound(values, count, low + 1) - 1;
      return i >= 0 ? values[i] : -1;
    }

    /** Adds sorted distinct low values; returns how many were new. */
    int merge(char[] incoming, int n) {
      if (bits != null) {
        int added = 0;
        for (int i = 0; i < n; i++) {
          added += setBit(incoming[i]) ? 1 : 0;
        }
        count += added;
        return added;
      }
      char[] merged;
      int total;
      if (count == 0 || incoming[0] > values[count - 1]) {
        total = count + n;
        if (total <= values.length) {
          System.arraycopy(incoming, 0, values, count, n);
          count = total;
          return n;
        }
        merged = new char[total];
        System.arraycopy(values, 0, merged, 0, count);
        System.arraycopy(incoming, 0, merged, count, n);
      } else {
        merged = new char[count + n];
        total = mergeInto(values, count, incoming, n, merged);
      }
      int added = total - count;
      store(merged, total);
      return added;
    }

    /** Replaces the contents with sorted distinct values. */
    void store(char[] sorted, int total) {
      if (total > SPARSE_MAX) {
        bits = new long[BITMAP_WORDS];
        values = null;
        for (int i = 0; i < total; i++) {
          setBit(sorted[i]);
        }
      } else {
        int capacity = capacityFor(values.length, total);
        if (capacity != values.length) {
          values = new char[capacity];
        }
        System.arraycopy(sorted, 0, values, 0, total);
      }
      count = total;
    }

    boolean setBit(int low) {
      long mask = 1L << low;
      long word = bits[low >>> 6];
      bits[low >>> 6] = word | mask;
      return (word & mask) == 0;
    }

    /** Removes one value; returns true when it was present. */
    boolean remove(int low) {
      if (bits != null) {
        long mask = 1L << low;
        if ((bits[low >>> 6] & mask) == 0) {
          return false;
        }
        bits[low >>> 6] &= ~mask;
        count--;
        maybeSparse();
        return true;
      }
      int i = Arrays.binarySearch(values, 0, count, (char) low);
      if (i < 0) {
        return false;
      }
      System.arraycopy(values, i + 1, values, i, count - i - 1);
      count--;
      shrink();
      return true;
    }

    /** Removes every value at or above low. */
    void removeFrom(int low) {
      if (bits != null) {
        int w = low >>> 6;
        bits[w] &= (1L << low) - 1;
        for (int i = w + 1; i < BITMAP_WORDS; i++) {
          bits[i] = 0;
        }
        int n = 0;
        for (long word : bits) {
          n += Long.bitCount(word);
        }
        count = n;
        maybeSparse();
        return;
      }
      count = lowerBound(values, count, low);
      shrink();
    }

    private void maybeSparse() {
      if (count > SPARSE_RETURN) {
        return;
      }
      char[] sorted = new char[count];
      int n = 0;
      for (int w = 0; w < BITMAP_WORDS; w++) {
        long word = bits[w];
        while (word != 0) {
          sorted[n++] = (char) ((w << 6) + Long.numberOfTrailingZeros(word));
          word &= word - 1;
        }
      }
      bits = null;
      values = new char[Math.max(MIN_CAPACITY, count)];
      System.arraycopy(sorted, 0, values, 0, count);
    }

    /** Halves an array that is less than a quarter full. */
    private void shrink() {
      if (values.length > MIN_CAPACITY && count < values.length / 4) {
        values = Arrays.copyOf(values, Math.max(MIN_CAPACITY, values.length / 2));
      }
    }
  }

  /**
   * Returns the array capacity a bucket grows to.
   *
   * @param current the capacity now
   * @param needed entries it must hold, at most {@link #SPARSE_MAX}
   * @return the new capacity: unchanged when it fits, else at least half again
   */
  static int capacityFor(int current, int needed) {
    if (needed <= current) {
      return current;
    }
    int grown = current + (current >> 1);
    return Math.min(SPARSE_MAX, Math.max(MIN_CAPACITY, Math.max(needed, grown)));
  }

  /** Bytes of a char array's elements, padded to the 8-byte object alignment. */
  static long arrayBytes(int capacity) {
    return (2L * capacity + 7) & ~7L;
  }

  /** Directory capacity after growing by doubling to hold the buckets. */
  private static int directoryFor(int current, int needed) {
    int capacity = current;
    while (capacity < needed) {
      capacity = capacity == 0 ? FIRST_DIRECTORY : capacity * 2;
    }
    return capacity;
  }

  private static int lowerBound(char[] values, int count, int low) {
    int lo = 0;
    int hi = count;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (values[mid] < low) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  private static int mergeInto(char[] a, int na, char[] b, int nb, char[] out) {
    int i = 0;
    int j = 0;
    int n = 0;
    while (i < na && j < nb) {
      if (a[i] < b[j]) {
        out[n++] = a[i++];
      } else if (a[i] > b[j]) {
        out[n++] = b[j++];
      } else {
        out[n++] = a[i++];
        j++;
      }
    }
    while (i < na) {
      out[n++] = a[i++];
    }
    while (j < nb) {
      out[n++] = b[j++];
    }
    return n;
  }

  @Override
  public long size() {
    lock.readLock().lock();
    try {
      return size;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public boolean contains(long line) {
    if (line < 0) {
      return false;
    }
    lock.readLock().lock();
    try {
      int i = find(line >>> BUCKET_SHIFT);
      return i >= 0 && buckets[i].contains((int) (line & LOW_MASK));
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public long select(long index) {
    lock.readLock().lock();
    try {
      if (index < 0 || index >= size) {
        throw new IndexOutOfBoundsException("index " + index + " of " + size);
      }
      int lo = 0;
      int hi = bucketCount - 1;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (before[mid] <= index) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }
      return (keys[lo] << BUCKET_SHIFT) | buckets[lo].select((int) (index - before[lo]));
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public long rank(long line) {
    if (line <= 0) {
      return 0;
    }
    lock.readLock().lock();
    try {
      long key = line >>> BUCKET_SHIFT;
      int i = lowerBound(key);
      if (i == bucketCount) {
        return size;
      }
      if (keys[i] != key) {
        return before[i];
      }
      return before[i] + buckets[i].rank((int) (line & LOW_MASK));
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public long ceiling(long line) {
    long from = Math.max(0, line);
    lock.readLock().lock();
    try {
      long key = from >>> BUCKET_SHIFT;
      int i = lowerBound(key);
      if (i < bucketCount && keys[i] == key) {
        int low = buckets[i].ceiling((int) (from & LOW_MASK));
        if (low >= 0) {
          return (key << BUCKET_SHIFT) | low;
        }
        i++;
      }
      return i < bucketCount ? (keys[i] << BUCKET_SHIFT) | buckets[i].select(0) : -1;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public long floor(long line) {
    if (line < 0) {
      return -1;
    }
    lock.readLock().lock();
    try {
      long key = line >>> BUCKET_SHIFT;
      int i = lowerBound(key);
      if (i < bucketCount && keys[i] == key) {
        int low = buckets[i].floor((int) (line & LOW_MASK));
        if (low >= 0) {
          return (key << BUCKET_SHIFT) | low;
        }
      }
      i--;
      return i >= 0 ? (keys[i] << BUCKET_SHIFT) | buckets[i].select(buckets[i].count - 1) : -1;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Returns the storage cost by the formula in the class description.
   *
   * @return bytes
   */
  public long retainedBytes() {
    lock.readLock().lock();
    try {
      return retained;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Returns a counter that every change increases, so a reader can tell a toggle that left the size
   * unchanged.
   *
   * @return the count of changes so far
   */
  public long version() {
    lock.readLock().lock();
    try {
      return version;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Adds a line, charging its storage without the limit check.
   *
   * @param line at least 0
   */
  public void add(long line) {
    checkLine(line);
    lock.writeLock().lock();
    try {
      addLocked(line);
      settle(Math.max(0, find(line >>> BUCKET_SHIFT)));
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Adds lines in ascending order, all or none. With a budget, the growth is reserved from {@link
   * MemoryShare#RESULTS} first.
   *
   * @param lines strictly ascending, at least 0; lines already present are skipped
   * @param count lines used from the start of the array
   * @return false, adding nothing, when the share cannot hold the growth
   * @throws IllegalArgumentException when the lines are negative or not strictly ascending
   */
  public boolean addSorted(long[] lines, int count) {
    if (count == 0) {
      return true;
    }
    for (int i = 0; i < count; i++) {
      checkLine(lines[i]);
      if (i > 0 && lines[i] <= lines[i - 1]) {
        throw new IllegalArgumentException("lines must be strictly ascending");
      }
    }
    lock.writeLock().lock();
    try {
      long growth = plannedGrowth(lines, count);
      if (budget != null && growth > 0) {
        if (!budget.reserve(MemoryShare.RESULTS, growth)) {
          return false;
        }
        reserved += growth;
      }
      int first = lowerBound(lines[0] >>> BUCKET_SHIFT);
      char[] group = new char[Math.min(count, BUCKET_LINES)];
      int i = 0;
      while (i < count) {
        long key = lines[i] >>> BUCKET_SHIFT;
        int n = 0;
        while (i < count && lines[i] >>> BUCKET_SHIFT == key) {
          group[n++] = (char) (lines[i] & LOW_MASK);
          i++;
        }
        int at = bucketFor(key);
        Bucket bucket = buckets[at];
        retained -= bucket.cost();
        bucket.merge(group, n);
        retained += bucket.cost();
      }
      settle(first);
      return true;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Adds or removes a line.
   *
   * @param line at least 0
   * @return true when the line is in the set afterwards
   */
  public boolean toggle(long line) {
    checkLine(line);
    lock.writeLock().lock();
    try {
      long key = line >>> BUCKET_SHIFT;
      int i = find(key);
      boolean present;
      if (i >= 0 && buckets[i].contains((int) (line & LOW_MASK))) {
        Bucket bucket = buckets[i];
        retained -= bucket.cost();
        bucket.remove((int) (line & LOW_MASK));
        retained += bucket.cost();
        dropIfEmpty(i);
        present = false;
      } else {
        addLocked(line);
        i = find(key);
        present = true;
      }
      settle(Math.max(0, i));
      return present;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Removes lines in one batch; lines not in the set are skipped. Released storage returns to the
   * budget.
   *
   * @param lines ascending lines
   * @param count lines used from the start of the array
   * @return lines removed
   */
  public int removeSorted(long[] lines, int count) {
    if (count == 0) {
      return 0;
    }
    lock.writeLock().lock();
    try {
      int removed = 0;
      for (int k = 0; k < count; k++) {
        long line = lines[k];
        if (line < 0) {
          continue;
        }
        int i = find(line >>> BUCKET_SHIFT);
        int low = (int) (line & LOW_MASK);
        if (i >= 0 && buckets[i].contains(low)) {
          Bucket bucket = buckets[i];
          retained -= bucket.cost();
          bucket.remove(low);
          retained += bucket.cost();
          removed++;
        }
      }
      for (int i = bucketCount - 1; i >= 0; i--) {
        dropIfEmpty(i);
      }
      settle(0);
      return removed;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Copies lines at or after a line, ascending, under one read lock.
   *
   * @param from the first line wanted
   * @param out receives the lines
   * @return lines copied; fewer than {@code out.length} only when none is left
   */
  public int fill(long from, long[] out) {
    long start = Math.max(0, from);
    lock.readLock().lock();
    try {
      long key = start >>> BUCKET_SHIFT;
      int i = lowerBound(key);
      int low = i < bucketCount && keys[i] == key ? (int) (start & LOW_MASK) : 0;
      int n = 0;
      while (i < bucketCount && n < out.length) {
        Bucket bucket = buckets[i];
        int value = bucket.ceiling(low);
        while (value >= 0 && n < out.length) {
          out[n++] = (keys[i] << BUCKET_SHIFT) | value;
          value = value == LOW_MASK ? -1 : bucket.ceiling(value + 1);
        }
        i++;
        low = 0;
      }
      return n;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Removes every line at or after a line.
   *
   * @param line any value; 0 or less empties the set
   */
  public void removeFrom(long line) {
    lock.writeLock().lock();
    try {
      long from = Math.max(0, line);
      long key = from >>> BUCKET_SHIFT;
      int i = lowerBound(key);
      int keep = i;
      if (i < bucketCount && keys[i] == key) {
        Bucket bucket = buckets[i];
        retained -= bucket.cost();
        bucket.removeFrom((int) (from & LOW_MASK));
        retained += bucket.cost();
        keep = bucket.count > 0 ? i + 1 : i;
      }
      for (int k = keep; k < bucketCount; k++) {
        retained -= BUCKET_OVERHEAD + buckets[k].cost();
        buckets[k] = null;
      }
      bucketCount = keep;
      settle(Math.max(0, keep - 1));
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** Empties the set and returns its whole reservation. */
  public void clear() {
    removeFrom(0);
  }

  /**
   * Returns a new set holding the lines of both, charging no budget.
   *
   * @param a a set
   * @param b another set
   * @return the union
   */
  public static MutableLineSet union(LineSet a, LineSet b) {
    MutableLineSet result = new MutableLineSet();
    long[] batch = new long[SPARSE_MAX];
    int n = 0;
    long x = a.ceiling(0);
    long y = b.ceiling(0);
    while (x >= 0 || y >= 0) {
      long next;
      if (y < 0 || x >= 0 && x < y) {
        next = x;
        x = x == Long.MAX_VALUE ? -1 : a.ceiling(x + 1);
      } else {
        next = y;
        if (x == y) {
          x = x == Long.MAX_VALUE ? -1 : a.ceiling(x + 1);
        }
        y = y == Long.MAX_VALUE ? -1 : b.ceiling(y + 1);
      }
      batch[n++] = next;
      if (n == batch.length) {
        result.addSorted(batch, n);
        n = 0;
      }
    }
    result.addSorted(batch, n);
    return result;
  }

  private static void checkLine(long line) {
    if (line < 0) {
      throw new IllegalArgumentException("line must not be negative");
    }
  }

  /** Upper bound of the cost the batch adds; the merge never grows a bucket past it. */
  private long plannedGrowth(long[] lines, int count) {
    long growth = 0;
    int added = 0;
    int i = 0;
    while (i < count) {
      long key = lines[i] >>> BUCKET_SHIFT;
      int n = 0;
      while (i < count && lines[i] >>> BUCKET_SHIFT == key) {
        n++;
        i++;
      }
      int at = find(key);
      if (at < 0) {
        growth += BUCKET_OVERHEAD + bucketCost(0, n);
        added++;
      } else {
        Bucket bucket = buckets[at];
        if (bucket.bits == null) {
          growth += bucketCost(bucket.values.length, bucket.count + n) - bucket.cost();
        }
      }
    }
    int directory = directoryFor(keys.length, bucketCount + added);
    return growth + DIRECTORY_SLOT_BYTES * (directory - keys.length);
  }

  private static long bucketCost(int capacity, int entries) {
    return entries > SPARSE_MAX ? 8L * BITMAP_WORDS : arrayBytes(capacityFor(capacity, entries));
  }

  private void addLocked(long line) {
    int at = bucketFor(line >>> BUCKET_SHIFT);
    Bucket bucket = buckets[at];
    retained -= bucket.cost();
    bucket.merge(new char[] {(char) (line & LOW_MASK)}, 1);
    retained += bucket.cost();
  }

  /** Returns the bucket for a key, inserting an empty one. */
  private int bucketFor(long key) {
    int i = lowerBound(key);
    if (i < bucketCount && keys[i] == key) {
      return i;
    }
    if (bucketCount == keys.length) {
      int capacity = directoryFor(keys.length, bucketCount + 1);
      retained += DIRECTORY_SLOT_BYTES * (capacity - keys.length);
      keys = Arrays.copyOf(keys, capacity);
      buckets = Arrays.copyOf(buckets, capacity);
      before = Arrays.copyOf(before, capacity);
    }
    System.arraycopy(keys, i, keys, i + 1, bucketCount - i);
    System.arraycopy(buckets, i, buckets, i + 1, bucketCount - i);
    System.arraycopy(before, i, before, i + 1, bucketCount - i);
    Bucket bucket = new Bucket();
    bucket.values = new char[0];
    keys[i] = key;
    buckets[i] = bucket;
    bucketCount++;
    retained += BUCKET_OVERHEAD;
    return i;
  }

  private void dropIfEmpty(int i) {
    if (buckets[i].count > 0) {
      return;
    }
    retained -= BUCKET_OVERHEAD + buckets[i].cost();
    System.arraycopy(keys, i + 1, keys, i, bucketCount - i - 1);
    System.arraycopy(buckets, i + 1, buckets, i, bucketCount - i - 1);
    System.arraycopy(before, i + 1, before, i, bucketCount - i - 1);
    bucketCount--;
    buckets[bucketCount] = null;
  }

  /**
   * Recomputes the counts before each bucket from one bucket on, drops the directory of an empty
   * set, then settles the reservation.
   */
  private void settle(int from) {
    version++;
    if (bucketCount == 0 && keys.length > 0) {
      retained -= DIRECTORY_SLOT_BYTES * keys.length;
      keys = new long[0];
      buckets = new Bucket[0];
      before = new long[0];
    }
    long n = from > 0 && from <= bucketCount ? before[from - 1] + buckets[from - 1].count : 0;
    int start = from > 0 && from <= bucketCount ? from : 0;
    for (int i = start; i < bucketCount; i++) {
      before[i] = n;
      n += buckets[i].count;
    }
    size = n;
    if (budget != null) {
      if (retained > reserved) {
        budget.charge(MemoryShare.RESULTS, retained - reserved);
      } else if (retained < reserved) {
        budget.release(MemoryShare.RESULTS, reserved - retained);
      }
      reserved = retained;
    }
  }

  private int find(long key) {
    int i = lowerBound(key);
    return i < bucketCount && keys[i] == key ? i : -1;
  }

  private int lowerBound(long key) {
    int lo = 0;
    int hi = bucketCount;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (keys[mid] < key) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
