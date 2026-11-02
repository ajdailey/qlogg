package io.github.ajdailey.qlogg.app.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Search history operations over an immutable list, most recent first. Entries are distinct; an
 * entry over {@link #MAX_ENTRY_CHARS} is not kept.
 */
public final class SearchHistory {
  /** Entries kept by default ({@code search.historySize}). */
  public static final int DEFAULT_SIZE = 50;

  /** Largest {@code search.historySize}. */
  public static final int MAX_SIZE = 1000;

  /** Longest entry kept, in chars. */
  public static final int MAX_ENTRY_CHARS = 4096;

  private SearchHistory() {}

  /**
   * Puts a search first, removing an equal earlier entry, and cuts the list to a size.
   *
   * @param history the entries
   * @param text the search text
   * @param size the most entries kept
   * @return the new list, unmodifiable; the same entries when the text is empty or too long
   */
  public static List<String> add(List<String> history, String text, int size) {
    if (text.isEmpty() || text.length() > MAX_ENTRY_CHARS) {
      return trim(history, size);
    }
    List<String> next = new ArrayList<String>(history.size() + 1);
    next.add(text);
    for (String entry : history) {
      if (!entry.equals(text)) {
        next.add(entry);
      }
    }
    return trim(next, size);
  }

  /**
   * Cuts the list to a size, dropping the oldest entries, duplicates and entries too long to keep.
   *
   * @param history the entries
   * @param size the most entries kept
   * @return the list, unmodifiable
   */
  public static List<String> trim(List<String> history, int size) {
    List<String> kept = new ArrayList<String>();
    for (String entry : history) {
      if (kept.size() >= size) {
        break;
      }
      if (!entry.isEmpty() && entry.length() <= MAX_ENTRY_CHARS && !kept.contains(entry)) {
        kept.add(entry);
      }
    }
    return Collections.unmodifiableList(kept);
  }

  /**
   * Returns the entries that start with a prefix, most recent first.
   *
   * @param history the entries
   * @param prefix the typed text
   * @param caseSensitive false compares ignoring case
   * @return the entries longer than the prefix that complete it
   */
  public static List<String> completions(
      List<String> history, String prefix, boolean caseSensitive) {
    List<String> found = new ArrayList<String>();
    if (prefix.isEmpty()) {
      return found;
    }
    for (String entry : history) {
      if (entry.length() > prefix.length()
          && entry.regionMatches(!caseSensitive, 0, prefix, 0, prefix.length())) {
        found.add(entry);
      }
    }
    return found;
  }
}
