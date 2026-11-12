package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Keys column of Help > Keyboard Shortcuts and the command palette: whole at the default size
 * for a 1100 x 700 screen, and a tool tip with the full keys where a 640 x 480 screen cuts a cell.
 */
@Tag("swt")
class TableFitTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final Rectangle WIDE = new Rectangle(0, 0, 1100, 700);
  private static final Rectangle SMALL = new Rectangle(0, 0, 640, 480);

  /** Pixels of the Keys column left in view when a test narrows the palette. */
  private static final int KEYS_SHOWN = 40;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() {
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
  }

  @AfterEach
  void close() {
    controller.shutdown();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        },
        BOUND);
    display.dispose();
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void shortcutsShowEveryKeyAtTheDefaultSize() {
    ShortcutsWindow shortcuts = new ShortcutsWindow(window.shell(), Keymap.defaults(), window);
    try {
      shortcuts.open(WIDE);
      assertWhole(shortcuts.shell(), shortcuts.table());
    } finally {
      shortcuts.shell().dispose();
    }
  }

  @Test
  void paletteShowsEveryKeyAtTheDefaultSize() {
    CommandPaletteWindow palette = palette();
    try {
      palette.open(WIDE);
      assertWhole(palette.shell(), palette.table());
    } finally {
      palette.shell().dispose();
    }
  }

  @Test
  void aShortcutCutOffInANarrowedDialogShowsItsKeysAsATip() {
    ShortcutsWindow shortcuts = new ShortcutsWindow(window.shell(), Keymap.defaults(), window);
    try {
      shortcuts.open(SMALL);
      shortcuts.shell().setSize(420, shortcuts.shell().getSize().y);
      assertCutKeysTip(shortcuts.shell(), shortcuts.table());
    } finally {
      shortcuts.shell().dispose();
    }
  }

  // Narrowed so the Keys column starts in view and is cut: win32's narrower font fits the palette's
  // longest keys at 640 px, and win32 finds no row under a point past the client area.
  @Test
  void aPaletteKeyCutOffInANarrowedDialogShowsItsKeysAsATip() {
    CommandPaletteWindow palette = palette();
    try {
      palette.open(SMALL);
      Table table = palette.table();
      settle(palette.shell(), table);
      int keysStart = 0;
      for (int column = 0; column < table.getColumnCount() - 1; column++) {
        keysStart += table.getColumn(column).getWidth();
      }
      int excess = table.getClientArea().width - (keysStart + KEYS_SHOWN);
      Point size = palette.shell().getSize();
      palette.shell().setSize(size.x - excess, size.y);
      assertCutKeysTip(palette.shell(), table);
    } finally {
      palette.shell().dispose();
    }
  }

  private CommandPaletteWindow palette() {
    return new CommandPaletteWindow(
        window.shell(),
        controller.commandPalette(),
        Keymap.defaults(),
        new SwtUiDispatcher(display));
  }

  /** Every cell of every column shows its whole text, and the dialog fits the screen. */
  private void assertWhole(Shell shell, Table table) {
    settle(shell, table);
    assertTrue(shell.getSize().x <= WIDE.width, "width " + shell.getSize().x);
    GC gc = new GC(table);
    try {
      for (TableItem item : table.getItems()) {
        for (int column = 0; column < table.getColumnCount(); column++) {
          int text = gc.textExtent(item.getText(column)).x;
          int shown = TableFit.shownWidth(item, column);
          assertTrue(
              text <= shown, "'" + item.getText(column) + "' needs " + text + ", shows " + shown);
          assertNull(TableFit.clippedText(item, column));
        }
      }
    } finally {
      gc.dispose();
    }
  }

  /**
   * The dialog fits the screen, the longest Keys cell is cut, and a pointer move over it sets the
   * table's tool tip to its full text.
   */
  private void assertCutKeysTip(Shell shell, Table table) {
    settle(shell, table);
    assertTrue(shell.getSize().x <= SMALL.width, "width " + shell.getSize().x);
    int keys = table.getColumnCount() - 1;
    TableItem longest = null;
    for (TableItem item : table.getItems()) {
      if (longest == null || item.getText(keys).length() > longest.getText(keys).length()) {
        longest = item;
      }
    }
    assertNotNull(longest);
    final TableItem cut = longest;
    final Table shown = table;
    boolean inView =
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                Rectangle client = shown.getClientArea();
                Rectangle cell = cut.getBounds(0);
                int middle = cell.y + cell.height / 2;
                if (middle >= client.y && middle < client.y + client.height) {
                  return true;
                }
                shown.showItem(cut);
                return false;
              }
            },
            BOUND);
    assertTrue(inView, "row never scrolled into view");
    assertEquals(cut.getText(keys), TableFit.clippedText(cut, keys), "cell not cut off");
    Rectangle cell = cut.getBounds(keys);
    Event move = new Event();
    move.x = cell.x + 2;
    move.y = cell.y + cell.height / 2;
    table.notifyListeners(SWT.MouseMove, move);
    assertEquals(cut.getText(keys), table.getToolTipText());
    table.notifyListeners(SWT.MouseExit, new Event());
    assertNull(table.getToolTipText());
  }

  /**
   * Runs the event loop until the table's width holds and its columns and rows have bounds; GTK 4
   * reports row bounds only after a later layout pass.
   */
  private void settle(final Shell shell, final Table table) {
    final int[] last = {-1};
    final int[] same = {0};
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                int width = table.getClientArea().width;
                same[0] = width > 0 && width == last[0] ? same[0] + 1 : 0;
                last[0] = width;
                return shell.isVisible()
                    && same[0] >= 5
                    && table.getColumn(0).getWidth() > 0
                    && table.getItem(0).getBounds(0).width > 0;
              }
            },
            BOUND),
        "table never laid out");
  }
}
