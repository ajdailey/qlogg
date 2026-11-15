package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Window > Switch to File: the open files by full path in a one-column {@code Table} with full-row
 * selection, narrowed by a filter field that matches any part of the path, ignoring case. Enter or
 * a double click shows the chosen tab; Escape cancels. The first match is selected after each
 * filter change.
 */
final class SwitchFileDialog {
  private final Shell shell;
  private final Text filter;
  private final Table table;
  private final Button ok;
  private final ApplicationController controller;
  private final List<DocumentController> shown = new ArrayList<DocumentController>();

  SwitchFileDialog(Shell parent, ApplicationController controller) {
    this.controller = controller;
    shell = Dialogs.shell(parent, CommandId.SWITCH_FILE.title(), true);
    Dialogs.label(shell, UiText.SWITCH_FILE_FILTER.text());
    filter = new Text(shell, SWT.BORDER | SWT.SINGLE | SWT.SEARCH);
    filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    table = new Table(shell, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
    final TableColumn column = new TableColumn(table, SWT.LEFT);
    GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
    tableData.widthHint = 640;
    tableData.heightHint = 320;
    table.setLayoutData(tableData);
    Composite buttons = Dialogs.buttonRow(shell, 1);
    ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                choose();
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
    filter.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fill();
          }
        });
    table.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            column.setWidth(table.getClientArea().width);
          }
        });
    table.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            choose();
          }
        });
    filter.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            moveSelection(event);
          }
        });
    fill();
  }

  /** Up and Down in the filter field move the table's selection. */
  private void moveSelection(Event event) {
    int step = event.keyCode == SWT.ARROW_DOWN ? 1 : event.keyCode == SWT.ARROW_UP ? -1 : 0;
    if (step == 0 || table.getItemCount() == 0) {
      return;
    }
    event.doit = false;
    int next = Math.max(0, Math.min(table.getItemCount() - 1, table.getSelectionIndex() + step));
    table.setSelection(next);
  }

  private void fill() {
    String needle = filter.getText().toLowerCase(Locale.ROOT);
    shown.clear();
    table.removeAll();
    for (DocumentController document : controller.documents()) {
      String text = label(document);
      if (text.toLowerCase(Locale.ROOT).contains(needle)) {
        shown.add(document);
        new TableItem(table, SWT.NONE).setText(text);
      }
    }
    if (!shown.isEmpty()) {
      table.setSelection(0);
    }
  }

  private String label(DocumentController document) {
    String path = document.state().path();
    return path.isEmpty() ? controller.tabTitle(document) : path;
  }

  private void choose() {
    int index = table.getSelectionIndex();
    DocumentController chosen = index < 0 ? null : shown.get(index);
    shell.dispose();
    if (chosen != null) {
      controller.activate(chosen);
    }
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    filter.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Text filter() {
    return filter;
  }

  Table table() {
    return table;
  }

  Button ok() {
    return ok;
  }
}
