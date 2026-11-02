package io.github.ajdailey.qlogg.core.index;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import java.util.Arrays;

/**
 * Full-mode line starts: blocks of 128 lines, each a base offset, a bit width and a word index (13
 * bytes), plus the block's 127 line lengths bit-packed at that width into {@code long[]} pages.
 * Lines not yet in a sealed block sit in a 128-entry tail. Single writer; sealed blocks never
 * change, so a {@link View} taken by the writer stays valid while the writer appends.
 *
 * <p>Allocation is in fixed steps, each reserved from {@link MemoryShare#LINE_INDEX} first: block
 * metadata in chunks of {@link #CHUNK_BLOCKS}, packed words in pages of {@link #PAGE_WORDS}, and
 * directories of chunk and page references doubling from {@link #DIRECTORY_START}.
 */
final class PackedStarts {
  static final int BLOCK_LINES = 128;
  static final int CHUNK_BLOCKS = 1024;
  static final int PAGE_SHIFT = 13;
  static final int PAGE_WORDS = 1 << PAGE_SHIFT;
  static final int DIRECTORY_START = 4;
  static final int BLOCK_META_BYTES = 8 + 1 + 4;

  private static final int DELTAS = BLOCK_LINES - 1;

  /** Receives line starts in order. */
  interface Sink {
    /**
     * Accepts one start.
     *
     * @param line the line number
     * @param start its offset
     * @return false to stop
     */
    boolean accept(long line, long start);
  }

  private final MemoryBudget budget;
  private long[][] bases = new long[DIRECTORY_START][];
  private byte[][] widths = new byte[DIRECTORY_START][];
  private int[][] wordIndex = new int[DIRECTORY_START][];
  private long[][] pages = new long[DIRECTORY_START][];
  private int chunks;
  private int pageCount;
  private int blocks;
  private int wordsUsed;
  private final long[] tail = new long[BLOCK_LINES];
  private int tailCount;
  private long retained;

  private PackedStarts(MemoryBudget budget, long retained) {
    this.budget = budget;
    this.retained = retained;
  }

  /**
   * Reserves the fixed part (directories and tail) and creates empty storage.
   *
   * @return null when the share refuses
   */
  static PackedStarts create(MemoryBudget budget) {
    long fixed = 4L * DIRECTORY_START * 8 + BLOCK_LINES * 8L;
    if (!budget.reserve(MemoryShare.LINE_INDEX, fixed)) {
      return null;
    }
    return new PackedStarts(budget, fixed);
  }

  /** Bytes held by every array, all reserved. */
  long retainedBytes() {
    return retained;
  }

  /** Returns the reservation; the storage is not used afterwards. */
  void release() {
    budget.release(MemoryShare.LINE_INDEX, retained);
    retained = 0;
  }

  /**
   * Appends the next line start, sealing the tail into a block when it is full.
   *
   * @return false, storing nothing, when a needed reservation is refused
   */
  boolean add(long start) {
    if (tailCount == BLOCK_LINES && !seal()) {
      return false;
    }
    tail[tailCount++] = start;
    return true;
  }

  /** Bytes the next seal of a block with this width would reserve. */
  long sealCost(int width) {
    long cost = 0;
    if (blocks == chunks * CHUNK_BLOCKS) {
      cost += (long) CHUNK_BLOCKS * BLOCK_META_BYTES;
      if (chunks == bases.length) {
        cost += 3L * bases.length * 8;
      }
    }
    int words = wordsFor(width);
    int pagesNeeded = (int) (((long) wordsUsed + words + PAGE_WORDS - 1) >>> PAGE_SHIFT);
    if (pagesNeeded > pageCount) {
      cost += (long) (pagesNeeded - pageCount) * PAGE_WORDS * 8;
      int directory = pages.length;
      while (directory < pagesNeeded) {
        cost += (long) directory * 8;
        directory *= 2;
      }
    }
    return cost;
  }

  static int wordsFor(int width) {
    return (DELTAS * width + 63) >>> 6;
  }

  private boolean seal() {
    long longest = 0;
    for (int i = 0; i < DELTAS; i++) {
      longest = Math.max(longest, tail[i + 1] - tail[i]);
    }
    int width = 64 - Long.numberOfLeadingZeros(longest);
    long cost = sealCost(width);
    if (cost > 0 && !budget.reserve(MemoryShare.LINE_INDEX, cost)) {
      return false;
    }
    retained += cost;
    if (blocks == chunks * CHUNK_BLOCKS) {
      if (chunks == bases.length) {
        bases = Arrays.copyOf(bases, chunks * 2);
        widths = Arrays.copyOf(widths, chunks * 2);
        wordIndex = Arrays.copyOf(wordIndex, chunks * 2);
      }
      bases[chunks] = new long[CHUNK_BLOCKS];
      widths[chunks] = new byte[CHUNK_BLOCKS];
      wordIndex[chunks] = new int[CHUNK_BLOCKS];
      chunks++;
    }
    int words = wordsFor(width);
    while (((long) wordsUsed + words) > (long) pageCount * PAGE_WORDS) {
      if (pageCount == pages.length) {
        pages = Arrays.copyOf(pages, pageCount * 2);
      }
      pages[pageCount++] = new long[PAGE_WORDS];
    }
    int chunk = blocks / CHUNK_BLOCKS;
    int slot = blocks % CHUNK_BLOCKS;
    bases[chunk][slot] = tail[0];
    widths[chunk][slot] = (byte) width;
    wordIndex[chunk][slot] = wordsUsed;
    long bit = (long) wordsUsed << 6;
    for (int i = 0; i < DELTAS; i++) {
      put(pages, bit, width, tail[i + 1] - tail[i]);
      bit += width;
    }
    wordsUsed += words;
    blocks++;
    tailCount = 0;
    return true;
  }

  private static void put(long[][] pages, long bit, int width, long value) {
    long word = bit >>> 6;
    int shift = (int) (bit & 63);
    long[] page = pages[(int) (word >>> PAGE_SHIFT)];
    int index = (int) (word & (PAGE_WORDS - 1));
    page[index] |= value << shift;
    if (shift + width > 64) {
      long next = word + 1;
      pages[(int) (next >>> PAGE_SHIFT)][(int) (next & (PAGE_WORDS - 1))] |= value >>> (64 - shift);
    }
  }

  private static long get(long[][] pages, long bit, int width) {
    long word = bit >>> 6;
    int shift = (int) (bit & 63);
    long value = pages[(int) (word >>> PAGE_SHIFT)][(int) (word & (PAGE_WORDS - 1))] >>> shift;
    if (shift + width > 64) {
      long next = word + 1;
      value |= pages[(int) (next >>> PAGE_SHIFT)][(int) (next & (PAGE_WORDS - 1))] << (64 - shift);
    }
    return width == 64 ? value : value & ((1L << width) - 1);
  }

  /** Captures the current content; the writer must not run concurrently. */
  View view() {
    return new View(bases, widths, wordIndex, pages, blocks, Arrays.copyOf(tail, tailCount));
  }

  /**
   * Immutable view of sealed blocks plus a copy of the tail.
   *
   * @param bases chunk directory of block base offsets
   * @param widths chunk directory of block bit widths
   * @param wordIndex chunk directory of block word indexes
   * @param pages page directory of packed lengths
   * @param blocks sealed blocks
   * @param tail starts after the sealed blocks
   */
  record View(
      long[][] bases, byte[][] widths, int[][] wordIndex, long[][] pages, int blocks, long[] tail) {

    /** Counts the starts in the view. */
    long count() {
      return (long) blocks * BLOCK_LINES + tail.length;
    }

    /** Returns the start of a line in the view. */
    long start(long line) {
      int block = (int) (line >>> 7);
      int within = (int) (line & (BLOCK_LINES - 1));
      if (block >= blocks) {
        return tail[within];
      }
      int chunk = block / CHUNK_BLOCKS;
      int slot = block % CHUNK_BLOCKS;
      int width = widths[chunk][slot];
      long bit = (long) wordIndex[chunk][slot] << 6;
      long start = bases[chunk][slot];
      for (int i = 0; i < within; i++) {
        start += get(pages, bit, width);
        bit += width;
      }
      return start;
    }

    /** Returns the last line whose start is at or before an offset at or after the first start. */
    long lineAt(long offset) {
      if (tail.length > 0 && offset >= tail[0]) {
        int low = 0;
        int high = tail.length - 1;
        while (low < high) {
          int mid = (low + high + 1) >>> 1;
          if (tail[mid] <= offset) {
            low = mid;
          } else {
            high = mid - 1;
          }
        }
        return (long) blocks * BLOCK_LINES + low;
      }
      if (blocks == 0) {
        return 0;
      }
      int low = 0;
      int high = blocks - 1;
      while (low < high) {
        int mid = (low + high + 1) >>> 1;
        if (bases[mid / CHUNK_BLOCKS][mid % CHUNK_BLOCKS] <= offset) {
          low = mid;
        } else {
          high = mid - 1;
        }
      }
      int chunk = low / CHUNK_BLOCKS;
      int slot = low % CHUNK_BLOCKS;
      int width = widths[chunk][slot];
      long bit = (long) wordIndex[chunk][slot] << 6;
      long start = bases[chunk][slot];
      for (int i = 0; i < DELTAS; i++) {
        long next = start + get(pages, bit, width);
        if (next > offset) {
          return (long) low * BLOCK_LINES + i;
        }
        start = next;
        bit += width;
      }
      return (long) low * BLOCK_LINES + DELTAS;
    }

    /** Passes every start, in order, to the sink until it returns false. */
    void forEach(Sink sink) {
      for (int block = 0; block < blocks; block++) {
        int chunk = block / CHUNK_BLOCKS;
        int slot = block % CHUNK_BLOCKS;
        int width = widths[chunk][slot];
        long bit = (long) wordIndex[chunk][slot] << 6;
        long start = bases[chunk][slot];
        long line = (long) block * BLOCK_LINES;
        if (!sink.accept(line, start)) {
          return;
        }
        for (int i = 0; i < DELTAS; i++) {
          start += get(pages, bit, width);
          bit += width;
          if (!sink.accept(line + i + 1, start)) {
            return;
          }
        }
      }
      long line = (long) blocks * BLOCK_LINES;
      for (int i = 0; i < tail.length; i++) {
        if (!sink.accept(line + i, tail[i])) {
          return;
        }
      }
    }
  }
}
