package io.github.ajdailey.qlogg.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Line starts found by a byte-by-byte scan, for comparing against the index. */
final class ReferenceIndex {
  private final List<Long> starts = new ArrayList<Long>();
  private final long indexedBytes;
  private final int unit;

  ReferenceIndex(byte[] content, TextEncoding encoding) {
    this.unit = encoding.unitWidth();
    byte[] lineFeed = encoding.lineFeed();
    int first = encoding.bomLength();
    int whole = first + (Math.max(0, content.length - first) / unit) * unit;
    starts.add((long) first);
    for (int at = first; at + unit <= whole; at += unit) {
      if (Arrays.equals(content, at, at + unit, lineFeed, 0, unit)) {
        starts.add((long) at + unit);
      }
    }
    this.indexedBytes = whole;
  }

  long lineCount() {
    long last = starts.get(starts.size() - 1);
    return indexedBytes > last ? starts.size() : starts.size() - 1;
  }

  long lineStart(long line) {
    return starts.get((int) line);
  }

  long lineEnd(long line) {
    return line == starts.size() - 1 ? indexedBytes : starts.get((int) line + 1) - unit;
  }

  long lineAt(long offset) {
    long count = lineCount();
    int low = 0;
    int high = (int) Math.max(0, count - 1);
    while (low < high) {
      int mid = (low + high + 1) >>> 1;
      if (starts.get(mid) <= offset) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return low;
  }

  long maxLineBytes() {
    long max = 0;
    for (long line = 0; line < lineCount(); line++) {
      max = Math.max(max, lineEnd(line) - lineStart(line));
    }
    return max;
  }

  /**
   * Compares every line. {@code lineAt} is compared at every offset up to two bytes past the end
   * for regions up to {@link #EVERY_OFFSET_BYTES}, otherwise at each line's first, second and last
   * byte and inside its LF.
   */
  void assertMatches(LineIndex index) {
    assertEquals(lineCount(), index.lineCount(), "lineCount");
    assertEquals(indexedBytes, index.indexedBytes(), "indexedBytes");
    assertEquals(
        indexedBytes == starts.get(starts.size() - 1), index.lastLineComplete(), "complete");
    assertEquals(maxLineBytes(), index.maxLineBytes(), "maxLineBytes");
    long count = lineCount();
    for (long line = 0; line < count; line++) {
      assertEquals(lineStart(line), index.lineStart(line), "lineStart " + line);
      assertEquals(lineEnd(line), index.lineEnd(line), "lineEnd " + line);
    }
    // Backward and strided orders: a sparse lookup must not start from a later remembered line.
    for (long line = count - 1; line >= 0; line--) {
      assertEquals(lineStart(line), index.lineStart(line), "lineStart backward " + line);
    }
    long stride = Math.max(1, count / 7 + 3);
    for (long i = 0, line = 0; i < Math.min(count, 2000); i++, line = (line + stride) % count) {
      assertEquals(lineStart(line), index.lineStart(line), "lineStart strided " + line);
    }
    if (indexedBytes <= EVERY_OFFSET_BYTES) {
      long line = 0;
      for (long offset = 0; offset <= indexedBytes + 2; offset++) {
        while (line + 1 < count && starts.get((int) line + 1) <= offset) {
          line++;
        }
        assertEquals(line, index.lineAt(offset), "lineAt " + offset);
      }
      return;
    }
    for (long line = 0; line < count; line++) {
      long[] offsets = {
        lineStart(line), lineStart(line) + 1, lineEnd(line) - 1, lineEnd(line) + unit - 1
      };
      for (long offset : offsets) {
        if (offset >= lineStart(line) && offset < indexedBytes) {
          assertEquals(lineAt(offset), index.lineAt(offset), "lineAt " + offset);
        }
      }
    }
    assertEquals(Math.max(0, count - 1), index.lineAt(indexedBytes + 2), "lineAt past end");
  }

  static final long EVERY_OFFSET_BYTES = 20_000;
}
