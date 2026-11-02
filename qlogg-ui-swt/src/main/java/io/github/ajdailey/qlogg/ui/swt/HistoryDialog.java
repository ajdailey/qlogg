package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.SearchHistory;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

/**
 * Search > Edit Search History: one entry per line, most recent first, and the number of entries
 * kept ({@code search.historySize}). OK saves both; blank lines are dropped.
 */
final class HistoryDialog {
  private final Shell shell;
  private final Text entries;
  private final Spinner size;

  HistoryDialog(Shell parent, final ApplicationController application) {
    shell = Dialogs.shell(parent, CommandId.EDIT_HISTORY.title(), true);
    Dialogs.label(shell, UiText.HISTORY_PROMPT.text());
    Preferences preferences = application.preferences();
    entries = new Text(shell, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
    entries.setText(String.join("\n", preferences.searchHistory()));
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.widthHint = 420;
    data.heightHint = 240;
    entries.setLayoutData(data);
    Composite row = new Composite(shell, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 0;
    row.setLayout(layout);
    Label label = new Label(row, SWT.NONE);
    label.setText(UiText.HISTORY_SIZE.text());
    size = new Spinner(row, SWT.BORDER);
    size.setValues(preferences.historySize(), 1, SearchHistory.MAX_SIZE, 0, 1, 10);
    Composite buttons = Dialogs.buttonRow(shell, 1);
    shell.setDefaultButton(
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                List<String> kept = lines(entries.getText());
                int chosen = size.getSelection();
                shell.dispose();
                application.setSearchHistory(kept, chosen);
              }
            }));
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
  }

  /**
   * Splits the editor text into entries, dropping blank lines.
   *
   * @param text the editor text
   * @return the entries in order
   */
  static List<String> lines(String text) {
    List<String> kept = new ArrayList<String>();
    for (String line : text.split("\\R")) {
      if (!line.isBlank()) {
        kept.add(line);
      }
    }
    return kept;
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }

  Text entries() {
    return entries;
  }

  Spinner size() {
    return size;
  }
}
