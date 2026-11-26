package io.github.ajdailey.qlogg.core.columns;

/** A reusable view of {@code text[from, to)}, so a sub-range reaches a parser without a copy. */
final class Span implements CharSequence {
  private CharSequence text = "";
  private int from;
  private int to;

  /** Points the view at a range and returns it. */
  Span set(CharSequence chars, int start, int end) {
    text = chars;
    from = start;
    to = end;
    return this;
  }

  @Override
  public int length() {
    return to - from;
  }

  @Override
  public char charAt(int index) {
    return text.charAt(from + index);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return text.subSequence(from + start, from + end);
  }

  @Override
  public String toString() {
    return text.subSequence(from, to).toString();
  }
}
