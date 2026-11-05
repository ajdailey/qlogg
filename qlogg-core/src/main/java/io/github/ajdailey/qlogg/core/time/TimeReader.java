package io.github.ajdailey.qlogg.core.time;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.text.LineDecoder;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/**
 * Blocking timestamp lookups on one document. A line without a timestamp continues the stamped line
 * above it. {@link #next} and {@link #at} read through the {@link BlockCache} and stop after {@link
 * #MAX_LINES} lines or {@link #MAX_BYTES} bytes from where they start; past the bound they report
 * no stamp. The parser sees the first {@link #PREFIX_CHARS} bytes times the unit width of a line,
 * decoded. Not thread-safe: one instance per worker, holding a {@link #BUFFER_BYTES} heap buffer.
 */
public final class TimeReader {
  /** Units of a line (bytes times the unit width) decoded for the parser. */
  public static final int PREFIX_CHARS = 256;

  /** Most lines one lookup reads. */
  public static final int MAX_LINES = 10_000;

  /** Most bytes one lookup reads past its start. */
  public static final int MAX_BYTES = 1 << 20;

  /** The read buffer. */
  public static final int BUFFER_BYTES = 1 << 16;

  /** Bytes one {@link #next} or {@link #at} read loads; a sequential scan loads the buffer. */
  public static final int LOOKUP_BYTES = 1 << 13;

  /** Bytes between two progress calls of a sequential scan. */
  public static final long PROGRESS_BYTES = 4L << 20;

  private final ByteSource source;
  private final BlockCache cache;
  private final TimestampParser parser;
  private final LineDecoder decoder;
  private final int unit;
  private final byte[] lineFeed;
  private final long firstStart;
  private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
  private final CharBuffer chars;
  private long base = -1;
  private int filled;
  private boolean direct;
  private long nanos;
  private int digits;

  /**
   * Creates a reader.
   *
   * @param source the document's bytes
   * @param cache lookups read through it
   * @param encoding the document's encoding; the first line starts after its BOM
   * @param parser the document's parser, used only by this reader
   */
  public TimeReader(
      ByteSource source, BlockCache cache, TextEncoding encoding, TimestampParser parser) {
    this.source = source;
    this.cache = cache;
    this.parser = parser;
    this.decoder = new LineDecoder(encoding);
    this.unit = encoding.unitWidth();
    this.lineFeed = encoding.lineFeed();
    this.firstStart = encoding.bomLength();
    this.chars = CharBuffer.allocate(PREFIX_CHARS);
  }

  /**
   * Returns the first stamp on a line that starts at or after an offset and before an end.
   *
   * @param offset any byte offset; a line that starts before it is skipped
   * @param end exclusive end of the region, normally the index snapshot's {@code indexedBytes()}
   * @param token checked between lines
   * @return the stamp, or null when none lies within the region and the bound
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  public Stamp next(long offset, long end, CancellationToken token) throws IOException {
    direct = false;
    long limit = Math.min(end, saturatedAdd(Math.max(offset, 0), MAX_BYTES));
    long start = lineStartAtOrAfter(offset, limit);
    for (int lines = 0; start >= 0 && start < limit && lines < MAX_LINES; lines++) {
      token.check();
      long lineFeedAt = parseLine(start, end, limit, true);
      if (nanos != TimestampParser.NONE) {
        return new Stamp(start, nanos, digits);
      }
      start = lineFeedAt < 0 ? -1 : lineFeedAt + unit;
    }
    return null;
  }

  /**
   * Returns a line's own stamp, or the stamp of the nearest line above it that has one.
   *
   * @param lineStart the line's first byte
   * @param token checked between lines
   * @return the stamp, whose offset is the stamped line's start; null when no line within the bound
   *     above has one
   * @throws IOException on a read error
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  public Stamp at(long lineStart, CancellationToken token) throws IOException {
    direct = false;
    parseLine(lineStart, Long.MAX_VALUE, Long.MAX_VALUE, false);
    if (nanos != TimestampParser.NONE) {
      return new Stamp(lineStart, nanos, digits);
    }
    long lower = Math.max(firstStart, lineStart - MAX_BYTES);
    long later = lineStart;
    for (int lines = 1; later > firstStart && lines < MAX_LINES; lines++) {
      token.check();
      long lineFeedAt = later - unit;
      long start = previousStart(lineFeedAt, lower);
      if (start < 0) {
        return null;
      }
      parseLine(start, lineFeedAt, lineFeedAt, false);
      if (nanos != TimestampParser.NONE) {
        return new Stamp(start, nanos, digits);
      }
      later = start;
    }
    return null;
  }

  /**
   * Scans every line in file order, reading the source directly, and stops at the first stamp at or
   * after the target. See {@link TimeSearch#locateLinear}.
   */
  Stamp linear(long end, long target, CancellationToken token, ScanProgress progress)
      throws IOException {
    direct = true;
    try {
      long start = firstStart;
      long report = PROGRESS_BYTES;
      long previousStart = -1;
      long previousNanos = 0;
      int previousDigits = 0;
      while (start >= 0 && start < end) {
        token.check();
        if (start >= report) {
          progress.scanned(start, end);
          report = start + PROGRESS_BYTES;
        }
        long lineFeedAt = parseLine(start, end, end, true);
        if (nanos != TimestampParser.NONE) {
          if (nanos >= target) {
            if (nanos == target || previousStart < 0) {
              return new Stamp(start, nanos, digits);
            }
            return new Stamp(previousStart, previousNanos, previousDigits);
          }
          previousStart = start;
          previousNanos = nanos;
          previousDigits = digits;
        }
        start = lineFeedAt < 0 ? -1 : lineFeedAt + unit;
      }
      progress.scanned(end, end);
      return previousStart < 0 ? null : new Stamp(previousStart, previousNanos, previousDigits);
    } finally {
      direct = false;
      base = -1;
    }
  }

  /**
   * Parses a line's prefix into {@link #nanos} and {@link #digits}.
   *
   * @param end the region's end; the prefix stops there
   * @param searchLimit the line feed is searched for before it
   * @param needLineFeed false when the caller does not need the line's end
   * @return the line feed's offset, or -1 when none lies before the limit
   */
  private long parseLine(long start, long end, long searchLimit, boolean needLineFeed)
      throws IOException {
    long prefixEnd = Math.min(end, saturatedAdd(start, (long) PREFIX_CHARS * unit));
    ensure(start, prefixEnd);
    long available = Math.min(prefixEnd, base + filled);
    long lineFeedAt = findLineFeed(start, Math.min(available, searchLimit));
    long decodeEnd = lineFeedAt >= 0 ? lineFeedAt : Math.max(start, available);
    buffer.limit((int) (decodeEnd - base));
    buffer.position((int) (start - base));
    chars.clear();
    decoder.decodeLine(buffer, chars);
    buffer.limit(buffer.capacity());
    chars.flip();
    nanos = parser.parse(chars);
    digits = nanos == TimestampParser.NONE ? 0 : parser.fractionDigits();
    if (lineFeedAt >= 0 || !needLineFeed) {
      return lineFeedAt;
    }
    return findLineFeedAcross(Math.max(start, available), searchLimit);
  }

  /** The first line start at or after an offset and before the limit, or -1. */
  private long lineStartAtOrAfter(long offset, long limit) throws IOException {
    if (offset <= firstStart) {
      return firstStart;
    }
    long aligned = firstStart + Math.ceilDiv(offset - firstStart, (long) unit) * unit;
    if (aligned >= limit) {
      return -1;
    }
    ensure(aligned - unit, aligned);
    if (base + filled >= aligned && lineFeedAt(aligned - unit)) {
      return aligned;
    }
    long found = findLineFeedAcross(aligned, limit);
    return found < 0 ? -1 : found + unit;
  }

  /**
   * Returns the start of the line that ends at a line feed, searching back no further than {@code
   * lower}.
   *
   * @return the start, or -1 when the search reaches {@code lower} above the first line
   */
  private long previousStart(long lineFeedAt, long lower) throws IOException {
    for (long at = lineFeedAt - unit; at >= lower; at -= unit) {
      if (at < base || at + unit > base + filled) {
        // The window also holds the prefix of the line after this unit, which is parsed next.
        load(Math.max(0, at + unit + (long) PREFIX_CHARS * unit - LOOKUP_BYTES));
      }
      if (lineFeedAt(at)) {
        return at + unit;
      }
    }
    return lower == firstStart ? firstStart : -1;
  }

  /** Searches the loaded bytes in {@code [from, limit)} for a line feed unit. */
  private long findLineFeed(long from, long limit) {
    long stop = Math.min(limit, base + filled);
    if (unit == 1) {
      byte[] bytes = buffer.array();
      byte feed = lineFeed[0];
      int end = (int) (stop - base);
      for (int i = (int) (from - base); i < end; i++) {
        if (bytes[i] == feed) {
          return base + i;
        }
      }
      return -1;
    }
    for (long at = from; at + unit <= stop; at += unit) {
      if (lineFeedAt(at)) {
        return at;
      }
    }
    return -1;
  }

  /** Searches {@code [from, limit)} for a line feed unit, loading as needed. */
  private long findLineFeedAcross(long from, long limit) throws IOException {
    long at = from;
    while (at < limit) {
      ensure(at, Math.min(limit, at + unit));
      long stop = Math.min(limit, base + filled);
      if (at + unit > stop) {
        return -1;
      }
      long found = findLineFeed(at, stop);
      if (found >= 0) {
        return found;
      }
      at = stop - (stop - at) % unit;
    }
    return -1;
  }

  /** Compares the unit at an offset, which must be loaded, with the line feed. */
  private boolean lineFeedAt(long at) {
    int index = (int) (at - base);
    for (int i = 0; i < unit; i++) {
      if (buffer.get(index + i) != lineFeed[i]) {
        return false;
      }
    }
    return true;
  }

  /** Loads from {@code from} unless {@code [from, to)} is already loaded. */
  private void ensure(long from, long to) throws IOException {
    if (base < 0 || from < base || to > base + filled) {
      load(from);
    }
  }

  private void load(long position) throws IOException {
    buffer.clear();
    if (direct) {
      int read = 0;
      while (buffer.hasRemaining() && read >= 0) {
        read = source.read(position + buffer.position(), buffer);
      }
    } else {
      cache.read(source, position, LOOKUP_BYTES, buffer);
    }
    base = position;
    filled = buffer.position();
  }

  private static long saturatedAdd(long a, long b) {
    long sum = a + b;
    return sum < a ? Long.MAX_VALUE : sum;
  }
}
