package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.config.ViewOptions;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.highlight.HighlightSelector;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
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
 * Highlighters > Automatic Highlighters: the ordered {@link HighlightSelector} rules in a table
 * (match kind, pattern, set) over an editor row. Add appends the editor row, Update replaces the
 * selected rule, and the rules move up and down; OK saves them, which applies to files opened
 * later. A rule whose set no longer exists is listed by its id and matches nothing.
 */
final class SelectorsDialog {
  private final Shell shell;
  private final Label hint;
  private final Table table;
  private final Combo kind;
  private final Text pattern;
  private final Combo set;
  private final List<HighlightSelector> rules = new ArrayList<HighlightSelector>();
  private final List<HighlightSet> sets;

  SelectorsDialog(Shell parent, final ApplicationController application) {
    shell = Dialogs.shell(parent, UiText.SELECTORS_TITLE.text(), true);
    HighlightSettings settings = application.highlightSettings();
    sets = settings.sets();
    rules.addAll(application.preferences().view().selectors());
    hint = Dialogs.label(shell, UiText.SELECTORS_HINT.text());
    table = new Table(shell, SWT.SINGLE | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.widthHint = 520;
    data.heightHint = 180;
    table.setLayoutData(data);
    column(UiText.SELECTORS_COLUMN_KIND, 100);
    column(UiText.SELECTORS_COLUMN_PATTERN, 260);
    column(UiText.SELECTORS_COLUMN_SET, 140);
    table.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            load(table.getSelectionIndex());
          }
        });
    Composite editor = new Composite(shell, SWT.NONE);
    GridLayout layout = new GridLayout(3, false);
    layout.marginWidth = 0;
    editor.setLayout(layout);
    editor.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    kind = new Combo(editor, SWT.READ_ONLY);
    kind.setItems(UiText.SELECTORS_BY_NAME.text(), UiText.SELECTORS_BY_FIRST_LINE.text());
    kind.select(0);
    pattern = new Text(editor, SWT.BORDER);
    pattern.setTextLimit(HighlightSelector.MAX_PATTERN_CHARS);
    pattern.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    set = new Combo(editor, SWT.READ_ONLY);
    for (HighlightSet each : sets) {
      set.add(each.name());
    }
    if (!sets.isEmpty()) {
      set.select(0);
    }
    Composite edits = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        edits,
        UiText.BUTTON_ADD.text(),
        new Runnable() {
          @Override
          public void run() {
            HighlightSelector rule = edited();
            if (rule != null && rules.size() < HighlightSelector.MAX_SELECTORS) {
              rules.add(rule);
              sync(rules.size() - 1);
            }
          }
        });
    Dialogs.button(
        edits,
        UiText.SELECTORS_UPDATE.text(),
        new Runnable() {
          @Override
          public void run() {
            int at = table.getSelectionIndex();
            HighlightSelector rule = edited();
            if (at >= 0 && rule != null) {
              rules.set(at, rule);
              sync(at);
            }
          }
        });
    Dialogs.button(
        edits,
        UiText.BUTTON_REMOVE.text(),
        new Runnable() {
          @Override
          public void run() {
            int at = table.getSelectionIndex();
            if (at >= 0) {
              rules.remove(at);
              sync(Math.min(at, rules.size() - 1));
            }
          }
        });
    Dialogs.button(
        edits,
        UiText.BUTTON_MOVE_UP.text(),
        new Runnable() {
          @Override
          public void run() {
            move(-1);
          }
        });
    Dialogs.button(
        edits,
        UiText.BUTTON_MOVE_DOWN.text(),
        new Runnable() {
          @Override
          public void run() {
            move(1);
          }
        });
    Composite buttons = Dialogs.buttonRow(shell, 1);
    shell.setDefaultButton(
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                ViewOptions view = application.preferences().view();
                List<HighlightSelector> chosen = new ArrayList<HighlightSelector>(rules);
                shell.dispose();
                application.setViewOptions(view.withSelectors(chosen));
              }
            }));
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    sync(-1);
  }

  private void column(UiText title, int width) {
    TableColumn column = new TableColumn(table, SWT.NONE);
    column.setText(title.text());
    column.setWidth(width);
  }

  /** The rule in the editor row, or null with a notice when it lacks a pattern or a set. */
  HighlightSelector edited() {
    String text = pattern.getText();
    int chosen = set.getSelectionIndex();
    if (text.isBlank() || chosen < 0) {
      hint.setText(UiText.SELECTORS_INVALID.text());
      return null;
    }
    hint.setText(UiText.SELECTORS_HINT.text());
    String id = sets.get(chosen).id();
    return kind.getSelectionIndex() == 0
        ? new HighlightSelector(text, "", id)
        : new HighlightSelector("", text, id);
  }

  private void load(int at) {
    if (at < 0 || at >= rules.size()) {
      return;
    }
    HighlightSelector rule = rules.get(at);
    kind.select(rule.byFileName() ? 0 : 1);
    pattern.setText(rule.byFileName() ? rule.fileNameGlob() : rule.firstLinePattern());
    for (int i = 0; i < sets.size(); i++) {
      if (sets.get(i).id().equals(rule.setId())) {
        set.select(i);
      }
    }
  }

  private void move(int step) {
    int at = table.getSelectionIndex();
    int to = at + step;
    if (at < 0 || to < 0 || to >= rules.size()) {
      return;
    }
    rules.add(to, rules.remove(at));
    sync(to);
  }

  private void sync(int selected) {
    table.removeAll();
    for (HighlightSelector rule : rules) {
      TableItem item = new TableItem(table, SWT.NONE);
      item.setText(
          new String[] {
            rule.byFileName()
                ? UiText.SELECTORS_BY_NAME.text()
                : UiText.SELECTORS_BY_FIRST_LINE.text(),
            rule.byFileName() ? rule.fileNameGlob() : rule.firstLinePattern(),
            setName(rule.setId())
          });
    }
    if (selected >= 0) {
      table.setSelection(selected);
    }
  }

  private String setName(String id) {
    for (HighlightSet each : sets) {
      if (each.id().equals(id)) {
        return each.name();
      }
    }
    return id;
  }

  List<HighlightSelector> rules() {
    return rules;
  }

  Combo kind() {
    return kind;
  }

  Text pattern() {
    return pattern;
  }

  Combo set() {
    return set;
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }
}
