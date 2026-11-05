package io.github.ajdailey.qlogg.core.time;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.index.LineStart;
import java.io.IOException;

/**
 * Stamps at evenly spaced lines, for the order check and the time-spread display. Sample {@code i}
 * is the first stamp at or after {@link LineIndex#nearestStart} of line {@code i * (lineCount - 1)
 * / (count - 1)}, so a sparse index snaps it to a checkpoint and reads no line start. Immutable.
 * The caller reserves {@link #bytesFor} from its memory share before sampling.
 */
public final class TimeSamples {
  private final long[] nanos;
  private final int descents;

  private TimeSamples(long[] nanos, int descents) {
    this.nanos = nanos;
    this.descents = descents;
  }

  /**
   * Reads the samples.
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
    int descents = 0;
    long last = TimestampParser.NONE;
    for (int i = 0; i < count; i++) {
      token.check();
      nanos[i] = TimestampParser.NONE;
      if (lineCount > 0) {
        long line = count == 1 ? 0 : i * (lineCount - 1) / (count - 1);
        LineStart start = index.nearestStart(line);
        Stamp stamp = reader.next(start.offset(), index.indexedBytes(), token);
        if (stamp != null) {
          nanos[i] = stamp.nanos();
          if (last != TimestampParser.NONE && stamp.nanos() < last) {
            descents++;
          }
          last = stamp.nanos();
        }
      }
    }
    return new TimeSamples(nanos, descents);
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
   * @return the count given to {@link #sample}
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
