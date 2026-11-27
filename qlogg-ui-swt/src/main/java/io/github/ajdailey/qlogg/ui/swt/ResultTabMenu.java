package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * A results tab's context menu ({@link MenuTable#RESULT_TAB_MENU}): Close, Close Others and Close
 * All act on the closable tabs as each tab's close button does ({@link ResultsPane#closeTab}), Keep
 * Results runs {@link CommandId#TOGGLE_KEEP_RESULTS}, and Filter to Tab shows the tab's set and
 * runs {@link CommandId#FILTER_TO_TAB}. One menu serves every tab; each show replaces its items,
 * and the pane's disposal disposes it. Created and used on the UI thread.
 */
final class ResultTabMenu {
  private final ResultsPane pane;
  private final DocumentController document;
  private final ApplicationController application;
  private Menu menu;

  ResultTabMenu(ResultsPane pane, DocumentController document, ApplicationController application) {
    this.pane = pane;
    this.document = document;
    this.application = application;
    final CTabFolder sets = pane.sets();
    sets.addListener(
        SWT.MenuDetect,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            CTabItem item = sets.getItem(sets.toControl(event.x, event.y));
            if (item != null && !item.isDisposed()) {
              event.doit = false;
              show(item, event.x, event.y);
            }
          }
        });
    sets.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (menu != null && !menu.isDisposed()) {
              menu.dispose();
            }
          }
        });
  }

  /**
   * Opens the menu for a tab.
   *
   * @param item a tab of the results strip
   * @param x display x
   * @param y display y
   * @return the menu, already visible
   */
  Menu show(final CTabItem item, int x, int y) {
    if (menu == null || menu.isDisposed()) {
      menu = new Menu(pane.sets().getShell(), SWT.POP_UP);
    }
    for (MenuItem old : menu.getItems()) {
      old.dispose();
    }
    final List<CTabItem> others = new ArrayList<CTabItem>();
    final List<CTabItem> all = new ArrayList<CTabItem>();
    for (CTabItem tab : pane.sets().getItems()) {
      if (tab.getShowClose()) {
        all.add(tab);
        if (tab != item) {
          others.add(tab);
        }
      }
    }
    for (UiText label : MenuTable.RESULT_TAB_MENU) {
      final List<CTabItem> closed =
          switch (label) {
            case TAB_CLOSE -> item.getShowClose() ? List.of(item) : List.of();
            case TAB_CLOSE_OTHERS -> others;
            default -> all;
          };
      MenuItem entry = new MenuItem(menu, SWT.PUSH);
      entry.setText(label.text());
      entry.setEnabled(!closed.isEmpty());
      entry.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              close(closed);
            }
          });
    }
    new MenuItem(menu, SWT.SEPARATOR);
    MenuItem keep = new MenuItem(menu, SWT.CHECK);
    keep.setText(CommandId.TOGGLE_KEEP_RESULTS.label());
    keep.setSelection(document.search().options().keepResults());
    keep.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            application.execute(CommandId.TOGGLE_KEEP_RESULTS);
          }
        });
    MenuItem filter = new MenuItem(menu, SWT.PUSH);
    filter.setText(CommandId.FILTER_TO_TAB.label());
    filter.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!item.isDisposed()) {
              pane.sets().setSelection(item);
              pane.sets().notifyListeners(SWT.Selection, new Event());
              application.filterToTab(document);
            }
          }
        });
    menu.setLocation(x, y);
    menu.setVisible(true);
    return menu;
  }

  /** Closes kept sets first, by identity, then the current search's tab. */
  private void close(List<CTabItem> tabs) {
    CTabItem current = null;
    List<Object> kept = new ArrayList<Object>();
    for (CTabItem tab : tabs) {
      if (tab.isDisposed()) {
        continue;
      }
      if (tab.getData() == null) {
        current = tab;
      } else {
        kept.add(tab.getData());
      }
    }
    for (Object set : kept) {
      for (CTabItem tab : pane.sets().getItems()) {
        if (tab.getData() == set) {
          pane.closeTab(tab);
          break;
        }
      }
    }
    if (current != null && !current.isDisposed()) {
      pane.closeTab(current);
    }
  }
}
