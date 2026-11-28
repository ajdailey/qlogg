package io.github.ajdailey.qlogg.app.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Column-view layouts in {@code qlogg.conf}, per column format id: each column's stored width and
 * visibility by column name, and the frozen cell count. Files of one format share it, so CSV files
 * with the same header names share widths. A format keeps at most {@link #MAX_NAMES} names, the
 * most recently shown first; a newer name pushes out the least recently shown. Immutable.
 *
 * @param formats per format id, its layout; formats with nothing stored are left out
 */
public record ColumnSettings(Map<String, Format> formats) {
  /** Most column names stored per format. */
  public static final int MAX_NAMES = 256;

  /** Widest stored column, in grid columns. */
  public static final int MAX_WIDTH = 4096;

  /** Nothing stored. */
  public static final ColumnSettings DEFAULTS = new ColumnSettings(Map.of());

  /** Copies the map. */
  public ColumnSettings {
    formats = Map.copyOf(formats);
  }

  /** Whether a column shows. */
  public enum Visibility {
    /** As the format gives it. */
    DEFAULT,
    /** Shown. */
    SHOWN,
    /** Hidden. */
    HIDDEN
  }

  /**
   * One column's stored settings.
   *
   * @param name the column name
   * @param width grid columns, the gap included, or 0 when the column fits its rows
   * @param visibility whether the column shows
   */
  public record Entry(String name, int width, Visibility visibility) {
    /** Checks the components. */
    public Entry {
      Objects.requireNonNull(name);
      Objects.requireNonNull(visibility);
      if (width < 0 || width > MAX_WIDTH) {
        throw new IllegalArgumentException("width out of range");
      }
    }

    /** True when the entry stores nothing. */
    boolean empty() {
      return width == 0 && visibility == Visibility.DEFAULT;
    }
  }

  /**
   * One format's stored layout.
   *
   * @param entries the columns with a stored width or visibility, most recently shown first, one
   *     per name, at most {@link #MAX_NAMES}
   * @param frozen leading shown cells that do not scroll
   */
  public record Format(List<Entry> entries, int frozen) {
    /** Nothing stored. */
    public static final Format EMPTY = new Format(List.of(), 0);

    /** Checks the components and copies the list, dropping entries past {@link #MAX_NAMES}. */
    public Format {
      if (frozen < 0) {
        throw new IllegalArgumentException("frozen is negative");
      }
      entries = List.copyOf(entries.size() > MAX_NAMES ? entries.subList(0, MAX_NAMES) : entries);
    }

    /**
     * Returns a column's entry.
     *
     * @param name the column name
     * @return the entry, or null when nothing is stored for it
     */
    public Entry entry(String name) {
      for (Entry entry : entries) {
        if (entry.name().equals(name)) {
          return entry;
        }
      }
      return null;
    }

    /**
     * Returns a copy with an entry replaced or added first; an entry that stores nothing goes.
     *
     * @param next the entry
     * @return the copy
     */
    public Format with(Entry next) {
      List<Entry> list = new ArrayList<Entry>();
      if (!next.empty()) {
        list.add(next);
      }
      for (Entry entry : entries) {
        if (!entry.name().equals(next.name())) {
          list.add(entry);
        }
      }
      return new Format(list, frozen);
    }

    /**
     * Returns a copy with another frozen count.
     *
     * @param count 0 or more
     * @return the copy
     */
    public Format withFrozen(int count) {
      return new Format(entries, count);
    }

    /**
     * Returns a copy with the named columns' entries first, in the given order: those names were
     * shown last.
     *
     * @param names the shown layout's column names
     * @return the copy; this instance when the order does not change
     */
    public Format shown(List<String> names) {
      List<Entry> list = new ArrayList<Entry>();
      for (String name : names) {
        Entry entry = entry(name);
        if (entry != null && !list.contains(entry)) {
          list.add(entry);
        }
      }
      for (Entry entry : entries) {
        if (!list.contains(entry)) {
          list.add(entry);
        }
      }
      return list.equals(entries) ? this : new Format(list, frozen);
    }

    /**
     * Returns a copy without the named columns' entries and nothing frozen.
     *
     * @param names column names
     * @return the copy
     */
    public Format without(List<String> names) {
      List<Entry> list = new ArrayList<Entry>();
      for (Entry entry : entries) {
        if (!names.contains(entry.name())) {
          list.add(entry);
        }
      }
      return new Format(list, 0);
    }
  }

  /**
   * Returns a format's layout.
   *
   * @param id the column format id
   * @return the stored layout, or {@link Format#EMPTY}
   */
  public Format format(String id) {
    Format format = formats.get(id);
    return format == null ? Format.EMPTY : format;
  }

  /**
   * Returns a copy with a format's layout replaced.
   *
   * @param id the column format id
   * @param format the layout; one that stores nothing removes the format
   * @return the copy
   */
  public ColumnSettings with(String id, Format format) {
    Map<String, Format> map = new LinkedHashMap<String, Format>(formats);
    if (format.entries().isEmpty() && format.frozen() == 0) {
      map.remove(id);
    } else {
      map.put(id, format);
    }
    return new ColumnSettings(map);
  }
}
