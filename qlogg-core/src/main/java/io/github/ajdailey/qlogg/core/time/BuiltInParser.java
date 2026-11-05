package io.github.ajdailey.qlogg.core.time;

/** Scanner for one {@link BuiltInFormat}; allocates nothing per line. */
final class BuiltInParser implements TimestampParser {
  private static final int NO_OFFSET = Integer.MIN_VALUE;
  private static final String MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec";
  private static final int[] SCALE = {
    1_000_000_000, 100_000_000, 10_000_000, 1_000_000, 100_000, 10_000, 1_000, 100, 10, 1
  };

  private final BuiltInFormat format;
  private final TimeContext context;
  private final LocalClock clock;
  private CharSequence text;
  private int length;
  private int pos;
  private int year;
  private int month;
  private int day;
  private int hour;
  private int minute;
  private int second;
  private int nano;
  private int digits;
  private int offset;
  private int lastDigits;

  BuiltInParser(BuiltInFormat format, TimeContext context) {
    this.format = format;
    this.context = context;
    this.clock = new LocalClock(context.zone());
  }

  @Override
  public long parse(CharSequence line) {
    text = line;
    length = Math.min(line.length(), TimeReader.PREFIX_CHARS);
    long result = NONE;
    if (format == BuiltInFormat.CLF) {
      for (int i = 0; i < length && result == NONE; i++) {
        if (text.charAt(i) == '[') {
          result = at(i + 1);
        }
      }
    } else {
      result = at(0);
      if (result == NONE && length > 0 && text.charAt(0) == '[') {
        result = at(1);
      }
    }
    text = null;
    if (result != NONE) {
      lastDigits = digits;
    }
    return result;
  }

  @Override
  public int fractionDigits() {
    return lastDigits;
  }

  private long at(int start) {
    pos = start;
    nano = 0;
    digits = 0;
    offset = NO_OFFSET;
    if (format == BuiltInFormat.EPOCH) {
      return epoch();
    }
    boolean parsed =
        switch (format) {
          case ISO -> iso();
          case YMD_SLASH -> ymd('/');
          case YMD_DOT -> ymd('.');
          case DMY_DOT -> dmyDot();
          case DMY_SLASH -> slash(true);
          case MDY_SLASH -> slash(false);
          case DMONY -> dmony();
          case SYSLOG -> syslog();
          case CLF -> clf();
          case JUL -> jul();
          case EPOCH -> false;
        };
    if (!parsed) {
      return NONE;
    }
    if (offset != NO_OFFSET) {
      return LocalClock.offset(year, month, day, hour, minute, second, nano, offset);
    }
    return clock.local(year, month, day, hour, minute, second, nano);
  }

  private boolean iso() {
    if (!ymdFields('-')) {
      return false;
    }
    if (!(is(' ') || is('T')) || !time()) {
      return false;
    }
    if (!fraction()) {
      return false;
    }
    zoneOffset(true);
    return true;
  }

  private boolean ymd(char separator) {
    if (!ymdFields(separator) || !is(' ') || !time()) {
      return false;
    }
    return fraction();
  }

  private boolean ymdFields(char separator) {
    year = number(4);
    if (year < 0 || !is(separator)) {
      return false;
    }
    month = number(2);
    if (month < 0 || !is(separator)) {
      return false;
    }
    day = number(2);
    return day >= 0;
  }

  private boolean dmyDot() {
    day = number(2);
    if (day < 0 || !is('.')) {
      return false;
    }
    month = number(2);
    if (month < 0 || !is('.')) {
      return false;
    }
    year = number(4);
    if (year < 0) {
      year = number(2);
      if (year < 0) {
        return false;
      }
      year = context.nearestYear(year, month);
    }
    if (!is(' ') || !time()) {
      return false;
    }
    return fraction();
  }

  private boolean slash(boolean dayFirst) {
    int first = number(2);
    if (first < 0 || !is('/')) {
      return false;
    }
    int middle = number(2);
    if (middle < 0 || !is('/')) {
      return false;
    }
    day = dayFirst ? first : middle;
    month = dayFirst ? middle : first;
    year = number(4);
    if (year < 0 || !is(' ') || !time()) {
      return false;
    }
    return fraction();
  }

  private boolean dmony() {
    day = upToTwo();
    if (day < 0 || !is(' ')) {
      return false;
    }
    month = monthName();
    if (month < 0 || !is(' ')) {
      return false;
    }
    year = number(4);
    if (year < 0 || !is(' ') || !time()) {
      return false;
    }
    return fraction();
  }

  private boolean syslog() {
    month = monthName();
    if (month < 0 || !is(' ')) {
      return false;
    }
    is(' ');
    day = upToTwo();
    if (day < 0 || !is(' ') || !time()) {
      return false;
    }
    year = context.yearOf(month);
    return fraction();
  }

  private boolean clf() {
    day = number(2);
    if (day < 0 || !is('/')) {
      return false;
    }
    month = monthName();
    if (month < 0 || !is('/')) {
      return false;
    }
    year = number(4);
    if (year < 0 || !is(':') || !time() || !is(' ')) {
      return false;
    }
    return fraction() && zoneOffset(false) && is(']');
  }

  private boolean jul() {
    month = monthName();
    if (month < 0 || !is(' ')) {
      return false;
    }
    day = upToTwo();
    if (day < 0 || !is(',') || !is(' ')) {
      return false;
    }
    year = number(4);
    if (year < 0 || !is(' ')) {
      return false;
    }
    hour = upToTwo();
    if (hour < 1 || hour > 12 || !is(':')) {
      return false;
    }
    minute = number(2);
    if (minute < 0 || !is(':')) {
      return false;
    }
    second = number(2);
    if (second < 0 || !is(' ') || pos + 2 > length) {
      return false;
    }
    char half = lower(text.charAt(pos));
    if ((half != 'a' && half != 'p') || lower(text.charAt(pos + 1)) != 'm') {
      return false;
    }
    pos += 2;
    hour = hour % 12 + (half == 'p' ? 12 : 0);
    return true;
  }

  private long epoch() {
    long value = 0;
    int count = 0;
    while (pos < length && digit(text.charAt(pos)) && count < 14) {
      value = value * 10 + text.charAt(pos) - '0';
      pos++;
      count++;
    }
    if (count != 10 && count != 13) {
      return NONE;
    }
    if (pos + 1 < length && text.charAt(pos) == '.' && digit(text.charAt(pos + 1))) {
      pos++;
      if (!fractionDigits(count == 13 ? 6 : 9)) {
        return NONE;
      }
    }
    if (pos < length && digit(text.charAt(pos))) {
      return NONE;
    }
    long seconds = count == 13 ? value / 1000 : value;
    long nanos = count == 13 ? value % 1000 * 1_000_000L + nano / 1000 : nano;
    if (count == 13) {
      digits += 3;
    }
    return LocalClock.nanos(seconds, nanos);
  }

  /** Reads {@code HH:mm:ss}. */
  private boolean time() {
    hour = number(2);
    if (hour < 0 || !is(':')) {
      return false;
    }
    minute = number(2);
    if (minute < 0 || !is(':')) {
      return false;
    }
    second = number(2);
    return second >= 0;
  }

  /** Reads an optional fraction; false when it has more than 9 digits. */
  private boolean fraction() {
    if (pos + 1 < length
        && (text.charAt(pos) == '.' || text.charAt(pos) == ',')
        && digit(text.charAt(pos + 1))) {
      pos++;
      return fractionDigits(9);
    }
    return true;
  }

  /**
   * Reads 1 to {@code max} digits as a fraction of a second into {@link #nano}; false when a digit
   * follows the last one allowed.
   */
  private boolean fractionDigits(int max) {
    int value = 0;
    int count = 0;
    while (pos < length && digit(text.charAt(pos))) {
      if (count == max) {
        return false;
      }
      value = value * 10 + text.charAt(pos) - '0';
      pos++;
      count++;
    }
    nano = value * SCALE[count];
    digits = count;
    return true;
  }

  /**
   * Reads {@code Z}, {@code +hh:mm} or {@code +hhmm} (also {@code -}).
   *
   * @param optional true when a missing offset leaves the time local
   * @return false only when a required offset is missing
   */
  private boolean zoneOffset(boolean optional) {
    if (optional && is('Z')) {
      offset = 0;
      return true;
    }
    int mark = pos;
    if (pos < length && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
      int sign = text.charAt(pos) == '-' ? -1 : 1;
      pos++;
      int hours = number(2);
      if (hours >= 0 && hours <= 18) {
        if (optional) {
          is(':');
        }
        int minutes = number(2);
        if (minutes >= 0 && minutes <= 59) {
          offset = sign * (hours * 3600 + minutes * 60);
          return true;
        }
      }
    }
    pos = mark;
    return optional;
  }

  /** Reads exactly {@code n} digits. */
  private int number(int n) {
    if (pos + n > length) {
      return -1;
    }
    int value = 0;
    for (int i = 0; i < n; i++) {
      char c = text.charAt(pos + i);
      if (!digit(c)) {
        return -1;
      }
      value = value * 10 + c - '0';
    }
    pos += n;
    return value;
  }

  /** Reads one or two digits. */
  private int upToTwo() {
    if (pos < length && digit(text.charAt(pos))) {
      if (pos + 1 < length && digit(text.charAt(pos + 1))) {
        return number(2);
      }
      return number(1);
    }
    return -1;
  }

  /** Reads an English three-letter month abbreviation in any case. */
  private int monthName() {
    if (pos + 3 > length) {
      return -1;
    }
    char a = lower(text.charAt(pos));
    char b = lower(text.charAt(pos + 1));
    char c = lower(text.charAt(pos + 2));
    for (int m = 0; m < 12; m++) {
      if (MONTHS.charAt(3 * m) == a
          && MONTHS.charAt(3 * m + 1) == b
          && MONTHS.charAt(3 * m + 2) == c) {
        pos += 3;
        return m + 1;
      }
    }
    return -1;
  }

  private boolean is(char expected) {
    if (pos < length && text.charAt(pos) == expected) {
      pos++;
      return true;
    }
    return false;
  }

  private static boolean digit(char c) {
    return c >= '0' && c <= '9';
  }

  private static char lower(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
  }
}
