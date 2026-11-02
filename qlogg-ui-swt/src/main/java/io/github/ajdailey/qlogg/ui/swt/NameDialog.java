package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Asks for a name: Save as Filter and Rename Tab. Enter confirms, Escape cancels. */
final class NameDialog {
  private final Shell shell;
  private final Text field;
  private final Button ok;

  NameDialog(
      Shell parent,
      String title,
      String prompt,
      String suggested,
      int limit,
      final MainView.NameListener listener) {
    shell = Dialogs.shell(parent, title, true);
    Dialogs.label(shell, prompt);
    field = new Text(shell, SWT.BORDER | SWT.SINGLE);
    field.setTextLimit(limit);
    field.setText(suggested);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 320;
    field.setLayoutData(data);
    Composite buttons = Dialogs.buttonRow(shell, 1);
    ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                String name = field.getText();
                shell.dispose();
                listener.nameChosen(name);
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    field.setFocus();
    field.selectAll();
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
