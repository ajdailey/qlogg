package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.ResultTabs;
import io.github.ajdailey.qlogg.app.controller.SearchController;
import io.github.ajdailey.qlogg.app.theme.Palette;
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
      LogCanvas.PrefixListener prefix,
      MenuIcons menuIcons) {
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
    canvas =
        new LogCanvas(body, document.filtered(), application, keymap, palette, prefix, menuIcons);
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
            closeTab((CTabItem) event.item);
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

  /**
   * Closes a result tab: a kept set's tab removes the set; the current search's tab stays and its
   * results are cleared, as {@code CLEAR_RESULTS} does.
   *
   * @param item a tab of {@link #sets}
   */
  void closeTab(CTabItem item) {
    if (item.getData() instanceof SearchController.KeptResults set) {
      document.search().removeKept(set);
    } else {
      document.search().clear();
    }
  }

  /**
   * One tab for the current search, closable while it holds a search, then one closable tab per
   * kept set, the shown one selected.
   */
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
      new CTabItem(sets, SWT.CLOSE);
    }
    SearchViewState state = search.state();
    sets.getItem(0).setText(ResultTabs.current(state, search.resultCount()));
    sets.getItem(0).setShowClose(state.phase() != SearchViewState.Phase.IDLE);
    for (int i = 0; i < kept.size(); i++) {
      SearchController.KeptResults set = kept.get(i);
      CTabItem item = sets.getItem(i + 1);
      // Selection and close match the set by identity.
      item.setData(set);
      item.setText(ResultTabs.kept(set));
      item.setToolTipText(ResultTabs.tip(set.text(), set.options()));
    }
    sets.getItem(0).setToolTipText(ResultTabs.tip(state.text(), state.options()));
    sets.setSelection(search.shown() + 1);
    // Accessible names.
    canvas
        .accessibleName()
        .set(ResultTabs.accessibleName(state, search.shown(), search.resultCount(), kept));
  }
}
