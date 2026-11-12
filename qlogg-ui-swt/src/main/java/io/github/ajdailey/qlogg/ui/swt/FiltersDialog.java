package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.SettingsFileListener;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.FilterFile;
import io.github.ajdailey.qlogg.app.search.PredefinedFilter;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * Tools > Predefined Filters: Add and Remove at the top left, Import and Export centered, Move Up
 * and Move Down at the right; a table of Name, Pattern and Regex; then OK, Cancel and Apply. A
 * click on a selected row's name or pattern edits it in place, and a click in the Regex column
 * toggles it; an empty name or pattern is not stored. Import appends the file's filters that are
 * not in the list yet. The file is read and written on a worker by the application controller. OK
 * and Apply save the list; OK also closes.
 */
final class FiltersDialog {
  /** Column of the name. */
  static final int NAME = 0;

  /** Column of the pattern. */
  static final int PATTERN = 1;

  /** Column of the regex flag. */
  static final int REGEX = 2;

  private final Shell shell;
  private final Table table;
  private final TableEditor editor;
  private final ToolIcons icons;
  private final Label status;
  private final ApplicationController application;
  private final List<PredefinedFilter> working;

  FiltersDialog(Shell parent, final ApplicationController application) {
    this.application = application;
    this.working = new ArrayList<PredefinedFilter>(application.preferences().filters());
    shell = Dialogs.shell(parent, CommandId.PREDEFINED_FILTERS.title(), true);
    Composite top = new Composite(shell, SWT.NONE);
    GridLayout topLayout = new GridLayout(3, false);
    topLayout.marginWidth = 0;
    topLayout.marginHeight = 0;
    top.setLayout(topLayout);
    top.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    icons = new ToolIcons(top);
    ToolBar rows = new ToolBar(top, SWT.FLAT);
    rows.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, true, false));
    tool(rows, ToolIcons.ADD, UiText.BUTTON_ADD, this::add);
    tool(rows, ToolIcons.REMOVE, UiText.BUTTON_REMOVE, this::remove);
    Composite files = new Composite(top, SWT.NONE);
    GridLayout filesLayout = new GridLayout(2, true);
    filesLayout.marginWidth = 0;
    filesLayout.marginHeight = 0;
    files.setLayout(filesLayout);
    files.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
    Dialogs.button(files, UiText.BUTTON_IMPORT.text(), this::importFile);
    Dialogs.button(files, UiText.BUTTON_EXPORT.text(), this::exportFile);
    ToolBar order = new ToolBar(top, SWT.FLAT);
    order.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
    tool(order, ToolIcons.UP, UiText.BUTTON_MOVE_UP, this::moveUp);
    tool(order, ToolIcons.DOWN, UiText.BUTTON_MOVE_DOWN, this::moveDown);
    table = new Table(shell, SWT.SINGLE | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
    GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
    tableData.widthHint = 600;
    tableData.heightHint = 360;
    table.setLayoutData(tableData);
    column(UiText.FILTER_COLUMN_NAME, 130);
    column(UiText.FILTER_COLUMN_PATTERN, 380);
    column(UiText.FILTER_COLUMN_REGEX, 70).setAlignment(SWT.CENTER);
    editor = new TableEditor(table);
    editor.grabHorizontal = true;
    status = Dialogs.label(shell, "");
    Composite buttons = Dialogs.buttonRow(shell, 1);
    shell.setDefaultButton(
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                apply();
                shell.dispose();
              }
            }));
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    Dialogs.button(buttons, UiText.BUTTON_APPLY.text(), this::apply);
    table.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            clicked(new Point(event.x, event.y));
          }
        });
    refill(working.isEmpty() ? -1 : 0);
  }

  private void tool(ToolBar bar, String icon, UiText tip, final Runnable action) {
    ToolItem item = new ToolItem(bar, SWT.PUSH);
    if (!icons.apply(item, icon)) {
      item.setText(tip.text().replace("&", ""));
    }
    item.setToolTipText(tip.text().replace("&", ""));
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            action.run();
          }
        });
  }

  private TableColumn column(UiText title, int width) {
    TableColumn column = new TableColumn(table, SWT.NONE);
    column.setText(title.text());
    column.setWidth(width);
    return column;
  }

  /** Edits a selected row's name or pattern in place, or toggles its regex flag. */
  private void clicked(Point at) {
    TableItem item = table.getItem(at);
    if (item == null) {
      return;
    }
    int row = table.indexOf(item);
    for (int column = 0; column < table.getColumnCount(); column++) {
      if (!item.getBounds(column).contains(at)) {
        continue;
      }
      if (column == REGEX) {
        toggleRegex(row);
      } else if (table.getSelectionIndex() == row) {
        edit(row, column);
      }
      return;
    }
  }

  /**
   * Flips a filter between regex and literal text.
   *
   * @param row the filter's index
   */
  void toggleRegex(int row) {
    PredefinedFilter filter = working.get(row);
    MatchMode mode = filter.mode() == MatchMode.REGEX ? MatchMode.LITERAL : MatchMode.REGEX;
    working.set(row, new PredefinedFilter(filter.name(), filter.pattern(), mode));
    show(table.getItem(row), working.get(row));
  }

  /**
   * Opens an in-place editor on a name or pattern cell.
   *
   * @param row the filter's index
   * @param column {@link #NAME} or {@link #PATTERN}
   * @return the editor field
   */
  Text edit(final int row, final int column) {
    closeEditor();
    final TableItem item = table.getItem(row);
    final Text text = new Text(table, SWT.SINGLE);
    text.setText(item.getText(column));
    text.selectAll();
    Listener done =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.type == SWT.Traverse && event.detail == SWT.TRAVERSE_ESCAPE) {
              event.doit = false;
              text.dispose();
              return;
            }
            if (event.type == SWT.FocusOut
                || (event.type == SWT.Traverse && event.detail == SWT.TRAVERSE_RETURN)) {
              if (event.type == SWT.Traverse) {
                event.doit = false;
              }
              store(row, column, text.getText());
              text.dispose();
            }
          }
        };
    text.addListener(SWT.FocusOut, done);
    text.addListener(SWT.Traverse, done);
    editor.setEditor(text, item, column);
    text.setFocus();
    return text;
  }

  private void closeEditor() {
    if (editor.getEditor() != null && !editor.getEditor().isDisposed()) {
      editor.getEditor().dispose();
    }
  }

  /** Writes an edited cell into its filter; an empty name or pattern is not stored. */
  private void store(int row, int column, String value) {
    if (row >= working.size()) {
      return;
    }
    PredefinedFilter filter = working.get(row);
    String name = column == NAME ? value.strip() : filter.name();
    String pattern = column == PATTERN ? value : filter.pattern();
    if (name.isEmpty() || pattern.isEmpty()) {
      return;
    }
    working.set(row, new PredefinedFilter(name, pattern, filter.mode()));
    show(table.getItem(row), working.get(row));
  }

  void add() {
    if (working.size() >= FilterFile.MAX_FILTERS) {
      status.setText(UiText.FILTER_FILE_TOO_MANY.format(FilterFile.MAX_FILTERS));
      return;
    }
    String label = UiText.FILTER_NEW.text();
    working.add(new PredefinedFilter(label, label, MatchMode.REGEX));
    refill(working.size() - 1);
    edit(working.size() - 1, NAME);
  }

  void remove() {
    int index = table.getSelectionIndex();
    if (index >= 0) {
      working.remove(index);
      refill(Math.min(index, working.size() - 1));
    }
  }

  void moveUp() {
    int index = table.getSelectionIndex();
    if (index > 0) {
      working.add(index - 1, working.remove(index));
      refill(index - 1);
    }
  }

  void moveDown() {
    int index = table.getSelectionIndex();
    if (index >= 0 && index < working.size() - 1) {
      working.add(index + 1, working.remove(index));
      refill(index + 1);
    }
  }

  /** Saves the list; the dialog stays open. */
  void apply() {
    closeEditor();
    application.setFilters(new ArrayList<PredefinedFilter>(working));
  }

  private void importFile() {
    Path path = choose(SWT.OPEN);
    if (path != null) {
      importFrom(path);
    }
  }

  private void exportFile() {
    Path path = choose(SWT.SAVE);
    if (path != null) {
      exportTo(path);
    }
  }

  /**
   * Imports filters from a file, appending those not in the list yet.
   *
   * @param path the file
   */
  void importFrom(Path path) {
    status.setText(UiText.FILTERS_WORKING.text());
    application.importFilters(
        path,
        new SettingsFileListener<PredefinedFilter>() {
          @Override
          public void done(List<PredefinedFilter> filters) {
            if (shell.isDisposed()) {
              return;
            }
            int added = 0;
            for (PredefinedFilter filter : filters) {
              if (!working.contains(filter) && working.size() < FilterFile.MAX_FILTERS) {
                working.add(filter);
                added++;
              }
            }
            refill(working.isEmpty() ? -1 : working.size() - 1);
            status.setText(UiText.FILTERS_IMPORTED.format(added));
          }

          @Override
          public void failed(String message) {
            showFailure(message);
          }
        });
  }

  /**
   * Exports the list to a file.
   *
   * @param path the file
   */
  void exportTo(Path path) {
    status.setText(UiText.FILTERS_WORKING.text());
    application.exportFilters(
        path,
        working,
        new SettingsFileListener<PredefinedFilter>() {
          @Override
          public void done(List<PredefinedFilter> filters) {
            if (!shell.isDisposed()) {
              status.setText(UiText.FILTERS_EXPORTED.format(filters.size()));
            }
          }

          @Override
          public void failed(String message) {
            showFailure(message);
          }
        });
  }

  private void showFailure(String message) {
    if (!shell.isDisposed()) {
      status.setText(UiText.FILTERS_FILE_FAILED.format(message));
    }
  }

  private Path choose(int style) {
    FileDialog dialog = new FileDialog(shell, style);
    dialog.setText(style == SWT.SAVE ? UiText.BUTTON_EXPORT.text() : UiText.BUTTON_IMPORT.text());
    dialog.setOverwrite(true);
    String chosen = dialog.open();
    if (chosen == null) {
      return null;
    }
    try {
      return Path.of(chosen);
    } catch (InvalidPathException e) {
      showFailure(UiText.OPEN_INVALID_PATH.format(e.getReason()));
      return null;
    }
  }

  private void refill(int selected) {
    closeEditor();
    table.removeAll();
    for (PredefinedFilter filter : working) {
      show(new TableItem(table, SWT.NONE), filter);
    }
    if (selected >= 0) {
      table.setSelection(selected);
    }
  }

  private void show(TableItem item, PredefinedFilter filter) {
    item.setText(NAME, filter.name());
    item.setText(PATTERN, filter.pattern());
    boolean regex = filter.mode() == MatchMode.REGEX;
    Image box = icons.get(regex ? ToolIcons.CHECKED : ToolIcons.UNCHECKED);
    if (box != null) {
      item.setImage(REGEX, box);
      item.setText(REGEX, "");
    } else {
      item.setText(REGEX, regex ? UiText.FILTER_COLUMN_REGEX.text() : "");
    }
  }

  /** Opens the dialog with the table focused, so no tool item starts highlighted. */
  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    table.setFocus();
  }

  Shell shell() {
    return shell;
  }

  List<PredefinedFilter> working() {
    return working;
  }

  Table table() {
    return table;
  }

  String statusText() {
    return status.getText();
  }
}
