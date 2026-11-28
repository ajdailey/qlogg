package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.ColumnSettings;
import io.github.ajdailey.qlogg.core.columns.Column;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.CellReader;
import io.github.ajdailey.qlogg.core.model.ColumnFit;
import io.github.ajdailey.qlogg.core.model.ColumnLayout;
import io.github.ajdailey.qlogg.core.model.ColumnRead;
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Viewport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One tab's column-view layout edits: builds the {@link ColumnLayout} from the stored {@link
 * ColumnSettings} of the tab's format, applies the header's edits and stores each through the
 * {@link DocumentHost}. Confined to the UI thread.
 *
 * <p>A column takes its stored width; one without waits for a fit ({@link ColumnLayout#unfitted}),
 * which the first rows a view reads give it, and keeps that width while the tab shows the format.
 * Fitted widths are not stored; Fit Columns and a double-click on a header boundary store theirs.
 * Edits change the tab they run in; another tab of the format takes them when it next builds its
 * layout.
 */
public final class ColumnSizing {
  private final ColumnController columns;
  private final DocumentController owner;

  ColumnSizing(ColumnController columns, DocumentController owner) {
    this.columns = columns;
    this.owner = owner;
  }

  /**
   * Builds a schema's layout from the stored settings.
   *
   * @param schema the format
   * @param current the layout shown, whose fitted widths stay, or null
   * @return the layout
   */
  ColumnLayout build(ColumnSchema schema, ColumnLayout current) {
    ColumnSettings.Format stored = stored(schema);
    List<Column> list = schema.columns();
    int[] shown = new int[list.size()];
    int[] widths = new int[list.size()];
    boolean[] unfitted = new boolean[list.size()];
    int n = 0;
    for (int i = 0; i < list.size(); i++) {
      if (hidden(stored, list.get(i))) {
        continue;
      }
      shown[n] = i;
      ColumnSettings.Entry entry = stored.entry(list.get(i).name());
      int at = current == null || current.schema() != schema ? -1 : cellOf(current, i);
      if (entry != null && entry.width() > 0) {
        widths[n] = entry.width();
      } else if (at >= 0 && !current.unfitted(at)) {
        widths[n] = current.width(at);
      } else {
        widths[n] = ColumnLayout.defaultWidth(schema, i);
        unfitted[n] = true;
      }
      n++;
    }
    if (n == 0) {
      shown[0] = 0;
      widths[0] = ColumnLayout.defaultWidth(schema, 0);
      unfitted[0] = true;
      n = 1;
    }
    return new ColumnLayout(
        schema,
        Arrays.copyOf(shown, n),
        Arrays.copyOf(widths, n),
        Math.min(stored.frozen(), n),
        Arrays.copyOf(unfitted, n));
  }

  /** Returns the cell showing a schema column, or -1. */
  private static int cellOf(ColumnLayout layout, int column) {
    for (int cell = 0; cell < layout.count(); cell++) {
      if (layout.column(cell) == column) {
        return cell;
      }
    }
    return -1;
  }

  private static boolean hidden(ColumnSettings.Format stored, Column column) {
    ColumnSettings.Entry entry = stored.entry(column.name());
    ColumnSettings.Visibility visibility =
        entry == null ? ColumnSettings.Visibility.DEFAULT : entry.visibility();
    return visibility == ColumnSettings.Visibility.DEFAULT
        ? column.hidden()
        : visibility == ColumnSettings.Visibility.HIDDEN;
  }

  private ColumnSettings.Format stored(ColumnSchema schema) {
    return owner.host().columnSettings().format(schema.format().id());
  }

  private void store(ColumnSchema schema, ColumnSettings.Format format) {
    ColumnSettings settings = owner.host().columnSettings();
    owner.host().setColumnSettings(settings.with(schema.format().id(), format));
  }

  /**
   * Records that a schema's columns show, so their stored entries go first and are the last
   * dropped.
   *
   * @param schema the format shown
   */
  void shown(ColumnSchema schema) {
    ColumnSettings.Format stored = stored(schema);
    ColumnSettings.Format next = stored.shown(names(schema));
    if (next != stored) {
      store(schema, next);
    }
  }

  private static List<String> names(ColumnSchema schema) {
    List<String> names = new ArrayList<String>();
    for (Column column : schema.columns()) {
      names.add(column.name());
    }
    return names;
  }

  /** Stores an edit of the shown schema and rebuilds the layout from it. */
  private void apply(ColumnSettings.Format next, boolean keepFitted) {
    ColumnLayout layout = columns.layout();
    if (layout == null) {
      return;
    }
    store(layout.schema(), next);
    columns.setLayout(build(layout.schema(), keepFitted ? layout : null));
  }

  private ColumnSettings.Entry entry(ColumnSettings.Format stored, String name) {
    ColumnSettings.Entry entry = stored.entry(name);
    return entry == null
        ? new ColumnSettings.Entry(name, 0, ColumnSettings.Visibility.DEFAULT)
        : entry;
  }

  private String name(int cell) {
    ColumnLayout layout = columns.layout();
    return layout.schema().columns().get(layout.column(cell)).name();
  }

  /**
   * Sets a cell's width and stores it.
   *
   * @param cell a cell of the layout
   * @param width grid columns, the gap included; held to {@link ColumnLayout#MIN_WIDTH} to {@link
   *     ColumnSettings#MAX_WIDTH}
   */
  public void resize(int cell, int width) {
    ColumnLayout layout = columns.layout();
    if (layout == null || cell < 0 || cell >= layout.count()) {
      return;
    }
    int next = Math.max(ColumnLayout.MIN_WIDTH, Math.min(ColumnSettings.MAX_WIDTH, width));
    if (next == layout.width(cell) && !layout.unfitted(cell)) {
      return;
    }
    ColumnSettings.Format stored = stored(layout.schema());
    ColumnSettings.Entry entry = entry(stored, name(cell));
    apply(stored.with(new ColumnSettings.Entry(entry.name(), next, entry.visibility())), true);
  }

  /**
   * Shows or hides a schema column and stores it; the last shown column stays.
   *
   * @param column a 0-based schema column
   * @param show true to show it
   */
  public void setShown(int column, boolean show) {
    ColumnLayout layout = columns.layout();
    if (layout == null || column < 0 || column >= layout.schema().columns().size()) {
      return;
    }
    int cell = cellOf(layout, column);
    if ((cell >= 0) == show || !show && layout.count() == 1) {
      return;
    }
    Column schemaColumn = layout.schema().columns().get(column);
    ColumnSettings.Format stored = stored(layout.schema());
    ColumnSettings.Entry entry = entry(stored, schemaColumn.name());
    ColumnSettings.Visibility visibility =
        show == schemaColumn.hidden()
            ? show ? ColumnSettings.Visibility.SHOWN : ColumnSettings.Visibility.HIDDEN
            : ColumnSettings.Visibility.DEFAULT;
    ColumnSettings.Format next =
        stored.with(new ColumnSettings.Entry(entry.name(), entry.width(), visibility));
    if (!show && cell < layout.frozen()) {
      next = next.withFrozen(layout.frozen() - 1);
    }
    apply(next, true);
  }

  /**
   * Hides a cell's column and stores it; the last shown column stays.
   *
   * @param cell a cell of the layout
   */
  public void hide(int cell) {
    ColumnLayout layout = columns.layout();
    if (layout != null && cell >= 0 && cell < layout.count()) {
      setShown(layout.column(cell), false);
    }
  }

  /**
   * Freezes the cells up to and including one and stores the count.
   *
   * @param cell a cell of the layout; -1 unfreezes
   */
  public void freeze(int cell) {
    ColumnLayout layout = columns.layout();
    if (layout == null || cell >= layout.count()) {
      return;
    }
    apply(stored(layout.schema()).withFrozen(Math.max(0, cell + 1)), true);
  }

  /** Removes the stored widths, visibility and frozen count of the shown format's columns. */
  public void reset() {
    ColumnLayout layout = columns.layout();
    if (layout != null) {
      apply(stored(layout.schema()).without(names(layout.schema())), false);
    }
  }

  /**
   * Fits cells to the rows a view shows and stores their widths. The records are read on a {@code
   * qlogg-read-} worker under the busy cursor; a layout changed meanwhile drops the result.
   *
   * @param view the view whose visible rows the cells fit
   * @param cells cells of the layout
   */
  public void fit(final LineViewController view, final int[] cells) {
    final ColumnRead read = columns.read();
    final long[] lines = visibleLines(view);
    if (read == null || lines.length == 0 || cells.length == 0) {
      return;
    }
    final LogDocument document = owner.document();
    final LineIndex index = document.index();
    new BusyWork(owner.ui(), owner.activity()) {
      private int[] widths;

      @Override
      @WorkerThread
      void work() {
        if (!document.enter()) {
          return;
        }
        try {
          widths =
              ColumnFit.widths(
                  new CellReader(document.newReader()), index, lines, lines.length, read);
        } catch (IOException | UncheckedIOException e) {
          widths = null;
        } finally {
          document.exit();
        }
      }

      @Override
      void finished(boolean completed) {
        if (widths != null
            && widths.length > 0
            && !owner.isClosed()
            && columns.layout() == read.layout()) {
          fitted(read.layout(), cells, widths);
        }
      }
    }.start(owner.workers(), Workers.Kind.READ);
  }

  private void fitted(ColumnLayout layout, int[] cells, int[] widths) {
    ColumnSettings.Format stored = stored(layout.schema());
    for (int cell : cells) {
      if (cell >= 0 && cell < layout.count()) {
        ColumnSettings.Entry entry = entry(stored, name(cell));
        stored =
            stored.with(new ColumnSettings.Entry(entry.name(), widths[cell], entry.visibility()));
      }
    }
    apply(stored, true);
  }

  /** The source lines of a view's rows on screen that its last read holds. */
  private static long[] visibleLines(LineViewController view) {
    LineBlock block = view.lines();
    Viewport viewport = view.viewport();
    List<Long> lines = new ArrayList<Long>();
    long end = Math.min(viewport.lineCount(), viewport.topLine() + viewport.rows());
    for (long row = viewport.topLine(); row < end; row++) {
      if (block.contains(row)) {
        long line = block.sourceLine(row);
        if (lines.isEmpty() || line > lines.get(lines.size() - 1)) {
          lines.add(line);
        }
      }
    }
    long[] result = new long[lines.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = lines.get(i);
    }
    return result;
  }
}
