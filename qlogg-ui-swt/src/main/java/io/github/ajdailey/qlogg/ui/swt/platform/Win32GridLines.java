package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * A table's grid lines drawn in {@code SWT.Paint}, over the list view's own painting, with the
 * native lines off. The win32 list view draws {@code LVS_EX_GRIDLINES} in {@code COLOR_3DFACE}, and
 * no public API colors them. Like the native lines, a rule runs along the last pixel row of every
 * row pitch from the header down, empty space included, and the last pixel column of every column,
 * from the header to the bottom. A column resize redraws the table. Confined to the UI thread.
 */
final class Win32GridLines implements Listener {
  /** Column data key: set once the column's resize redraws the table. */
  private static final String HOOKED = Win32GridLines.class.getName() + ".hooked";

  private final Table table;
  private final Color color;

  private Win32GridLines(Table table, Color color) {
    this.table = table;
    this.color = color;
  }

  /**
   * Turns a table's native grid lines off and draws them in a color.
   *
   * @param table the table
   * @param color the line color, which must outlive the table
   */
  static void draw(Table table, Color color) {
    table.setLinesVisible(false);
    table.addListener(SWT.Paint, new Win32GridLines(table, color));
  }

  @Override
  public void handleEvent(Event event) {
    GC gc = event.gc;
    Rectangle area = table.getClientArea();
    int top = table.getHeaderVisible() ? table.getHeaderHeight() : 0;
    int bottom = area.y + area.height;
    int pitch = table.getItemHeight();
    if (pitch <= 0) {
      return;
    }
    gc.setForeground(color);
    gc.setLineWidth(0);
    int first = top;
    if (table.getItemCount() > 0) {
      first = table.getItem(table.getTopIndex()).getBounds().y;
    }
    for (int y = first + pitch - 1; y < bottom; y += pitch) {
      gc.drawLine(area.x, y, area.x + area.width, y);
    }
    TableItem sample = table.getItemCount() > 0 ? table.getItem(table.getTopIndex()) : null;
    int x = area.x;
    for (int index : table.getColumnOrder()) {
      TableColumn column = table.getColumn(index);
      hook(column);
      int right;
      if (sample != null) {
        Rectangle cell = sample.getBounds(index);
        right = cell.x + cell.width;
      } else {
        right = x + column.getWidth();
      }
      x = right;
      gc.drawLine(right - 1, top, right - 1, bottom);
    }
  }

  private void hook(TableColumn column) {
    if (column.getData(HOOKED) != null) {
      return;
    }
    column.setData(HOOKED, Boolean.TRUE);
    column.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!table.isDisposed()) {
              table.redraw();
            }
          }
        });
  }
}
