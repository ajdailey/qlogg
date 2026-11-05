package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.search.LineSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The comments of a document's marks, one single-line text per marked line. Thread-safe: every
 * method holds the object's monitor. Each comment reserves {@link #ENTRY_BYTES} plus 2 bytes per
 * char from {@link MemoryShare#RESULTS}; a comment the share refuses, or one past {@link
 * #MAX_COMMENTS}, is not stored. The owner keeps comments on marked lines only: it removes a
 * comment when it removes the line's mark.
 */
public final class MarkComments {
  /** Most chars of a comment; {@link #clean} cuts longer text. */
  public static final int MAX_LENGTH = 200;

  /** Most comments per document. */
  public static final int MAX_COMMENTS = 1000;

  /** Bytes charged per comment besides its chars: map entry, boxed line and string header. */
  static final long ENTRY_BYTES = 96;

  /**
   * One comment.
   *
   * @param line the 0-based marked line
   * @param text the comment, as {@link #clean} returns it, never empty
   */
  public record Comment(long line, String text) {}

  private final TreeMap<Long, String> comments = new TreeMap<Long, String>();
  private final MemoryBudget budget;
  private long charged;
  private long version;

  /**
   * Creates an empty set of comments.
   *
   * @param budget charged for each comment
   */
  public MarkComments(MemoryBudget budget) {
    this.budget = budget;
  }

  /**
   * Returns a comment as it is stored: each control char (C0, DEL, C1, line and paragraph
   * separators) replaced by a space, outer whitespace stripped, cut to {@link #MAX_LENGTH} chars
   * without splitting a surrogate pair.
   *
   * @param text any text, or null
   * @return the text; empty for null
   */
  public static String clean(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      boolean control = c < 0x20 || (c >= 0x7f && c < 0xa0) || c == 0x2028 || c == 0x2029;
      out.append(control ? ' ' : c);
    }
    String stripped = out.toString().strip();
    if (stripped.length() <= MAX_LENGTH) {
      return stripped;
    }
    int end = MAX_LENGTH;
    if (Character.isHighSurrogate(stripped.charAt(end - 1))) {
      end--;
    }
    return stripped.substring(0, end).strip();
  }

  /**
   * Returns a line's comment.
   *
   * @param line a 0-based line
   * @return the comment, or null when the line has none
   */
  public synchronized String get(long line) {
    return comments.isEmpty() ? null : comments.get(line);
  }

  /**
   * Sets a line's comment; text that {@link #clean} makes empty removes it.
   *
   * @param line a 0-based line
   * @param text the comment
   * @return false, changing nothing, when the budget or {@link #MAX_COMMENTS} refused it
   */
  public synchronized boolean put(long line, String text) {
    String cleaned = clean(text);
    if (cleaned.isEmpty()) {
      remove(line);
      return true;
    }
    String old = comments.get(line);
    if (old == null && comments.size() >= MAX_COMMENTS) {
      return false;
    }
    long oldCost = old == null ? 0 : cost(old);
    long newCost = cost(cleaned);
    if (newCost > oldCost && !budget.reserve(MemoryShare.RESULTS, newCost - oldCost)) {
      return false;
    }
    if (newCost < oldCost) {
      budget.release(MemoryShare.RESULTS, oldCost - newCost);
    }
    charged += newCost - oldCost;
    comments.put(line, cleaned);
    version++;
    return true;
  }

  /**
   * Removes a line's comment.
   *
   * @param line a 0-based line
   * @return true when it had one
   */
  public synchronized boolean remove(long line) {
    String old = comments.remove(line);
    if (old == null) {
      return false;
    }
    release(cost(old));
    version++;
    return true;
  }

  /**
   * Removes the comments of lines.
   *
   * @param lines lines in any order
   * @param count lines used from the start of the array
   * @return comments removed
   */
  public synchronized int removeAll(long[] lines, int count) {
    if (comments.isEmpty()) {
      return 0;
    }
    int removed = 0;
    for (int i = 0; i < count; i++) {
      if (remove(lines[i])) {
        removed++;
      }
    }
    return removed;
  }

  /**
   * Removes the comments at or after a line.
   *
   * @param line any value; 0 or less removes every comment
   */
  public synchronized void removeFrom(long line) {
    Map<Long, String> tail = comments.tailMap(Math.max(0, line), true);
    if (tail.isEmpty()) {
      return;
    }
    long freed = 0;
    for (String text : tail.values()) {
      freed += cost(text);
    }
    tail.clear();
    release(freed);
    version++;
  }

  /** Removes every comment and returns the whole reservation. */
  public void clear() {
    removeFrom(0);
  }

  /**
   * Adds comments whose line is marked; a comment on an unmarked line is skipped.
   *
   * @param added the comments
   * @param marks the document's marks
   * @return false when the budget or {@link #MAX_COMMENTS} refused one
   */
  public synchronized boolean addAll(List<Comment> added, LineSet marks) {
    boolean complete = true;
    for (Comment comment : added) {
      if (marks.contains(comment.line()) && !put(comment.line(), comment.text())) {
        complete = false;
      }
    }
    return complete;
  }

  /**
   * Returns the comments at or after a line, ascending.
   *
   * @param from the first line wanted
   * @param max most comments returned
   * @return the comments
   */
  public synchronized List<Comment> list(long from, int max) {
    List<Comment> out = new ArrayList<Comment>();
    for (Map.Entry<Long, String> entry : comments.tailMap(Math.max(0, from), true).entrySet()) {
      if (out.size() >= max) {
        break;
      }
      out.add(new Comment(entry.getKey(), entry.getValue()));
    }
    return out;
  }

  /**
   * Returns how many lines have a comment.
   *
   * @return the count
   */
  public synchronized int size() {
    return comments.size();
  }

  /**
   * Returns a number that changes with every change of the comments.
   *
   * @return the version
   */
  public synchronized long version() {
    return version;
  }

  /**
   * Returns the bytes reserved for the comments.
   *
   * @return bytes
   */
  public synchronized long retainedBytes() {
    return charged;
  }

  private void release(long bytes) {
    charged -= bytes;
    budget.release(MemoryShare.RESULTS, bytes);
  }

  private static long cost(String text) {
    return ENTRY_BYTES + 2L * text.length();
  }
}
