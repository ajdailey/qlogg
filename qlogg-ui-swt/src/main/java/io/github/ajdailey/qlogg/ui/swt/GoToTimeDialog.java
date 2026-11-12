package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.TimeText;
import java.time.LocalDate;
import java.time.ZoneId;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Go > Go to Time: asks for a time that {@link TimeText#parseTarget} reads; OK stays disabled until
 * it does. Enter confirms, Escape cancels.
 */
final class GoToTimeDialog {
  private final Shell shell;
  private final Text field;
  private final Button ok;
  private final ZoneId zone;
  private final LocalDate date;

  GoToTimeDialog(
      Shell parent,
      String initial,
      ZoneId zone,
      LocalDate date,
      final MainView.TimeListener listener) {
    this.zone = zone;
    this.date = date;
    shell = Dialogs.shell(parent, CommandId.GO_TO_TIME.title(), true);
    Dialogs.label(shell, UiText.GO_TO_TIME_PROMPT.format(zone.getId()));
    field = new Text(shell, SWT.BORDER | SWT.SINGLE);
    field.setText(initial);
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
                long target = target();
                if (target != TimeText.INVALID) {
                  shell.dispose();
                  listener.timeChosen(target);
                }
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
    ok.setEnabled(target() != TimeText.INVALID);
    field.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            ok.setEnabled(target() != TimeText.INVALID);
          }
        });
  }

  private long target() {
    return TimeText.parseTarget(field.getText(), zone, date);
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
