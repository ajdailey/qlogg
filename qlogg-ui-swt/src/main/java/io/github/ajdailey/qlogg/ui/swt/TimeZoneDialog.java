package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * View > Timestamps > Time Zone: an editable list of the runtime's zone ids after a Default entry.
 * OK stays disabled until the text is Default or a zone {@link ZoneId#of} accepts. Enter confirms,
 * Escape cancels.
 */
final class TimeZoneDialog {
  private final Shell shell;
  private final Combo combo;
  private final Button ok;
  private final String defaultText;

  TimeZoneDialog(
      Shell parent, String current, ZoneId defaultZone, final MainView.NameListener listener) {
    defaultText = UiText.TIME_ZONE_DEFAULT.format(defaultZone.getId());
    shell = Dialogs.shell(parent, CommandId.TIME_ZONE.title(), true);
    Dialogs.label(shell, UiText.TIME_ZONE_PROMPT.text());
    combo = new Combo(shell, SWT.DROP_DOWN);
    List<String> ids = new ArrayList<String>(ZoneId.getAvailableZoneIds());
    Collections.sort(ids);
    ids.add(0, defaultText);
    combo.setItems(ids.toArray(new String[0]));
    combo.setVisibleItemCount(20);
    combo.setText(current.isEmpty() ? defaultText : current);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 320;
    combo.setLayoutData(data);
    Composite buttons = Dialogs.buttonRow(shell, 1);
    ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                String chosen = chosen();
                if (chosen != null) {
                  shell.dispose();
                  listener.nameChosen(chosen);
                }
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
    Listener check =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            ok.setEnabled(chosen() != null);
          }
        };
    combo.addListener(SWT.Modify, check);
    combo.addListener(SWT.Selection, check);
    ok.setEnabled(chosen() != null);
  }

  /** The zone id entered, empty for Default, or null when no zone has that id. */
  private String chosen() {
    String text = combo.getText().strip();
    if (text.equals(defaultText)) {
      return "";
    }
    try {
      return ZoneId.of(text).getId();
    } catch (DateTimeException e) {
      return null;
    }
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    combo.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Combo combo() {
    return combo;
  }

  Button ok() {
    return ok;
  }
}
