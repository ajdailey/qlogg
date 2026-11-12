package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.SearchController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewListener;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The filtered view under the search row: one tab per result set (the current search, then each
 * kept search, closable) across the full width, and below it a {@link LogCanvas} over the
 * document's {@link io.github.ajdailey.qlogg.app.controller.FilteredController}. Selecting a tab
 * shows that set; closing a kept tab removes it and returns its memory.
 */
final class ResultsPane {
  private final Composite pane;
  private final CTabFolder sets;
  private final TabStripButtons tabButtons;
  private final Composite body;
  private final LogCanvas canvas;
  private final DocumentController document;
  private final SearchListener searchListener = new SearchListener();

  /** Rebuilds the tabs when the result sets change. */
  private final class SearchListener implements SearchViewListener {
    @Override
    public void stateChanged(SearchViewState state) {
      syncTabs();
    }

    @Override
    public void resultsChanged() {
      syncTabs();
    }
  }

  ResultsPane(
      Composite parent,
      final DocumentController document,
      final ApplicationController application,
      Keymap keymap,
      Palette palette,
      LogCanvas.PrefixListener prefix) {
    this.document = document;
    pane = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 0;
    pane.setLayout(layout);
    sets = new CTabFolder(pane, SWT.TOP | SWT.FLAT);
    GridData setsData = new GridData(SWT.FILL, SWT.TOP, true, false);
    // The folder adds its tab row as trim around the hint, which is the empty client area.
    setsData.heightHint = 1;
    sets.setLayoutData(setsData);
    // Tab strip buttons: kept sets overflow the strip (11 short searches at 1100 px).
    tabButtons = new TabStripButtons(sets, palette, null, null);
    body = new Composite(pane, SWT.NONE);
    body.setLayout(new FillLayout());
    body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    canvas = new LogCanvas(body, document.filtered(), application, keymap, palette, prefix);
    sets.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            CTabItem item = sets.getSelection();
            if (item != null && item.getData() instanceof SearchController.KeptResults set) {
              document.search().show(set);
            } else {
              document.search().show(-1);
            }
          }
        });
    sets.addCTabFolder2Listener(
        new CTabFolder2Adapter() {
          @Override
          public void close(CTabFolderEvent event) {
            event.doit = false;
            if (event.item.getData() instanceof SearchController.KeptResults set) {
              document.search().removeKept(set);
            }
          }
        });
    document.search().addListener(searchListener);
    pane.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            document.search().removeListener(searchListener);
          }
        });
    syncTabs();
  }

  Composite control() {
    return pane;
  }

  LogCanvas canvas() {
    return canvas;
  }

  CTabFolder sets() {
    return sets;
  }

  // Tab strip buttons.
  TabStripButtons tabButtons() {
    return tabButtons;
  }

  /** One tab for the current search, then one closable tab per kept set, the shown one selected. */
  private void syncTabs() {
    if (sets.isDisposed()) {
      return;
    }
    SearchController search = document.search();
    List<SearchController.KeptResults> kept = search.kept();
    int wanted = kept.size() + 1;
    while (sets.getItemCount() > wanted) {
      sets.getItem(sets.getItemCount() - 1).dispose();
    }
    while (sets.getItemCount() < wanted) {
      new CTabItem(sets, sets.getItemCount() == 0 ? SWT.NONE : SWT.CLOSE);
    }
    SearchViewState state = search.state();
    String current =
        state.text().isEmpty()
            ? UiText.RESULTS_CURRENT_EMPTY.text()
            : UiText.RESULTS_TAB.format(
                shorten(label(state.text(), state.options())), search.resultCount());
    // A CTabItem takes a single & as a mnemonic, and SWT drops one from a tool tip.
    sets.getItem(0).setText(current.replace("&", "&&"));
    for (int i = 0; i < kept.size(); i++) {
      SearchController.KeptResults set = kept.get(i);
      CTabItem item = sets.getItem(i + 1);
      // Selection and close match the set by identity.
      item.setData(set);
      item.setText(
          UiText.RESULTS_TAB
              .format(shorten(label(set.text(), set.options())), set.lineCount())
              .replace("&", "&&"));
      item.setToolTipText(label(set.text(), set.options()).replace("&", "&&"));
    }
    sets.getItem(0).setToolTipText(label(state.text(), state.options()).replace("&", "&&"));
    sets.setSelection(search.shown() + 1);
    // Accessible names.
    canvas
        .accessibleName()
        .set(
            state.text().isEmpty() && search.shown() < 0
                ? UiText.ACCESSIBLE_FILTERED_VIEW.text()
                : UiText.ACCESSIBLE_FILTERED_VIEW_COUNT.format(
                    search.shown() < 0
                        ? search.resultCount()
                        : kept.get(search.shown()).lineCount()));
  }

  /** A search's text, with a range search's end after it. */
  private static String label(String text, SearchOptions options) {
    return options.range() ? UiText.RESULTS_RANGE.format(text, options.rangeEnd()) : text;
  }

  private static String shorten(String text) {
    return text.length() > 32 ? text.substring(0, 31) + "..." : text;
  }
}
