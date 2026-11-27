package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.columns.FieldParser;
import io.github.ajdailey.qlogg.core.columns.TimeCell;

/**
 * The record a {@link CellReader} read last: its lines, raw text and column values. The Time
 * column's value is the instant as {@link TimeCell} formats it, or the stamp as written when it did
 * not parse. Every view it returns is valid until the reader's next call. Not thread-safe.
 */
public final class CellRecord {
  private final StringBuilder time = new StringBuilder(32);
  private ColumnSchema schema;
  private FieldParser parser;
  private CharSequence text = "";
  private long first;
  private long end;
  private boolean parsed;
  private boolean cut;
  private boolean header;
  private boolean timed;

  CellRecord() {}

  /** Parses a record's text and keeps its place. */
  void set(
      ColumnRead view,
      FieldParser fields,
      CharSequence chars,
      long firstLine,
      long endLine,
      boolean truncated,
      boolean headerRow) {
    schema = view.layout().schema();
    parser = fields;
    text = chars;
    first = firstLine;
    end = endLine;
    cut = truncated;
    header = headerRow;
    parsed = parser.parse(chars);
    time.setLength(0);
    timed =
        parsed
            && schema.timeColumn() >= 0
            && view.time().append(parser.nanos(), parser.fractionDigits(), time);
  }

  /**
   * Returns the format.
   *
   * @return the schema
   */
  public ColumnSchema schema() {
    return schema;
  }

  /**
   * Returns the record's first line.
   *
   * @return a 0-based line
   */
  public long first() {
    return first;
  }

  /**
   * Returns the line after the record's last line.
   *
   * @return a 0-based line
   */
  public long end() {
    return end;
  }

  /**
   * Returns the record as read: its lines joined by line feeds, each without its terminator, at
   * most {@link LineReader#MAX_LINE_BYTES} chars.
   *
   * @return the chars
   */
  public CharSequence text() {
    return text;
  }

  /**
   * Reports whether the record parsed. A record that did not shows {@link #text} across all
   * columns.
   *
   * @return true when {@link #value} holds every column
   */
  public boolean parsed() {
    return parsed;
  }

  /**
   * Reports whether the record is cut: it reached {@link
   * io.github.ajdailey.qlogg.core.search.RecordScan#MAX_RECORD_LINES} lines or {@link
   * io.github.ajdailey.qlogg.core.search.RecordScan#MAX_RECORD_BYTES} bytes, where the scan starts
   * a new record whatever the content, or {@link #text} holds only its first part.
   *
   * @return true for a cut record
   */
  public boolean cut() {
    return cut;
  }

  /**
   * Reports whether the record shows as a header row.
   *
   * @return true for a header line
   */
  public boolean header() {
    return header;
  }

  /**
   * Returns a column's value as shown.
   *
   * @param column 0 to the schema's column count - 1
   * @return the chars; empty when the record did not parse
   */
  public CharSequence value(int column) {
    if (!parsed || parser == null || schema == null) {
      return "";
    }
    if (column == schema.timeColumn() && timed) {
      return time;
    }
    return parser.values().value(column);
  }
}
