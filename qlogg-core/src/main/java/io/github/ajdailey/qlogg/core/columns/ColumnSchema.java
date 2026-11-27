package io.github.ajdailey.qlogg.core.columns;

import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * How one file splits into columns: the format, its columns and, for CSV and TSV, the delimiter and
 * whether the first record is the header.
 *
 * @param format the format
 * @param columns 1 to {@link #MAX_FIELDS} columns
 * @param delimiter the field delimiter for {@link ColumnFormat#CSV} and {@link ColumnFormat#TSV},
 *     else 0
 * @param header true when the file's first record names the columns rather than holding values
 * @param timeFormat the stamp format for {@link ColumnFormat#TIME}, else null
 */
public record ColumnSchema(
    ColumnFormat format,
    List<Column> columns,
    char delimiter,
    boolean header,
    TimestampFormat timeFormat) {
  /** Most columns of a record; more CSV fields join the last column. */
  public static final int MAX_FIELDS = 1024;

  /** Longest column name; a longer header field is cut. */
  public static final int MAX_NAME_CHARS = 256;

  /** Checks the columns and the format's settings and copies the list. */
  public ColumnSchema {
    if (columns.isEmpty() || columns.size() > MAX_FIELDS) {
      throw new IllegalArgumentException("1 to " + MAX_FIELDS + " columns");
    }
    boolean delimited = format == ColumnFormat.CSV || format == ColumnFormat.TSV;
    if (delimited != (delimiter != 0)) {
      throw new IllegalArgumentException("a delimiter for CSV and TSV only");
    }
    if ((format == ColumnFormat.TIME) != (timeFormat != null)) {
      throw new IllegalArgumentException("a time format for TIME only");
    }
    columns = List.copyOf(columns);
  }

  /**
   * Returns the schema of a format with fixed columns.
   *
   * @param format {@link ColumnFormat#CLF}, {@link ColumnFormat#CLEF} or {@link
   *     ColumnFormat#LOG4J_XML}
   * @return the schema
   */
  public static ColumnSchema fixed(ColumnFormat format) {
    return new ColumnSchema(format, format.columns(), (char) 0, false, null);
  }

  /**
   * Returns the {@link ColumnFormat#TIME} schema.
   *
   * @param timeFormat the document's timestamp format
   * @return the schema
   */
  public static ColumnSchema timestamp(TimestampFormat timeFormat) {
    return new ColumnSchema(
        ColumnFormat.TIME, ColumnFormat.TIME.columns(), (char) 0, false, timeFormat);
  }

  /**
   * Returns a CSV or TSV schema.
   *
   * @param format {@link ColumnFormat#CSV} or {@link ColumnFormat#TSV}
   * @param delimiter the field delimiter
   * @param names the header fields, cut to {@link #MAX_NAME_CHARS}; with {@code header} false only
   *     their count matters and the columns are numbered from 1
   * @param header true when the first record is the header
   * @return the schema
   */
  public static ColumnSchema delimited(
      ColumnFormat format, char delimiter, List<String> names, boolean header) {
    int count = Math.min(names.size(), MAX_FIELDS);
    List<Column> columns = new ArrayList<Column>(count);
    for (int i = 0; i < count; i++) {
      String name = header ? names.get(i) : Integer.toString(i + 1);
      if (name.length() > MAX_NAME_CHARS) {
        name = name.substring(0, MAX_NAME_CHARS);
      }
      columns.add(new Column(name, false));
    }
    return new ColumnSchema(format, columns, delimiter, header, null);
  }

  /**
   * Returns the index of the Time column.
   *
   * @return the index, or -1 for CSV and TSV
   */
  public int timeColumn() {
    return switch (format) {
      case CSV, TSV -> -1;
      case CLF -> 3;
      case CLEF, LOG4J_XML, TIME -> 0;
    };
  }

  /**
   * Finds a column by name, ignoring ASCII case, or by its 1-based number when the name is all
   * ASCII digits.
   *
   * @param name a column name or number
   * @return the 0-based index, or -1 when no column matches
   */
  public int columnIndex(String name) {
    if (!name.isEmpty() && digits(name)) {
      int number = name.length() > 4 ? -1 : Integer.parseInt(name);
      return number >= 1 && number <= columns.size() ? number - 1 : -1;
    }
    for (int i = 0; i < columns.size(); i++) {
      if (asciiEqualsIgnoreCase(columns.get(i).name(), name)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns the text whose presence in a record can make a {@link #verbatim} column's value differ
   * from the record's own chars.
   *
   * @return a quote for CSV and TSV, a backslash for CLEF, {@code &} and {@code <![CDATA[} for
   *     log4j XML; empty for CLF and Timestamp
   */
  public List<String> escapes() {
    return switch (format) {
      case CSV, TSV -> List.of("\"");
      case CLEF -> List.of("\\");
      case LOG4J_XML -> List.of("&", "<![CDATA[");
      case CLF, TIME -> List.of();
    };
  }

  /**
   * Reports whether a column's value is empty or a run of the record's own chars whenever the
   * record holds none of {@link #escapes}. Not so for a value composed from parts or defaulted: the
   * CLEF Level, Message and Properties, the log4j Location and Properties.
   *
   * @param column 0 to the column count - 1
   * @return true when a literal absent from such a record is absent from the value
   */
  public boolean verbatim(int column) {
    return switch (format) {
      case CSV, TSV, CLF, TIME -> true;
      case CLEF -> column == 0 || column == 3 || column >= 5;
      case LOG4J_XML -> column <= 6;
    };
  }

  private static boolean asciiEqualsIgnoreCase(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    for (int i = 0; i < a.length(); i++) {
      if (lower(a.charAt(i)) != lower(b.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static char lower(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
  }

  private static boolean digits(String name) {
    for (int i = 0; i < name.length(); i++) {
      if (name.charAt(i) < '0' || name.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * Creates a parser for one thread.
   *
   * @param context the zone and file date for the Time column
   * @return a new parser
   */
  public FieldParser parser(TimeContext context) {
    return switch (format) {
      case CSV, TSV -> new DelimitedParser(delimiter, columns.size());
      case CLF -> new ClfParser(context);
      case CLEF -> new ClefParser(context);
      case LOG4J_XML -> new Log4jXmlParser();
      case TIME -> new StampParser(timeFormat.parser(context));
    };
  }
}
