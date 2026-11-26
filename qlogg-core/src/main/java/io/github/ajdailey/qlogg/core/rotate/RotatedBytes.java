package io.github.ajdailey.qlogg.core.rotate;

import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileChangedException;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

/**
 * The members of a rotated set read as one stream, oldest first. Every member's byte order mark is
 * left out, so the stream has none. A member before the last that does not end with LF is followed
 * by one encoded LF; the last member never is, so its partial last line stays partial. A member
 * before the last keeps the length it had at {@link #open}, cut to whole code units; the last
 * member grows with its file. A read stops at a member boundary and at the start of an added LF.
 *
 * <p>Thread-safe as the member sources are. Closing closes every member source.
 */
public final class RotatedBytes implements ByteSource {
  /**
   * One member's source.
   *
   * @param source the open file; {@link RotatedBytes} owns it once {@link #open} returns
   * @param bomLength bytes of byte order mark at the file's start, left out of the stream
   */
  public record Part(ByteSource source, int bomLength) {}

  private final Path base;
  private final ByteSource[] sources;
  private final ByteSource[] views;
  private final int[] bom;
  private final long[] start;
  private final long[] content;
  private final int[] added;
  private final byte[] lineFeed;
  private final int last;

  private RotatedBytes(Path base, List<Part> parts, byte[] lineFeed) throws IOException {
    int count = parts.size();
    this.base = base;
    this.lineFeed = lineFeed;
    this.last = count - 1;
    this.sources = new ByteSource[count];
    this.views = new ByteSource[count];
    this.bom = new int[count];
    this.start = new long[count];
    this.content = new long[count];
    this.added = new int[count];
    int unit = lineFeed.length;
    long next = 0;
    for (int i = 0; i < count; i++) {
      sources[i] = parts.get(i).source();
      bom[i] = parts.get(i).bomLength();
      start[i] = next;
      views[i] = new MemberView(i);
      if (i < last) {
        content[i] = Math.max(0, sources[i].size() - bom[i]) / unit * unit;
        if (content[i] > 0 && !endsWithLineFeed(i)) {
          added[i] = unit;
        }
        next += content[i] + added[i];
      }
    }
  }

  /**
   * Assembles the stream. Reads each member's size and, before the last member, its last code unit.
   *
   * @param base the set's base path, returned by {@link #path}
   * @param encoding the set's encoding; its LF is the one added
   * @param parts the members, oldest first, at least one
   * @return the stream; it owns the sources
   * @throws IOException when a member cannot be read; the caller still owns the sources
   */
  public static RotatedBytes open(Path base, TextEncoding encoding, List<Part> parts)
      throws IOException {
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("A rotated set needs a member");
    }
    return new RotatedBytes(base, parts, encoding.lineFeed());
  }

  /**
   * Returns the member count.
   *
   * @return members, at least 1
   */
  public int memberCount() {
    return sources.length;
  }

  /**
   * Returns a member's bytes in its own offsets, byte order mark included, ending where the stream
   * takes them: a member before the last ends at {@link #memberEnd}. Index a member through this
   * source. Closing it does nothing.
   *
   * @param member 0 to {@code memberCount() - 1}
   * @return the source, the same instance on every call
   */
  public ByteSource member(int member) {
    return views[member];
  }

  /**
   * Returns the stream offset of a member's first byte after its byte order mark.
   *
   * @param member 0 to {@code memberCount() - 1}
   * @return byte offset
   */
  public long memberStart(int member) {
    return start[member];
  }

  /**
   * Returns a member's byte order mark length.
   *
   * @param member 0 to {@code memberCount() - 1}
   * @return bytes
   */
  public int bomLength(int member) {
    return bom[member];
  }

  /**
   * Returns where the stream stops taking a member's bytes, in the member's own offsets.
   *
   * @param member 0 to {@code memberCount() - 1}
   * @return byte offset; {@code Long.MAX_VALUE} for the last member
   */
  public long memberEnd(int member) {
    return member == last ? Long.MAX_VALUE : bom[member] + content[member];
  }

  /**
   * Returns the bytes added after a member: one LF, or none.
   *
   * @param member 0 to {@code memberCount() - 1}
   * @return 0 or the unit width; 0 for the last member
   */
  public int addedBytes(int member) {
    return added[member];
  }

  /** Returns the set's base path. */
  @Override
  public Path path() {
    return base;
  }

  @Override
  public long size() throws IOException {
    return start[last] + Math.max(0, sources[last].size() - bom[last]);
  }

  @Override
  public int read(long position, ByteBuffer dst) throws IOException {
    if (position < 0) {
      throw new IllegalArgumentException("Negative position");
    }
    if (!dst.hasRemaining()) {
      return 0;
    }
    int i = memberAt(position);
    long within = position - start[i];
    if (i == last) {
      return sources[i].read(bom[i] + within, dst);
    }
    if (within < content[i]) {
      int read = readUpTo(sources[i], bom[i] + within, dst, content[i] - within);
      if (read < 0) {
        throw new FileChangedException(sources[i].path());
      }
      return read;
    }
    int from = (int) (within - content[i]);
    int length = Math.min(dst.remaining(), added[i] - from);
    dst.put(lineFeed, from, length);
    return length;
  }

  /** Returns the last member's identity. */
  @Override
  public FileIdentity identity() {
    return sources[last].identity();
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    for (ByteSource source : sources) {
      try {
        source.close();
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /**
   * Last member whose start is at or before the position; an empty member starts where the next
   * does.
   */
  private int memberAt(long position) {
    int low = 0;
    int high = last;
    while (low < high) {
      int mid = (low + high + 1) >>> 1;
      if (start[mid] <= position) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return low;
  }

  private boolean endsWithLineFeed(int member) throws IOException {
    ByteBuffer unit = ByteBuffer.allocate(lineFeed.length);
    long at = bom[member] + content[member] - lineFeed.length;
    while (unit.hasRemaining()) {
      if (sources[member].read(at + unit.position(), unit) < 0) {
        throw new FileChangedException(sources[member].path());
      }
    }
    for (int i = 0; i < lineFeed.length; i++) {
      if (unit.get(i) != lineFeed[i]) {
        return false;
      }
    }
    return true;
  }

  private static int readUpTo(ByteSource source, long position, ByteBuffer dst, long most)
      throws IOException {
    if (dst.remaining() <= most) {
      return source.read(position, dst);
    }
    ByteBuffer slice = dst.duplicate();
    slice.limit(slice.position() + (int) most);
    int read = source.read(position, slice);
    if (read > 0) {
      dst.position(dst.position() + read);
    }
    return read;
  }

  /** A member in its own offsets, cut at {@link #memberEnd}. */
  private final class MemberView implements ByteSource {
    private final int member;

    MemberView(int member) {
      this.member = member;
    }

    @Override
    public Path path() {
      return sources[member].path();
    }

    @Override
    public long size() throws IOException {
      return Math.min(sources[member].size(), memberEnd(member));
    }

    @Override
    public int read(long position, ByteBuffer dst) throws IOException {
      long end = memberEnd(member);
      if (position >= end) {
        return -1;
      }
      return readUpTo(sources[member], position, dst, end - position);
    }

    @Override
    public FileIdentity identity() {
      return sources[member].identity();
    }

    @Override
    public void close() {
      // The stream owns the source.
    }
  }
}
