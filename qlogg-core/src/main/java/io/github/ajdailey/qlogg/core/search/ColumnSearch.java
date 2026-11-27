package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.time.TimeContext;

/**
 * The columns a search tests in column view. With {@code records}, a line search tests whole
 * records: a chunk ends at a record start, the matcher reads each record's span with its internal
 * line feeds, and a hit adds the record's first line. A record is tested when its first line is in
 * the scope and, for a restricted search, a member. With a {@link ColumnSchema#header}, the header
 * records are not tested: line 0 and the lines of {@code headers}. A range search tests lines.
 *
 * @param schema the shown file's columns; parsed per line, or per record with {@code records}
 * @param context the zone and file date the parsers read stamps with
 * @param records the record starts taken when the search starts, or null to test lines; required
 *     for column terms on a {@link io.github.ajdailey.qlogg.core.columns.ColumnFormat#multiLine}
 *     format
 * @param headers record first lines after line 0 that repeat the header, such as a rotated set's
 *     member starts; empty for none
 */
public record ColumnSearch(
    ColumnSchema schema, TimeContext context, RecordLines records, LineSet headers) {
  private static final LineSet NONE = new SortedLines(new long[0]);

  /** Checks the components. */
  public ColumnSearch {
    if (schema == null || context == null || headers == null) {
      throw new IllegalArgumentException("schema, context and headers are required");
    }
  }

  /**
   * Creates the columns of a file whose header, if any, is line 0 alone.
   *
   * @param schema the shown file's columns
   * @param context the zone and file date the parsers read stamps with
   * @param records the record starts, or null to test lines
   */
  public ColumnSearch(ColumnSchema schema, TimeContext context, RecordLines records) {
    this(schema, context, records, NONE);
  }

  /**
   * Returns these columns tested line by line.
   *
   * @return the copy without records
   */
  public ColumnSearch lineByLine() {
    return records == null ? this : new ColumnSearch(schema, context, null, headers);
  }
}
