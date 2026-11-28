package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.ColumnController;
import io.github.ajdailey.qlogg.app.controller.ColumnSizing;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.columns.Column;
import io.github.ajdailey.qlogg.core.model.ColumnLayout;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * The column names above a {@link LogCanvas} in column view, on the same {@link CellGrid}: the
 * frozen cells stay and the others scroll with the view's horizontal bar. Painted in the canvas's
 * font and palette, gutter color as ground, names in the text color, rules in {@link
 * ColumnRowPainter#ruleColor}; hidden in text view. A drag on a cell's rule resizes the cell, to at
 * least {@link ColumnLayout#MIN_WIDTH} grid columns; a double-click on a rule fits the cell to the
 * visible rows; the context menu ({@link #menuAt}) shows and hides columns, freezes, fits and
 * resets them, and sets the search bar's column. Each edit goes to the tab's {@link ColumnSizing}.
 * A screen reader reads each header cell as a child named after its column. UI thread only.
 */
final class ColumnHeader {
  /** Pixels above and below the names. */
  static final int PAD = 2;

  /** Pixels either side of a rule that grab it. */
  static final int GRAB = 3;

  private final LogCanvas view;
  private final Canvas header;
  private boolean shown;
  private int height;
  private int dragCell = -1;

  /**
   * Creates the header, hidden.
   *
   * @param parent a composite with a {@code GridLayout} of one column, above the view's canvas
   * @param view the view it names the columns of
   */
  ColumnHeader(Composite parent, LogCanvas view) {
    this.view = view;
    header = new Canvas(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
    GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
    data.exclude = true;
    header.setLayoutData(data);
    header.setVisible(false);
    AccessibleName.attach(
        header,
        UiText.ACCESSIBLE_COLUMN_HEADER.text(),
        new AccessibleName.Parts() {
          @Override
          public int count() {
            ColumnLayout layout = layout();
            return layout == null ? 0 : layout.count();
          }

          @Override
          public String name(int part) {
            return accessibleName(part);
          }
        });
    listen();
  }

  /**
   * Returns the SWT control.
   *
   * @return the canvas
   */
  Canvas control() {
    return header;
  }

  /**
   * Reports whether the header shows.
   *
   * @return true in column view
   */
  boolean shown() {
    return shown;
  }

  /** Shows the header in column view and hides it in text view, fitting its height to the font. */
  void sync() {
    if (header.isDisposed()) {
      return;
    }
    boolean next = layout() != null;
    int nextHeight = view.lineHeight() + 2 * PAD + 1;
    if (next != shown || nextHeight != height) {
      shown = next;
      height = nextHeight;
      GridData data = (GridData) header.getLayoutData();
      data.exclude = !next;
      data.heightHint = nextHeight;
      header.setVisible(next);
      header.getParent().layout(true);
    }
    header.redraw();
  }

  /**
   * Returns the accessible name of a header cell.
   *
   * @param cell a cell of the layout
   * @return the name, or empty outside the layout
   */
  String accessibleName(int cell) {
    ColumnLayout layout = layout();
    if (layout == null || cell < 0 || cell >= layout.count()) {
      return "";
    }
    return UiText.ACCESSIBLE_COLUMN_CELL.format(name(layout, cell));
  }

  private ColumnController columns() {
    return view.document().owner().columns();
  }

  /** The layout shown, or null in text view. */
  private ColumnLayout layout() {
    ColumnController columns = columns();
    return columns.shown() ? columns.layout() : null;
  }

  private static String name(ColumnLayout layout, int cell) {
    return layout.schema().columns().get(layout.column(cell)).name();
  }

  private CellGrid cells(ColumnLayout layout) {
    return new CellGrid(
        view.grid(),
        view.gutterWidth(),
        view.document().viewport().firstColumn(),
        layout.frozenWidth());
  }

  /** The right edge of the text area, as the canvas paints it. */
  private int textRight() {
    return view.control().getClientArea().width - view.markerMargin();
  }

  private void listen() {
    header.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paint(event.gc);
          }
        });
    header.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1) {
              dragCell = boundaryAt(event.x);
            }
          }
        });
    header.addListener(
        SWT.MouseMove,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            moved(event.x);
          }
        });
    header.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            dragCell = -1;
          }
        });
    header.addListener(
        SWT.MouseDoubleClick,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            int cell = boundaryAt(event.x);
            if (event.button == 1 && cell >= 0) {
              columns().sizing().fit(view.document(), new int[] {cell});
            }
          }
        });
    header.addListener(
        SWT.MenuDetect,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            Point at = header.toControl(event.x, event.y);
            Menu old = header.getMenu();
            header.setMenu(layout() == null ? null : menuAt(at.x));
            if (old != null) {
              old.dispose();
            }
          }
        });
  }

  /** Resizes the dragged cell to the pointer, or shows the resize cursor over a rule. */
  private void moved(int x) {
    ColumnLayout layout = layout();
    if (layout == null) {
      return;
    }
    if (dragCell >= 0 && dragCell < layout.count()) {
      int left = cells(layout).columnX(layout.start(dragCell));
      columns().sizing().resize(dragCell, (int) view.grid().columnAt(x - left));
      return;
    }
    header.setCursor(
        boundaryAt(x) >= 0 ? header.getDisplay().getSystemCursor(SWT.CURSOR_SIZEWE) : null);
  }

  /**
   * Returns the cell whose rule lies within {@link #GRAB} pixels of an x.
   *
   * @param x pixels in the header
   * @return the cell, or -1
   */
  int boundaryAt(int x) {
    ColumnLayout layout = layout();
    if (layout == null) {
      return -1;
    }
    CellGrid cells = cells(layout);
    int right = textRight();
    for (int cell = layout.count() - 1; cell >= 0; cell--) {
      long end = layout.start(cell + 1);
      if (cells.ruleShows(end, right) && Math.abs(cells.ruleX(end) - x) <= GRAB) {
        return cell;
      }
    }
    return -1;
  }

  /**
   * Returns the cell under an x.
   *
   * @param x pixels in the header
   * @return the cell, or -1 left of the cells and past the last
   */
  int cellAt(int x) {
    ColumnLayout layout = layout();
    if (layout == null || x < view.gutterWidth()) {
      return -1;
    }
    CellGrid cells = cells(layout);
    for (int cell = 0; cell < layout.count(); cell++) {
      long start = layout.start(cell);
      boolean frozen = cell < layout.frozen();
      int left = frozen ? cells.columnX(start) : Math.max(cells.columnX(start), cells.frozenEdge());
      if (x >= left && x <= cells.ruleX(layout.start(cell + 1))) {
        return cell;
      }
    }
    return -1;
  }

  /**
   * Builds the context menu for a header x: a check item per column of the format, Hide Column,
   * Freeze Columns Up To Here and Unfreeze for the cell there, Fit Columns, Reset Columns, and
   * Search in Column for the cell there.
   *
   * @param x pixels in the header
   * @return the menu, which the caller disposes
   */
  Menu menuAt(int x) {
    final ColumnLayout layout = layout();
    final ColumnSizing sizing = columns().sizing();
    final int cell = cellAt(x);
    Menu menu = new Menu(header);
    List<Column> all = layout.schema().columns();
    for (int i = 0; i < all.size(); i++) {
      final int column = i;
      final boolean on = shownCell(layout, i) >= 0;
      MenuItem item = new MenuItem(menu, SWT.CHECK);
      item.setText(all.get(i).name().replace("&", "&&"));
      item.setSelection(on);
      item.setEnabled(!on || layout.count() > 1);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              sizing.setShown(column, !on);
            }
          });
    }
    new MenuItem(menu, SWT.SEPARATOR);
    item(
        menu,
        UiText.COLUMN_HIDE,
        cell >= 0 && layout.count() > 1,
        new Runnable() {
          @Override
          public void run() {
            sizing.hide(cell);
          }
        });
    item(
        menu,
        UiText.COLUMN_FREEZE,
        cell >= 0,
        new Runnable() {
          @Override
          public void run() {
            sizing.freeze(cell);
          }
        });
    item(
        menu,
        UiText.COLUMN_UNFREEZE,
        layout.frozen() > 0,
        new Runnable() {
          @Override
          public void run() {
            sizing.freeze(-1);
          }
        });
    new MenuItem(menu, SWT.SEPARATOR);
    item(
        menu,
        UiText.COLUMN_FIT,
        true,
        new Runnable() {
          @Override
          public void run() {
            int[] cells = new int[layout.count()];
            for (int k = 0; k < cells.length; k++) {
              cells[k] = k;
            }
            sizing.fit(view.document(), cells);
          }
        });
    item(
        menu,
        UiText.COLUMN_RESET,
        true,
        new Runnable() {
          @Override
          public void run() {
            sizing.reset();
          }
        });
    new MenuItem(menu, SWT.SEPARATOR);
    item(
        menu,
        UiText.COLUMN_SEARCH,
        cell >= 0,
        new Runnable() {
          @Override
          public void run() {
            view.document().owner().search().searchInColumn(name(layout, cell));
          }
        });
    return menu;
  }

  private static int shownCell(ColumnLayout layout, int column) {
    for (int cell = 0; cell < layout.count(); cell++) {
      if (layout.column(cell) == column) {
        return cell;
      }
    }
    return -1;
  }

  private static void item(Menu menu, UiText text, boolean enabled, final Runnable action) {
    MenuItem item = new MenuItem(menu, SWT.PUSH);
    item.setText(text.text());
    item.setEnabled(enabled);
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            action.run();
          }
        });
  }

  private void paint(GC gc) {
    PaletteColors colors = view.colors();
    Rectangle area = header.getClientArea();
    gc.setBackground(colors.gutter);
    gc.fillRectangle(area);
    ColumnLayout layout = layout();
    if (layout == null) {
      return;
    }
    gc.setFont(view.font());
    CellGrid cells = cells(layout);
    int right = textRight();
    int frozenEdge = cells.frozenEdge();
    gc.setForeground(ColumnRowPainter.ruleColor(colors));
    gc.drawLine(0, area.height - 1, area.width, area.height - 1);
    for (int cell = 0; cell < layout.count(); cell++) {
      long start = layout.start(cell);
      long end = layout.start(cell + 1);
      boolean frozen = cell < layout.frozen();
      int x = cells.columnX(start);
      int left = frozen ? x : Math.max(x, frozenEdge);
      int edge = Math.min(right, cells.ruleX(end));
      if (edge > left) {
        gc.setClipping(left, 0, edge - left, area.height);
        gc.setForeground(colors.text);
        gc.drawString(name(layout, cell), x, PAD, true);
        gc.setClipping((Rectangle) null);
      }
      if (cells.ruleShows(end, right)) {
        gc.setForeground(ColumnRowPainter.ruleColor(colors));
        int at = cells.ruleX(end);
        gc.drawLine(at, 0, at, area.height - 1);
      }
    }
  }
}
