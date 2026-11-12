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
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
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

/** Go Back, File > Recently Closed and the command palette in the real window, under Xvfb. */
@Tag("swt")
class NavigationGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Path first;

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
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      text.append("line ").append(i).append('\n');
    }
    first = Files.writeString(temp.resolve("first.log"), text);
    Path second = Files.writeString(temp.resolve("second.log"), "x\ny\n");
    controller.open(List.of(new FileRequest(first, 0), new FileRequest(second, 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return controller.documents().size() == 2
                    && shown != null
                    && shown.state().title().equals("second.log")
                    && !controller.documents().get(0).state().indexing()
                    && !shown.state().indexing();
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

  private MenuItem item(CommandId command) {
    for (MenuItem header : window.shell().getMenuBar().getItems()) {
      MenuItem found = find(header.getMenu(), command);
      if (found != null) {
        return found;
      }
    }
    throw new AssertionError(command.name());
  }

  private static MenuItem find(Menu menu, CommandId command) {
    for (MenuItem item : menu.getItems()) {
      if (item.getData() == command) {
        return item;
      }
      if (item.getMenu() != null) {
        MenuItem nested = find(item.getMenu(), command);
        if (nested != null) {
          return nested;
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

  @Test
  void goBackCarriesItsKeyAndReturnsToTheLineBeforeAJump() {
    final DocumentController document = controller.documents().get(0);
    controller.activate(document);
    document.jumpToLine(151);
    assertEquals(150, document.selection().caret().line());
    MenuItem back = item(CommandId.GO_BACK);
    int expected = SwtKeys.mac() ? SWT.MOD4 | SWT.MOD1 | SWT.ARROW_LEFT : SWT.MOD3 | SWT.ARROW_LEFT;
    assertEquals(expected, back.getAccelerator());
    assertTrue(back.getEnabled());
    back.notifyListeners(SWT.Selection, new Event());
    assertEquals(0, document.selection().caret().line());
    item(CommandId.GO_FORWARD).notifyListeners(SWT.Selection, new Event());
    assertEquals(150, document.selection().caret().line());
  }

  @Test
  void goBackIsEnabledRightAfterAJumpAndFollowsItsEntries() {
    final DocumentController document = controller.documents().get(0);
    controller.activate(document);
    MenuItem back = item(CommandId.GO_BACK);
    MenuItem forward = item(CommandId.GO_FORWARD);
    assertFalse(back.getEnabled());
    assertFalse(forward.getEnabled());
    // No menu shows between these steps: navigationChanged alone keeps the accelerators live.
    document.jumpToLine(151);
    assertTrue(back.getEnabled());
    assertFalse(forward.getEnabled());
    back.notifyListeners(SWT.Selection, new Event());
    assertEquals(0, document.selection().caret().line());
    assertFalse(back.getEnabled());
    assertTrue(forward.getEnabled());
  }

  // Reopen Closed Tab.

  @Test
  void reopenClosedTabCarriesItsKeyAndReopensTheClosedFile() {
    MenuItem reopen = item(CommandId.REOPEN_CLOSED);
    int expected = SWT.MOD1 | SWT.MOD2 | 'T';
    assertEquals(expected, reopen.getAccelerator());
    assertFalse(reopen.getEnabled());
    controller.closeDocument(controller.documents().get(0));
    assertTrue(reopen.getEnabled());
    reopen.notifyListeners(SWT.Selection, new Event());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.documents().size() == 2;
              }
            }));
    assertEquals(List.of(), controller.preferences().state().closedFiles());
    window.syncMenu();
    assertFalse(reopen.getEnabled());
  }

  @Test
  void recentlyClosedListsAClosedFileAndOpenAllReopensIt() {
    controller.closeDocument(controller.documents().get(0));
    window.syncMenu();
    Menu closed = window.menuBar().closedMenu();
    assertNotNull(closed);
    MenuItem[] items = closed.getItems();
    assertEquals(first.toAbsolutePath().normalize().toString(), items[0].getText());
    assertEquals(SWT.SEPARATOR, items[1].getStyle() & SWT.SEPARATOR);
    assertEquals(CommandId.OPEN_ALL_CLOSED, items[2].getData());
    assertEquals(CommandId.CLEAR_CLOSED, items[3].getData());
    assertTrue(items[2].getEnabled());
    items[2].notifyListeners(SWT.Selection, new Event());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.documents().size() == 2;
              }
            }));
    window.syncMenu();
    assertEquals(UiText.CLOSED_NONE.text(), closed.getItems()[0].getText());
    assertTrue(!item(CommandId.CLEAR_CLOSED).getEnabled());
  }

  @Test
  void thePaletteFiltersCommandsAndRunsTheChosenOne() {
    controller.execute(CommandId.COMMAND_PALETTE);
    final CommandPaletteWindow palette = window.commandPalette();
    assertNotNull(palette);
    shoot(palette.shell(), "palette.png");
    palette.filter().setText("previous tab");
    assertEquals("Previous Tab", palette.table().getItem(0).getText(0));
    assertEquals("Window", palette.table().getItem(0).getText(1));
    assertTrue(palette.table().getItem(0).getText(2).contains("PgUp"));
    shoot(palette.shell(), "palette-filtered.png");
    palette.filter().notifyListeners(SWT.DefaultSelection, new Event());
    assertTrue(palette.shell().isDisposed());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.activeDocument().state().title().equals("first.log");
              }
            }));
  }
}
