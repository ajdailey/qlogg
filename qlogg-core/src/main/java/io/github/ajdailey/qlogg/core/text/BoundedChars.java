package io.github.ajdailey.qlogg.core.text;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import java.util.regex.Matcher;

/**
 * A line's chars that throw {@link LimitReached} once {@link #charAt} has been called more times
 * than a limit, so a backtracking regex run over it ends instead of stalling its thread. A regex
 * reads the subject through {@code charAt}, so the count bounds the regex's steps. Built with a
 * token, {@code charAt} also checks it every {@link #CHECK_CHARS} reads. Not thread-safe; reuse one
 * instance per thread with {@link #reset}.
 */
public final class BoundedChars implements CharSequence {
  /** Reads allowed beyond {@code 8 * length} by {@link #lineLimit}. */
  public static final int BASE_READS = 1 << 16;

  /** Reads between checks of the token. */
  public static final int CHECK_CHARS = 4096;

  private final CancellationToken token;
  private CharSequence text = "";
  private long remaining;
  private long used;
  private int sinceCheck;

  /** Creates chars that check no token. */
  public BoundedChars() {
    this(null);
  }

  /**
   * Creates chars that check a token as a regex reads them.
   *
   * @param token checked every {@link #CHECK_CHARS} reads, or null for none
   */
  public BoundedChars(CancellationToken token) {
    this.token = token;
  }

  /** Thrown by {@link #charAt} past the limit; carries no stack trace. */
  public static final class LimitReached extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LimitReached() {
      super(null, null, false, false);
    }
  }

  /**
   * Returns the read limit for one regex run over a line: {@code 8 * length + 65,536}.
   *
   * @param line the line
   * @return reads
   */
  public static long lineLimit(CharSequence line) {
    return 8L * line.length() + BASE_READS;
  }

  /**
   * Runs a user regex with {@code find} over a line, at most {@link #lineLimit} reads. Reaching the
   * limit or overflowing the regex engine's stack counts as no match. The matcher reads this
   * instance until its next reset.
   *
   * @param matcher the matcher, reset to these chars
   * @param line the line
   * @return true when the regex finds a match within the limit
   * @throws java.util.concurrent.CancellationException when the token is canceled
   */
  public boolean find(Matcher matcher, CharSequence line) {
    return find(matcher, line, false);
  }

  /**
   * Runs a user regex with {@code find} over a line, at most {@link #lineLimit} reads, and returns
   * {@code unfinished} when the run reaches the limit or overflows the regex engine's stack. The
   * matcher reads this instance until its next reset.
   *
   * @param matcher the matcher, reset to these chars
   * @param line the line
   * @param unfinished the result of a run that reaches the limit or overflows the stack
   * @return true when the regex finds a match within the limit
   * @throws java.util.concurrent.CancellationException when the token is canceled
   */
  public boolean find(Matcher matcher, CharSequence line, boolean unfinished) {
    matcher.reset(reset(line, lineLimit(line)));
    try {
      return matcher.find();
    } catch (LimitReached | StackOverflowError e) {
      return unfinished;
    }
  }

  /**
   * Sets the chars and the limit, and zeroes {@link #used}.
   *
   * @param line the chars read
   * @param limit reads allowed before {@link LimitReached}
   * @return this
   */
  public BoundedChars reset(CharSequence line, long limit) {
    text = line;
    remaining = limit;
    used = 0;
    return this;
  }

  /**
   * Returns the reads since the last {@link #reset}, the throwing read excluded.
   *
   * @return reads
   */
  public long used() {
    return used;
  }

  @Override
  public int length() {
    return text.length();
  }

  @Override
  public char charAt(int index) {
    if (--remaining < 0) {
      throw new LimitReached();
    }
    used++;
    if (token != null && ++sinceCheck >= CHECK_CHARS) {
      sinceCheck = 0;
      token.check();
    }
    return text.charAt(index);
  }

  @Override
  public CharSequence subSequence(int from, int to) {
    return text.subSequence(from, to).toString();
  }

  @Override
  public String toString() {
    return text.toString();
  }
}
