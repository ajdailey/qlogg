package io.github.ajdailey.qlogg.core.time;

/** A timestamp layout: a built-in scanner or a user-defined regex and formatter. */
public sealed interface TimestampFormat permits BuiltInFormat, UserFormat {
  /**
   * Returns the identifier stored per file.
   *
   * @return a built-in id such as {@code iso}, or {@code user:} and the user format's name
   */
  String id();

  /**
   * Creates a parser for one thread.
   *
   * @param context the zone and file date for local and year-less times
   * @return a new parser
   */
  TimestampParser parser(TimeContext context);
}
