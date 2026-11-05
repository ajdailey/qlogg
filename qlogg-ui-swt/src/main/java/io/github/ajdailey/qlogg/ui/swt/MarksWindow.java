package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.MarksListController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Tools > Marks List: a modeless window listing the shown tab's marks from a {@link
 * MarksListController}, one virtual table row per mark with its line number, comment and line text.
 * {@code Enter} or a double click goes to the line, {@code Delete} removes the selected marks, and
 * {@code F2} edits the selected row's comment in place ({@code Enter} or leaving the field saves
 * it, {@code Esc} drops the edit).
 */
final class MarksWindow {
  /** The comment column. */
  static final int COMMENT_COLUMN = 1;

  private final Shell shell;
  private final Label summary;
  private final Table table;
  private final TableEditor editor;
  private final MarksListController marks;
  private Text field;

  MarksWindow(Shell parent, ApplicationController application) {
    shell = Dialogs.shell(parent, UiText.MARKS_TITLE.text(), false);
    summary = Dialogs.label(shell, "");
    table =
        new Table(shell, SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.VIRTUAL);
    table.setHeaderVisible(true);
    column(UiText.MARKS_COLUMN_LINE, 80, SWT.RIGHT);
    column(UiText.MARKS_COLUMN_COMMENT, 200, SWT.LEFT);
    column(UiText.MARKS_COLUMN_TEXT, 420, SWT.LEFT);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.widthHint = 720;
    data.heightHint = 280;
    table.setLayoutData(data);
    editor = new TableEditor(table);
    editor.grabHorizontal = true;
    marks =
        new MarksListController(
            application,
            new MarksListController.Listener() {
              @Override
              public void rowsChanged() {
                sync();
              }
            });
    table.addListener(
        SWT.SetData,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fill((TableItem) event.item);
          }
        });
    table.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            goTo();
          }
        });
    table.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.keyCode == SWT.DEL) {
              remove();
            } else if (event.keyCode == SWT.F2) {
              editComment();
            }
          }
        });
    Composite buttons = Dialogs.buttonRow(shell, 4);
    Dialogs.button(
        buttons,
        UiText.MARKS_GO_TO.text(),
        new Runnable() {
          @Override
          public void run() {
            goTo();
          }
        });
    Dialogs.button(
        buttons,
        UiText.MARKS_EDIT_COMMENT.text(),
        new Runnable() {
          @Override
          public void run() {
            editComment();
          }
        });
    Dialogs.button(
        buttons,
        UiText.MARKS_REMOVE.text(),
        new Runnable() {
          @Override
          public void run() {
            remove();
          }
        });
    Dialogs.button(buttons, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell));
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            marks.close();
          }
        });
    sync();
  }

  private void column(UiText title, int width, int alignment) {
    TableColumn column = new TableColumn(table, alignment);
    column.setText(title.text());
    column.setWidth(width);
  }

  /** Rewrites the summary and the row count; rows fill as they show. */
  void sync() {
    if (marks == null || shell.isDisposed()) {
      return;
    }
    List<MarksListController.Row> rows = marks.rows();
    if (marks.document() == null) {
      summary.setText(UiText.MARKS_NO_FILE.text());
    } else {
      String title = marks.document().state().title();
      summary.setText(
          rows.size() < marks.total()
              ? UiText.MARKS_SUMMARY_PARTIAL.format(title, rows.size(), marks.total())
              : UiText.MARKS_SUMMARY.format(title, marks.total()));
    }
    summary.requestLayout();
    table.setItemCount(rows.size());
    table.clearAll();
  }

  private void fill(TableItem item) {
    int index = table.indexOf(item);
    List<MarksListController.Row> rows = marks.rows();
    if (index < 0 || index >= rows.size()) {
      return;
    }
    MarksListController.Row row = rows.get(index);
    item.setText(new String[] {Long.toString(row.line() + 1), row.comment(), row.text()});
  }

  /** Returns the selected rows' lines, ascending. */
  private long[] selectedLines() {
    int[] indices = table.getSelectionIndices();
    List<MarksListController.Row> rows = marks.rows();
    long[] lines = new long[indices.length];
    int n = 0;
    for (int index : indices) {
      if (index < rows.size()) {
        lines[n++] = rows.get(index).line();
      }
    }
    long[] out = new long[n];
    System.arraycopy(lines, 0, out, 0, n);
    return out;
  }

  private void goTo() {
    long[] lines = selectedLines();
    if (lines.length > 0) {
      marks.goTo(lines[0]);
    }
  }

  private void remove() {
    long[] lines = selectedLines();
    if (lines.length > 0) {
      marks.remove(lines);
    }
  }

  /** Opens a field over the selected row's comment; the row's line is kept for the save. */
  void editComment() {
    int index = table.getSelectionIndex();
    List<MarksListController.Row> rows = marks.rows();
    if (index < 0 || index >= rows.size()) {
      return;
    }
    closeField();
    final long line = rows.get(index).line();
    final Text text = new Text(table, SWT.SINGLE);
    text.setTextLimit(MarkComments.MAX_LENGTH);
    text.setText(rows.get(index).comment());
    text.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            save(text, line);
          }
        });
    text.addListener(
        SWT.FocusOut,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            save(text, line);
          }
        });
    text.addListener(
        SWT.Traverse,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.detail == SWT.TRAVERSE_ESCAPE) {
              event.doit = false;
              closeField();
              table.setFocus();
            }
          }
        });
    field = text;
    editor.setEditor(text, table.getItem(index), COMMENT_COLUMN);
    text.selectAll();
    text.setFocus();
  }

  private void save(Text text, long line) {
    if (text != field || text.isDisposed()) {
      return;
    }
    String comment = text.getText();
    closeField();
    marks.setComment(line, comment);
  }

  private void closeField() {
    Text open = field;
    field = null;
    editor.setEditor(null);
    if (open != null && !open.isDisposed()) {
      open.dispose();
    }
  }

  Table table() {
    return table;
  }

  Label summary() {
    return summary;
  }

  Text field() {
    return field;
  }

  MarksListController marks() {
    return marks;
  }

  boolean isDisposed() {
    return shell.isDisposed();
  }

  void open() {
    sync();
    if (shell.isVisible()) {
      shell.setActive();
      return;
    }
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }
}
