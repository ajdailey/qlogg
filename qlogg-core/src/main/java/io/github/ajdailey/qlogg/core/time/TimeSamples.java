package io.github.ajdailey.qlogg.core.time;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import java.io.IOException;
import java.util.Arrays;

/**
 * Stamps at chosen lines, for the order check and the time-spread display. A sample is the first
 * stamp at or after {@link LineIndex#nearestStart} of its line, so a sparse index snaps it to a
 * checkpoint and reads no line start. Immutable. The caller reserves {@link #bytesFor} from its
 * memory share before sampling.
 */
public final class TimeSamples {
  private final long[] nanos;
  private final int descents;

  private TimeSamples(long[] nanos, int descents) {
    this.nanos = nanos;
    this.descents = descents;
  }

  /**
   * Wraps stamps already read.
   *
   * @param nanos one stamp per sample, or {@link TimestampParser#NONE}; kept, not copied
   * @return the samples
   */
  static TimeSamples of(long... nanos) {
    int descents = 0;
    long last = TimestampParser.NONE;
    for (long stamp : nanos) {
      if (stamp != TimestampParser.NONE) {
        if (last != TimestampParser.NONE && stamp < last) {
          descents++;
        }
        last = stamp;
      }
    }
    return new TimeSamples(nanos, descents);
  }

  /**
   * Reads samples at evenly spaced lines: sample {@code i} starts at line {@code i * (lineCount -
   * 1) / (count - 1)}.
   *
   * @param reader the document's reader
   * @param index the snapshot; samples end at its {@code indexedBytes()}
   * @param lineCount lines to cover, at most the snapshot's line count
   * @param count samples, at least 1
   * @param token checked before each sample
   * @return the samples
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  public static TimeSamples sample(
      TimeReader reader, LineIndex index, long lineCount, int count, CancellationToken token)
      throws IOException {
    if (count < 1 || lineCount < 0 || lineCount > index.lineCount()) {
      throw new IllegalArgumentException("count " + count + ", lineCount " + lineCount);
    }
    long[] nanos = new long[count];
    Arrays.fill(nanos, TimestampParser.NONE);
    if (lineCount > 0) {
      long[] lines = new long[count];
      for (int i = 0; i < count; i++) {
        lines[i] = count == 1 ? 0 : i * (lineCount - 1) / (count - 1);
      }
      read(reader, index, lines, nanos, token);
    }
    return of(nanos);
  }

  /**
   * Reads one sample per given line. Lines that snap to one start share one read.
   *
   * @param reader the document's reader
   * @param index the snapshot; samples end at its {@code indexedBytes()}
   * @param lines 0-based lines, at least one; a line past the snapshot's last reads as its last
   * @param token checked before each sample
   * @return the samples, all {@link TimestampParser#NONE} for an empty snapshot
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  public static TimeSamples sample(
      TimeReader reader, LineIndex index, long[] lines, CancellationToken token)
      throws IOException {
    if (lines.length == 0) {
      throw new IllegalArgumentException("no lines");
    }
    long[] nanos = new long[lines.length];
    Arrays.fill(nanos, TimestampParser.NONE);
    if (index.lineCount() > 0) {
      read(reader, index, lines, nanos, token);
    }
    return of(nanos);
  }

  private static void read(
      TimeReader reader, LineIndex index, long[] lines, long[] nanos, CancellationToken token)
      throws IOException {
    long last = index.lineCount() - 1;
    long previous = -1;
    for (int i = 0; i < lines.length; i++) {
      token.check();
      long offset = index.nearestStart(Math.min(lines[i], last)).offset();
      if (offset == previous) {
        nanos[i] = nanos[i - 1];
      } else {
        Stamp stamp = reader.next(offset, index.indexedBytes(), token);
        nanos[i] = stamp == null ? TimestampParser.NONE : stamp.nanos();
        previous = offset;
      }
    }
  }

  /**
   * Returns the heap cost of a sample set.
   *
   * @param count samples
   * @return {@code 8 * count + 64} bytes
   */
  public static long bytesFor(int count) {
    return 8L * count + 64;
  }

  /**
   * Counts the samples.
   *
   * @return the samples held
   */
  public int count() {
    return nanos.length;
  }

  /**
   * Returns one sample.
   *
   * @param i 0 to {@code count() - 1}
   * @return epoch nanoseconds, or {@link TimestampParser#NONE} when no stamp lies within the lookup
   *     bound
   */
  public long nanos(int i) {
    return nanos[i];
  }

  /**
   * Counts samples earlier than the stamped sample before them; any descent marks the document out
   * of order.
   *
   * @return descents
   */
  public int descents() {
    return descents;
  }

  /**
   * Returns the heap held.
   *
   * @return {@link #bytesFor} of the count
   */
  public long retainedBytes() {
    return bytesFor(nanos.length);
  }
}
