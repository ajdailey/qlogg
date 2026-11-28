package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.columns.Column;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import java.util.Arrays;

/**
 * The cells of a column-view row: which schema columns show, in order, each one's width in grid
 * columns, and how many leading cells are frozen. A cell of width w shows at most w - 1 columns of
 * its value and one blank column, where the view draws the rule between cells. Cell i starts at
 * {@link #start}(i) in the composed row; {@link #width()} is the row's width. Immutable.
 */
public final class ColumnLayout {
  /** Narrowest cell, in grid columns. */
  public static final int MIN_WIDTH = 3;

  /** Default width of a column with a short value. */
  static final int DEFAULT_WIDTH = 16;

  /** Default width of the Time column: a stamp with milliseconds and the gap. */
  static final int TIME_WIDTH = 24;

  /** Default width of a Message, Text or Exception column. */
  static final int TEXT_WIDTH = 100;

  /** Widest fitted cell of most columns, the gap included. */
  public static final int FIT_WIDTH = 60;

  /** Widest fitted Message, Text or Exception cell, the gap included. */
  public static final int FIT_TEXT_WIDTH = 200;

  private final ColumnSchema schema;
  private final int[] columns;
  private final int[] widths;
  private final int[] starts;
  private final int[] cellStarts;
  private final int frozen;
  private final boolean[] unfitted;

  /**
   * Creates a layout whose widths are all set.
   *
   * @param schema the format the cells come from
   * @param columns the schema columns shown, in order, at least one
   * @param widths each shown column's width, at least {@link #MIN_WIDTH}
   * @param frozen leading cells that do not scroll, 0 to the cell count
   * @throws IllegalArgumentException for a column outside the schema or mismatched arrays
   */
  public ColumnLayout(ColumnSchema schema, int[] columns, int[] widths, int frozen) {
    this(schema, columns, widths, frozen, new boolean[columns.length]);
  }

  /**
   * Creates a layout in which some widths wait for {@link #fitted}.
   *
   * @param schema the format the cells come from
   * @param columns the schema columns shown, in order, at least one
   * @param widths each shown column's width, at least {@link #MIN_WIDTH}; a placeholder where
   *     {@code unfitted} is set
   * @param frozen leading cells that do not scroll, 0 to the cell count
   * @param unfitted per shown column, true when its width fits the first rows read
   * @throws IllegalArgumentException for a column outside the schema or mismatched arrays
   */
  public ColumnLayout(
      ColumnSchema schema, int[] columns, int[] widths, int frozen, boolean[] unfitted) {
    if (columns.length == 0
        || columns.length != widths.length
        || columns.length != unfitted.length) {
      throw new IllegalArgumentException("one width per shown column");
    }
    if (frozen < 0 || frozen > columns.length) {
      throw new IllegalArgumentException("frozen out of range");
    }
    this.schema = schema;
    this.columns = columns.clone();
    this.widths = widths.clone();
    this.starts = new int[columns.length + 1];
    for (int i = 0; i < columns.length; i++) {
      if (columns[i] < 0 || columns[i] >= schema.columns().size()) {
        throw new IllegalArgumentException("no column " + columns[i]);
      }
      this.widths[i] = Math.max(MIN_WIDTH, widths[i]);
      starts[i + 1] = starts[i] + this.widths[i];
    }
    this.cellStarts = Arrays.copyOf(starts, columns.length);
    this.frozen = frozen;
    this.unfitted = unfitted.clone();
  }

  /**
   * Returns a schema's layout before the user sizes it: every column not hidden by default, the
   * Time column {@link #TIME_WIDTH} wide, Message, Text and Exception {@link #TEXT_WIDTH}, the
   * others {@link #DEFAULT_WIDTH} or their name's length and the gap, at most 60; nothing frozen,
   * nothing to fit.
   *
   * @param schema the format
   * @return the layout
   */
  public static ColumnLayout of(ColumnSchema schema) {
    int[] shown = new int[schema.columns().size()];
    int[] sizes = new int[shown.length];
    int n = 0;
    for (int i = 0; i < shown.length; i++) {
      Column column = schema.columns().get(i);
      if (column.hidden()) {
        continue;
      }
      shown[n] = i;
      sizes[n++] = defaultWidth(schema, i, column.name());
    }
    if (n == 0) {
      shown[0] = 0;
      sizes[0] = defaultWidth(schema, 0, schema.columns().get(0).name());
      n = 1;
    }
    return new ColumnLayout(schema, Arrays.copyOf(shown, n), Arrays.copyOf(sizes, n), 0);
  }

  /**
   * Returns a column's width before it is sized or fitted.
   *
   * @param schema the format
   * @param column a 0-based schema column
   * @return grid columns, the gap included
   */
  public static int defaultWidth(ColumnSchema schema, int column) {
    return defaultWidth(schema, column, schema.columns().get(column).name());
  }

  private static int defaultWidth(ColumnSchema schema, int column, String name) {
    if (column == schema.timeColumn()) {
      return TIME_WIDTH;
    }
    if (schema.delimiter() == 0 && textColumn(name)) {
      return TEXT_WIDTH;
    }
    return Math.min(FIT_WIDTH, Math.max(DEFAULT_WIDTH, name.length() + 1));
  }

  /** True for a Message, Text or Exception column, capitalized or lower case. */
  private static boolean textColumn(String name) {
    return switch (name) {
      case "Message", "message", "Text", "text", "Exception", "exception" -> true;
      default -> false;
    };
  }

  /**
   * Returns the widest a column fits to.
   *
   * @param name the column's name
   * @return {@link #FIT_TEXT_WIDTH} for Message, Text and Exception, capitalized or lower case,
   *     else {@link #FIT_WIDTH}
   */
  public static int fitLimit(String name) {
    return textColumn(name) ? FIT_TEXT_WIDTH : FIT_WIDTH;
  }

  /**
   * Reports whether a cell's width waits for the first rows read.
   *
   * @param cell 0 to {@link #count} - 1
   * @return true until {@link #fitted}
   */
  public boolean unfitted(int cell) {
    return unfitted[cell];
  }

  /**
   * Reports whether any cell waits for a fit.
   *
   * @return true when {@link #fitted} would change a width
   */
  public boolean unfitted() {
    for (boolean wait : unfitted) {
      if (wait) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns this layout with fitted widths for the cells waiting for one; the other cells keep
   * theirs.
   *
   * @param fit per cell, the fitted width; read only where {@link #unfitted}(cell)
   * @return the layout, with no cell waiting
   */
  public ColumnLayout fitted(int[] fit) {
    int[] next = widths.clone();
    for (int i = 0; i < next.length; i++) {
      if (unfitted[i]) {
        next[i] = fit[i];
      }
    }
    return new ColumnLayout(schema, columns, next, frozen);
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
   * Counts the cells.
   *
   * @return the shown columns
   */
  public int count() {
    return columns.length;
  }

  /**
   * Returns the schema column a cell shows.
   *
   * @param cell 0 to {@link #count} - 1
   * @return the 0-based schema column
   */
  public int column(int cell) {
    return columns[cell];
  }

  /**
   * Returns a cell's width.
   *
   * @param cell 0 to {@link #count} - 1
   * @return grid columns, the gap included
   */
  public int width(int cell) {
    return widths[cell];
  }

  /**
   * Returns a cell's first column in the composed row.
   *
   * @param cell 0 to {@link #count}
   * @return the column; at {@link #count}, the row's width
   */
  public int start(int cell) {
    return starts[cell];
  }

  /**
   * Returns the composed row's width.
   *
   * @return grid columns
   */
  public int width() {
    return starts[columns.length];
  }

  /**
   * Counts the frozen cells.
   *
   * @return 0 to {@link #count}
   */
  public int frozen() {
    return frozen;
  }

  /**
   * Returns the columns the frozen cells take.
   *
   * @return the start of the first scrolling cell
   */
  public int frozenWidth() {
    return starts[frozen];
  }

  /**
   * Returns the cell holding a composed column.
   *
   * @param column a 0-based composed column
   * @return the cell, the last one at or past the row's end, 0 below it
   */
  public int cellAt(long column) {
    for (int i = columns.length - 1; i > 0; i--) {
      if (column >= starts[i]) {
        return i;
      }
    }
    return 0;
  }

  /**
   * Returns the shared cell starts of a parsed row.
   *
   * @return {@link #start}(0) to {@link #start}({@link #count} - 1); callers do not modify it
   */
  int[] starts() {
    return cellStarts;
  }
}
