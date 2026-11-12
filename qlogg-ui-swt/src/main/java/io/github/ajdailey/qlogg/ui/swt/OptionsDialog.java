package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.Binding;
import io.github.ajdailey.qlogg.app.command.CommandGroup;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.command.ShortcutOverrides;
import io.github.ajdailey.qlogg.app.config.FoldRulesText;
import io.github.ajdailey.qlogg.app.config.FollowSettings;
import io.github.ajdailey.qlogg.app.config.FontSettings;
import io.github.ajdailey.qlogg.app.config.Options;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.ViewOptions;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.SearchHistory;
import io.github.ajdailey.qlogg.app.theme.ColorScheme;
import io.github.ajdailey.qlogg.app.theme.ColorSchemes;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Tools > Options: General, View, File, Shortcuts and Advanced tabs over the settings. OK and Apply
 * hand the edited settings to the controller, which saves them and applies them at once, except the
 * result limit, read at the next start. The Shortcuts tab edits overrides on top of the default
 * keymap: a key field records one or two strokes, Add refuses keys another command holds, Default
 * returns the selected command to its defaults and Restore All Defaults drops every override.
 * Modeless; it returns at once.
 */
final class OptionsDialog {
  private final Shell shell;
  private final ApplicationController controller;
  private final Keymap defaults = Keymap.defaults();
  private final List<CommandId> rows = new ArrayList<CommandId>();
  private final List<KeyStroke> captured = new ArrayList<KeyStroke>();
  private final FontSettings initialFont;
  private final int platformSize;
  private ShortcutOverrides overrides;
  private Button loadSession;
  private Spinner recentCount;
  private Spinner historySize;
  private Button runOnAdd;
  private Button quickIncremental;
  private Button quickRegex;
  private Button followOnLoad;
  private Button followOnOverscroll;
  private Combo fontFamily;
  private Spinner fontSize;
  private Button bold;
  private Button antialias;
  private Combo encoding;
  private Button watch;
  private Spinner poll;
  private Spinner maxResults;
  // Keep file closed.
  private Button keepClosed;
  private Text filter;
  private Table commands;
  private org.eclipse.swt.widgets.List keys;
  private Text capture;
  private Label message;
  private TabFolder folder;
  // Folding tab.
  private Text foldStart;
  private Text foldContinuation;
  private Label foldError;
  // View colors.
  private Combo viewColors;
  private final List<String> colorIds = new ArrayList<String>();
  // Last tab.
  private Button closeWindowWithLastTab;

  OptionsDialog(Shell parent, ApplicationController controller) {
    this.controller = controller;
    Preferences preferences = controller.preferences();
    this.overrides = preferences.options().shortcuts();
    this.initialFont = preferences.options().font();
    this.platformSize = Dialogs.monospaceSize();
    shell = Dialogs.shell(parent, CommandId.OPTIONS.title(), false);
    folder = new TabFolder(shell, SWT.TOP);
    folder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    general(tab(folder, UiText.OPTIONS_TAB_GENERAL), preferences);
    view(tab(folder, UiText.OPTIONS_TAB_VIEW));
    file(tab(folder, UiText.OPTIONS_TAB_FILE), preferences.options());
    shortcuts(tab(folder, UiText.OPTIONS_TAB_SHORTCUTS));
    advanced(tab(folder, UiText.OPTIONS_TAB_ADVANCED), preferences);
    folding(tab(folder, UiText.OPTIONS_TAB_FOLDING), preferences.view().folds());
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Button ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                if (apply()) {
                  shell.dispose();
                }
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    Dialogs.button(
        buttons,
        UiText.BUTTON_APPLY.text(),
        new Runnable() {
          @Override
          public void run() {
            apply();
          }
        });
    shell.setDefaultButton(ok);
  }

  private static Composite tab(TabFolder folder, UiText title) {
    TabItem item = new TabItem(folder, SWT.NONE);
    item.setText(title.text());
    Composite page = new Composite(folder, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 10;
    layout.marginHeight = 10;
    page.setLayout(layout);
    item.setControl(page);
    return page;
  }

  private static Button check(Composite page, UiText label, boolean value) {
    Button button = SwtThemeService.check(page);
    button.setText(label.text());
    button.setSelection(value);
    button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    return button;
  }

  private static Spinner spinner(Composite page, UiText label, int min, int max, int value) {
    new Label(page, SWT.NONE).setText(label.text());
    Spinner spinner = new Spinner(page, SWT.BORDER);
    spinner.setValues(Math.max(min, Math.min(max, value)), min, max, 0, 1, 10);
    spinner.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    return spinner;
  }

  private static void note(Composite page, UiText text) {
    Label label = new Label(page, SWT.WRAP);
    label.setText(text.text());
    label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
  }

  private void general(Composite page, Preferences preferences) {
    loadSession = check(page, UiText.OPTIONS_LOAD_SESSION, preferences.options().loadLastSession());
    recentCount =
        spinner(
            page,
            UiText.OPTIONS_RECENT_COUNT,
            1,
            Options.MAX_RECENT,
            preferences.options().recentCount());
    historySize =
        spinner(
            page,
            UiText.OPTIONS_HISTORY_SIZE,
            1,
            SearchHistory.MAX_SIZE,
            preferences.historySize());
    runOnAdd = check(page, UiText.OPTIONS_RUN_ON_ADD, preferences.runSearchOnAdd());
    quickIncremental =
        check(page, UiText.OPTIONS_QUICK_INCREMENTAL, preferences.quickFindIncremental());
    quickRegex = check(page, UiText.OPTIONS_QUICK_REGEX, preferences.quickFindRegex());
    followOnLoad = check(page, UiText.OPTIONS_FOLLOW_ON_LOAD, preferences.follow().onLoad());
    followOnOverscroll =
        check(page, UiText.OPTIONS_FOLLOW_ON_OVERSCROLL, preferences.follow().onOverscroll());
    // Last tab.
    closeWindowWithLastTab =
        check(
            page,
            UiText.OPTIONS_CLOSE_WINDOW_WITH_LAST_TAB,
            preferences.options().closeWindowWithLastTab());
  }

  private void view(Composite page) {
    new Label(page, SWT.NONE).setText(UiText.OPTIONS_FONT_FAMILY.text());
    fontFamily = new Combo(page, SWT.DROP_DOWN);
    fontFamily.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    fontFamily.setTextLimit(FontSettings.MAX_FAMILY_CHARS);
    fontFamily.add(UiText.OPTIONS_FONT_DEFAULT.text());
    TreeSet<String> families = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    for (FontData data : shell.getDisplay().getFontList(null, true)) {
      families.add(data.getName());
    }
    for (String family : families) {
      fontFamily.add(family);
    }
    if (initialFont.family().isEmpty()) {
      fontFamily.select(0);
    } else {
      fontFamily.setText(initialFont.family());
    }
    fontSize =
        spinner(
            page,
            UiText.OPTIONS_FONT_SIZE,
            FontSettings.MIN_SIZE,
            FontSettings.MAX_SIZE,
            initialFont.effectiveSize(platformSize));
    bold = check(page, UiText.OPTIONS_FONT_BOLD, initialFont.bold());
    antialias = check(page, UiText.OPTIONS_FONT_ANTIALIAS, initialFont.antialias());
    // View colors.
    viewColors(page);
  }

  private void file(Composite page, Options options) {
    new Label(page, SWT.NONE).setText(UiText.OPTIONS_ENCODING.text());
    encoding = new Combo(page, SWT.READ_ONLY | SWT.DROP_DOWN);
    encoding.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    for (String name : Charset.availableCharsets().keySet()) {
      encoding.add(name);
    }
    encoding.setText(options.defaultEncoding());
    watch = check(page, UiText.OPTIONS_WATCH, options.useWatchService());
    poll =
        spinner(
            page,
            UiText.OPTIONS_POLL,
            Options.MIN_POLL_MILLIS,
            Options.MAX_POLL_MILLIS,
            options.pollMillis());
  }

  private void advanced(Composite page, Preferences preferences) {
    maxResults =
        spinner(
            page,
            UiText.OPTIONS_MAX_RESULTS,
            0,
            Integer.MAX_VALUE,
            (int) Math.min(Integer.MAX_VALUE, preferences.maxResults()));
    note(page, UiText.OPTIONS_NEXT_START);
    // Keep file closed.
    keepClosed = check(page, UiText.OPTIONS_KEEP_CLOSED, preferences.follow().keepClosed());
    note(page, UiText.OPTIONS_KEEP_CLOSED_NOTE);
  }

  private void shortcuts(Composite page) {
    page.setLayout(new GridLayout(1, false));
    filter = new Text(page, SWT.SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
    filter.setMessage(UiText.SHORTCUTS_FILTER.text());
    filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    commands = new Table(page, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
    commands.setHeaderVisible(true);
    commands.setLinesVisible(true);
    GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
    tableData.heightHint = 220;
    commands.setLayoutData(tableData);
    for (UiText title :
        new UiText[] {UiText.SHORTCUTS_GROUP, UiText.SHORTCUTS_COMMAND, UiText.SHORTCUTS_KEYS}) {
      new TableColumn(commands, SWT.LEFT).setText(title.text());
    }
    Composite editor = new Composite(page, SWT.NONE);
    editor.setLayout(new GridLayout(4, false));
    editor.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    keys = new org.eclipse.swt.widgets.List(editor, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
    GridData keysData = new GridData(SWT.FILL, SWT.FILL, true, false, 1, 3);
    keysData.heightHint = 72;
    keys.setLayoutData(keysData);
    capture = new Text(editor, SWT.BORDER | SWT.READ_ONLY);
    capture.setMessage(UiText.SHORTCUTS_CAPTURE.text());
    GridData captureData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    captureData.widthHint = 160;
    capture.setLayoutData(captureData);
    Button clear =
        Dialogs.button(
            editor,
            UiText.BUTTON_CLEAR.text(),
            new Runnable() {
              @Override
              public void run() {
                clearCaptured();
              }
            });
    clear.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    Dialogs.button(
            editor,
            UiText.BUTTON_ADD.text(),
            new Runnable() {
              @Override
              public void run() {
                addCaptured();
              }
            })
        .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    Dialogs.button(
            editor,
            UiText.BUTTON_REMOVE.text(),
            new Runnable() {
              @Override
              public void run() {
                removeSelected();
              }
            })
        .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    Dialogs.button(
            editor,
            UiText.SHORTCUTS_RESET.text(),
            new Runnable() {
              @Override
              public void run() {
                CommandId command = selected();
                if (command != null) {
                  setOverrides(overrides.without(command));
                }
              }
            })
        .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    Dialogs.button(
            editor,
            UiText.SHORTCUTS_RESET_ALL.text(),
            new Runnable() {
              @Override
              public void run() {
                setOverrides(ShortcutOverrides.NONE);
              }
            })
        .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false, 3, 1));
    message = new Label(page, SWT.WRAP);
    message.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    capture.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            KeyStroke stroke = SwtKeys.stroke(event.stateMask, event.keyCode, event.character);
            if (stroke == null) {
              return;
            }
            event.doit = false;
            record(stroke);
          }
        });
    capture.addListener(
        SWT.Traverse,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.detail == SWT.TRAVERSE_ESCAPE || event.detail == SWT.TRAVERSE_RETURN) {
              event.doit = false;
            }
          }
        });
    filter.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fill();
          }
        });
    commands.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            showKeys();
          }
        });
    fill();
    for (TableColumn column : commands.getColumns()) {
      column.pack();
    }
  }

  /** Empties the key field. */
  void clearCaptured() {
    captured.clear();
    capture.setText("");
  }

  /** Records a stroke: up to two make a sequence, and a third starts over. */
  void record(KeyStroke stroke) {
    if (captured.size() == 2) {
      captured.clear();
    }
    captured.add(stroke);
    StringBuilder text = new StringBuilder();
    for (KeyStroke one : captured) {
      if (text.length() > 0) {
        text.append(' ');
      }
      text.append(one.text(SwtKeys.mac()));
    }
    capture.setText(text.toString());
  }

  private Keymap working() {
    return defaults.withOverrides(overrides);
  }

  private void setOverrides(ShortcutOverrides next) {
    overrides = next;
    message.setText("");
    fill();
  }

  /** Adds the captured keys to the selected command unless another command holds them. */
  void addCaptured() {
    CommandId command = selected();
    if (command == null || captured.isEmpty()) {
      return;
    }
    KeyStroke first = captured.get(0);
    KeyStroke second = captured.size() > 1 ? captured.get(1) : null;
    Binding candidate =
        new Binding(
            command, first, second, ShortcutOverrides.scopeFor(command, first, second, defaults));
    Keymap current = working();
    List<Binding> conflicts = current.conflicts(candidate);
    if (!conflicts.isEmpty()) {
      message.setText(
          UiText.SHORTCUTS_CONFLICT.format(
              candidate.text(SwtKeys.mac()), conflicts.get(0).command().title()));
      message.getParent().layout(true);
      return;
    }
    List<Binding> list = new ArrayList<Binding>(current.bindingsFor(command));
    if (list.contains(candidate) || list.size() >= ShortcutOverrides.MAX_PER_COMMAND) {
      return;
    }
    list.add(candidate);
    clearCaptured();
    setOverrides(overrides.with(command, list));
  }

  /** Removes the selected key binding from the selected command. */
  void removeSelected() {
    CommandId command = selected();
    int index = keys.getSelectionIndex();
    if (command == null || index < 0) {
      return;
    }
    List<Binding> list = new ArrayList<Binding>(working().bindingsFor(command));
    if (index < list.size()) {
      list.remove(index);
      setOverrides(overrides.with(command, list));
    }
  }

  private CommandId selected() {
    int index = commands.getSelectionIndex();
    return index < 0 || index >= rows.size() ? null : rows.get(index);
  }

  /** Lists every command matching the filter with its keys; an overridden one is marked. */
  private void fill() {
    CommandId keep = selected();
    commands.removeAll();
    rows.clear();
    Keymap current = working();
    boolean mac = SwtKeys.mac();
    String needle = filter.getText().toLowerCase(Locale.ROOT);
    for (CommandGroup group : CommandGroup.values()) {
      for (CommandId command : CommandId.values()) {
        if (command.group() != group) {
          continue;
        }
        String title =
            overrides.bindings(command) == null
                ? command.title()
                : UiText.SHORTCUTS_OVERRIDDEN.format(command.title());
        String[] row = {group.title(), title, current.keysText(command, mac)};
        if (!String.join(" ", row).toLowerCase(Locale.ROOT).contains(needle)) {
          continue;
        }
        new TableItem(commands, SWT.NONE).setText(row);
        rows.add(command);
        if (command == keep) {
          commands.setSelection(rows.size() - 1);
        }
      }
    }
    showKeys();
  }

  private void showKeys() {
    keys.removeAll();
    CommandId command = selected();
    if (command == null) {
      return;
    }
    boolean mac = SwtKeys.mac();
    for (Binding binding : working().bindingsFor(command)) {
      String where =
          binding.scope() == Binding.Scope.WINDOW
              ? UiText.SHORTCUTS_WINDOW.text()
              : UiText.SHORTCUTS_VIEW.text();
      keys.add(binding.text(mac) + "  (" + where + ")");
    }
  }

  // Folding tab.

  private void folding(Composite page, FoldRules rules) {
    note(page, UiText.OPTIONS_FOLD_NOTE);
    new Label(page, SWT.NONE).setText(UiText.OPTIONS_FOLD_START.text());
    foldStart = new Text(page, SWT.SINGLE | SWT.BORDER);
    foldStart.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Label label = new Label(page, SWT.WRAP);
    label.setText(UiText.OPTIONS_FOLD_CONTINUATION.text());
    label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    foldContinuation = new Text(page, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
    foldContinuation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
    foldError = new Label(page, SWT.WRAP);
    foldError.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    Composite row = Dialogs.buttonRow(page, 2);
    Dialogs.button(
        row,
        UiText.OPTIONS_FOLD_DEFAULTS.text(),
        new Runnable() {
          @Override
          public void run() {
            showFoldRules(FoldRules.DEFAULTS);
          }
        });
    showFoldRules(rules);
  }

  private void showFoldRules(FoldRules rules) {
    foldStart.setText(rules.entryStart());
    foldContinuation.setText(FoldRulesText.continuationText(rules));
    foldError.setText("");
  }

  /**
   * Hands the edited settings to the controller, unless a fold pattern is refused: then nothing is
   * applied, and the Folding tab shows the reason.
   *
   * @return false when a fold pattern was refused
   */
  boolean apply() {
    FoldRulesText folds = FoldRulesText.parse(foldStart.getText(), foldContinuation.getText());
    if (folds.rules() == null) {
      foldError.setText(folds.error());
      folder.setSelection(folder.getItemCount() - 1);
      foldError.getParent().layout(true);
      return false;
    }
    foldError.setText("");
    applyOthers();
    ViewOptions view = controller.preferences().view();
    if (!folds.rules().equals(view.folds())) {
      controller.setViewOptions(view.withFolds(folds.rules()));
    }
    return true;
  }

  /** Hands the settings of every tab but Folding to the controller. */
  private void applyOthers() {
    Preferences current = controller.preferences();
    Options old = current.options();
    String family = fontFamily.getSelectionIndex() == 0 ? "" : fontFamily.getText().strip();
    if (family.equals(UiText.OPTIONS_FONT_DEFAULT.text())) {
      family = "";
    }
    int size = fontSize.getSelection();
    boolean sizeChanged = size != initialFont.effectiveSize(platformSize);
    int optionsSize = sizeChanged ? size : initialFont.size();
    int zoom = sizeChanged || !family.equals(initialFont.family()) ? 0 : initialFont.zoom();
    FontSettings font =
        new FontSettings(family, optionsSize, zoom, bold.getSelection(), antialias.getSelection());
    String chosenEncoding = encoding.getText();
    if (Options.charset(chosenEncoding) == null) {
      chosenEncoding = old.defaultEncoding();
    }
    controller.setOptions(
        new Options(
            font,
            recentCount.getSelection(),
            poll.getSelection(),
            watch.getSelection(),
            chosenEncoding,
            loadSession.getSelection(),
            overrides,
            old.favorites(),
            old.debugLogFile(),
            closeWindowWithLastTab.getSelection()));
    controller.setSearchHistory(
        controller.preferences().searchHistory(), historySize.getSelection());
    controller.setQuickFindOptions(quickIncremental.getSelection(), quickRegex.getSelection());
    controller.setFollowSettings(
        new FollowSettings(
            followOnLoad.getSelection(),
            followOnOverscroll.getSelection(),
            keepClosed.getSelection()));
    controller.setPreferences(
        controller
            .preferences()
            .withRunSearchOnAdd(runOnAdd.getSelection())
            .withMaxResults(maxResults.getSelection()));
    // View colors.
    applyViewColors();
  }

  // View colors.

  /** Lists Follow theme, then every scheme; an id naming no scheme stays listed by its id. */
  private void viewColors(Composite page) {
    new Label(page, SWT.NONE).setText(UiText.OPTIONS_VIEW_COLORS.text());
    viewColors = new Combo(page, SWT.READ_ONLY | SWT.DROP_DOWN);
    viewColors.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    String current = controller.preferences().view().colors();
    colorIds.add(ColorSchemes.FOLLOW_THEME);
    viewColors.add(UiText.VIEW_COLORS_THEME.text());
    for (ColorScheme scheme : controller.theme().schemes().all()) {
      colorIds.add(scheme.id());
      viewColors.add(scheme.name());
    }
    if (!colorIds.contains(current)) {
      colorIds.add(current);
      viewColors.add(current);
    }
    viewColors.select(colorIds.indexOf(current));
    note(page, UiText.OPTIONS_VIEW_COLORS_NOTE);
  }

  /** Hands a changed color scheme to the controller. */
  private void applyViewColors() {
    int index = viewColors.getSelectionIndex();
    ViewOptions view = controller.preferences().view();
    if (index >= 0 && !colorIds.get(index).equals(view.colors())) {
      controller.setViewOptions(view.withColors(colorIds.get(index)));
    }
  }

  Combo viewColors() {
    return viewColors;
  }

  void open() {
    Dialogs.open(shell, 640, 520);
  }

  Shell shell() {
    return shell;
  }

  TabFolder folder() {
    return folder;
  }

  Button keepClosed() {
    return keepClosed;
  }

  Table commands() {
    return commands;
  }

  Text capture() {
    return capture;
  }

  Label message() {
    return message;
  }

  Spinner fontSize() {
    return fontSize;
  }

  Button loadSession() {
    return loadSession;
  }

  Text foldStart() {
    return foldStart;
  }

  Text foldContinuation() {
    return foldContinuation;
  }

  Label foldError() {
    return foldError;
  }

  // Last tab.
  Button closeWindowWithLastTab() {
    return closeWindowWithLastTab;
  }
}
