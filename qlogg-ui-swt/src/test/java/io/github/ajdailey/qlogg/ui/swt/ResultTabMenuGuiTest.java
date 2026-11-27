package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A results tab's context menu: its items and their enabled states, Close Others and Close All on
 * the current search's tab and the kept sets, and Keep Results checked as the toggle is.
 */
@Tag("swt")
class ResultTabMenuGuiTest {
  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;
  private DocumentController document;

  @AfterEach
  void close() {
    try {
      if (windows != null) {
        windows.close();
        assertTrue(windows.failures.isEmpty(), windows.failures.toString());
      }
    } finally {
      if (display != null && !display.isDisposed()) {
        display.dispose();
      }
    }
  }

  @Test
  void closeItemsActOnTheClosableTabsAndKeepResultsFollowsTheToggle() throws IOException {
    display = new Display();
    windows = new SlotWindows(display, temp.resolve("config"), ThemeMode.LIGHT, null);
    final ApplicationController controller = windows.open(40, 40, 1000, 640);
    final MainWindowSlot slot = windows.slots.get(0);
    Path file = Files.writeString(temp.resolve("results.log"), "mock line a\nmock line b\n");
    controller.open(List.of(new FileRequest(file, 0)));
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.activeDocument() != null
                    && !controller.activeDocument().state().indexing()
                    && slot.window().results() != null;
              }
            }),
        "document open");
    document = controller.activeDocument();
    search("line a");
    controller.execute(CommandId.TOGGLE_KEEP_RESULTS);
    search("line b");
    search("mock");
    assertEquals(2, document.search().kept().size());
    ResultsPane pane = slot.window().results();
    assertEquals(3, pane.sets().getItemCount());

    List<String> labels = new ArrayList<String>();
    for (UiText label : MenuTable.RESULT_TAB_MENU) {
      labels.add(label.text());
    }
    labels.add(null);
    labels.add(CommandId.TOGGLE_KEEP_RESULTS.label());
    labels.add(CommandId.FILTER_TO_TAB.label());
    MenuItem[] items = open(pane, pane.sets().getItem(1));
    List<String> shown = new ArrayList<String>();
    for (MenuItem item : items) {
      shown.add((item.getStyle() & SWT.SEPARATOR) != 0 ? null : item.getText());
      if (item.getText().length() > 0) {
        assertTrue(item.isEnabled(), item.getText());
      }
    }
    assertEquals(labels, shown);
    assertTrue(items[4].getSelection(), "Keep Results checked");

    // Close Others on the first kept set: the other kept set and the current search close.
    Object first = pane.sets().getItem(1).getData();
    select(items[1]);
    assertEquals(List.of(first), new ArrayList<Object>(document.search().kept()));
    assertEquals(SearchViewState.Phase.IDLE, document.search().state().phase());

    // Keep Results runs the toggle.
    select(open(pane, pane.sets().getItem(0))[4]);
    assertFalse(document.search().options().keepResults());

    // Close All.
    items = open(pane, pane.sets().getItem(0));
    assertFalse(items[0].isEnabled(), "the idle current tab does not close");
    select(items[2]);
    assertEquals(0, document.search().kept().size());
    assertEquals(1, pane.sets().getItemCount());
    assertFalse(open(pane, pane.sets().getItem(0))[2].isEnabled(), "nothing left to close");

    // Filter to Tab on a kept set that is not shown opens a tab of that set's lines.
    search("line b");
    controller.execute(CommandId.TOGGLE_KEEP_RESULTS);
    search("mock");
    assertEquals(2, pane.sets().getItemCount());
    select(open(pane, pane.sets().getItem(1))[5]);
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return controller.documents().size() == 2
                    && shown != document
                    && shown.viewport().lineCount() == 1
                    && shown.sourceLine(0) == 1;
              }
            }),
        "filter tab of the kept set");
  }

  /** Shows the menu for a tab and hides it before any dispatch. */
  private MenuItem[] open(ResultsPane pane, CTabItem tab) {
    Menu menu = pane.tabMenu().show(tab, 100, 100);
    menu.setVisible(false);
    return menu.getItems();
  }

  private void select(MenuItem item) {
    if ((item.getStyle() & SWT.CHECK) != 0) {
      item.setSelection(!item.getSelection());
    }
    item.notifyListeners(SWT.Selection, new Event());
    windows.settle();
  }

  private void search(String text) {
    document.search().run(text, document.search().options());
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.search().state().phase() == SearchViewState.Phase.DONE;
              }
            }),
        "search " + text);
    windows.settle();
  }
}
