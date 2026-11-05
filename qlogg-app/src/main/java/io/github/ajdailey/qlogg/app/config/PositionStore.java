package io.github.ajdailey.qlogg.app.config;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The remembered position, encoding override and timestamp choices per file, in {@code
 * positions.conf}: {@code pos.N.path}, {@code .top}, {@code .line}, {@code .column}, {@code
 * .encoding}, {@code .timeFormat} and {@code .zone}, most recently used first. Keys are canonical
 * paths. At most {@link #MAX_ENTRIES}; the least recently used goes first. Thread-safe: {@link
 * #get}, {@link #put} and the setters touch memory only under a lock held for no I/O, so the UI
 * thread may call them; {@link #load} and {@link #save} block and run on workers.
 */
public final class PositionStore {
  /** The file name in the settings directory. */
  public static final String FILE = "positions.conf";

  /** Most files remembered. */
  public static final int MAX_ENTRIES = 500;

  /** Largest file read. */
  static final int MAX_BYTES = 1 << 20;

  private static final String PREFIX = "pos.";

  /**
   * One file's entry.
   *
   * @param top the first visible line, 0-based
   * @param line the caret's line, 0-based
   * @param column the caret's column
   * @param encoding the forced charset's name, or empty for detection
   * @param timeFormat the timestamp format id the user chose, {@link TimeSettings#NO_FORMAT}, or
   *     empty for detection
   * @param zone the time zone id the user chose, or empty for {@code time.zone}
   */
  public record Entry(
      long top, long line, long column, String encoding, String timeFormat, String zone) {
    /** No position and no override. */
    public static final Entry NONE = new Entry(0, 0, 0, "");

    /** Checks the components. */
    public Entry {
      if (top < 0
          || line < 0
          || column < 0
          || encoding == null
          || timeFormat == null
          || zone == null) {
        throw new IllegalArgumentException("negative position or null text");
      }
    }

    /**
     * Creates an entry without timestamp choices.
     *
     * @param top the first visible line, 0-based
     * @param line the caret's line, 0-based
     * @param column the caret's column
     * @param encoding the forced charset's name, or empty for detection
     */
    public Entry(long top, long line, long column, String encoding) {
      this(top, line, column, encoding, "", "");
    }

    /**
     * Returns the forced charset.
     *
     * @return the charset, or null for detection or an unsupported name
     */
    public Charset charset() {
      return encoding.isEmpty() ? null : Options.charset(encoding);
    }
  }

  private final SettingsFile file;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<String, Entry> entries =
      new LinkedHashMap<String, Entry>(16, 0.75f, true);
  private boolean dirty;

  /**
   * Creates an empty store.
   *
   * @param location the settings directory
   */
  public PositionStore(SettingsLocation location) {
    this.file = new SettingsFile(location, FILE, MAX_BYTES);
  }

  /** Reads the file, replacing the entries in memory. Blocking. */
  public void load() {
    Properties properties = file.load();
    List<String> paths = new ArrayList<String>();
    List<Entry> read = new ArrayList<Entry>();
    for (int i = 0; i < MAX_ENTRIES; i++) {
      String path = properties.getProperty(PREFIX + i + ".path");
      if (path == null) {
        break;
      }
      try {
        read.add(
            new Entry(
                Long.parseLong(properties.getProperty(PREFIX + i + ".top", "0").strip()),
                Long.parseLong(properties.getProperty(PREFIX + i + ".line", "0").strip()),
                Long.parseLong(properties.getProperty(PREFIX + i + ".column", "0").strip()),
                properties.getProperty(PREFIX + i + ".encoding", "").strip(),
                properties.getProperty(PREFIX + i + ".timeFormat", "").strip(),
                properties.getProperty(PREFIX + i + ".zone", "").strip()));
        paths.add(path);
      } catch (IllegalArgumentException e) {
        // A malformed entry is dropped; the others stay.
      }
    }
    lock.lock();
    try {
      entries.clear();
      for (int i = paths.size() - 1; i >= 0; i--) {
        entries.put(paths.get(i), read.get(i));
      }
      dirty = false;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Writes the file when an entry changed since the last load or save. Blocking.
   *
   * @return false when nothing changed or the write failed
   */
  public boolean save() {
    Properties properties = snapshot();
    if (properties == null) {
      return false;
    }
    boolean saved = file.save(properties);
    if (!saved) {
      markDirty();
    }
    return saved;
  }

  /** Returns the entries as properties, most recently used first, or null when none changed. */
  private Properties snapshot() {
    lock.lock();
    try {
      if (!dirty) {
        return null;
      }
      dirty = false;
      Properties properties = new Properties();
      List<Map.Entry<String, Entry>> list =
          new ArrayList<Map.Entry<String, Entry>>(entries.entrySet());
      int n = 0;
      for (int i = list.size() - 1; i >= 0; i--) {
        Entry entry = list.get(i).getValue();
        String key = PREFIX + n++;
        properties.setProperty(key + ".path", list.get(i).getKey());
        properties.setProperty(key + ".top", Long.toString(entry.top()));
        properties.setProperty(key + ".line", Long.toString(entry.line()));
        properties.setProperty(key + ".column", Long.toString(entry.column()));
        if (!entry.encoding().isEmpty()) {
          properties.setProperty(key + ".encoding", entry.encoding());
        }
        if (!entry.timeFormat().isEmpty()) {
          properties.setProperty(key + ".timeFormat", entry.timeFormat());
        }
        if (!entry.zone().isEmpty()) {
          properties.setProperty(key + ".zone", entry.zone());
        }
      }
      return properties;
    } finally {
      lock.unlock();
    }
  }

  private void markDirty() {
    lock.lock();
    try {
      dirty = true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns a file's entry and marks it the most recently used.
   *
   * @param path the canonical path
   * @return the entry, or {@link Entry#NONE}
   */
  public Entry get(Path path) {
    lock.lock();
    try {
      Entry entry = entries.get(path.toString());
      return entry == null ? Entry.NONE : entry;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Records a file's position, keeping its encoding override and timestamp choices.
   *
   * @param path the canonical path
   * @param top the first visible line
   * @param line the caret's line
   * @param column the caret's column
   */
  public void put(Path path, long top, long line, long column) {
    lock.lock();
    try {
      Entry old = entries.get(path.toString());
      if (old == null) {
        old = Entry.NONE;
      }
      store(path, new Entry(top, line, column, old.encoding(), old.timeFormat(), old.zone()));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Records a file's encoding override, keeping its position.
   *
   * @param path the canonical path
   * @param charset the charset, or null for detection
   */
  public void setEncoding(Path path, Charset charset) {
    lock.lock();
    try {
      Entry old = entries.get(path.toString());
      if (old == null) {
        old = Entry.NONE;
      }
      store(
          path,
          new Entry(
              old.top(),
              old.line(),
              old.column(),
              charset == null ? "" : charset.name(),
              old.timeFormat(),
              old.zone()));
    } finally {
      lock.unlock();
    }
  }

  // Timestamps.

  /**
   * Records the timestamp format the user chose for a file, keeping the rest of its entry.
   *
   * @param path the canonical path
   * @param formatId a format id or {@link TimeSettings#NO_FORMAT}
   */
  public void setTimeFormat(Path path, String formatId) {
    lock.lock();
    try {
      Entry old = entries.get(path.toString());
      if (old == null) {
        old = Entry.NONE;
      }
      store(
          path,
          new Entry(old.top(), old.line(), old.column(), old.encoding(), formatId, old.zone()));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Records the time zone the user chose for a file, keeping the rest of its entry.
   *
   * @param path the canonical path
   * @param zoneId a zone id, or empty for {@code time.zone}
   */
  public void setZone(Path path, String zoneId) {
    lock.lock();
    try {
      Entry old = entries.get(path.toString());
      if (old == null) {
        old = Entry.NONE;
      }
      store(
          path,
          new Entry(old.top(), old.line(), old.column(), old.encoding(), old.timeFormat(), zoneId));
    } finally {
      lock.unlock();
    }
  }

  private void store(Path path, Entry entry) {
    String key = path.toString();
    if (!entry.equals(entries.get(key))) {
      dirty = true;
    }
    entries.put(key, entry);
    while (entries.size() > MAX_ENTRIES) {
      entries.remove(entries.keySet().iterator().next());
      dirty = true;
    }
  }

  /**
   * Returns the number of files remembered.
   *
   * @return the count
   */
  public int size() {
    lock.lock();
    try {
      return entries.size();
    } finally {
      lock.unlock();
    }
  }
}
