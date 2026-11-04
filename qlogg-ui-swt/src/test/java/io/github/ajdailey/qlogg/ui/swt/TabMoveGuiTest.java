package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.github.ajdailey.qlogg.app.controller.WindowSet;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tab moves between main windows under Xvfb: the menu items, a drag released outside every window
 * or over another window's tab strip, and a drag inside the strip that still reorders. The release
 * finds the control under the pointer through a {@link MainWindow.PointerProbe} the test sets.
 */
@Tag("swt")
class TabMoveGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<MainWindow> windows = new ArrayList<MainWindow>();
  private Display display;
  private Workers workers;
  private WindowSet set;
  private MainWindow first;
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
    set =
        new WindowSet(
            workers,
            new SwtUiDispatcher(display),
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS,
            ThemeService.LIGHT);
    set.setFactory(
        new WindowSet.Factory() {
          @Override
          public ApplicationController create(WindowSet owner, WindowGeometry.Request at) {
            return window(owner, at);
          }
        });
    controller = window(set, new WindowGeometry.Request(0, 0, null, null));
    first = windows.get(0);
    controller.open(
        List.of(
            new FileRequest(log("first.log", "alpha"), 0),
            new FileRequest(log("second.log", "beta"), 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return first.tabs().getItemCount() == 2
                    && shown != null
                    && !shown.state().indexing()
                    && first.canvas() != null;
              }
            }));
  }

  private ApplicationController window(WindowSet owner, WindowGeometry.Request at) {
    MainWindow window = new MainWindow(display);
    windows.add(window);
    ApplicationController created = new ApplicationController(owner, window);
    window.attach(created);
    // Without a window manager a window with no position opens at 0,0 over the first one.
    Integer x = at.x() == null ? Integer.valueOf(1150) : at.x();
    Integer y = at.y() == null ? Integer.valueOf(0) : at.y();
    window.place(new WindowGeometry.Request(x, y, 1100, 700), null);
    window.open();
    return created;
  }

  private Path log(String name, String word) throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 60; i++) {
      text.append("2026-11-04 10:00:")
          .append(String.format("%02d", i % 60))
          .append(" INFO ")
          .append(word)
          .append(" request ")
          .append(i)
          .append(" handled\n");
    }
    return Files.writeString(temp.resolve(name), text.toString(), StandardCharsets.US_ASCII);
  }

  @AfterEach
  void close() {
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (MainWindow window : windows) {
              if (!window.shell().isDisposed()) {
                return false;
              }
            }
            return true;
          }
        });
    display.dispose();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private static DocumentController documentOf(CTabFolder tabs, int index) {
    return (DocumentController) tabs.getItem(index).getData();
  }

  private static void mouse(CTabFolder tabs, int type, int x, int y) {
    Event event = new Event();
    event.button = 1;
    event.x = x;
    event.y = y;
    tabs.notifyListeners(type, event);
  }

  /** Presses on a tab and releases at a display point. */
  private static void drag(CTabFolder tabs, int index, Point release) {
    Rectangle from = tabs.getItem(index).getBounds();
    mouse(tabs, SWT.MouseDown, from.x + from.width / 2, from.y + from.height / 2);
    Point local = tabs.toControl(release);
    mouse(tabs, SWT.MouseUp, local.x, local.y);
  }

  private void probe(MainWindow window, final Control under) {
    window.setPointerProbe(
        new MainWindow.PointerProbe() {
          @Override
          public Control controlAt(Point point) {
            return under;
          }
        });
  }

  @Test
  void theMenuItemsMoveTheTabToANewWindow() {
    DocumentController shown = controller.activeDocument();
    MenuItem item = menuItem(first.shell().getMenuBar(), CommandId.MOVE_TO_NEW_WINDOW);
    assertNotNull(item);
    assertTrue(item.getEnabled());
    item.notifyListeners(SWT.Selection, new Event());
    assertEquals(2, windows.size());
    MainWindow second = windows.get(1);
    assertSame(shown, second.canvas().document());
    assertEquals(1, second.tabs().getItemCount());
    assertFalse(second.tabs().getVisible(), "one tab hides the strip");
    assertEquals(1, first.tabs().getItemCount());
    assertTrue(first.canvas().document() != shown);
    assertFalse(shown.isClosed());

    DocumentController last = documentOf(first.tabs(), 0);
    Menu menu = first.showTabMenu(last, 0, 0);
    menu.setVisible(false);
    MenuItem[] items = menu.getItems();
    items[items.length - 1].notifyListeners(SWT.Selection, new Event());
    assertEquals(3, windows.size());
    assertSame(last, windows.get(2).canvas().document());
    assertEquals(0, first.tabs().getItemCount());
    assertTrue(first.canvas() == null, "the emptied window shows no views");
    assertFalse(first.shell().isDisposed(), "the emptied window stays open");
    assertFalse(menuItem(first.shell().getMenuBar(), CommandId.MOVE_TO_NEW_WINDOW).getEnabled());
  }

  @Test
  void aTabDraggedOutsideEveryWindowOpensANewWindow() {
    CTabFolder tabs = first.tabs();
    DocumentController dragged = documentOf(tabs, 0);
    Rectangle bounds = first.shell().getBounds();
    probe(first, null);
    drag(tabs, 0, new Point(bounds.x + bounds.width - 5, bounds.y + bounds.height - 5));
    assertEquals(1, windows.size(), "a release in its own window's bounds does nothing");
    drag(tabs, 0, new Point(bounds.x + bounds.width + 40, bounds.y + 30));
    assertEquals(2, windows.size());
    assertSame(dragged, windows.get(1).canvas().document());
    assertEquals(1, tabs.getItemCount());
    assertFalse(tabs.getVisible());
  }

  @Test
  void aTabDroppedOnAnotherWindowsStripMovesThereAndShowsThere() throws Exception {
    controller.execute(CommandId.NEW_WINDOW);
    MainWindow second = windows.get(1);
    final ApplicationController target = set.windows().get(1);
    target.open(
        List.of(
            new FileRequest(log("third.log", "gamma"), 0),
            new FileRequest(log("fourth.log", "delta"), 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return target.documents().size() == 2
                    && !target.activeDocument().state().indexing();
              }
            }));
    CTabFolder strip = second.tabs();
    assertTrue(strip.getVisible());
    // GTK 4 opens both windows at 0,0, so a release over the other strip would also hit this one.
    DocumentController appended = documentOf(first.tabs(), 1);
    probe(first, second.canvas().control());
    drag(first.tabs(), 1, first.tabs().toDisplay(10, -400));
    assertSame(appended, target.documents().get(2), "a drop off the other strip appends");
    assertSame(appended, second.canvas().document());
    assertEquals(1, first.tabs().getItemCount());
    shoot(first.shell(), "tabmove-source.png");
    shoot(second.shell(), "tabmove-target.png");

    DocumentController dragged = documentOf(first.tabs(), 0);
    Rectangle over = strip.getItem(1).getBounds();
    first.dropTab(
        dragged, strip.toDisplay(over.x + over.width / 2, over.y + over.height / 2), strip);
    assertEquals(4, strip.getItemCount());
    assertSame(dragged, documentOf(strip, 1));
    assertSame(dragged, target.documents().get(1));
    assertSame(dragged, second.canvas().document());
    assertEquals(0, first.tabs().getItemCount());
    assertFalse(dragged.isClosed());
  }

  @Test
  void aDragInsideTheStripStillReorders() {
    CTabFolder tabs = first.tabs();
    DocumentController moved = documentOf(tabs, 0);
    first.setPointerProbe(
        new MainWindow.PointerProbe() {
          @Override
          public Control controlAt(Point point) {
            throw new AssertionError("a reorder does not look for another window");
          }
        });
    Rectangle to = tabs.getItem(1).getBounds();
    drag(tabs, 0, tabs.toDisplay(to.x + to.width / 2, to.y + to.height / 2));
    assertSame(moved, documentOf(tabs, 1));
    assertSame(moved, controller.documents().get(1));
    assertEquals(1, windows.size());
  }

  private static MenuItem menuItem(Menu bar, CommandId command) {
    for (MenuItem header : bar.getItems()) {
      Menu menu = header.getMenu();
      if (menu == null) {
        continue;
      }
      for (MenuItem item : menu.getItems()) {
        if (item.getData() == command) {
          return item;
        }
      }
    }
    return null;
  }

  /** Writes a PNG of a shell when {@code qlogg.shots} names a directory. */
  private void shoot(Shell shell, String name) {
    String dir = System.getProperty("qlogg.shots");
    if (dir == null) {
      return;
    }
    while (display.readAndDispatch()) {
      // Flush pending paints.
    }
    Point size = shell.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      shell.print(gc);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(Path.of(dir, name).toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }
}
