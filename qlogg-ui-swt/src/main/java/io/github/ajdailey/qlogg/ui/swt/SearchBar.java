package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.Keymap;
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
import org.eclipse.swt.graphics.Image;
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
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The search row at the top of the bottom pane, in this order: the filtered view's visibility
 * combo, the option toggles (match case, regex, whole word, inverse, combining, in selection,
 * auto-refresh), the Predefined filters menu, the pattern field with the search history, then
 * Clear, Search, Keep results, Stop and the search status. Toggles and buttons are icon tool items
 * whose tooltips name the option and its key. The row drives the shown document's {@link
 * SearchController} and filtered view; with no document it is disabled. Enter in the field searches
 * and Escape stops.
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
          showOptions(text, options);
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
    GridLayout layout = new GridLayout(6, false);
    layout.marginHeight = 2;
    layout.marginWidth = 2;
    layout.horizontalSpacing = 4;
    bar.setLayout(layout);
    icons = new ToolIcons(bar);
    visibility = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
    for (UiText mode : MODES) {
      visibility.add(mode.text());
    }
    visibility.setToolTipText(UiText.VISIBILITY_TOOLTIP.text());
    visibility.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    ToolBar toggles = new ToolBar(bar, SWT.FLAT);
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
    showOptions("", SearchOptions.DEFAULTS);
    listen();
    unbind();
  }

  /** Adds an icon tool item; its tooltip names the option and, when bound, its key. */
  private ToolItem item(ToolBar parent, int style, String icon, UiText tip, CommandId command) {
    ToolItem item = new ToolItem(parent, style);
    Image image = icons.get(icon);
    if (image != null) {
      item.setImage(image);
    } else {
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
    CommandId[] commands = {
      CommandId.SAVE_AS_FILTER, CommandId.EDIT_HISTORY, CommandId.CLEAR_HISTORY
    };
    for (final CommandId command : commands) {
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
    syncing = true;
    try {
      pattern.setItems(items);
      if (!pattern.getText().equals(text)) {
        pattern.setText(text);
        pattern.setSelection(selection);
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
    showOptions(FilterPatterns.compose(checked, combining), next);
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
    showOptions(controller.fieldText(), controller.options());
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
        keepResults.getSelection());
  }

  private void runSearch() {
    if (controller != null) {
      controller.run(pattern.getText(), options());
    }
  }

  private void showOptions(String text, SearchOptions options) {
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
    } finally {
      syncing = false;
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
