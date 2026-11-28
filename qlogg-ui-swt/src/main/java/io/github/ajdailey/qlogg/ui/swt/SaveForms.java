package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import org.eclipse.swt.widgets.FileDialog;

/**
 * The save chooser's form choice in column view, as its file types: Columns (TSV), first and
 * preselected, then Original Lines.
 */
final class SaveForms {
  /** File type index of Original Lines. */
  static final int ORIGINAL = 1;

  private SaveForms() {}

  /**
   * Offers both forms as the chooser's file types.
   *
   * @param dialog a save chooser not yet open
   */
  static void offer(FileDialog dialog) {
    dialog.setFilterNames(
        new String[] {UiText.SAVE_FORM_COLUMNS.text(), UiText.SAVE_FORM_ORIGINAL.text()});
    dialog.setFilterExtensions(new String[] {"*.tsv", "*.txt"});
    dialog.setFilterIndex(0);
  }

  /**
   * Reports the form chosen.
   *
   * @param dialog the chooser, after it returned a path
   * @return true for Original Lines; a platform that reports no file type gives Columns (TSV)
   */
  static boolean original(FileDialog dialog) {
    return dialog.getFilterIndex() == ORIGINAL;
  }
}
