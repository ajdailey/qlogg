package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.ColumnController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.columns.Column;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Listener;

/**
 * The search bar's Column drop-down: All Columns, then the shown tab's column names. It shows only
 * in column view; hidden, {@link #column} is empty, so a text-view search tests whole lines. UI
 * thread only.
 */
final class ColumnChooser {
  private final Combo combo;
  private ColumnSchema schema;

  /**
   * Creates the drop-down, hidden.
   *
   * @param parent the search bar, with a {@code GridLayout}
   * @param chosen runs when the user picks an entry
   */
  ColumnChooser(Composite parent, Listener chosen) {
    combo = Dialogs.combo(parent, false);
    combo.setToolTipText(UiText.SEARCH_COLUMN_TOOLTIP.text());
    combo.add(UiText.SEARCH_ALL_COLUMNS.text());
    combo.select(0);
    GridData data = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
    data.exclude = true;
    combo.setLayoutData(data);
    combo.setVisible(false);
    combo.addListener(SWT.Selection, chosen);
  }

  /**
   * Returns the drop-down.
   *
   * @return the combo
   */
  Combo control() {
    return combo;
  }

  /**
   * Returns the chosen column.
   *
   * @return its name as the format gives it, or empty for All Columns or while hidden
   */
  String column() {
    int index = combo.getSelectionIndex();
    return schema == null || index <= 0 ? "" : schema.columns().get(index - 1).name();
  }

  /**
   * Fills the entries from a tab's format and shows the drop-down in its column view; keeps the
   * chosen name when the new format has it.
   *
   * @param columns the tab's column state, or null for no tab
   * @return true when the bar's layout changed
   */
  boolean sync(ColumnController columns) {
    ColumnSchema next = columns != null && columns.shown() ? columns.schema() : null;
    if (next != schema) {
      String kept = column();
      schema = next;
      combo.removeAll();
      combo.add(UiText.SEARCH_ALL_COLUMNS.text());
      if (schema != null) {
        for (Column column : schema.columns()) {
          combo.add(column.name());
        }
      }
      select(kept);
    }
    GridData data = (GridData) combo.getLayoutData();
    if (data.exclude == (schema == null)) {
      return false;
    }
    data.exclude = schema == null;
    combo.setVisible(schema != null);
    return true;
  }

  /**
   * Selects a column by name or 1-based number, ignoring ASCII case.
   *
   * @param name the column, or empty for All Columns; a name the format lacks selects All Columns
   */
  void select(String name) {
    int index = schema == null || name.isEmpty() ? -1 : schema.columnIndex(name);
    combo.select(index + 1);
  }
}
