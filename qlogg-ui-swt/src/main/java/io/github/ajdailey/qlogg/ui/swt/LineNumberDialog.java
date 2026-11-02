package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Navigate > Go to Line: asks for a 1-based line number. Enter confirms, Escape cancels. */
final class LineNumberDialog {
  private final Shell shell;
  private final Text field;
  private final Button ok;

  LineNumberDialog(Shell parent, long lineCount, final MainView.LineNumberListener listener) {
    shell = Dialogs.shell(parent, CommandId.GO_TO_LINE.title(), true);
    Dialogs.label(shell, UiText.GO_TO_LINE_PROMPT.format(Math.max(1, lineCount)));
    field = new Text(shell, SWT.BORDER | SWT.SINGLE);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 240;
    field.setLayoutData(data);
    Composite buttons = Dialogs.buttonRow(shell, 1);
    ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                long line = parse(field.getText());
                if (line > 0) {
                  shell.dispose();
                  listener.lineChosen(line);
                }
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
    ok.setEnabled(false);
    field.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            ok.setEnabled(parse(field.getText()) > 0);
          }
        });
  }

  /**
   * Parses a line number.
   *
   * @param text digits, surrounding blanks ignored
   * @return the line, or 0 when the text is not a positive number
   */
  static long parse(String text) {
    String digits = text.strip();
    if (digits.isEmpty() || digits.length() > 18) {
      return 0;
    }
    for (int i = 0; i < digits.length(); i++) {
      if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
        return 0;
      }
    }
    return Long.parseLong(digits);
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    field.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Text field() {
    return field;
  }

  Button ok() {
    return ok;
  }
}
