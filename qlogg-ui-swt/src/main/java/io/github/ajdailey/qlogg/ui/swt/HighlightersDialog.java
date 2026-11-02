package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.HighlightFileListener;
import io.github.ajdailey.qlogg.app.highlight.ColorLabel;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.highlight.MatchHighlight;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;

/**
 * Highlighters > Configure Highlighters: three tabs. Highlighter Sets lists the sets (add, remove,
 * move, rename, import, export) and the selected set's highlighters (add, remove, move) with an
 * editor for the selected one; the higher highlighter wins. Color Labels edits each label's name,
 * colors and cycle membership. Match Highlight edits the search match coloring. The dialog edits a
 * copy; OK applies and saves it. Import and export run on a worker through the application
 * controller; import adds the sets whose ids are new.
 */
final class HighlightersDialog {
  private final Shell shell;
  private final ApplicationController application;
  private final List<HighlightSet> sets;
  private final List<String> active;
  private final org.eclipse.swt.widgets.List setList;
  private final org.eclipse.swt.widgets.List ruleList;
  private final Text setName;
  private final Text pattern;
  private final Button regex;
  private final Button ignoreCase;
  private final Button wholeLine;
  private final Button enabled;
  private final ColorField foreground;
  private final ColorField background;
  private final Spinner alpha;
  private final Spinner variance;
  private final Label status;
  private final List<Text> labelNames = new ArrayList<Text>();
  private final List<ColorField> labelForegrounds = new ArrayList<ColorField>();
  private final List<ColorField> labelBackgrounds = new ArrayList<ColorField>();
  private final List<Button> labelCycles = new ArrayList<Button>();
  private final Button matchEnabled;
  private final ColorField matchColor;
  private final Spinner matchVariance;
  private boolean syncing;

  HighlightersDialog(Shell parent, final ApplicationController application) {
    this.application = application;
    HighlightSettings settings = application.highlightSettings();
    this.sets = new ArrayList<HighlightSet>(settings.sets());
    this.active = new ArrayList<String>(settings.active());
    shell = Dialogs.shell(parent, CommandId.EDIT_HIGHLIGHTERS.title(), true);
    TabFolder tabs = new TabFolder(shell, SWT.TOP);
    tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    Runnable edited =
        new Runnable() {
          @Override
          public void run() {
            ruleEdited();
          }
        };

    Composite setsTab = tab(tabs, UiText.HIGHLIGHTERS_TAB_SETS, 3);
    Composite left = column(setsTab, UiText.HIGHLIGHTERS_SETS);
    setList = list(left);
    buttons(
        left,
        new UiText[] {
          UiText.BUTTON_ADD,
          UiText.BUTTON_REMOVE,
          UiText.BUTTON_MOVE_UP,
          UiText.BUTTON_MOVE_DOWN,
          UiText.BUTTON_IMPORT,
          UiText.BUTTON_EXPORT
        },
        new Runnable[] {
          this::addSet,
          this::removeSet,
          this::setUp,
          this::setDown,
          this::importFile,
          this::exportFile
        });
    Dialogs.label(left, UiText.FILTER_NAME.text());
    setName = new Text(left, SWT.SINGLE | SWT.BORDER);
    setName.setTextLimit(HighlightSet.MAX_NAME_CHARS);
    setName.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Composite middle = column(setsTab, UiText.HIGHLIGHTERS_RULES);
    ruleList = list(middle);
    buttons(
        middle,
        new UiText[] {
          UiText.BUTTON_ADD, UiText.BUTTON_REMOVE, UiText.BUTTON_MOVE_UP, UiText.BUTTON_MOVE_DOWN
        },
        new Runnable[] {this::addRule, this::removeRule, this::ruleUp, this::ruleDown});
    Composite editor = new Composite(setsTab, SWT.NONE);
    editor.setLayout(new GridLayout(1, false));
    editor.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    Dialogs.label(editor, UiText.FILTER_PATTERN.text());
    pattern = new Text(editor, SWT.SINGLE | SWT.BORDER);
    pattern.setTextLimit(HighlightRule.MAX_PATTERN_CHARS);
    GridData patternData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    patternData.widthHint = 240;
    pattern.setLayoutData(patternData);
    regex = check(editor, UiText.SEARCH_REGEX, edited);
    ignoreCase = check(editor, UiText.RULE_IGNORE_CASE, edited);
    wholeLine = check(editor, UiText.RULE_WHOLE_LINE, edited);
    enabled = check(editor, UiText.RULE_ENABLED, edited);
    foreground = new ColorField(editor, UiText.RULE_FOREGROUND.text(), false, edited);
    background = new ColorField(editor, UiText.RULE_BACKGROUND.text(), false, edited);
    alpha = spinner(editor, UiText.RULE_ALPHA, 255, edited);
    variance = spinner(editor, UiText.RULE_VARIANCE, HighlightRule.MAX_VARIANCE, edited);

    Composite labelsTab = tab(tabs, UiText.HIGHLIGHTERS_TAB_LABELS, 4);
    for (int i = 0; i < ColorLabel.COUNT; i++) {
      ColorLabel label = settings.labels().get(i);
      Text name = new Text(labelsTab, SWT.SINGLE | SWT.BORDER);
      name.setTextLimit(ColorLabel.MAX_NAME_CHARS);
      name.setText(label.name());
      GridData nameData = new GridData(SWT.FILL, SWT.CENTER, true, false);
      nameData.widthHint = 140;
      name.setLayoutData(nameData);
      labelNames.add(name);
      ColorField fore = new ColorField(labelsTab, UiText.RULE_FOREGROUND.text(), false, nothing());
      fore.set(label.foreground());
      labelForegrounds.add(fore);
      ColorField back = new ColorField(labelsTab, UiText.RULE_BACKGROUND.text(), false, nothing());
      back.set(label.background());
      back.required();
      labelBackgrounds.add(back);
      Button cycle = new Button(labelsTab, SWT.CHECK);
      cycle.setText(UiText.LABEL_CYCLE.text());
      cycle.setSelection(label.cycle());
      labelCycles.add(cycle);
    }

    Composite matchTab = tab(tabs, UiText.HIGHLIGHTERS_TAB_MATCH, 1);
    MatchHighlight match = settings.match();
    matchEnabled = new Button(matchTab, SWT.CHECK);
    matchEnabled.setText(UiText.MATCH_ENABLED.text());
    matchEnabled.setSelection(match.enabled());
    matchColor = new ColorField(matchTab, UiText.MATCH_THEME_COLOR.text(), true, nothing());
    matchColor.set(match.color());
    matchVariance = spinner(matchTab, UiText.RULE_VARIANCE, HighlightRule.MAX_VARIANCE, nothing());
    matchVariance.setSelection(match.variance());

    status = Dialogs.label(shell, "");
    status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Composite row = Dialogs.buttonRow(shell, 1);
    shell.setDefaultButton(
        Dialogs.button(
            row,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                HighlightSettings result = result();
                shell.dispose();
                application.setHighlightSettings(result);
              }
            }));
    Dialogs.button(row, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    listen();
    refillSets(sets.isEmpty() ? -1 : 0);
  }

  private static Runnable nothing() {
    return new Runnable() {
      @Override
      public void run() {
        // Read when OK is pressed.
      }
    };
  }

  private static Composite tab(TabFolder tabs, UiText title, int columns) {
    TabItem item = new TabItem(tabs, SWT.NONE);
    item.setText(title.text());
    Composite body = new Composite(tabs, SWT.NONE);
    body.setLayout(new GridLayout(columns, false));
    item.setControl(body);
    return body;
  }

  private static Composite column(Composite parent, UiText caption) {
    Composite column = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    column.setLayout(layout);
    column.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
    Dialogs.label(column, caption.text());
    return column;
  }

  private static org.eclipse.swt.widgets.List list(Composite parent) {
    org.eclipse.swt.widgets.List list =
        new org.eclipse.swt.widgets.List(parent, SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.widthHint = 180;
    data.heightHint = 220;
    list.setLayoutData(data);
    return list;
  }

  private static void buttons(Composite parent, UiText[] labels, Runnable[] actions) {
    Composite row = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(2, true);
    layout.marginWidth = 0;
    row.setLayout(layout);
    for (int i = 0; i < labels.length; i++) {
      Button button = Dialogs.button(row, labels[i].text(), actions[i]);
      button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }
  }

  private static Button check(Composite parent, UiText label, final Runnable edited) {
    Button button = new Button(parent, SWT.CHECK);
    button.setText(label.text());
    button.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            edited.run();
          }
        });
    return button;
  }

  private static Spinner spinner(Composite parent, UiText label, int max, final Runnable edited) {
    Composite row = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 0;
    row.setLayout(layout);
    new Label(row, SWT.NONE).setText(label.text());
    Spinner spinner = new Spinner(row, SWT.BORDER);
    spinner.setValues(0, 0, max, 0, 1, 10);
    spinner.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            edited.run();
          }
        });
    return spinner;
  }

  private void listen() {
    setList.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            refillRules(0);
          }
        });
    ruleList.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            showRule();
          }
        });
    pattern.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            ruleEdited();
          }
        });
    setName.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            int index = setList.getSelectionIndex();
            if (!syncing && index >= 0) {
              sets.set(index, sets.get(index).withName(setName.getText().strip()));
              setList.setItem(index, sets.get(index).name());
            }
          }
        });
  }

  /** Writes the editor into the selected rule; a pattern that does not compile is reported. */
  private void ruleEdited() {
    int s = setList.getSelectionIndex();
    int r = ruleList.getSelectionIndex();
    if (syncing || s < 0 || r < 0) {
      return;
    }
    HighlightRule rule =
        new HighlightRule(
            pattern.getText(),
            regex.getSelection() ? MatchMode.REGEX : MatchMode.LITERAL,
            !ignoreCase.getSelection(),
            wholeLine.getSelection(),
            foreground.get(),
            background.get(),
            alpha.getSelection(),
            variance.getSelection(),
            enabled.getSelection());
    List<HighlightRule> rules = new ArrayList<HighlightRule>(sets.get(s).rules());
    rules.set(r, rule);
    sets.set(s, sets.get(s).withRules(rules));
    ruleList.setItem(r, label(rule));
    try {
      Highlighter.compile(rule);
      status.setText("");
    } catch (PatternSyntaxException e) {
      status.setText(UiText.RULE_INVALID.format(e.getDescription()));
    }
  }

  private static String label(HighlightRule rule) {
    return rule.pattern().isEmpty() ? " " : rule.pattern();
  }

  void addSet() {
    if (sets.size() >= HighlightSettings.MAX_SETS) {
      return;
    }
    sets.add(HighlightSet.create(UiText.HIGHLIGHTERS_NEW_SET.text()));
    refillSets(sets.size() - 1);
    setName.setFocus();
    setName.selectAll();
  }

  void removeSet() {
    int index = setList.getSelectionIndex();
    if (index >= 0) {
      active.remove(sets.remove(index).id());
      refillSets(Math.min(index, sets.size() - 1));
    }
  }

  void setUp() {
    int index = setList.getSelectionIndex();
    if (index > 0) {
      sets.add(index - 1, sets.remove(index));
      refillSets(index - 1);
    }
  }

  void setDown() {
    int index = setList.getSelectionIndex();
    if (index >= 0 && index < sets.size() - 1) {
      sets.add(index + 1, sets.remove(index));
      refillSets(index + 1);
    }
  }

  void addRule() {
    int s = setList.getSelectionIndex();
    if (s < 0 || sets.get(s).rules().size() >= HighlightSet.MAX_RULES) {
      return;
    }
    List<HighlightRule> rules = new ArrayList<HighlightRule>(sets.get(s).rules());
    rules.add(
        HighlightRule.matchOnly(
            UiText.HIGHLIGHTERS_NEW_RULE.text(), MatchMode.REGEX, null, Rgb.of(0xffe680)));
    sets.set(s, sets.get(s).withRules(rules));
    refillRules(rules.size() - 1);
    pattern.setFocus();
    pattern.selectAll();
  }

  void removeRule() {
    int s = setList.getSelectionIndex();
    int r = ruleList.getSelectionIndex();
    if (s >= 0 && r >= 0) {
      List<HighlightRule> rules = new ArrayList<HighlightRule>(sets.get(s).rules());
      rules.remove(r);
      sets.set(s, sets.get(s).withRules(rules));
      refillRules(Math.min(r, rules.size() - 1));
    }
  }

  void ruleUp() {
    moveRule(-1);
  }

  void ruleDown() {
    moveRule(1);
  }

  private void moveRule(int delta) {
    int s = setList.getSelectionIndex();
    int r = ruleList.getSelectionIndex();
    if (s < 0 || r < 0) {
      return;
    }
    List<HighlightRule> rules = new ArrayList<HighlightRule>(sets.get(s).rules());
    int to = r + delta;
    if (to < 0 || to >= rules.size()) {
      return;
    }
    rules.add(to, rules.remove(r));
    sets.set(s, sets.get(s).withRules(rules));
    refillRules(to);
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
   * Imports sets from a file, adding those whose ids are new.
   *
   * @param path the file
   */
  void importFrom(Path path) {
    status.setText(UiText.FILTERS_WORKING.text());
    application.importHighlights(
        path,
        new HighlightFileListener() {
          @Override
          public void done(List<HighlightSet> read) {
            if (shell.isDisposed()) {
              return;
            }
            int before = sets.size();
            List<HighlightSet> merged =
                HighlightSettings.DEFAULTS.withSets(sets).withImported(read).sets();
            sets.clear();
            sets.addAll(merged);
            refillSets(sets.isEmpty() ? -1 : sets.size() - 1);
            status.setText(UiText.HIGHLIGHTERS_IMPORTED.format(sets.size() - before));
          }

          @Override
          public void failed(String message) {
            showFailure(message);
          }
        });
  }

  /**
   * Exports the sets to a file.
   *
   * @param path the file
   */
  void exportTo(Path path) {
    status.setText(UiText.FILTERS_WORKING.text());
    application.exportHighlights(
        path,
        sets,
        new HighlightFileListener() {
          @Override
          public void done(List<HighlightSet> written) {
            if (!shell.isDisposed()) {
              status.setText(UiText.HIGHLIGHTERS_EXPORTED.format(written.size()));
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

  private void refillSets(int selected) {
    setList.removeAll();
    for (HighlightSet set : sets) {
      setList.add(set.name().isEmpty() ? " " : set.name());
    }
    if (selected >= 0) {
      setList.select(selected);
    }
    syncing = true;
    try {
      setName.setText(selected >= 0 ? sets.get(selected).name() : "");
      setName.setEnabled(selected >= 0);
    } finally {
      syncing = false;
    }
    refillRules(0);
  }

  private void refillRules(int selected) {
    ruleList.removeAll();
    int s = setList.getSelectionIndex();
    if (s >= 0) {
      syncing = true;
      try {
        setName.setText(sets.get(s).name());
      } finally {
        syncing = false;
      }
      for (HighlightRule rule : sets.get(s).rules()) {
        ruleList.add(label(rule));
      }
      if (selected >= 0 && selected < ruleList.getItemCount()) {
        ruleList.select(selected);
      }
    }
    showRule();
  }

  private void showRule() {
    int s = setList.getSelectionIndex();
    int r = ruleList.getSelectionIndex();
    boolean any = s >= 0 && r >= 0;
    syncing = true;
    try {
      HighlightRule rule = any ? sets.get(s).rules().get(r) : null;
      pattern.setText(any ? rule.pattern() : "");
      regex.setSelection(!any || rule.mode() == MatchMode.REGEX);
      ignoreCase.setSelection(any && !rule.caseSensitive());
      wholeLine.setSelection(any && rule.wholeLine());
      enabled.setSelection(!any || rule.enabled());
      foreground.set(any ? rule.foreground() : null);
      background.set(any ? rule.background() : null);
      alpha.setSelection(any ? rule.alpha() : 255);
      variance.setSelection(any ? rule.variance() : 0);
      pattern.setEnabled(any);
      regex.setEnabled(any);
      ignoreCase.setEnabled(any);
      wholeLine.setEnabled(any);
      enabled.setEnabled(any);
      foreground.setEnabled(any);
      background.setEnabled(any);
      alpha.setEnabled(any);
      variance.setEnabled(any);
    } finally {
      syncing = false;
    }
  }

  /** The edited settings; a blank label name keeps the old one. */
  HighlightSettings result() {
    HighlightSettings current = application.highlightSettings();
    List<ColorLabel> labels = new ArrayList<ColorLabel>();
    for (int i = 0; i < ColorLabel.COUNT; i++) {
      String name = labelNames.get(i).getText().strip();
      labels.add(
          new ColorLabel(
              name.isEmpty() ? current.labels().get(i).name() : name,
              labelForegrounds.get(i).get(),
              labelBackgrounds.get(i).get(),
              labelCycles.get(i).getSelection()));
    }
    MatchHighlight match =
        new MatchHighlight(
            matchEnabled.getSelection(), matchColor.get(), matchVariance.getSelection());
    return new HighlightSettings(sets, active, labels, match);
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }

  List<HighlightSet> sets() {
    return sets;
  }

  Text pattern() {
    return pattern;
  }

  String statusText() {
    return status.getText();
  }
}
