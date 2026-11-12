package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.FolderSearchController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.folder.FileHits;
import io.github.ajdailey.qlogg.core.folder.FolderProgress;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Search > Search in Folder: a modeless window with the folder, file name globs, subfolders, search
 * text and toggles, a summary and progress bar, a table of the listed files and a table of the
 * chosen file's matching lines. Choosing a line (double-click or Enter) shows it in a tab through
 * {@link FolderSearchController#open}. Both tables are virtual. Closing the window stops the search
 * and drops its results.
 */
final class FolderSearchWindow {
  private final Shell shell;
  private final FolderSearchController search;
  private final Text folder;
  private final Text names;
  private final Button recursive;
  private final Text text;
  private final Button regex;
  private final Button matchCase;
  private final Button wholeWord;
  private final Button inverse;
  private final Button combining;
  private final Label summary;
  private final ProgressBar bar;
  private final Table files;
  private final Table lines;
  private final Button stop;
  private List<FileHits> listed = List.of();
  private FileHits shown;
  private final FolderSearchController.Listener listener =
      new FolderSearchController.Listener() {
        @Override
        public void changed() {
          sync();
        }
      };

  FolderSearchWindow(Shell parent, FolderSearchController search) {
    this.search = search;
    shell = Dialogs.shell(parent, UiText.FOLDER_TITLE.text(), false);
    Composite form = new Composite(shell, SWT.NONE);
    form.setLayout(new GridLayout(3, false));
    form.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    folder = field(form, UiText.FOLDER_FOLDER, 1);
    Button browse = new Button(form, SWT.PUSH);
    browse.setText(UiText.FOLDER_BROWSE.text());
    browse.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            browse();
          }
        });
    names = field(form, UiText.FOLDER_NAMES, 1);
    recursive = new Button(form, SWT.CHECK);
    recursive.setText(UiText.FOLDER_RECURSIVE.text());
    text = field(form, UiText.FOLDER_TEXT, 2);
    Composite toggles = new Composite(shell, SWT.NONE);
    toggles.setLayout(new RowLayout(SWT.HORIZONTAL));
    regex = check(toggles, UiText.SEARCH_REGEX);
    matchCase = check(toggles, UiText.SEARCH_MATCH_CASE);
    wholeWord = check(toggles, UiText.SEARCH_WHOLE_WORD);
    inverse = check(toggles, UiText.SEARCH_INVERSE);
    combining = check(toggles, UiText.SEARCH_COMBINE);
    summary = Dialogs.label(shell, "");
    bar = new ProgressBar(shell, SWT.HORIZONTAL);
    bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    files = table(140);
    column(files, UiText.FOLDER_COLUMN_FILE, 420, SWT.LEFT);
    column(files, UiText.FOLDER_COLUMN_MATCHES, 80, SWT.RIGHT);
    column(files, UiText.FOLDER_COLUMN_STATUS, 200, SWT.LEFT);
    lines = table(220);
    column(lines, UiText.FOLDER_COLUMN_LINE, 80, SWT.RIGHT);
    column(lines, UiText.FOLDER_COLUMN_TEXT, 620, SWT.LEFT);
    files.addListener(
        SWT.SetData,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fillFile((TableItem) event.item);
          }
        });
    files.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            show(files.getSelectionIndex());
          }
        });
    lines.addListener(
        SWT.SetData,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fillLine((TableItem) event.item);
          }
        });
    lines.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            openLine(lines.getSelectionIndex());
          }
        });
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Button run =
        Dialogs.button(
            buttons,
            UiText.BUTTON_SEARCH.text(),
            new Runnable() {
              @Override
              public void run() {
                FolderSearchWindow.this.run();
              }
            });
    stop =
        Dialogs.button(
            buttons,
            UiText.BUTTON_STOP.text(),
            new Runnable() {
              @Override
              public void run() {
                FolderSearchWindow.this.search.cancel();
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell));
    shell.setDefaultButton(run);
    load(search.request());
    search.addListener(listener);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            FolderSearchWindow.this.search.removeListener(listener);
            FolderSearchWindow.this.search.clear();
          }
        });
    sync();
  }

  private static Text field(Composite form, UiText label, int span) {
    Label caption = new Label(form, SWT.NONE);
    caption.setText(label.text());
    Text field = new Text(form, SWT.BORDER | SWT.SINGLE);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false, span, 1);
    data.widthHint = 420;
    field.setLayoutData(data);
    return field;
  }

  private static Button check(Composite row, UiText label) {
    Button button = new Button(row, SWT.CHECK);
    button.setText(label.text());
    return button;
  }

  private Table table(int height) {
    Table table =
        new Table(shell, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.VIRTUAL);
    table.setHeaderVisible(true);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.heightHint = height;
    table.setLayoutData(data);
    return table;
  }

  private static void column(Table table, UiText label, int width, int align) {
    TableColumn column = new TableColumn(table, align);
    column.setText(label.text());
    column.setWidth(width);
  }

  /** Puts a request's values in the fields. */
  private void load(FolderRequest request) {
    folder.setText(request.folder());
    names.setText(request.names());
    recursive.setSelection(request.recursive());
    text.setText(request.text());
    SearchOptions options = request.options();
    regex.setSelection(options.mode() == MatchMode.REGEX);
    matchCase.setSelection(options.caseSensitive());
    wholeWord.setSelection(options.wholeWord());
    inverse.setSelection(options.inverse());
    combining.setSelection(options.combining());
  }

  /** Runs the search the fields describe. */
  void run() {
    SearchOptions options =
        search
            .request()
            .options()
            .withMode(regex.getSelection() ? MatchMode.REGEX : MatchMode.LITERAL)
            .withCaseSensitive(matchCase.getSelection())
            .withWholeWord(wholeWord.getSelection())
            .withInverse(inverse.getSelection())
            .withCombining(combining.getSelection());
    shown = null;
    lines.setItemCount(0);
    search.run(
        new FolderRequest(
            folder.getText(), names.getText(), recursive.getSelection(), text.getText(), options));
  }

  private void browse() {
    DirectoryDialog dialog = new DirectoryDialog(shell);
    dialog.setFilterPath(folder.getText());
    String chosen = dialog.open();
    if (chosen != null && !folder.isDisposed()) {
      folder.setText(chosen);
    }
  }

  /** Rewrites the summary, the progress bar and the file rows; keeps the shown file's lines. */
  void sync() {
    if (shell.isDisposed()) {
      return;
    }
    FolderSearchState state = search.state();
    listed = search.files();
    summary.setText(summary(state, search.request().text(), listed, search.limited()));
    bar.setSelection(
        state.phase() == FolderSearchState.Phase.DONE ? 100 : state.progress().percent());
    stop.setEnabled(state.phase() == FolderSearchState.Phase.RUNNING);
    files.setItemCount(listed.size());
    files.clearAll();
    if (shown != null && !listed.contains(shown)) {
      shown = null;
      lines.setItemCount(0);
    }
  }

  /** The summary line for a state. */
  static String summary(
      FolderSearchState state, String searched, List<FileHits> files, boolean limited) {
    long matches = 0;
    long withMatches = 0;
    for (FileHits file : files) {
      matches += file.matches();
      if (file.matches() > 0) {
        withMatches++;
      }
    }
    FolderProgress progress = state.progress();
    return switch (state.phase()) {
      case IDLE -> UiText.FOLDER_IDLE.text();
      case RUNNING -> {
        if (progress.filesTotal() < 0) {
          yield UiText.FOLDER_WALKING.text();
        }
        Path file = progress.file() == null ? null : progress.file().getFileName();
        yield UiText.FOLDER_RUNNING.format(
            file == null ? "" : file.toString(),
            Math.min(progress.filesDone() + 1, progress.filesTotal()),
            progress.filesTotal(),
            progress.percent(),
            progress.matches());
      }
      case DONE ->
          limited
              ? UiText.FOLDER_LIMITED.format(searched, matches)
              : UiText.FOLDER_DONE.format(searched, matches, withMatches, files.size());
      case CANCELED -> UiText.FOLDER_STOPPED.format(searched, matches, withMatches);
      case FAILED -> state.message();
    };
  }

  /** A file row's status column. */
  static String status(FileHits file) {
    return switch (file.status()) {
      case SEARCHED ->
          file.complete()
              ? UiText.FOLDER_STATUS_DONE.text()
              : UiText.FOLDER_STATUS_PARTIAL.format(file.size());
      case UNREADABLE -> UiText.FOLDER_STATUS_UNREADABLE.format(String.valueOf(file.detail()));
      case NOT_TEXT -> UiText.FOLDER_STATUS_NOT_TEXT.text();
      case FAILED -> UiText.FOLDER_STATUS_FAILED.format(String.valueOf(file.detail()));
    };
  }

  private void fillFile(TableItem item) {
    int i = files.indexOf(item);
    if (i < 0 || i >= listed.size()) {
      return;
    }
    FileHits file = listed.get(i);
    item.setText(
        new String[] {
          file.path().toString(),
          file.status() == FileHits.Status.SEARCHED ? String.valueOf(file.matches()) : "",
          status(file)
        });
  }

  /** Lists a file's matching lines. */
  void show(int index) {
    if (index < 0 || index >= listed.size()) {
      return;
    }
    shown = listed.get(index);
    lines.setItemCount(shown.size());
    lines.clearAll();
  }

  private void fillLine(TableItem item) {
    int i = lines.indexOf(item);
    if (shown == null || i < 0 || i >= shown.size()) {
      return;
    }
    item.setText(
        new String[] {String.valueOf(shown.line(i) + 1), shown.text(i).replace('\t', ' ')});
  }

  /** Shows a listed line of the chosen file in a tab. */
  void openLine(int index) {
    if (shown != null && index >= 0 && index < shown.size()) {
      search.open(shown, index);
    }
  }

  Table files() {
    return files;
  }

  Table lines() {
    return lines;
  }

  Label summary() {
    return summary;
  }

  Text folder() {
    return folder;
  }

  Text names() {
    return names;
  }

  Text text() {
    return text;
  }

  Button recursive() {
    return recursive;
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
