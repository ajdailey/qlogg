package io.github.ajdailey.qlogg.core.index;

/**
 * Bounds and bookkeeping shared by both storage modes. Subclasses look up starts before the last
 * one; the last start is held here.
 */
abstract class IndexSnapshot implements LineIndex {
  final long firstStart;
  final long startCount;
  final long lastStart;
  final long indexedBytes;
  final long maxLineBytes;
  final long generation;
  final int unit;

  IndexSnapshot(
      long firstStart,
      long startCount,
      long lastStart,
      long indexedBytes,
      long maxLineBytes,
      long generation,
      int unit) {
    this.firstStart = firstStart;
    this.startCount = startCount;
    this.lastStart = lastStart;
    this.indexedBytes = indexedBytes;
    this.maxLineBytes = maxLineBytes;
    this.generation = generation;
    this.unit = unit;
  }

  /** Start of a line before the last start. */
  abstract long startOf(long line);

  /** Line containing an offset in {@code [firstStart, lastStart)}. */
  abstract long lineContaining(long offset);

  @Override
  public long lineCount() {
    return indexedBytes > lastStart ? startCount : startCount - 1;
  }

  @Override
  public long indexedBytes() {
    return indexedBytes;
  }

  @Override
  public boolean lastLineComplete() {
    return indexedBytes == lastStart;
  }

  @Override
  public long lineStart(long line) {
    check(line);
    return line == startCount - 1 ? lastStart : startOf(line);
  }

  @Override
  public LineStart nearestStart(long line) {
    return new LineStart(line, lineStart(line));
  }

  @Override
  public long lineEnd(long line) {
    check(line);
    if (line == startCount - 1) {
      return indexedBytes;
    }
    return (line + 1 == startCount - 1 ? lastStart : startOf(line + 1)) - unit;
  }

  @Override
  public long lineAt(long byteOffset) {
    long count = lineCount();
    if (count == 0 || byteOffset < firstStart) {
      return 0;
    }
    if (byteOffset >= lastStart) {
      return count - 1;
    }
    return lineContaining(byteOffset);
  }

  @Override
  public long maxLineBytes() {
    return Math.max(maxLineBytes, indexedBytes - lastStart);
  }

  @Override
  public long generation() {
    return generation;
  }

  private void check(long line) {
    if (line < 0 || line >= lineCount()) {
      throw new IndexOutOfBoundsException("Line " + line + " of " + lineCount());
    }
  }
}
