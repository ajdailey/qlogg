package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Loads and saves one {@link Session} per main window in {@code qlogg_session.conf}: {@code
 * window.W.active} and {@code window.W.tab.N.path}, {@code .name}, {@code .follow}, {@code .marks}
 * {@code .timeOffset} (nanoseconds, written when not 0; a malformed value reads as 0) and {@code
 * .syncGroup} (time sync group from 1, written when in one; a malformed value reads as 0), windows
 * numbered from 0 without gaps. Marks are ranges {@code first-last} or single lines,
 * comma-separated, 0-based. A session keeps at most {@link #MAX_WINDOWS} windows, {@link #MAX_TABS}
 * tabs over all windows and {@link #MAX_MARK_RANGES} mark ranges over all tabs; marks past that are
 * not saved. Each mark comment is {@code window.W.tab.N.comment.K}, its 0-based line, a space and
 * the text, numbered from 0 without gaps; at most {@link #MAX_COMMENTS} over all tabs are kept.
 * {@code droppedComments}, written when not 0, sums the windows' {@link Session#droppedComments}
 * and loads on the first window; a missing, malformed or negative value reads as 0. {@code
 * window.W.onTop} is written when true. Blocking; workers call it.
 */
public final class SessionStore {
  /** The file name in the settings directory. */
  public static final String FILE = "qlogg_session.conf";

  /** Most tabs kept over all windows. */
  public static final int MAX_TABS = 256;

  /** Most windows kept. */
  public static final int MAX_WINDOWS = 32;

  /** Most mark ranges kept over all tabs. */
  public static final int MAX_MARK_RANGES = 100_000;

  /** Most mark comments kept over all tabs. */
  public static final int MAX_COMMENTS = MarkComments.MAX_COMMENTS;

  /** Largest file read. */
  static final int MAX_BYTES = 4 << 20;

  private static final String WINDOW = "window.";
  private static final String COMMENT = "comment.";
  private static final String DROPPED_COMMENTS = "droppedComments";
  private static final int BATCH = 4096;

  private final SettingsFile file;

  /**
   * Creates the store.
   *
   * @param location the settings directory
   */
  public SessionStore(SettingsLocation location) {
    this.file = new SettingsFile(location, FILE, MAX_BYTES);
  }

  /**
   * Reads the first window's session. Blocking.
   *
   * @return the session; empty when the file is missing or malformed
   */
  public Session load() {
    List<Session> windows = loadWindows();
    return windows.isEmpty() ? Session.EMPTY : windows.get(0);
  }

  /**
   * Reads every window's session. Blocking.
   *
   * @return one session per window, in order; empty when the file is missing or malformed
   */
  public List<Session> loadWindows() {
    Properties p = file.load();
    List<Session> windows = new ArrayList<Session>();
    int count = 0;
    int ranges = 0;
    int comments = 0;
    for (int w = 0; w < MAX_WINDOWS; w++) {
      String prefix = WINDOW + w + ".";
      if (p.getProperty(prefix + "active") == null) {
        break;
      }
      List<Session.Tab> tabs = new ArrayList<Session.Tab>();
      for (int i = 0; count < MAX_TABS; i++) {
        String key = prefix + "tab." + i + ".";
        String path = p.getProperty(key + "path");
        if (path == null) {
          break;
        }
        long[] marks = parseMarks(p.getProperty(key + "marks", ""), MAX_MARK_RANGES - ranges);
        ranges += marks.length / 2;
        List<MarkComments.Comment> tabComments = parseComments(p, key, MAX_COMMENTS - comments);
        comments += tabComments.size();
        count++;
        tabs.add(
            new Session.Tab(
                path,
                p.getProperty(key + "name", ""),
                "true".equals(p.getProperty(key + "follow", "false").strip()),
                marks,
                tabComments,
                offset(p.getProperty(key + "timeOffset", "0")),
                syncGroup(p.getProperty(key + "syncGroup", "0"))));
      }
      int active = -1;
      try {
        active = Integer.parseInt(p.getProperty(prefix + "active", "-1").strip());
      } catch (NumberFormatException e) {
        // The first tab shows.
      }
      windows.add(
          new Session(
              tabs,
              active,
              windows.isEmpty() ? dropped(p) : 0,
              // Always on Top.
              "true".equals(p.getProperty(prefix + "onTop", "false").strip())));
    }
    return windows;
  }

  /**
   * Writes one window's session atomically. Blocking.
   *
   * @param session the session
   * @return false when the write failed, saving is disabled, or the file would be over the 4 MiB a
   *     load reads; the last saved file then stays
   */
  public boolean save(Session session) {
    return saveWindows(List.of(session));
  }

  /**
   * Writes every window's session atomically. Blocking.
   *
   * @param windows one session per window, in order
   * @return false when the write failed, saving is disabled, or the file would be over the 4 MiB a
   *     load reads; the last saved file then stays
   */
  public boolean saveWindows(List<Session> windows) {
    Properties p = new Properties();
    int count = 0;
    int comments = 0;
    long dropped = 0;
    for (int w = 0; w < Math.min(MAX_WINDOWS, windows.size()); w++) {
      Session session = windows.get(w);
      dropped += session.droppedComments();
      String prefix = WINDOW + w + ".";
      int tabs = Math.min(MAX_TABS - count, session.tabs().size());
      p.setProperty(
          prefix + "active", Integer.toString(session.active() < tabs ? session.active() : -1));
      // Always on Top.
      if (session.onTop()) {
        p.setProperty(prefix + "onTop", "true");
      }
      for (int i = 0; i < tabs; i++) {
        Session.Tab tab = session.tabs().get(i);
        String key = prefix + "tab." + i + ".";
        p.setProperty(key + "path", tab.path());
        if (!tab.name().isEmpty()) {
          p.setProperty(key + "name", tab.name());
        }
        p.setProperty(key + "follow", Boolean.toString(tab.follow()));
        if (tab.timeOffset() != 0) {
          p.setProperty(key + "timeOffset", Long.toString(tab.timeOffset()));
        }
        if (tab.syncGroup() > 0) {
          p.setProperty(key + "syncGroup", Integer.toString(tab.syncGroup()));
        }
        if (tab.marks().length > 0) {
          p.setProperty(key + "marks", formatMarks(tab.marks()));
        }
        for (int k = 0; k < tab.comments().size() && comments < MAX_COMMENTS; k++) {
          MarkComments.Comment comment = tab.comments().get(k);
          p.setProperty(key + COMMENT + k, comment.line() + " " + comment.text());
          comments++;
        }
      }
      count += tabs;
    }
    if (dropped > 0) {
      p.setProperty(DROPPED_COMMENTS, Long.toString(dropped));
    }
    return file.save(p);
  }

  private static long dropped(Properties p) {
    try {
      return Math.max(0, Long.parseLong(p.getProperty(DROPPED_COMMENTS, "0").strip()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Reads a time offset; a malformed value reads as none. */
  private static int syncGroup(String value) {
    try {
      return Math.max(0, Integer.parseInt(value.strip()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static long offset(String value) {
    try {
      return Long.parseLong(value.strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Returns the marked lines as ranges.
   *
   * @param marks the marks
   * @param maxRanges most ranges returned; later marks are left out
   * @return pairs of first and last line
   */
  public static long[] ranges(MutableLineSet marks, int maxRanges) {
    long[] batch = new long[BATCH];
    long[] out = new long[16];
    int n = 0;
    long next = 0;
    while (n / 2 < maxRanges) {
      int count = marks.fill(next, batch);
      for (int i = 0; i < count; i++) {
        long line = batch[i];
        if (n > 0 && out[n - 1] == line - 1) {
          out[n - 1] = line;
        } else if (n / 2 < maxRanges) {
          if (n + 2 > out.length) {
            out = Arrays.copyOf(out, out.length * 2);
          }
          out[n++] = line;
          out[n++] = line;
        }
      }
      if (count < batch.length) {
        break;
      }
      next = batch[count - 1] + 1;
    }
    return Arrays.copyOf(out, n);
  }

  /**
   * Adds marked ranges to a set.
   *
   * @param ranges pairs of first and last line, ascending
   * @param into the set
   * @return false when the set's budget refused part of them
   */
  public static boolean addRanges(long[] ranges, MutableLineSet into) {
    long[] batch = new long[BATCH];
    int count = 0;
    for (int i = 0; i + 1 < ranges.length; i += 2) {
      for (long line = ranges[i]; line <= ranges[i + 1]; line++) {
        batch[count++] = line;
        if (count == batch.length) {
          if (!into.addSorted(batch, count)) {
            return false;
          }
          count = 0;
        }
      }
    }
    return count == 0 || into.addSorted(batch, count);
  }

  /**
   * Reads a tab's mark comments; an entry without a line number and text is skipped.
   *
   * @param p the file
   * @param key the tab's key prefix
   * @param max most comments read
   * @return the comments
   */
  static List<MarkComments.Comment> parseComments(Properties p, String key, int max) {
    List<MarkComments.Comment> out = new ArrayList<MarkComments.Comment>();
    for (int k = 0; out.size() < max; k++) {
      String value = p.getProperty(key + COMMENT + k);
      if (value == null) {
        break;
      }
      int space = value.indexOf(' ');
      if (space <= 0) {
        continue;
      }
      String text = MarkComments.clean(value.substring(space + 1));
      try {
        long line = Long.parseLong(value.substring(0, space));
        if (line >= 0 && !text.isEmpty()) {
          out.add(new MarkComments.Comment(line, text));
        }
      } catch (NumberFormatException e) {
        // Skipped.
      }
    }
    return out;
  }

  static String formatMarks(long[] ranges) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i + 1 < ranges.length; i += 2) {
      if (text.length() > 0) {
        text.append(',');
      }
      text.append(ranges[i]);
      if (ranges[i + 1] != ranges[i]) {
        text.append('-').append(ranges[i + 1]);
      }
    }
    return text.toString();
  }

  /** Returns ascending, non-overlapping ranges; a malformed or unordered item ends the list. */
  static long[] parseMarks(String text, int maxRanges) {
    if (text.isBlank() || maxRanges <= 0) {
      return new long[0];
    }
    String[] items = text.split(",");
    long[] out = new long[2 * Math.min(items.length, maxRanges)];
    int n = 0;
    long last = -1;
    for (String item : items) {
      if (n == out.length) {
        break;
      }
      int dash = item.indexOf('-');
      long first;
      long end;
      try {
        first = Long.parseLong(dash < 0 ? item.strip() : item.substring(0, dash).strip());
        end = dash < 0 ? first : Long.parseLong(item.substring(dash + 1).strip());
      } catch (NumberFormatException e) {
        break;
      }
      if (first <= last || end < first) {
        break;
      }
      out[n++] = first;
      out[n++] = end;
      last = end;
    }
    return Arrays.copyOf(out, n);
  }
}
