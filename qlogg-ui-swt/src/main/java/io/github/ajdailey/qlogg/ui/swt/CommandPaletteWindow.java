package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.controller.CommandPalette;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * View > Command Palette: a modal dialog with a filter field over a table of commands, their menus
 * and keys, refilled from {@link CommandPalette#filter} on each change with the first row selected.
 * Up, Down, Page Up and Page Down in the field move the selection. Enter or a double click closes
 * the dialog and runs the row's command on the next UI turn, after the main window has its focus
 * back. A disabled command's row is gray and does not run. Escape closes the dialog.
 */
final class CommandPaletteWindow {
  /** Rows Page Up and Page Down move. */
  static final int PAGE = 10;

  private final Shell shell;
  private final CommandPalette palette;
  private final Keymap keymap;
  private final UiDispatcher ui;
  private final Text filter;
  private final Table table;
  private List<CommandPalette.Entry> entries = List.of();

  CommandPaletteWindow(Shell parent, CommandPalette palette, Keymap keymap, UiDispatcher ui) {
    this.palette = palette;
    this.keymap = keymap;
    this.ui = ui;
    shell =
        Dialogs.shell(
            parent,
            UiText.PALETTE_TITLE.text(),
            SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
    filter = new Text(shell, SWT.SEARCH | SWT.BORDER | SWT.SINGLE);
    filter.setMessage(UiText.PALETTE_FILTER.text());
    filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    table = new Table(shell, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
    GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
    tableData.widthHint = 560;
    tableData.heightHint = 320;
    table.setLayoutData(tableData);
    table.setHeaderVisible(true);
    for (UiText title :
        new UiText[] {UiText.SHORTCUTS_COMMAND, UiText.PALETTE_MENU, UiText.SHORTCUTS_KEYS}) {
      new TableColumn(table, SWT.LEFT).setText(title.text());
    }
    filter.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fill();
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
    Listener choose =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            choose();
          }
        };
    filter.addListener(SWT.DefaultSelection, choose);
    table.addListener(SWT.DefaultSelection, choose);
    fill();
    for (TableColumn column : table.getColumns()) {
      column.pack();
    }
  }

  /** Up, Down, Page Up and Page Down in the filter field move the table's selection. */
  private void moveSelection(Event event) {
    int step =
        switch (event.keyCode) {
          case SWT.ARROW_UP -> -1;
          case SWT.ARROW_DOWN -> 1;
          case SWT.PAGE_UP -> -PAGE;
          case SWT.PAGE_DOWN -> PAGE;
          default -> 0;
        };
    if (step == 0 || table.getItemCount() == 0) {
      return;
    }
    event.doit = false;
    int next = Math.max(0, Math.min(table.getItemCount() - 1, table.getSelectionIndex() + step));
    table.setSelection(next);
    table.showSelection();
  }

  /** Refills the table for the filter and selects its first row. */
  private void fill() {
    entries = palette.filter(filter.getText());
    boolean mac = SwtKeys.mac();
    Color gray = Dialogs.disabledText(shell);
    table.setRedraw(false);
    try {
      table.removeAll();
      for (CommandPalette.Entry entry : entries) {
        TableItem item = new TableItem(table, SWT.NONE);
        item.setText(
            new String[] {entry.title(), entry.menu(), keymap.keysText(entry.command(), mac)});
        if (!entry.enabled()) {
          item.setForeground(gray);
        }
      }
      if (!entries.isEmpty()) {
        table.setSelection(0);
      }
    } finally {
      table.setRedraw(true);
    }
  }

  /** Closes the dialog and runs the selected row's command when it is enabled. */
  private void choose() {
    int index = table.getSelectionIndex();
    if (index < 0 || index >= entries.size() || !entries.get(index).enabled()) {
      return;
    }
    final CommandId command = entries.get(index).command();
    shell.dispose();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            palette.run(command);
          }
        });
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
}
