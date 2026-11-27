package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Counts line feeds in a byte range as a line index does, one code unit at a time. */
final class LineCounter {
  private static final int READ_BYTES = 64 * 1024;

  /**
   * A count.
   *
   * @param complete lines ended by a line feed
   * @param open true when bytes follow the last line feed
   */
  record Counted(long complete, boolean open) {
    /** Returns the lines, a last line without a line feed included. */
    long lines() {
      return complete + (open ? 1 : 0);
    }
  }

  private LineCounter() {}

  /**
   * Counts the line feeds in {@code [from, end)}, cut to whole code units. Blocking.
   *
   * @param source the bytes
   * @param name the file named in a failure
   * @param from the first byte, after any byte order mark
   * @param end the end
   * @param encoding the unit width and line feed
   * @param closing true ends the count with an {@link IOException}
   * @return the count; {@code open} is false for an empty range
   * @throws IOException when a read fails, the source ends early, or {@code closing} is true
   */
  static Counted count(
      ByteSource source,
      Path name,
      long from,
      long end,
      TextEncoding encoding,
      BooleanSupplier closing)
      throws IOException {
    int unit = encoding.unitWidth();
    long stop = from + (end - from) / unit * unit;
    byte[] lineFeed = encoding.lineFeed();
    long complete = 0;
    boolean open = false;
    ByteBuffer block = ByteBuffer.allocate(READ_BYTES / unit * unit);
    long at = from;
    while (at < stop) {
      if (closing.getAsBoolean()) {
        throw new IOException("The set is closing");
      }
      block.clear();
      block.limit((int) Math.min(block.capacity(), stop - at));
      int read = source.read(at, block);
      if (read <= 0) {
        throw new IOException(name + " shrank while its lines were counted");
      }
      int whole = read / unit * unit;
      for (int i = 0; i < whole; i += unit) {
        boolean lf = true;
        for (int b = 0; b < unit && lf; b++) {
          lf = block.get(i + b) == lineFeed[b];
        }
        if (lf) {
          complete++;
        }
        open = !lf;
      }
      at += whole;
    }
    return new Counted(complete, open);
  }
}
