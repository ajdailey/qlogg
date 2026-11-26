package io.github.ajdailey.qlogg.core.columns;

import java.util.ArrayList;
import java.util.List;

/** The built-in column formats. The id is stored per file. */
public enum ColumnFormat {
  /** Comma, semicolon or bar separated values, RFC 4180 quoting; columns from the header. */
  CSV("csv", true),
  /** Tab separated values, quoted as {@link #CSV}. */
  TSV("tsv", true),
  /** Apache common and combined access log. */
  CLF(
      "clf",
      false,
      "Host",
      "-Ident",
      "User",
      "Time",
      "Request",
      "Status",
      "Size",
      "Referer",
      "User agent"),
  /** Serilog compact JSON, one event object per line. */
  CLEF(
      "clef",
      false,
      "Time",
      "Level",
      "Message",
      "Exception",
      "Properties",
      "-Template",
      "-Event id"),
  /** log4j XML events ({@code <log4j:event>}), several lines per event. */
  LOG4J_XML(
      "log4j-xml",
      true,
      "Time",
      "Level",
      "Logger",
      "Thread",
      "Message",
      "Throwable",
      "-NDC",
      "-Location",
      "-Properties"),
  /** The document's timestamp format: the stamp, then the rest of the line. */
  TIME("time", false, "Time", "Text");

  private final String id;
  private final boolean multiLine;
  private final List<Column> columns;

  /** A name starting with {@code -} is a column hidden by default. */
  ColumnFormat(String id, boolean multiLine, String... names) {
    this.id = id;
    this.multiLine = multiLine;
    List<Column> list = new ArrayList<Column>();
    for (String name : names) {
      boolean hidden = name.startsWith("-");
      list.add(new Column(hidden ? name.substring(1) : name, hidden));
    }
    this.columns = List.copyOf(list);
  }

  /**
   * Returns the identifier stored per file.
   *
   * @return {@code csv}, {@code tsv}, {@code clf}, {@code clef}, {@code log4j-xml} or {@code time}
   */
  public String id() {
    return id;
  }

  /**
   * Reports whether a record may span lines: a CSV or TSV quoted field holding a line feed, or a
   * log4j event.
   *
   * @return true for {@link #CSV}, {@link #TSV} and {@link #LOG4J_XML}
   */
  public boolean multiLine() {
    return multiLine;
  }

  /**
   * Returns the fixed columns.
   *
   * @return the columns in order; empty for {@link #CSV} and {@link #TSV}, whose columns come from
   *     the file
   */
  public List<Column> columns() {
    return columns;
  }

  /**
   * Finds a format by its id.
   *
   * @param id the id
   * @return the format, or null when none has that id
   */
  public static ColumnFormat byId(String id) {
    for (ColumnFormat format : values()) {
      if (format.id.equals(id)) {
        return format;
      }
    }
    return null;
  }
}
