package io.github.ajdailey.qlogg.core.model;

/**
 * A position in a document.
 *
 * @param line 0-based line
 * @param column 0-based display column, tabs expanded to {@link LineReader#TAB_WIDTH}
 */
public record TextPosition(long line, long column) implements Comparable<TextPosition> {
  @Override
  public int compareTo(TextPosition other) {
    int byLine = Long.compare(line, other.line);
    return byLine != 0 ? byLine : Long.compare(column, other.column);
  }
}
