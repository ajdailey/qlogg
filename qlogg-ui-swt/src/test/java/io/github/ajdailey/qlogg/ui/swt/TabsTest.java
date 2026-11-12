package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One tab per file: switching, dragging, closing, and the follow key, under Xvfb. */
@Tag("swt")
class TabsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    MemoryBudget budget = MemoryBudget.of(1L << 28);
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    window = new MainWindow(display);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
    window.open();
    Path first = Files.writeString(temp.resolve("first.log"), "a\nb\nc\n");
    Path second = Files.writeString(temp.resolve("second.log"), "x\ny\n");
    controller.open(List.of(new FileRequest(first, 0), new FileRequest(second, 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return window.tabs().getItemCount() == 2
                    && shown != null
                    && shown.state().title().equals("second.log")
                    && !shown.state().indexing()
                    && window.canvas() != null;
              }
            }));
  }

  @AfterEach
  void close() {
    controller.shutdown();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private static DocumentController documentOf(CTabItem item) {
    return (DocumentController) item.getData();
  }

  @Test
  void selectingATabShowsItsDocument() {
    CTabFolder tabs = window.tabs();
    assertTrue(
        tabs.getSize().y <= tabs.getTabHeight() + 8,
        "tab strip " + tabs.getSize().y + " px for " + tabs.getTabHeight() + " px tabs");
    assertEquals("first.log", tabs.getItem(0).getText());
    assertEquals(tabs.getItem(1), tabs.getSelection());
    assertSame(controller.activeDocument(), window.canvas().document());
    assertTrue(window.infoText().contains("4 B"), window.infoText());
    Event event = new Event();
    event.item = tabs.getItem(0);
    tabs.setSelection(0);
    tabs.notifyListeners(SWT.Selection, event);
    assertSame(documentOf(tabs.getItem(0)), controller.activeDocument());
    assertSame(controller.activeDocument(), window.canvas().document());
    assertTrue(window.infoText().contains("6 B"), window.infoText());
  }

  @Test
  void draggingATabMovesItAndClosingRemovesIt() {
    CTabFolder tabs = window.tabs();
    DocumentController first = documentOf(tabs.getItem(0));
    Rectangle from = tabs.getItem(0).getBounds();
    Rectangle to = tabs.getItem(1).getBounds();
    mouse(tabs, SWT.MouseDown, from.x + from.width / 2, from.y + from.height / 2);
    mouse(tabs, SWT.MouseUp, to.x + to.width / 2, to.y + to.height / 2);
    assertSame(first, documentOf(tabs.getItem(1)));
    assertSame(first, controller.documents().get(1));
    controller.closeDocument(first);
    assertEquals(1, tabs.getItemCount());
    assertTrue(first.isClosed());
    controller.execute(CommandId.CLOSE_ALL);
    assertEquals(0, tabs.getItemCount());
    assertNull(window.canvas());
  }

  @Test
  void theFollowKeyFollowsTheShownDocument() {
    Event event = new Event();
    event.keyCode = 'f';
    event.character = 'f';
    window.canvas().control().notifyListeners(SWT.KeyDown, event);
    assertTrue(controller.activeDocument().following());
    assertTrue(window.statusText().contains("Following"), window.statusText());
  }

  @Test
  void theTabMenuMatchesTheTab() {
    CTabFolder tabs = window.tabs();
    DocumentController first = documentOf(tabs.getItem(0));
    Menu menu = window.showTabMenu(first, 0, 0);
    MenuItem[] items = menu.getItems();
    assertEquals(15, items.length);
    assertEquals("&Close", items[0].getText());
    assertEquals("Time S&ync", items[13].getText());
    assertEquals("Move to New &Window", items[14].getText());
    assertTrue(items[14].getEnabled());
    assertFalse(items[2].getEnabled(), "close to the left of the first tab");
    assertTrue(items[3].getEnabled());
    assertEquals("Copy &Full Path", items[6].getText());
    assertEquals("Copy File &Name", items[7].getText());
    assertEquals("Rena&me Tab...", items[10].getText());
    menu.setVisible(false);
    items[3].notifyListeners(SWT.Selection, new Event());
    assertEquals(1, tabs.getItemCount());
    Menu again = window.showTabMenu(first, 0, 0);
    again.setVisible(false);
    assertSame(menu, again);
    assertEquals(15, again.getItemCount());
    assertTrue(items[0].isDisposed(), "the previous items are disposed");
    controller.setTabName(first, "renamed");
    assertEquals("renamed", tabs.getItem(0).getText());
  }

  @Test
  void aMiddleClickClosesTabsAndNoFileHidesTheTabRow() {
    CTabFolder tabs = window.tabs();
    assertTrue(tabs.getVisible(), "two files show the tab row");
    DocumentController second = documentOf(tabs.getItem(1));
    middleClick(tabs, 1);
    assertEquals(1, tabs.getItemCount());
    assertTrue(second.isClosed());
    assertTrue(tabs.getVisible(), "one file shows the tab row");
    DocumentController last = documentOf(tabs.getItem(0));
    assertEquals(last.state().path(), window.pathField().getText());
    middleClick(tabs, 0);
    assertEquals(0, tabs.getItemCount());
    assertTrue(last.isClosed(), "a middle click closes the last tab");
    assertFalse(tabs.getVisible(), "no file hides the tab row");
    assertFalse(window.shell().isDisposed(), "the window stays open");
    assertEquals("", window.pathField().getText());
    assertFalse(window.pathField().getEnabled());
  }

  @Test
  void tabsShowFileNamesAndTheTabRowFollowsTheFileCount() {
    CTabFolder tabs = window.tabs();
    assertEquals("first.log", tabs.getItem(0).getText());
    assertEquals("second.log", tabs.getItem(1).getText());
    assertEquals(documentOf(tabs.getItem(0)).state().path(), tabs.getItem(0).getToolTipText());
    Rectangle row = window.toolBar().getParent().getBounds();
    assertTrue(tabs.getBounds().y >= row.y + row.height, "tab row under the top row");
    controller.closeDocument(documentOf(tabs.getItem(1)));
    assertTrue(tabs.getVisible(), "one file shows the tab row");
    assertEquals("first.log", tabs.getItem(0).getText());
    controller.closeDocument(documentOf(tabs.getItem(0)));
    assertFalse(tabs.getVisible(), "no file hides the tab row");
    controller.open(List.of(new FileRequest(temp.resolve("second.log"), 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == 1;
              }
            }));
    assertTrue(tabs.getVisible(), "a file shows the tab row again");
  }

  @Test
  void theTopRowHoldsToolbarPathStopAndFileFields() {
    Control[] row = window.toolBar().getParent().getChildren();
    assertSame(window.toolBar(), row[0]);
    assertSame(window.pathField(), row[1]);
    assertTrue(row[2] instanceof ToolBar);
    assertTrue(((ToolBar) row[2]).getItem(0).getToolTipText().startsWith("Stop Loading"));
    assertEquals(controller.activeDocument().state().path(), window.pathField().getText());
    assertTrue(window.pathField().getEditable() == false);
    MenuItem[] menu = window.pathField().getMenu().getItems();
    assertEquals("Copy &Full Path", menu[0].getText());
    assertEquals("Copy File &Name", menu[1].getText());
    assertEquals("Open Containing Fol&der", menu[2].getText());
    assertEquals("&Copy", menu[4].getText());
    assertEquals("Select &All", menu[6].getText());
    assertTrue(window.infoText().contains("4 B"), window.infoText());
    assertTrue(
        window.sizeTip().matches("Last modified \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
        window.sizeTip());
    assertTrue(window.positionText().startsWith("Ln "), window.positionText());
  }

  private static void middleClick(CTabFolder tabs, int index) {
    Rectangle bounds = tabs.getItem(index).getBounds();
    Event middle = new Event();
    middle.button = 2;
    middle.x = bounds.x + bounds.width / 2;
    middle.y = bounds.y + bounds.height / 2;
    tabs.notifyListeners(SWT.MouseUp, middle);
  }

  @Test
  void theToolBarFollowsTheShownDocument() {
    ToolBar bar = window.toolBar();
    assertEquals(3, bar.getItemCount());
    ToolItem follow = bar.getItem(2);
    assertNotNull(follow.getImage());
    assertTrue(follow.getToolTipText().startsWith("Follow"), follow.getToolTipText());
    assertTrue(follow.getEnabled());
    follow.setSelection(true);
    follow.notifyListeners(SWT.Selection, new Event());
    assertTrue(controller.activeDocument().following());
    assertTrue(follow.getSelection());
    controller.execute(CommandId.CLOSE_ALL);
    assertFalse(follow.getEnabled());
    assertFalse(bar.getItem(1).getEnabled());
  }

  @Test
  void openedFilesListsEveryTabAndSwitchToFileFilters() {
    window.syncMenu();
    MenuItem[] opened = window.openedFilesMenu().getItems();
    assertEquals(2, opened.length);
    assertTrue(opened[1].getSelection());
    assertTrue(opened[0].getText().endsWith("first.log"), opened[0].getText());
    SwitchFileDialog dialog = new SwitchFileDialog(window.shell(), controller);
    dialog.open();
    dialog.filter().setText("FIRST");
    assertEquals(1, dialog.list().getItemCount());
    dialog.ok().notifyListeners(SWT.Selection, new Event());
    assertTrue(dialog.shell().isDisposed());
    assertEquals("first.log", controller.activeDocument().state().title());
  }

  @Test
  void droppedFilesOpenInTabs() throws Exception {
    Path third = Files.writeString(temp.resolve("third.log"), "z\n");
    window.dropped(new String[] {third.toString()});
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == 3;
              }
            }));
  }

  /** A drag gets Copy unless every dragged path is a folder, which gets no drop. */
  @Test
  void aDragOfFoldersOnlyGetsNoDrop() throws Exception {
    final Path folder = Files.createDirectory(temp.resolve("folder"));
    Path log = Files.writeString(temp.resolve("third.log"), "z\n");
    assertEquals(DND.DROP_COPY, window.dropDetail());
    window.checkDragged(new String[] {folder.toString()});
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.dropDetail() == DND.DROP_NONE;
              }
            }));
    window.checkDragged(new String[] {folder.toString(), log.toString()});
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.dropDetail() == DND.DROP_COPY;
              }
            }));
  }

  /** Pressing a tab selects it, so a dragged tab ends selected and its document shown. */
  @Test
  void aDraggedTabStaysSelectedAndShown() throws Exception {
    Path third = Files.writeString(temp.resolve("third.log"), "z\n");
    controller.open(List.of(new FileRequest(third, 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return window.tabs().getItemCount() == 3
                    && shown != null
                    && shown.state().title().equals("third.log")
                    && window.canvas() != null;
              }
            }));
    CTabFolder tabs = window.tabs();
    DocumentController dragged = documentOf(tabs.getItem(2));
    drag(tabs, 2, 0);
    assertSame(dragged, documentOf(tabs.getItem(0)));
    assertShown(dragged, 0);
    DocumentController other = documentOf(tabs.getItem(1));
    drag(tabs, 1, 2);
    assertSame(other, documentOf(tabs.getItem(2)));
    assertShown(other, 2);
  }

  private void assertShown(DocumentController document, int index) {
    CTabFolder tabs = window.tabs();
    assertSame(tabs.getItem(index), tabs.getSelection());
    assertSame(document, controller.activeDocument());
    assertSame(document, window.canvas().document());
    assertEquals(document.state().path(), window.pathField().getText());
  }

  private static void drag(CTabFolder tabs, int from, int to) {
    Rectangle source = tabs.getItem(from).getBounds();
    Rectangle target = tabs.getItem(to).getBounds();
    mouse(tabs, SWT.MouseDown, source.x + source.width / 2, source.y + source.height / 2);
    mouse(tabs, SWT.MouseUp, target.x + target.width / 2, target.y + target.height / 2);
  }

  @Test
  void documentItemsAreEnabledWithoutAMenuShow() throws Exception {
    controller.execute(CommandId.CLOSE_ALL);
    Menu view = topMenu(CommandId.RELOAD);
    view.notifyListeners(SWT.Show, new Event());
    view.notifyListeners(SWT.Hide, new Event());
    MenuItem reload = item(view, CommandId.RELOAD);
    MenuItem numbers = item(view, CommandId.TOGGLE_LINE_NUMBERS);
    assertFalse(reload.getEnabled());
    assertFalse(numbers.getEnabled());
    controller.open(List.of(new FileRequest(temp.resolve("first.log"), 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.activeDocument() != null;
              }
            }));
    assertTrue(reload.getEnabled());
    assertTrue(numbers.getEnabled());
  }

  /** The menu bar's menu that holds a command. */
  private Menu topMenu(CommandId command) {
    for (MenuItem header : window.shell().getMenuBar().getItems()) {
      if (item(header.getMenu(), command) != null) {
        return header.getMenu();
      }
    }
    throw new AssertionError("no menu holds " + command);
  }

  private static MenuItem item(Menu menu, CommandId command) {
    for (MenuItem item : menu.getItems()) {
      if (item.getData() == command) {
        return item;
      }
      if (item.getMenu() != null) {
        MenuItem found = item(item.getMenu(), command);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static void mouse(CTabFolder tabs, int type, int x, int y) {
    Event event = new Event();
    event.button = 1;
    event.x = x;
    event.y = y;
    tabs.notifyListeners(type, event);
  }
}
