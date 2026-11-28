package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.columns.Column;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Configure Highlighters' Column field: the column a rule tests in column view, picked from the
 * shown tab's names or typed, empty for the whole row ({@link HighlightRule#column}). UI thread
 * only.
 */
final class RuleColumnField {
  private final Combo combo;

  /**
   * Creates the caption and the field.
   *
   * @param parent a composite with a one-column {@code GridLayout}
   * @param shown the shown tab, whose format lists the names; null lists none
   * @param edited runs when the user changes the text
   */
  RuleColumnField(Composite parent, DocumentController shown, final Runnable edited) {
    Dialogs.label(parent, UiText.RULE_COLUMN.text());
    combo = Dialogs.combo(parent, true);
    combo.setTextLimit(ColumnSchema.MAX_NAME_CHARS);
    combo.setToolTipText(UiText.RULE_COLUMN_HINT.text());
    combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    ColumnSchema schema = shown == null ? null : shown.columns().schema();
    if (schema != null) {
      for (Column column : schema.columns()) {
        combo.add(column.name());
      }
    }
    combo.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            edited.run();
          }
        });
  }

  /**
   * Returns the column.
   *
   * @return the text stripped, empty for the whole row
   */
  String get() {
    return combo.getText().strip();
  }

  /**
   * Shows a rule's column.
   *
   * @param column the column, or empty
   */
  void set(String column) {
    if (!combo.getText().equals(column)) {
      combo.setText(column);
    }
  }

  /**
   * Enables or disables the field.
   *
   * @param on true to enable
   */
  void setEnabled(boolean on) {
    combo.setEnabled(on);
  }

  /**
   * Returns the field.
   *
   * @return the combo
   */
  Combo control() {
    return combo;
  }
}
