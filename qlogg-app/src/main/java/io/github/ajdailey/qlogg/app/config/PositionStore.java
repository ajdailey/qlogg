package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.io.PathCase;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The remembered position, encoding override, timestamp and column choices per file, in {@code
 * positions.conf}: {@code pos.N.path}, {@code .top}, {@code .line}, {@code .column}, {@code
 * .encoding}, {@code .timeFormat}, {@code .zone}, {@code .columns} and {@code .header}, most
 * recently used first. Keys are canonical paths; {@code pos.N.path} passes through {@link
 * InstallPaths}, and a load that finds a value the next save writes differently marks the store
 * changed, so the first save rewrites it. Where file names ignore case ({@link PathCase}), a path
 * with no entry in its own spelling reads and updates the most recently used entry in another case,
 * which keeps its key; a new entry takes the path's spelling. At most {@link #MAX_ENTRIES}; the
 * least recently used goes first. Thread-safe: {@link #get}, {@link #put} and the setters touch
 * memory only under a lock held for no I/O, so the UI thread may call them; {@link #load} and
 * {@link #save} block and run on workers.
 */
public final class PositionStore {
  /** The file name in the settings directory. */
  public static final String FILE = "positions.conf";

  /** Most files remembered. */
  public static final int MAX_ENTRIES = 500;

  /** Largest file read. */
  static final int MAX_BYTES = 1 << 20;

  /** The {@code .columns} value of a file the user shows as text. */
  public static final String NO_COLUMNS = "none";

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
   * @param columns the column format id the user chose, {@link #NO_COLUMNS} for text, or empty for
   *     detection
   * @param header {@code 1} or {@code 0} when the user chose whether a CSV or TSV file's first line
   *     is its header, or empty for the guess
   */
  public record Entry(
      long top,
      long line,
      long column,
      String encoding,
      String timeFormat,
      String zone,
      String columns,
      String header) {
    /** No position and no override. */
    public static final Entry NONE = new Entry(0, 0, 0, "");

    /** Checks the components. */
    public Entry {
      if (top < 0
          || line < 0
          || column < 0
          || encoding == null
          || timeFormat == null
          || zone == null
          || columns == null
          || header == null) {
        throw new IllegalArgumentException("negative position or null text");
      }
    }

    /**
     * Creates an entry without column choices.
     *
     * @param top the first visible line, 0-based
     * @param line the caret's line, 0-based
     * @param column the caret's column
     * @param encoding the forced charset's name, or empty for detection
     * @param timeFormat the timestamp format id, {@link TimeSettings#NO_FORMAT}, or empty
     * @param zone the time zone id, or empty
     */
    public Entry(
        long top, long line, long column, String encoding, String timeFormat, String zone) {
      this(top, line, column, encoding, timeFormat, zone, "", "");
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
  private final InstallPaths paths;
  private final PathCase names;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>(16, 0.75f, true);
  private boolean dirty;

  /**
   * Creates an empty store with this system's file name case rule.
   *
   * @param location the settings directory
   */
  public PositionStore(SettingsLocation location) {
    this(location, PathCase.of(OsTraits.current()));
  }

  /**
   * Creates an empty store.
   *
   * @param location the settings directory
   * @param names the case rule paths match entries by
   */
  public PositionStore(SettingsLocation location, PathCase names) {
    this.file = new SettingsFile(location, FILE, MAX_BYTES);
    this.paths = new InstallPaths(location);
    this.names = names;
  }

  /** Reads the file, replacing the entries in memory. Blocking. */
  public void load() {
    Properties properties = file.load();
    List<String> keys = new ArrayList<String>();
    List<Entry> read = new ArrayList<Entry>();
    boolean stale = false;
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
                properties.getProperty(PREFIX + i + ".zone", "").strip(),
                properties.getProperty(PREFIX + i + ".columns", "").strip(),
                header(properties.getProperty(PREFIX + i + ".header", "").strip())));
        keys.add(paths.read(path));
        stale = stale || paths.stale(path);
      } catch (IllegalArgumentException e) {
        // A malformed entry is dropped; the others stay.
      }
    }
    boolean rewrite = stale && file.writable();
    lock.lock();
    try {
      entries.clear();
      for (int i = keys.size() - 1; i >= 0; i--) {
        entries.put(keys.get(i), read.get(i));
      }
      dirty = rewrite;
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

  /**
   * Returns the entries as properties, most recently used first, or null when none changed. The
   * paths convert after the lock is released, because {@link InstallPaths#write} reads the disk.
   */
  private Properties snapshot() {
    List<Map.Entry<String, Entry>> list;
    lock.lock();
    try {
      if (!dirty) {
        return null;
      }
      dirty = false;
      list = new ArrayList<Map.Entry<String, Entry>>();
      for (Map.Entry<String, Entry> item : entries.entrySet()) {
        list.add(Map.entry(item.getKey(), item.getValue()));
      }
    } finally {
      lock.unlock();
    }
    Properties properties = new Properties();
    int n = 0;
    for (int i = list.size() - 1; i >= 0; i--) {
      Entry entry = list.get(i).getValue();
      String key = PREFIX + n++;
      properties.setProperty(key + ".path", paths.write(list.get(i).getKey()));
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
      if (!entry.columns().isEmpty()) {
        properties.setProperty(key + ".columns", entry.columns());
      }
      if (!entry.header().isEmpty()) {
        properties.setProperty(key + ".header", entry.header());
      }
    }
    return properties;
  }

  /** Keeps {@code 0} and {@code 1}; any other header value reads as no choice. */
  private static String header(String value) {
    return value.equals("0") || value.equals("1") ? value : "";
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
      Entry entry = entries.get(key(path));
      return entry == null ? Entry.NONE : entry;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Records a file's position, keeping its encoding override and timestamp and column choices.
   *
   * @param path the canonical path
   * @param top the first visible line
   * @param line the caret's line
   * @param column the caret's column
   */
  public void put(Path path, long top, long line, long column) {
    lock.lock();
    try {
      Entry old = entries.get(key(path));
      if (old == null) {
        old = Entry.NONE;
      }
      store(
          path,
          new Entry(
              top,
              line,
              column,
              old.encoding(),
              old.timeFormat(),
              old.zone(),
              old.columns(),
              old.header()));
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
      Entry old = entries.get(key(path));
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
              old.zone(),
              old.columns(),
              old.header()));
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
      Entry old = entries.get(key(path));
      if (old == null) {
        old = Entry.NONE;
      }
      store(
          path,
          new Entry(
              old.top(),
              old.line(),
              old.column(),
              old.encoding(),
              formatId,
              old.zone(),
              old.columns(),
              old.header()));
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
      Entry old = entries.get(key(path));
      if (old == null) {
        old = Entry.NONE;
      }
      store(
          path,
          new Entry(
              old.top(),
              old.line(),
              old.column(),
              old.encoding(),
              old.timeFormat(),
              zoneId,
              old.columns(),
              old.header()));
    } finally {
      lock.unlock();
    }
  }

  // Columns.

  /**
   * Records the column format the user chose for a file, keeping the rest of its entry.
   *
   * @param path the canonical path
   * @param formatId a column format id or {@link #NO_COLUMNS}
   */
  public void setColumns(Path path, String formatId) {
    lock.lock();
    try {
      Entry old = entries.get(key(path));
      if (old == null) {
        old = Entry.NONE;
      }
      store(
          path,
          new Entry(
              old.top(),
              old.line(),
              old.column(),
              old.encoding(),
              old.timeFormat(),
              old.zone(),
              formatId,
              old.header()));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Records whether the user made a CSV or TSV file's first line its header, keeping the rest of
   * its entry.
   *
   * @param path the canonical path
   * @param header true for a header line
   */
  public void setHeader(Path path, boolean header) {
    lock.lock();
    try {
      Entry old = entries.get(key(path));
      if (old == null) {
        old = Entry.NONE;
      }
      store(
          path,
          new Entry(
              old.top(),
              old.line(),
              old.column(),
              old.encoding(),
              old.timeFormat(),
              old.zone(),
              old.columns(),
              header ? "1" : "0"));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns a path's key: its own spelling when stored, else the most recently used entry that
   * matches it apart from case where names ignore case, else its own spelling. Call under the lock.
   */
  private String key(Path path) {
    String own = path.toString();
    if (!names.ignoreCase() || entries.containsKey(own)) {
      return own;
    }
    String match = own;
    for (String stored : entries.keySet()) {
      if (names.same(stored, own)) {
        match = stored;
      }
    }
    return match;
  }

  private void store(Path path, Entry entry) {
    String key = key(path);
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
