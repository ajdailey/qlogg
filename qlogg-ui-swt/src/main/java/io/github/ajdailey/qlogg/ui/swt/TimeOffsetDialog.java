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
 * View > Timestamps > Time Offset: the offset as {@link TimeText#parseOffset} reads it, or the time
 * the caret line should show, from which the offset follows; editing either field rewrites the
 * other. OK stays disabled while the offset does not parse or moves the caret line's time out of
 * the {@code long} range. Enter confirms, Escape cancels.
 */
final class TimeOffsetDialog {
  private final Shell shell;
  private final Text offsetField;
  private final Text caretField;
  private final Button ok;
  private final long caret;
  private final int digits;
  private final ZoneId zone;
  private final LocalDate date;
  private boolean syncing;

  TimeOffsetDialog(
      Shell parent,
      long offset,
      long caret,
      int digits,
      ZoneId zone,
      final MainView.TimeListener listener) {
    this.caret = caret;
    this.digits = digits;
    this.zone = zone;
    String shown = shown(offset);
    this.date = shown.isEmpty() ? null : TimeText.date(caret + offset, zone);
    shell = Dialogs.shell(parent, CommandId.TIME_OFFSET.title(), true);
    Dialogs.label(shell, UiText.TIME_OFFSET_PROMPT.text());
    offsetField = field(TimeText.offset(offset));
    Dialogs.label(
        shell,
        caret == TimeText.INVALID
            ? UiText.TIME_OFFSET_NO_CARET.text()
            : UiText.TIME_OFFSET_CARET_PROMPT.format(zone.getId()));
    caretField = field(shown);
    caretField.setEnabled(caret != TimeText.INVALID);
    Composite buttons = Dialogs.buttonRow(shell, 1);
    ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                long chosen = offset();
                if (chosen != TimeText.INVALID) {
                  shell.dispose();
                  listener.timeChosen(chosen);
                }
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
    offsetField.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            offsetEdited();
          }
        });
    caretField.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            caretEdited();
          }
        });
    ok.setEnabled(offset() != TimeText.INVALID);
  }

  private Text field(String text) {
    Text field = new Text(shell, SWT.BORDER | SWT.SINGLE);
    field.setText(text);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 320;
    field.setLayoutData(data);
    return field;
  }

  /** The caret line's time with an offset, or empty without one or out of range. */
  private String shown(long offset) {
    if (caret == TimeText.INVALID) {
      return "";
    }
    try {
      return TimeText.instant(Math.addExact(caret, offset), digits, zone);
    } catch (ArithmeticException e) {
      return "";
    }
  }

  /** The offset field's value, or {@link TimeText#INVALID} when it is refused. */
  private long offset() {
    long offset = TimeText.parseOffset(offsetField.getText());
    if (offset == TimeText.INVALID || caret == TimeText.INVALID) {
      return offset;
    }
    return shown(offset).isEmpty() ? TimeText.INVALID : offset;
  }

  private void offsetEdited() {
    long offset = offset();
    ok.setEnabled(offset != TimeText.INVALID);
    if (syncing || offset == TimeText.INVALID || caret == TimeText.INVALID) {
      return;
    }
    syncing = true;
    caretField.setText(shown(offset));
    syncing = false;
  }

  private void caretEdited() {
    if (syncing) {
      return;
    }
    long target = TimeText.parseTarget(caretField.getText(), zone, date);
    if (target == TimeText.INVALID) {
      ok.setEnabled(false);
      return;
    }
    try {
      long offset = Math.subtractExact(target, caret);
      syncing = true;
      offsetField.setText(TimeText.offset(offset));
      syncing = false;
      ok.setEnabled(offset() != TimeText.INVALID);
    } catch (ArithmeticException e) {
      ok.setEnabled(false);
    }
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    offsetField.setFocus();
    offsetField.selectAll();
  }

  Shell shell() {
    return shell;
  }

  Text offsetField() {
    return offsetField;
  }

  Text caretField() {
    return caretField;
  }

  Button ok() {
    return ok;
  }
}
