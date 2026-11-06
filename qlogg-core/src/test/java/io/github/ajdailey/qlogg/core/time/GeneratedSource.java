package io.github.ajdailey.qlogg.core.time;

import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A log of any size that stores nothing: line {@code i} is {@link #WIDTH} bytes, a stack-trace line
 * when {@code i % 4 == 3}, else the stamp {@link TimeFixture#BASE} plus {@code 10 * i} ms. Lines of
 * an optional block carry a shifted stamp.
 */
final class GeneratedSource implements ByteSource {
  static final int WIDTH = 40;
  static final long STEP_MILLIS = 10;

  private final long size;
  private final long blockFirst;
  private final long blockEnd;
  private final long shiftMillis;
  private long cachedLine = -1;
  private final byte[] cached = new byte[WIDTH];

  GeneratedSource(long size) {
    this(size, 0, 0, 0);
  }

  /** A source whose lines in {@code [blockFirst, blockEnd)} are stamped {@code shiftMillis} off. */
  GeneratedSource(long size, long blockFirst, long blockEnd, long shiftMillis) {
    this.size = size;
    this.blockFirst = blockFirst;
    this.blockEnd = blockEnd;
    this.shiftMillis = shiftMillis;
  }

  static boolean stamped(long line) {
    return line % 4 != 3;
  }

  @Override
  public Path path() {
    return null;
  }

  @Override
  public long size() {
    return size;
  }

  @Override
  public synchronized int read(long position, ByteBuffer dst) {
    if (position >= size) {
      return -1;
    }
    int count = (int) Math.min(dst.remaining(), size - position);
    for (int i = 0; i < count; i++) {
      long at = position + i;
      dst.put(line(at / WIDTH)[(int) (at % WIDTH)]);
    }
    return count;
  }

  private byte[] line(long line) {
    if (line != cachedLine) {
      long shift = line >= blockFirst && line < blockEnd ? shiftMillis : 0;
      String text =
          stamped(line)
              ? TimeFixture.stampAt(line * STEP_MILLIS + shift) + " mock"
              : "\tat mock.Frame.run";
      Arrays.fill(cached, (byte) ' ');
      byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(bytes, 0, cached, 0, bytes.length);
      cached[WIDTH - 1] = '\n';
      cachedLine = line;
    }
    return cached;
  }

  @Override
  public FileIdentity identity() {
    return new FileIdentity(null, 0, size, 0, 0, null);
  }

  @Override
  public void close() {}
}
