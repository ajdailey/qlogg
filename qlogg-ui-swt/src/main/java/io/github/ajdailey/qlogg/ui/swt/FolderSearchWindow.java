package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.Binding;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.controller.FolderResultSet;
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
import java.util.function.Supplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
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
 * Search > Search in Folder: a modeless window with the folder, file name and exclude globs,
 * subfolders, search text and toggles, a summary and progress bar, the kept sets with Pin, Clear
 * and Clear All, a table of the shown set's files and a table of the chosen file's matching lines.
 * Choosing a line (double-click or Enter) shows it in a tab through {@link
 * FolderSearchController#open}; the step keys of {@link CommandId#NEXT_FOLDER_MATCH} and {@link
 * CommandId#PREVIOUS_FOLDER_MATCH} work in the window too. Both tables are virtual. Closing the
 * window stops the search; the sets stay with the controller.
 */
final class FolderSearchWindow {
  private final Shell shell;
  private final FolderSearchController search;
  private final Text folder;
  private final Text names;
  private final Text exclude;
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
  private final Combo setList;
  private final Button pin;
  private final Button clear;
  private final Button clearAll;
  private List<FolderResultSet> kept = List.of();
  private FolderResultSet shownSet;
  private List<FileHits> listed = List.of();
  private FileHits shown;
  private final FolderSearchController.Listener listener =
      new FolderSearchController.Listener() {
        @Override
        public void changed() {
          sync();
        }
      };

  FolderSearchWindow(Shell parent, FolderSearchController search, Supplier<Keymap> keymap) {
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
    exclude = field(form, UiText.FOLDER_EXCLUDE, 2);
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
    Composite setRow = new Composite(shell, SWT.NONE);
    GridLayout setLayout = new GridLayout(5, false);
    setLayout.marginWidth = 0;
    setRow.setLayout(setLayout);
    setRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    new Label(setRow, SWT.NONE).setText(UiText.FOLDER_SETS.text());
    setList = new Combo(setRow, SWT.READ_ONLY | SWT.DROP_DOWN);
    setList.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    setList.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            int i = setList.getSelectionIndex();
            if (i >= 0 && i < kept.size()) {
              FolderSearchWindow.this.search.show(kept.get(i));
            }
          }
        });
    pin = new Button(setRow, SWT.TOGGLE);
    pin.setText(UiText.FOLDER_PIN.text());
    pin.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (shownSet != null) {
              FolderSearchWindow.this.search.togglePin(shownSet);
            }
          }
        });
    clear = new Button(setRow, SWT.PUSH);
    clear.setText(UiText.FOLDER_CLEAR.text());
    clear.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (shownSet != null) {
              FolderSearchWindow.this.search.drop(shownSet);
            }
          }
        });
    clearAll = new Button(setRow, SWT.PUSH);
    clearAll.setText(UiText.FOLDER_CLEAR_ALL.text());
    clearAll.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            FolderSearchWindow.this.search.clear();
          }
        });
    files = table(120);
    column(files, UiText.FOLDER_COLUMN_FILE, 420, SWT.LEFT);
    column(files, UiText.FOLDER_COLUMN_MATCHES, 80, SWT.RIGHT);
    column(files, UiText.FOLDER_COLUMN_STATUS, 200, SWT.LEFT);
    lines = table(200);
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
            stepFrom(files.getSelectionIndex());
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
    final Display display = shell.getDisplay();
    final Listener steps = stepKeys(keymap);
    display.addFilter(SWT.KeyDown, steps);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            display.removeFilter(SWT.KeyDown, steps);
            FolderSearchWindow.this.search.removeListener(listener);
            FolderSearchWindow.this.search.cancel();
          }
        });
    sync();
  }

  /** Runs the step commands' single-stroke keys while this window has a key event. */
  private Listener stepKeys(final Supplier<Keymap> keymap) {
    return new Listener() {
      @Override
      public void handleEvent(Event event) {
        if (!(event.widget instanceof Control control)
            || control.isDisposed()
            || control.getShell() != shell) {
          return;
        }
        KeyStroke stroke = SwtKeys.stroke(event.stateMask, event.keyCode, event.character);
        if (stroke == null) {
          return;
        }
        for (Binding binding : keymap.get().bindings()) {
          boolean step =
              binding.command() == CommandId.NEXT_FOLDER_MATCH
                  || binding.command() == CommandId.PREVIOUS_FOLDER_MATCH;
          if (step && binding.second() == null && binding.first().equals(stroke)) {
            event.type = SWT.None;
            event.doit = false;
            search.step(binding.command() == CommandId.NEXT_FOLDER_MATCH);
            return;
          }
        }
      }
    };
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
    exclude.setText(request.exclude());
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
    search.run(
        new FolderRequest(
            folder.getText(),
            names.getText(),
            recursive.getSelection(),
            text.getText(),
            options,
            exclude.getText()));
  }

  private void browse() {
    DirectoryDialog dialog = new DirectoryDialog(shell);
    dialog.setFilterPath(folder.getText());
    String chosen = dialog.open();
    if (chosen != null && !folder.isDisposed()) {
      folder.setText(chosen);
    }
  }

  /**
   * Rewrites the set list, the summary, the progress bar and the file rows; keeps the shown file's
   * lines, and selects the rows of the last line stepped to.
   */
  void sync() {
    if (shell.isDisposed()) {
      return;
    }
    syncSets();
    FolderSearchState state = search.state();
    listed = search.files();
    String searched = shownSet == null ? "" : shownSet.request().text();
    summary.setText(summary(state, searched, listed, search.limited()));
    bar.setSelection(
        state.phase() == FolderSearchState.Phase.DONE ? 100 : state.progress().percent());
    stop.setEnabled(
        shownSet != null && shownSet.state().phase() == FolderSearchState.Phase.RUNNING);
    files.setItemCount(listed.size());
    files.clearAll();
    if (shown != null && !listed.contains(shown)) {
      shown = null;
      lines.setItemCount(0);
    }
    FileHits stepped = search.stepFile();
    int at = stepped == null ? -1 : listed.indexOf(stepped);
    if (at >= 0 && search.stepHit() >= 0) {
      files.setSelection(at);
      if (stepped != shown) {
        show(at);
      }
      lines.setSelection(search.stepHit());
      lines.showSelection();
    }
  }

  /** Rewrites the set list and the set buttons; a new shown set drops the shown file's lines. */
  private void syncSets() {
    kept = search.sets();
    FolderResultSet now = search.shown();
    if (now != shownSet) {
      shownSet = now;
      shown = null;
      lines.setItemCount(0);
    }
    String[] items = new String[kept.size()];
    for (int i = 0; i < items.length; i++) {
      items[i] = setText(kept.get(i));
    }
    if (items.length != setList.getItemCount()) {
      setList.setItems(items);
    } else {
      for (int i = 0; i < items.length; i++) {
        if (!items[i].equals(setList.getItem(i))) {
          setList.setItem(i, items[i]);
        }
      }
    }
    int index = kept.indexOf(shownSet);
    if (index >= 0) {
      setList.select(index);
    } else {
      setList.deselectAll();
    }
    pin.setSelection(shownSet != null && shownSet.pinned());
    pin.setEnabled(shownSet != null);
    clear.setEnabled(shownSet != null);
    clearAll.setEnabled(!kept.isEmpty());
  }

  /** A set's line in the set list. */
  static String setText(FolderResultSet set) {
    String text =
        UiText.FOLDER_SET.format(
            set.request().text(), lastName(set.request().folder()), set.matches());
    if (set.state().phase() == FolderSearchState.Phase.RUNNING) {
      text = UiText.FOLDER_SET_RUNNING.format(text);
    }
    return set.pinned() ? UiText.FOLDER_SET_PINNED.format(text) : text;
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

  /** The last name of a folder as typed, without parsing it as a path. */
  static String lastName(String folder) {
    String trimmed = folder.strip();
    int end = trimmed.length();
    while (end > 1 && (trimmed.charAt(end - 1) == '/' || trimmed.charAt(end - 1) == '\\')) {
      end--;
    }
    int start = Math.max(trimmed.lastIndexOf('/', end - 1), trimmed.lastIndexOf('\\', end - 1));
    String name = trimmed.substring(start + 1, end);
    return name.isEmpty() ? trimmed : name;
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

  /** Starts the next step at a chosen file. */
  private void stepFrom(int index) {
    if (index >= 0 && index < listed.size()) {
      search.stepFrom(listed.get(index));
    }
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

  Text exclude() {
    return exclude;
  }

  Combo setList() {
    return setList;
  }

  Button pin() {
    return pin;
  }

  Button clear() {
    return clear;
  }

  Button clearAll() {
    return clearAll;
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
