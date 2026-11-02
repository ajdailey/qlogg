package io.github.ajdailey.qlogg.bench;

import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.testkit.SyntheticLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Sequential scan of a generated log, counting LF bytes: positional {@code FileChannel} reads into
 * one 4 MiB direct buffer (the indexer's read path) against a {@code MappedByteBuffer} per 1 GiB
 * window. Both count with the same eight-bytes-at-a-time loop, so the difference is the read path.
 * The file is written once to {@code -Dqlogg.bench.dir} and reused; the cache is warm.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
public class ReadProbe {
  private static final long WINDOW = 1L << 30;
  private static final long LF_BYTES = 0x0a0a0a0a0a0a0a0aL;
  private static final long LOW7 = 0x7f7f7f7f7f7f7f7fL;

  /** File size in MiB. */
  @Param({"1024"})
  public int fileMib;

  private Path file;

  /**
   * Writes the file when it does not exist.
   *
   * @throws IOException when the directory is missing or the write fails
   */
  @Setup(Level.Trial)
  public void prepare() throws IOException {
    String dir = System.getProperty("qlogg.bench.dir");
    if (dir == null) {
      throw new IOException("Set -Dqlogg.bench.dir to a scratch directory");
    }
    file = Path.of(dir).resolve("read-probe-" + fileMib + "m.log");
    if (!Files.exists(file)) {
      SyntheticLog.write(file, (long) fileMib << 20, 1, "\n");
    }
    if (positional() != mapped()) {
      throw new IllegalStateException("Read paths disagree");
    }
  }

  /**
   * Scans with positional reads.
   *
   * @return LF count
   * @throws IOException on a read error
   */
  @Benchmark
  public long positional() throws IOException {
    ByteBuffer buffer =
        ByteBuffer.allocateDirect(IndexSettings.DEFAULT_BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    long count = 0;
    try (FileByteSource source = FileByteSource.open(file)) {
      long size = source.size();
      long position = 0;
      while (position < size) {
        buffer.clear();
        while (buffer.hasRemaining()) {
          if (source.read(position + buffer.position(), buffer) < 0) {
            break;
          }
        }
        count += countLineFeeds(buffer, buffer.position());
        position += buffer.position();
      }
    }
    return count;
  }

  /**
   * Scans through memory-mapped windows. Each mapping is released only by garbage collection.
   *
   * @return LF count
   * @throws IOException on a map error
   */
  @Benchmark
  public long mapped() throws IOException {
    long count = 0;
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = channel.size();
      for (long position = 0; position < size; position += WINDOW) {
        long length = Math.min(WINDOW, size - position);
        MappedByteBuffer window = channel.map(FileChannel.MapMode.READ_ONLY, position, length);
        window.order(ByteOrder.LITTLE_ENDIAN);
        count += countLineFeeds(window, (int) length);
      }
    }
    return count;
  }

  private static long countLineFeeds(ByteBuffer buffer, int length) {
    long count = 0;
    int at = 0;
    int stop = length - 8;
    while (at <= stop) {
      long x = buffer.getLong(at) ^ LF_BYTES;
      count += Long.bitCount(~(((x & LOW7) + LOW7) | x | LOW7));
      at += 8;
    }
    while (at < length) {
      if (buffer.get(at) == '\n') {
        count++;
      }
      at++;
    }
    return count;
  }
}
