package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.SearchController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.FilterPatterns;
import io.github.ajdailey.qlogg.app.search.PredefinedFilter;
import io.github.ajdailey.qlogg.app.search.SearchHistory;
import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewListener;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.model.Visibility;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The search row at the top of the bottom pane, in this order: the filtered view's visibility
 * combo, the option toggles (match case, regex, whole word, inverse, combining, in selection,
 * auto-refresh, range), the Predefined filters menu, the pattern field with the search history, the
 * range end field (shown while range is on), the context line counts, then Clear, Search, Keep
 * results, Stop and the search status. Toggles and buttons are icon tool items whose tooltips name
 * the option and its key. The row drives the shown document's {@link SearchController} and filtered
 * view; with no document it is disabled. Enter in the field searches and Escape stops.
 *
 * <p>The field's list holds the history, most recent first. Typing completes inline with the most
 * recent entry that starts with the text, compared with the Match case toggle's rule; Backspace and
 * Delete do not complete. The filters menu checks each filter whose pattern stands in the field,
 * and checking or unchecking one rewrites the field from the checked filters and searches. The
 * field's context menu holds Save as Filter, Edit Search History and Clear Search History.
 */
public final class SearchBar {
  /** Narrowest the pattern field gets, in pixels. */
  static final int MIN_PATTERN_WIDTH = 160;

  /** Narrowest the pattern and range end fields get while both show, in pixels. */
  static final int MIN_RANGE_WIDTH = 100;

  /** Widget data key of an icon item's tooltip text. */
  private static final String TIP = "qlogg.tip";

  /** Widget data key of the command an icon item's key runs. */
  private static final String COMMAND = "qlogg.command";

  private static final UiText[] MODES = {
    UiText.VISIBILITY_MARKS_AND_MATCHES, UiText.VISIBILITY_MARKS, UiText.VISIBILITY_MATCHES
  };

  private final Composite bar;
  private Keymap keymap;
  private final ToolIcons icons;
  private final Combo visibility;
  private final Combo pattern;
  private final ToolBar filterBar;
  private final ToolItem filters;
  private final Menu filterMenu;
  private final ToolItem regex;
  private final ToolItem matchCase;
  private final ToolItem wholeWord;
  private final ToolItem inverse;
  private final ToolItem combine;
  private final ToolItem inSelection;
  private final ToolItem autoRefresh;
  private final ToolItem keepResults;
  private final ToolItem range;
  private final Combo rangeEnd;
  private final Text contextBefore;
  private final Text contextAfter;
  private final ToolItem search;
  private final ToolItem stop;
  private final ToolItem clear;
  private final Label status;
  private ApplicationController application;
  private DocumentController document;
  private SearchController controller;
  private boolean syncing;
  private boolean deleting;
  private final SearchViewListener listener =
      new SearchViewListener() {
        @Override
        public void stateChanged(SearchViewState state) {
          show(state);
          refreshHistory();
        }

        @Override
        public void resultsChanged() {
          // The count arrives with the state.
        }

        @Override
        public void optionsChanged(String text, SearchOptions options) {
          showPreferences(text, options);
        }
      };
  private final DocumentViewListener filteredListener =
      new DocumentViewListener() {
        @Override
        public void stateChanged(DocumentViewState state) {
          // The top row shows the document state.
        }

        @Override
        public void contentChanged() {
          syncVisibility();
        }
      };

  /**
   * Creates the row, disabled.
   *
   * @param parent the parent
   * @param keymap the bindings the tooltips name
   */
  public SearchBar(Composite parent, Keymap keymap) {
    this.keymap = keymap;
    bar = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(8, false);
    layout.marginHeight = 2;
    layout.marginWidth = 2;
    layout.horizontalSpacing = 4;
    bar.setLayout(layout);
    visibility = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
    for (UiText mode : MODES) {
      visibility.add(mode.text());
    }
    visibility.setToolTipText(UiText.VISIBILITY_TOOLTIP.text());
    visibility.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    ToolBar toggles = new ToolBar(bar, SWT.FLAT);
    icons = new ToolIcons(bar, toggles);
    toggles.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    matchCase =
        item(toggles, SWT.CHECK, ToolIcons.CASE, UiText.SEARCH_MATCH_CASE, CommandId.TOGGLE_CASE);
    regex = item(toggles, SWT.CHECK, ToolIcons.REGEX, UiText.SEARCH_REGEX, CommandId.TOGGLE_REGEX);
    wholeWord = item(toggles, SWT.CHECK, ToolIcons.WORD, UiText.SEARCH_WHOLE_WORD, null);
    inverse =
        item(
            toggles, SWT.CHECK, ToolIcons.INVERSE, UiText.SEARCH_INVERSE, CommandId.TOGGLE_INVERSE);
    combine =
        item(
            toggles,
            SWT.CHECK,
            ToolIcons.COMBINE,
            UiText.SEARCH_COMBINE,
            CommandId.TOGGLE_COMBINING);
    inSelection = item(toggles, SWT.CHECK, ToolIcons.SELECTION, UiText.SEARCH_IN_SELECTION, null);
    autoRefresh =
        item(
            toggles,
            SWT.CHECK,
            ToolIcons.REFRESH,
            UiText.SEARCH_AUTO_REFRESH,
            CommandId.TOGGLE_AUTO_REFRESH);
    range = item(toggles, SWT.CHECK, ToolIcons.RANGE, UiText.SEARCH_RANGE, null);
    filterBar = new ToolBar(bar, SWT.FLAT);
    filterBar.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    filters = new ToolItem(filterBar, SWT.DROP_DOWN);
    filters.setText(UiText.SEARCH_FILTERS.text());
    filters.setToolTipText(CommandId.PREDEFINED_FILTERS.title());
    filterMenu = new Menu(filterBar);
    pattern = new Combo(bar, SWT.DROP_DOWN);
    GridData patternData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    patternData.widthHint = MIN_PATTERN_WIDTH;
    patternData.minimumWidth = MIN_PATTERN_WIDTH;
    pattern.setLayoutData(patternData);
    pattern.setToolTipText(UiText.SEARCH_HINT.text());
    rangeEnd = new Combo(bar, SWT.DROP_DOWN);
    GridData endData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    endData.widthHint = MIN_RANGE_WIDTH;
    endData.minimumWidth = MIN_RANGE_WIDTH;
    rangeEnd.setLayoutData(endData);
    rangeEnd.setToolTipText(UiText.SEARCH_RANGE_END_HINT.text());
    Composite context = new Composite(bar, SWT.NONE);
    GridLayout contextLayout = new GridLayout(2, true);
    contextLayout.marginHeight = 0;
    contextLayout.marginWidth = 0;
    contextLayout.horizontalSpacing = 2;
    context.setLayout(contextLayout);
    context.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    contextBefore = countField(context, UiText.SEARCH_CONTEXT_BEFORE);
    contextAfter = countField(context, UiText.SEARCH_CONTEXT_AFTER);
    ToolBar actions = new ToolBar(bar, SWT.FLAT);
    actions.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    clear = item(actions, SWT.PUSH, ToolIcons.CLEAR, UiText.BUTTON_CLEAR, null);
    search = item(actions, SWT.PUSH, ToolIcons.SEARCH, UiText.BUTTON_SEARCH, null);
    keepResults =
        item(
            actions,
            SWT.CHECK,
            ToolIcons.KEEP,
            UiText.SEARCH_KEEP_RESULTS,
            CommandId.TOGGLE_KEEP_RESULTS);
    stop = item(actions, SWT.PUSH, ToolIcons.STOP, UiText.BUTTON_STOP, null);
    status = new Label(bar, SWT.NONE);
    GridData statusData = new GridData(SWT.FILL, SWT.CENTER, false, false);
    statusData.widthHint = 170;
    status.setLayoutData(statusData);
    showPreferences("", SearchOptions.DEFAULTS);
    listen();
    unbind();
  }

  /** Re-inks the row's icons after a theme change ({@link ToolIcons#reink}). */
  void reinkIcons() {
    icons.reink();
  }

  /**
   * Adds a field for a context line count: digits only, up to {@link SearchOptions#MAX_CONTEXT}.
   */
  private static Text countField(Composite parent, UiText tip) {
    final Text field = new Text(parent, SWT.SINGLE | SWT.BORDER | SWT.RIGHT);
    field.setTextLimit(Integer.toString(SearchOptions.MAX_CONTEXT).length());
    field.setText("0");
    field.setToolTipText(tip.text());
    field.addListener(
        SWT.Verify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            for (int i = 0; i < event.text.length(); i++) {
              if (!Character.isDigit(event.text.charAt(i))) {
                event.doit = false;
                return;
              }
            }
          }
        });
    GridData data = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
    GC gc = new GC(field);
    try {
      data.widthHint = gc.stringExtent("0000").x;
    } finally {
      gc.dispose();
    }
    field.setLayoutData(data);
    return field;
  }

  /** Parses a context count field; empty is 0, a larger value is capped. */
  private static int count(Text field) {
    String text = field.getText();
    if (text.isEmpty()) {
      return 0;
    }
    try {
      return Math.min(SearchOptions.MAX_CONTEXT, Integer.parseInt(text));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Shows the range end field while the range toggle is on; the two fields share the width. */
  private void showRangeEnd(boolean shown) {
    GridData data = (GridData) rangeEnd.getLayoutData();
    if (data.exclude == !shown) {
      return;
    }
    data.exclude = !shown;
    rangeEnd.setVisible(shown);
    GridData patternData = (GridData) pattern.getLayoutData();
    patternData.widthHint = shown ? MIN_RANGE_WIDTH : MIN_PATTERN_WIDTH;
    patternData.minimumWidth = patternData.widthHint;
    bar.layout(true);
  }

  /** Adds an icon tool item; its tooltip names the option and, when bound, its key. */
  private ToolItem item(ToolBar parent, int style, String icon, UiText tip, CommandId command) {
    ToolItem item = new ToolItem(parent, style);
    if (!icons.apply(item, icon)) {
      item.setText(tip.text());
    }
    item.setData(TIP, tip);
    item.setData(COMMAND, command);
    item.setToolTipText(toolTip(tip, command));
    return item;
  }

  // Tooltips after a shortcut change.

  private String toolTip(UiText tip, CommandId command) {
    String keys = command == null ? "" : keymap.keysText(command, SwtKeys.mac());
    return keys.isEmpty() ? tip.text() : tip.text() + " (" + keys + ")";
  }

  /**
   * Names another keymap's keys in the icon tooltips.
   *
   * @param next the bindings in effect
   */
  void setKeymap(Keymap next) {
    keymap = next;
    for (ToolItem item : keyedItems()) {
      if (!item.isDisposed()) {
        item.setToolTipText(toolTip((UiText) item.getData(TIP), (CommandId) item.getData(COMMAND)));
      }
    }
  }

  /**
   * Returns an icon item's tooltip.
   *
   * @param command the command the item runs
   * @return the tooltip, or null for a command with no item
   */
  String toolTip(CommandId command) {
    for (ToolItem item : keyedItems()) {
      if (item.getData(COMMAND) == command) {
        return item.getToolTipText();
      }
    }
    return null;
  }

  private ToolItem[] keyedItems() {
    return new ToolItem[] {matchCase, regex, inverse, combine, autoRefresh, keepResults};
  }

  /**
   * Connects the history, filters, visibility combo and field menu to the application.
   *
   * @param app the application controller
   */
  void attach(ApplicationController app) {
    this.application = app;
    pattern.setMenu(fieldMenu());
    refreshHistory();
  }

  private Menu fieldMenu() {
    Menu menu = new Menu(pattern);
    for (final CommandId command : MenuTable.SEARCH_FIELD_MENU) {
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      item.setText(command.label());
      item.setData(command);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              application.execute(command);
            }
          });
    }
    return menu;
  }

  private void listen() {
    Listener run =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            runSearch();
          }
        };
    pattern.addListener(SWT.DefaultSelection, run);
    rangeEnd.addListener(SWT.DefaultSelection, run);
    search.addListener(SWT.Selection, run);
    stop.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null) {
              controller.cancel();
            }
          }
        });
    clear.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            pattern.setText("");
            pattern.setFocus();
          }
        });
    pattern.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            deleting = event.keyCode == SWT.BS || event.keyCode == SWT.DEL;
            if (event.keyCode == SWT.ESC && controller != null) {
              controller.cancel();
            }
          }
        });
    pattern.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!syncing) {
              typed();
            }
          }
        });
    Listener toggled =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null && !syncing) {
              controller.setOptions(options());
            }
          }
        };
    for (ToolItem item :
        new ToolItem[] {
          regex, matchCase, wholeWord, inverse, combine, inSelection, autoRefresh, keepResults
        }) {
      item.addListener(SWT.Selection, toggled);
    }
    range.addListener(SWT.Selection, toggled);
    range.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            showRangeEnd(range.getSelection());
          }
        });
    rangeEnd.addListener(SWT.Modify, toggled);
    contextBefore.addListener(SWT.Modify, toggled);
    contextAfter.addListener(SWT.Modify, toggled);
    filters.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            showFilterMenu();
          }
        });
    visibility.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            visibilityChosen();
          }
        });
  }

  private void visibilityChosen() {
    int index = visibility.getSelectionIndex();
    if (index < 0 || application == null || document == null) {
      return;
    }
    CommandId command =
        switch (Visibility.values()[index]) {
          case MARKS_AND_MATCHES -> CommandId.SHOW_MARKS_AND_MATCHES;
          case MARKS -> CommandId.SHOW_MARKS;
          case MATCHES -> CommandId.SHOW_MATCHES;
        };
    application.execute(command);
  }

  /** Records the text and completes it inline from the history. */
  private void typed() {
    String text = pattern.getText();
    if (controller != null) {
      controller.setFieldText(text);
    }
    if (application == null || deleting || text.isEmpty()) {
      return;
    }
    Point selection = pattern.getSelection();
    if (selection.x != text.length() || selection.y != text.length()) {
      return;
    }
    List<String> found =
        SearchHistory.completions(
            application.preferences().searchHistory(), text, matchCase.getSelection());
    if (found.isEmpty()) {
      return;
    }
    String completed = text + found.get(0).substring(text.length());
    syncing = true;
    try {
      pattern.setText(completed);
      pattern.setSelection(new Point(text.length(), completed.length()));
    } finally {
      syncing = false;
    }
    if (controller != null) {
      controller.setFieldText(completed);
    }
  }

  private void refreshHistory() {
    if (application == null || pattern.isDisposed()) {
      return;
    }
    List<String> history = application.preferences().searchHistory();
    String[] items = history.toArray(new String[0]);
    String[] current = pattern.getItems();
    if (Arrays.equals(items, current)) {
      return;
    }
    String text = pattern.getText();
    Point selection = pattern.getSelection();
    String end = rangeEnd.getText();
    syncing = true;
    try {
      pattern.setItems(items);
      if (!pattern.getText().equals(text)) {
        pattern.setText(text);
        pattern.setSelection(selection);
      }
      rangeEnd.setItems(items);
      if (!rangeEnd.getText().equals(end)) {
        rangeEnd.setText(end);
      }
    } finally {
      syncing = false;
    }
  }

  private void showFilterMenu() {
    if (application == null) {
      return;
    }
    for (MenuItem item : filterMenu.getItems()) {
      item.dispose();
    }
    final List<PredefinedFilter> all = application.preferences().filters();
    boolean combining = combine.getSelection();
    boolean[] present = FilterPatterns.present(pattern.getText(), all, combining);
    for (int i = 0; i < all.size(); i++) {
      final int index = i;
      MenuItem item = new MenuItem(filterMenu, SWT.CHECK);
      item.setText(all.get(i).name());
      item.setSelection(present[i]);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              filterToggled(all, index);
            }
          });
    }
    if (!all.isEmpty()) {
      new MenuItem(filterMenu, SWT.SEPARATOR);
    }
    MenuItem edit = new MenuItem(filterMenu, SWT.PUSH);
    edit.setText(CommandId.PREDEFINED_FILTERS.label());
    edit.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            application.execute(CommandId.PREDEFINED_FILTERS);
          }
        });
    Rectangle bounds = filters.getBounds();
    Point at = filterBar.toDisplay(bounds.x, bounds.y + bounds.height);
    filterMenu.setLocation(at);
    filterMenu.setVisible(true);
  }

  /**
   * Checks or unchecks one filter, rewrites the field from the checked filters and searches.
   *
   * @param all the filters, in menu order
   * @param index the filter toggled
   */
  void filterToggled(List<PredefinedFilter> all, int index) {
    boolean combining = combine.getSelection();
    boolean[] present = FilterPatterns.present(pattern.getText(), all, combining);
    present[index] = !present[index];
    List<PredefinedFilter> checked = new ArrayList<PredefinedFilter>();
    for (int i = 0; i < all.size(); i++) {
      if (present[i]) {
        checked.add(all.get(i));
      }
    }
    SearchOptions next = options().withMode(MatchMode.REGEX);
    showPreferences(FilterPatterns.compose(checked, combining), next);
    if (controller != null) {
      controller.setText(pattern.getText(), next);
      runSearch();
    }
  }

  /**
   * Returns the row.
   *
   * @return the composite
   */
  public Composite control() {
    return bar;
  }

  /**
   * Returns the pattern field.
   *
   * @return the field
   */
  Combo pattern() {
    return pattern;
  }

  /**
   * Returns the Search button.
   *
   * @return the tool item
   */
  ToolItem searchButton() {
    return search;
  }

  /**
   * Returns the Clear button.
   *
   * @return the tool item
   */
  ToolItem clearButton() {
    return clear;
  }

  /**
   * Returns the visibility combo.
   *
   * @return the combo; its index is the {@link Visibility} ordinal
   */
  Combo visibility() {
    return visibility;
  }

  /**
   * Returns the Predefined filters menu button.
   *
   * @return the drop-down tool item
   */
  ToolItem filtersButton() {
    return filters;
  }

  /**
   * Returns the option toggles in row order.
   *
   * @return match case, regex, whole word, inverse, combining, in selection, auto-refresh
   */
  ToolItem[] toggles() {
    return new ToolItem[] {matchCase, regex, wholeWord, inverse, combine, inSelection, autoRefresh};
  }

  /**
   * Returns the range toggle.
   *
   * @return the tool item
   */
  ToolItem rangeToggle() {
    return range;
  }

  /**
   * Returns the range end field.
   *
   * @return the field
   */
  Combo rangeEnd() {
    return rangeEnd;
  }

  /**
   * Returns the context line count fields.
   *
   * @return the lines above and the lines below each result
   */
  Text[] contextFields() {
    return new Text[] {contextBefore, contextAfter};
  }

  /**
   * Returns the filters menu.
   *
   * @return the menu, filled when shown
   */
  Menu filterMenu() {
    return filterMenu;
  }

  /**
   * Returns the status text.
   *
   * @return the text
   */
  public String statusText() {
    return status.getText();
  }

  /**
   * Shows and drives a document's search and filtered view.
   *
   * @param shown the document
   */
  public void bind(DocumentController shown) {
    unbind();
    document = shown;
    controller = shown.search();
    controller.addListener(listener);
    shown.filtered().addListener(filteredListener);
    setEnabled(true);
    showPreferences(controller.fieldText(), controller.options());
    show(controller.state());
    syncVisibility();
  }

  /** Detaches from the document and disables the row. */
  public void unbind() {
    if (controller != null) {
      controller.removeListener(listener);
      controller = null;
    }
    if (document != null) {
      document.filtered().removeListener(filteredListener);
      document = null;
    }
    setEnabled(false);
    show(SearchViewState.IDLE);
  }

  /** Selects the filtered view's mode in the combo. */
  private void syncVisibility() {
    if (visibility.isDisposed() || document == null) {
      return;
    }
    int index = document.filtered().visibility().ordinal();
    if (visibility.getSelectionIndex() != index) {
      visibility.select(index);
    }
  }

  /** Focuses the pattern field and selects its text. */
  public void focus() {
    if (!pattern.isDisposed() && pattern.isEnabled()) {
      pattern.setFocus();
      pattern.setSelection(new Point(0, pattern.getText().length()));
    }
  }

  /**
   * Returns the toggles' state.
   *
   * @return the options
   */
  public SearchOptions options() {
    return new SearchOptions(
        regex.getSelection() ? MatchMode.REGEX : MatchMode.LITERAL,
        matchCase.getSelection(),
        wholeWord.getSelection(),
        inverse.getSelection(),
        combine.getSelection(),
        inSelection.getSelection(),
        autoRefresh.getSelection(),
        keepResults.getSelection(),
        range.getSelection(),
        rangeEnd.getText(),
        count(contextBefore),
        count(contextAfter));
  }

  private void runSearch() {
    if (controller != null) {
      controller.run(pattern.getText(), options());
    }
  }

  private void showPreferences(String text, SearchOptions options) {
    if (bar.isDisposed()) {
      return;
    }
    syncing = true;
    try {
      if (!pattern.getText().equals(text)) {
        pattern.setText(text);
      }
      regex.setSelection(options.mode() == MatchMode.REGEX);
      matchCase.setSelection(options.caseSensitive());
      wholeWord.setSelection(options.wholeWord());
      inverse.setSelection(options.inverse());
      combine.setSelection(options.combining());
      inSelection.setSelection(options.inSelection());
      autoRefresh.setSelection(options.autoRefresh());
      keepResults.setSelection(options.keepResults());
      range.setSelection(options.range());
      if (!rangeEnd.getText().equals(options.rangeEnd())) {
        rangeEnd.setText(options.rangeEnd());
      }
      setCount(contextBefore, options.contextBefore());
      setCount(contextAfter, options.contextAfter());
    } finally {
      syncing = false;
    }
    showRangeEnd(options.range());
  }

  private static void setCount(Text field, int value) {
    if (count(field) != value || field.getText().isEmpty()) {
      field.setText(Integer.toString(value));
    }
  }

  private void setEnabled(boolean enabled) {
    for (Control control : bar.getChildren()) {
      if (control != status) {
        control.setEnabled(enabled);
      }
    }
  }

  private void show(SearchViewState state) {
    if (bar.isDisposed()) {
      return;
    }
    status.setText(state.statusText());
    status.setToolTipText(state.statusText());
    stop.setEnabled(controller != null && state.phase() == SearchViewState.Phase.RUNNING);
  }
}
