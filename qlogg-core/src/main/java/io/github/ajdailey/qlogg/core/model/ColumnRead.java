package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.columns.TimeCell;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.RecordLines;
import io.github.ajdailey.qlogg.core.time.TimeContext;

/**
 * What a column-view read needs besides the lines: the cells, the zone stamps are parsed in, how
 * the Time cell shows, the records and the header rows. A read of a line reads the whole record
 * holding it, from the record's first line.
 *
 * @param layout the cells
 * @param context the zone and file date the parser reads stamps with
 * @param time the display zone and time offset of the Time cell
 * @param records the records of a multi-line format, or null when each line is a record
 * @param headers a rotated set's repeated member headers, shown as header rows as line 0 is when
 *     the schema has a header line
 */
public record ColumnRead(
    ColumnLayout layout,
    TimeContext context,
    TimeCell time,
    RecordLines records,
    LineSet headers) {}
