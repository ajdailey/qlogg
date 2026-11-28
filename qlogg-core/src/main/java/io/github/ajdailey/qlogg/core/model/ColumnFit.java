package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import java.io.IOException;

/**
 * Fits column-view cells to records: a cell takes the widest shown value among the records of the
 * listed lines, or its column's name when that is wider, plus the gap, from {@link
 * ColumnLayout#MIN_WIDTH} to {@link ColumnLayout#fitLimit}. Header records and records that do not
 * parse are left out; with none left the result is empty. Reads through a {@link CellReader}, so
 * the caller's thread rules apply.
 */
public final class ColumnFit {
  private ColumnFit() {}

  /**
   * Returns the width each cell of a layout fits.
   *
   * @param reader reads the records
   * @param index the snapshot to read
   * @param lines ascending 0-based lines; lines past the end of the index are left out
   * @param count lines used from the start of the array
   * @param view the cells, format and records
   * @return per cell the fitted width; empty when no listed record parsed
   * @throws IOException on a read error
   */
  public static int[] widths(
      CellReader reader, LineIndex index, long[] lines, int count, ColumnRead view)
      throws IOException {
    ColumnLayout layout = view.layout();
    int[] widest = new int[layout.count()];
    int[] limits = new int[layout.count()];
    for (int cell = 0; cell < widest.length; cell++) {
      String name = layout.schema().columns().get(layout.column(cell)).name();
      limits[cell] = ColumnLayout.fitLimit(name);
      widest[cell] = Math.min(limits[cell] - 1, name.length());
    }
    boolean any = false;
    long lastEnd = -1;
    for (int i = 0; i < count && lines[i] < index.lineCount(); i++) {
      if (lines[i] < lastEnd) {
        continue;
      }
      CellRecord record = reader.record(index, lines[i], view);
      lastEnd = record.end();
      if (!record.parsed() || record.header()) {
        continue;
      }
      any = true;
      for (int cell = 0; cell < widest.length; cell++) {
        widest[cell] =
            Math.max(widest[cell], shown(record.value(layout.column(cell)), limits[cell] - 1));
      }
    }
    if (!any) {
      return new int[0];
    }
    int[] widths = new int[widest.length];
    for (int cell = 0; cell < widths.length; cell++) {
      widths[cell] = Math.max(ColumnLayout.MIN_WIDTH, widest[cell] + 1);
    }
    return widths;
  }

  /** Display columns of a value, at most {@code limit}. */
  private static int shown(CharSequence value, int limit) {
    long column = 0;
    for (int k = 0; k < value.length() && column < limit; k++) {
      column = LineReader.advance(column, value.charAt(k));
    }
    return (int) Math.min(limit, column);
  }
}
