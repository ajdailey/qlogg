package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.config.TimeSettings;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Time Sync cascades, the tab tooltip and a sync between shown and hidden tabs under Xvfb. */
@Tag("swt")
class TimeSyncGuiTest {
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
            Preferences.DEFAULTS.withTime(
                new TimeSettings(ZoneOffset.UTC, ZoneOffset.UTC, List.of())));
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
    controller.open(
        List.of(
            new FileRequest(timed("first & one.log"), 0),
            new FileRequest(timed("second.log"), 0),
            new FileRequest(timed("third.log"), 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return window.tabStrip().tabs().getItemCount() == 3
                    && shown != null
                    && window.canvas() != null
                    && !shown.state().indexing()
                    && !tab(1).state().indexing()
                    && !tab(0).state().indexing();
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
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private Path timed(String name) throws IOException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      text.append(String.format("2026-03-01 05:%02d:%02d.250 INFO entry %d%n", i / 60, i % 60, i));
    }
    Path file = temp.resolve(name);
    Files.writeString(file, text.toString(), StandardCharsets.US_ASCII);
    return file;
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private DocumentController tab(int index) {
    return controller.documents().get(index);
  }

  private static MenuItem item(Menu menu, String text) {
    for (MenuItem item : menu.getItems()) {
      if (item.getText().equals(text)) {
        return item;
      }
    }
    return null;
  }

  private Menu tabCascade(DocumentController document) {
    Menu menu = window.tabStrip().showTabMenu(document, 0, 0);
    menu.setVisible(false);
    MenuItem cascade = item(menu, UiText.MENU_TIME_SYNC.text());
    assertNotNull(cascade);
    return cascade.getMenu();
  }

  private String tip(int index) {
    CTabItem item = window.tabStrip().tabs().getItem(index);
    return item.getToolTipText();
  }

  private void lands(final DocumentController document, final long line) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.selection().caret().line() == line;
              }
            }),
        "caret at " + document.selection().caret().line());
  }

  @Test
  void anAmpersandInATabTitleShowsLiterally() {
    CTabItem item = window.tabStrip().tabs().getItem(0);
    assertEquals("first && one.log", item.getText());
    assertEquals("first & one.log", controller.tabTitle(tab(0)));
    assertTrue(item.getToolTipText().endsWith("first && one.log"), item.getToolTipText());
    GC gc = new GC(window.tabStrip().tabs());
    try {
      // The tab draws with mnemonics on; escaped, its text is as wide as the title drawn without.
      assertEquals(
          gc.textExtent("first & one.log", 0).x,
          gc.textExtent(item.getText(), SWT.DRAW_MNEMONIC).x);
    } finally {
      gc.dispose();
    }
    controller.setTabName(tab(0), "logs & more");
    assertEquals("logs && more", item.getText());
    assertTrue(item.getToolTipText().endsWith("first && one.log"), item.getToolTipText());
    assertNotNull(item(tabCascade(tab(2)), "logs && more"));
    controller.setTabName(tab(0), "");
    assertEquals("first && one.log", item.getText());
  }

  @Test
  void theTabCascadeJoinsListsAndLeaves() {
    DocumentController shown = tab(2);
    assertEquals(shown, controller.activeDocument());
    Menu cascade = tabCascade(shown);
    // Check items, then a separator and Leave Time Sync, disabled outside a group.
    assertEquals(4, cascade.getItemCount());
    MenuItem first = item(cascade, "first && one.log");
    assertNotNull(first);
    assertFalse(first.getSelection());
    MenuItem leave = item(cascade, UiText.TIME_SYNC_LEAVE.text());
    assertFalse(leave.getEnabled());
    shown.select(Selection.caretAt(new TextPosition(70, 0)));
    first.notifyListeners(SWT.Selection, new Event());
    lands(tab(0), 70);
    String synced = UiText.TAB_TIME_SYNCED.format(1);
    assertTrue(tip(0).endsWith("\n" + synced), tip(0));
    assertTrue(tip(2).endsWith("\n" + synced), tip(2));
    assertFalse(tip(1).contains(synced), tip(1));

    Menu again = tabCascade(shown);
    assertTrue(cascade.isDisposed(), "a rebuilt tab menu disposes the old cascade");
    assertTrue(item(again, "first && one.log").getSelection());
    assertTrue(item(again, UiText.TIME_SYNC_LEAVE.text()).getEnabled());
    // A tab outside the group sees the members with their number.
    Menu other = tabCascade(tab(1));
    assertFalse(item(other, "third.log (group 1)").getSelection());

    shown.select(Selection.caretAt(new TextPosition(12, 0)));
    lands(tab(0), 12);
    item(tabCascade(shown), UiText.TIME_SYNC_LEAVE.text())
        .notifyListeners(SWT.Selection, new Event());
    assertFalse(tip(0).contains(synced), tip(0));
    assertFalse(tip(2).contains(synced), tip(2));
  }

  @Test
  void theLogViewCascadeFillsWhenShownAndTheMenuRunsScrollAllTabs() throws Exception {
    LogCanvas canvas = window.canvas();
    canvas.menuAt(5, 5);
    Menu menu = canvas.control().getMenu();
    MenuItem cascade = item(menu, UiText.MENU_TIME_SYNC.text());
    assertNotNull(cascade);
    Menu sync = cascade.getMenu();
    sync.notifyListeners(SWT.Show, new Event());
    assertEquals(4, sync.getItemCount());
    item(sync, "second.log").notifyListeners(SWT.Selection, new Event());
    assertEquals(1, controller.windowSet().timeSync().number(tab(1)));

    tab(2).select(Selection.caretAt(new TextPosition(40, 0)));
    lands(tab(1), 40);
    shoot();
    MenuItem scroll = null;
    for (MenuItem item : menu.getItems()) {
      if (item.getData() == CommandId.SCROLL_TABS_TO_TIME) {
        scroll = item;
      }
    }
    assertNotNull(scroll);
    scroll.notifyListeners(SWT.Selection, new Event());
    lands(tab(0), 40);
    assertFalse(tip(0).contains(UiText.TAB_TIME_SYNCED.format(1)), tip(0));
  }

  /**
   * Writes a PNG of the screen with the cascade open when {@code qlogg.shots} names a directory.
   */
  private void shoot() {
    String dir = System.getProperty("qlogg.shots");
    if (dir == null) {
      return;
    }
    Menu popup = new Menu(window.shell(), SWT.POP_UP);
    TimeSyncMenu.fill(popup, controller.windowSet().timeSync(), tab(2));
    Point corner = window.shell().toDisplay(420, 90);
    popup.setLocation(corner);
    popup.setVisible(true);
    final long end = System.nanoTime() + 700_000_000L;
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > end;
          }
        });
    Point size = window.shell().getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, 0, 0);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(Path.of(dir, "time-sync.png").toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
      popup.setVisible(false);
      popup.dispose();
    }
  }
}
