package io.github.ajdailey.qlogg.core.time;

/**
 * The hand-written scanners, in detection tie-break order. Each is tried at the line's first char
 * and after one leading {@code [}; {@link #CLF} is searched in the line's first {@link
 * TimeReader#PREFIX_CHARS} chars. Month names are English, any letter case. A fraction is 1-9
 * digits after {@code .} or {@code ,}.
 */
public enum BuiltInFormat implements TimestampFormat {
  /** {@code yyyy-MM-dd} then space or {@code T}, time, optional fraction and offset. */
  ISO("iso"),
  /** {@code yyyy/MM/dd HH:mm:ss}, optional fraction. */
  YMD_SLASH("ymd-slash"),
  /** {@code yyyy.MM.dd HH:mm:ss}, optional fraction. */
  YMD_DOT("ymd-dot"),
  /**
   * {@code dd.MM.yyyy HH:mm:ss} or a two-digit year ({@link TimeContext#nearestYear}), optional
   * fraction.
   */
  DMY_DOT("dmy-dot"),
  /** {@code dd/MM/yyyy HH:mm:ss}, optional fraction. */
  DMY_SLASH("dmy-slash"),
  /** {@code MM/dd/yyyy HH:mm:ss}, optional fraction. */
  MDY_SLASH("mdy-slash"),
  /** {@code dd MMM yyyy HH:mm:ss}, optional fraction. */
  DMONY("dmony"),
  /** {@code MMM d HH:mm:ss}; the year comes from {@link TimeContext#yearOf}. */
  SYSLOG("syslog"),
  /** {@code [dd/MMM/yyyy:HH:mm:ss +hhmm]} anywhere in the line's first chars. */
  CLF("clf"),
  /** {@code MMM dd, yyyy h:mm:ss a}, the {@code java.util.logging.SimpleFormatter} default. */
  JUL("jul"),
  /** 10 digits of seconds or 13 of milliseconds since 1970 UTC, optional fraction, a non-digit. */
  EPOCH("epoch");

  private final String id;

  BuiltInFormat(String id) {
    this.id = id;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public TimestampParser parser(TimeContext context) {
    return new BuiltInParser(this, context);
  }

  /**
   * Finds a built-in by its id.
   *
   * @param id the id
   * @return the format, or null when none has that id
   */
  public static BuiltInFormat byId(String id) {
    for (BuiltInFormat format : values()) {
      if (format.id.equals(id)) {
        return format;
      }
    }
    return null;
  }
}
