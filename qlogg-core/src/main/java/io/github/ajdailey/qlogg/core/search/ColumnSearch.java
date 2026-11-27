package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.time.TimeContext;

/**
 * The columns a search tests in column view. With {@code records}, a line search tests whole
 * records: a chunk ends at a record start, the matcher reads each record's span with its internal
 * line feeds, and a hit adds the record's first line. A record is tested when its first line is in
 * the scope and, for a restricted search, a member. A range search tests lines.
 *
 * @param schema the shown file's columns; parsed per line, or per record with {@code records}
 * @param context the zone and file date the parsers read stamps with
 * @param records the record starts taken when the search starts, or null to test lines; required
 *     for column terms on a {@link io.github.ajdailey.qlogg.core.columns.ColumnFormat#multiLine}
 *     format
 */
public record ColumnSearch(ColumnSchema schema, TimeContext context, RecordLines records) {
  /** Checks the components. */
  public ColumnSearch {
    if (schema == null || context == null) {
      throw new IllegalArgumentException("schema and context are required");
    }
  }

  /**
   * Returns these columns tested line by line.
   *
   * @return the copy without records
   */
  public ColumnSearch lineByLine() {
    return records == null ? this : new ColumnSearch(schema, context, null);
  }
}
