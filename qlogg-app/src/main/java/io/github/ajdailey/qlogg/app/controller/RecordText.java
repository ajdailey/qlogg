package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.columns.Column;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.CpuSteps;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.CellReader;
import io.github.ajdailey.qlogg.core.model.CellRecord;
import io.github.ajdailey.qlogg.core.model.ColumnLayout;
import io.github.ajdailey.qlogg.core.model.ColumnRead;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.search.RecordLines;
import java.io.IOException;
import java.util.List;

/**
 * Builds the text of a column-view copy or save, a selected row at a time, for {@link
 * TextExporter}. Each row is the record holding its line ({@link CellReader}):
 *
 * <table>
 *   <caption>Text per form</caption>
 *   <tr><th>Form</th><th>Text of a row</th></tr>
 *   <tr><td>{@link Form#COLUMNS}</td><td>The shown cells' full values, tab-separated, with a tab,
 *       line feed or backslash inside a value written {@code \t}, {@code \n} or {@code \\}; a
 *       record that did not parse is one value, its raw text</td></tr>
 *   <tr><td>{@link Form#LINES}</td><td>Every line of the record as in the file</td></tr>
 *   <tr><td>{@link Form#CELL}</td><td>One cell's full value, unescaped; the raw text of a record
 *       that did not parse</td></tr>
 * </table>
 *
 * <p>The text can be longer than the record's bytes (escapes, the Time cell), so {@link #bytes}
 * counts {@link #COLUMN_BYTES_PER_FILE_BYTE} per record byte and {@link #CELL_BYTES} per cell, and
 * the build fails with {@link TooLarge} past the size it was given. Not thread-safe.
 */
final class RecordText implements CpuSteps.Steps {
  /** Text counted per record byte for {@link Form#COLUMNS} and {@link Form#CELL}. */
  static final int COLUMN_BYTES_PER_FILE_BYTE = 2;

  /** Text counted per shown cell of a row for the separator and a formatted Time value. */
  static final int CELL_BYTES = 32;

  /** What a column-view copy takes from each row. */
  enum Form {
    /** The shown cells, tab-separated. */
    COLUMNS,
    /** The record's lines. */
    LINES,
    /** One cell. */
    CELL
  }

  /**
   * A column-view copy.
   *
   * @param view the cells, format and records
   * @param form what each row gives
   * @param cell for {@link Form#CELL}, the layout's cell
   */
  record Columns(ColumnRead view, Form form, int cell) {}

  /** The text grew past the size it was given. */
  static final class TooLarge extends IOException {
    private static final long serialVersionUID = 1L;

    TooLarge() {
      super("Column text is larger than its estimate");
    }
  }

  private final CellReader cells;
  private final LineReader lines;
  private final LineIndex index;
  private final TextExporter.Rows rows;
  private final Columns columns;
  private final boolean numbered;
  private final String separator;
  private final StringBuilder out;
  private final long maxChars;
  private final CancellationToken token;
  private long row;
  private long line;
  private long taken;

  RecordText(
      LineReader cellLines,
      LineReader rawLines,
      LineIndex index,
      TextExporter.Rows rows,
      Columns columns,
      boolean numbered,
      StringBuilder out,
      long maxChars,
      CancellationToken token) {
    this.cells = new CellReader(cellLines);
    this.lines = rawLines;
    this.index = index;
    this.rows = rows;
    this.columns = columns;
    this.numbered = numbered;
    this.separator = System.lineSeparator();
    this.out = out;
    this.maxChars = maxChars;
    this.token = token;
    this.row = rows.first();
    this.line = Math.min(rows.firstLine(), index.lineCount() - 1);
  }

  /**
   * Counts the text a copy takes at most: the file bytes of each row's record, times {@link
   * #COLUMN_BYTES_PER_FILE_BYTE} outside {@link Form#LINES}, plus {@link #CELL_BYTES} per shown
   * cell of a row.
   *
   * @param index the snapshot
   * @param rows the rows taken
   * @param columns the copy
   * @param token checked every row
   * @return bytes
   */
  static long bytes(
      LineIndex index, TextExporter.Rows rows, Columns columns, CancellationToken token) {
    RecordLines records = columns.view().records();
    long last = index.lineCount() - 1;
    long bytes = 0;
    long line = Math.min(rows.firstLine(), last);
    long perRow =
        columns.form() == Form.LINES ? 0 : (long) CELL_BYTES * columns.view().layout().count();
    int factor = columns.form() == Form.LINES ? 1 : COLUMN_BYTES_PER_FILE_BYTE;
    for (long at = rows.first(); at <= rows.last() && line >= 0 && line <= last; at++) {
      token.check();
      long first = records == null ? line : records.startOf(line);
      long end = records == null ? line + 1 : Math.min(records.nextStart(first + 1), last + 1);
      end = Math.max(end, first + 1);
      long record = index.lineEnd(end - 1) - index.lineStart(first) + end - first;
      bytes += Math.min(record, LineReader.MAX_LINE_BYTES + 1L) * factor + perRow;
      line = rows.walk().next(line);
    }
    return bytes;
  }

  /**
   * Returns the rows taken so far.
   *
   * @return rows
   */
  long taken() {
    return taken;
  }

  @Override
  @WorkerThread
  public boolean step() throws IOException {
    long last = index.lineCount() - 1;
    for (int n = 0; n < CpuSteps.STEP_LINES; n++) {
      if (row > rows.last() || line < 0 || line > last) {
        return false;
      }
      token.check();
      CellRecord record = cells.record(index, line, columns.view());
      if (numbered) {
        out.append(record.first() + 1).append('\t');
      }
      switch (columns.form()) {
        case COLUMNS -> appendColumns(record);
        case LINES -> appendLines(record);
        case CELL -> out.append(cellValue(record, columns.view().layout(), columns.cell()));
      }
      if (row != rows.last()) {
        out.append(separator);
      }
      if (out.length() > maxChars) {
        throw new TooLarge();
      }
      taken++;
      line = rows.walk().next(line);
      row++;
    }
    return true;
  }

  @WorkerThread
  private void appendColumns(CellRecord record) {
    if (!record.parsed()) {
      escape(record.text());
      return;
    }
    ColumnLayout layout = columns.view().layout();
    for (int cell = 0; cell < layout.count(); cell++) {
      if (cell > 0) {
        out.append('\t');
      }
      escape(record.value(layout.column(cell)));
    }
  }

  @WorkerThread
  private void appendLines(CellRecord record) throws IOException {
    for (long at = record.first(); at < record.end(); at++) {
      if (at > record.first()) {
        out.append(separator);
      }
      lines.appendText(index, at, 0, Long.MAX_VALUE, out);
    }
  }

  private void escape(CharSequence value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\t' -> out.append("\\t");
        case '\n' -> out.append("\\n");
        case '\\' -> out.append("\\\\");
        default -> out.append(c);
      }
    }
  }

  /**
   * Returns a cell's full value as shown, or the raw text of a record that did not parse.
   *
   * @param record the record
   * @param layout the cells
   * @param cell 0 to the layout's count - 1
   * @return the chars, valid until the record's reader reads again
   */
  static CharSequence cellValue(CellRecord record, ColumnLayout layout, int cell) {
    return record.parsed() ? record.value(layout.column(cell)) : record.text();
  }

  /**
   * Returns Show Record's text: {@code Name: value} per column of the format, hidden ones included,
   * each value in full with its line feeds; the raw text of a record that did not parse.
   *
   * @param record the record
   * @return the text
   */
  static String show(CellRecord record) {
    if (!record.parsed()) {
      return record.text().toString();
    }
    StringBuilder text = new StringBuilder();
    List<Column> all = record.schema().columns();
    for (int i = 0; i < all.size(); i++) {
      if (i > 0) {
        text.append('\n');
      }
      text.append(all.get(i).name()).append(": ").append(record.value(i));
    }
    return text.toString();
  }
}
