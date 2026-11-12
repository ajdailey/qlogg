package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.Binding;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.FoldRulesText;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.theme.ColorSchemes;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.FoldRules;
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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Preferences dialog's shortcut editor, font zoom, margin clicks and the Encoding, Favorites
 * and Recent Files menus, under Xvfb. With {@code -Dqlogg.shots=<dir>} it also writes PNGs of the
 * window and the dialog.
 */
@Tag("swt")
class PreferencesAndMenusTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Path log;

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
    for (int i = 0; i < 50; i++) {
      text.append("2026-11-01 12:00:").append(i % 60).append(" INFO mock entry ").append(i);
      text.append('\n');
    }
    log = Files.writeString(temp.resolve("service.log"), text.toString());
    controller.open(List.of(new FileRequest(log, 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != null && !shown.state().indexing() && window.canvas() != null;
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

  // Regression: Licenses from the modal About opened a modeless window that GTK showed behind
  // About, with no input.
  @Test
  void textOverAModalDialogOpensModalAndOwnedByIt() {
    Shell about = Dialogs.shell(window.shell(), "About", true);
    try {
      Dialogs.open(about, SWT.DEFAULT, SWT.DEFAULT);
      window.showText("Licenses", "mock license text");
      Shell[] owned = about.getShells();
      assertEquals(1, owned.length);
      assertTrue((owned[0].getStyle() & SWT.APPLICATION_MODAL) != 0);
      assertTrue(owned[0].isVisible());
    } finally {
      about.dispose();
    }
  }

  // Regression: dialogs and secondary windows, Keyboard Shortcuts among them, showed the
  // window manager's generic icon instead of the application icon.
  @Test
  void dialogsAndSecondaryWindowsCarryTheApplicationIcons() {
    Image[] icons = window.shell().getImages();
    assertTrue(icons.length > 0);
    Shell modal = Dialogs.shell(window.shell(), "Modal", true);
    ShortcutsWindow shortcuts = new ShortcutsWindow(window.shell(), Keymap.defaults(), window);
    try {
      assertArrayEquals(icons, modal.getImages());
      assertArrayEquals(icons, shortcuts.shell().getImages());
      Shell nested = Dialogs.shell(modal, "Nested", true);
      assertArrayEquals(icons, nested.getImages());
    } finally {
      shortcuts.shell().dispose();
      modal.dispose();
    }
  }

  @Test
  void aClickInTheBulletColumnTogglesTheMark() {
    LogCanvas canvas = window.canvas();
    final DocumentController document = controller.activeDocument();
    Event event = new Event();
    event.button = 1;
    event.x = 1;
    event.y = canvas.lineHeight() * 2 + 1;
    canvas.control().notifyListeners(SWT.MouseDown, event);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.marks().contains(2) && !document.markingRunning();
              }
            }),
        "caret "
            + document.selection()
            + " marks "
            + document.marks().size()
            + " lines "
            + document.viewport().lineCount()
            + " bullet "
            + canvas.bulletWidth());
    canvas.control().notifyListeners(SWT.MouseDown, event);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.marks().size() == 0 && !document.markingRunning();
              }
            }));
  }

  @Test
  void ctrlWheelZoomsTheFontInPlace() {
    final LogCanvas main = window.canvas();
    final LogCanvas filtered = window.results().canvas();
    Menu bar = window.shell().getMenuBar();
    filtered.control().setFocus();
    // Xvfb without a window manager may not deliver FocusIn; the view tracks focus from it.
    filtered.control().notifyListeners(SWT.FocusIn, new Event());
    assertSame(filtered.document(), controller.focusedView());
    Control focus = display.getFocusControl();
    int height = filtered.lineHeight();
    int rows = main.document().viewport().rows();
    Event event = new Event();
    event.stateMask = SWT.MOD1;
    event.count = 3;
    for (int i = 0; i < 3; i++) {
      filtered.control().notifyListeners(SWT.MouseVerticalWheel, event);
    }
    assertEquals(Dialogs.monospaceSize() + 3, controller.preferences().options().font().zoom());
    assertTrue(filtered.lineHeight() > height);
    assertTrue(main.document().viewport().rows() < rows);
    // Let a queued rebuild run before checking that none happened.
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return false;
          }
        },
        Duration.ofMillis(200));
    assertSame(main, window.canvas());
    assertSame(filtered, window.results().canvas());
    assertSame(bar, window.shell().getMenuBar());
    assertSame(filtered.document(), controller.focusedView());
    assertSame(focus, display.getFocusControl());
    shoot(window.shell(), "zoom-in-place.png");
    controller.execute(CommandId.ZOOM_RESET);
    assertEquals(0, controller.preferences().options().font().zoom());
    assertEquals(height, filtered.lineHeight());
  }

  @Test
  void menusListEncodingsFavoritesAndRecentFiles() throws Exception {
    window.syncMenu();
    Menu encoding = window.encodingMenu();
    assertNotNull(encoding);
    MenuItem auto = encoding.getItem(0);
    assertTrue(auto.getSelection());
    assertTrue(texts(encoding).contains("UTF-8"));
    assertTrue(texts(window.recentMenu()).contains(log.toAbsolutePath().normalize().toString()));
    controller.execute(CommandId.ADD_FAVORITE);
    window.syncMenu();
    List<String> favorites = texts(window.favoritesMenu());
    assertTrue(
        favorites.contains(log.toAbsolutePath().normalize().toString()), favorites.toString());
    assertFalse(window.favoritesMenu().getItem(0).getEnabled());
    assertTrue(window.favoritesMenu().getItem(1).getEnabled());
  }

  private static List<String> texts(Menu menu) {
    List<String> list = new java.util.ArrayList<String>();
    for (MenuItem item : menu.getItems()) {
      list.add(item.getText());
    }
    return list;
  }

  /** Returns the shortcut editor's list of the selected command's keys. */
  private static org.eclipse.swt.widgets.List keyList(Composite parent) {
    for (Control child : parent.getChildren()) {
      if (child instanceof org.eclipse.swt.widgets.List list) {
        return list;
      }
      if (child instanceof Composite composite) {
        org.eclipse.swt.widgets.List found = keyList(composite);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  @Test
  void theShortcutEditorRefusesTakenKeysAndAppliesFreeOnes() throws Exception {
    final PreferencesDialog dialog = new PreferencesDialog(window.shell(), controller);
    dialog.open();
    int row = -1;
    TableItem[] items = dialog.commands().getItems();
    for (int i = 0; i < items.length; i++) {
      if (items[i].getText(1).equals(CommandId.RELOAD.title())) {
        row = i;
      }
    }
    assertTrue(row >= 0);
    dialog.commands().setSelection(row);
    dialog.commands().notifyListeners(SWT.Selection, new Event());
    dialog.record(KeyStroke.parse("Ctrl+O"));
    dialog.addCaptured();
    assertTrue(dialog.message().getText().contains(CommandId.OPEN.title()));
    dialog.clearCaptured();
    dialog.record(KeyStroke.parse("Ctrl+Alt+R"));
    dialog.addCaptured();
    // A menu shows a command's first key, so the default key goes and the new one comes first.
    org.eclipse.swt.widgets.List keys = keyList(dialog.shell());
    keys.setSelection(0);
    dialog.removeSelected();
    dialog.folder().setSelection(3);
    shoot(dialog.shell(), "options-shortcuts.png");
    dialog.folder().setSelection(1);
    shoot(dialog.shell(), "options-view.png");
    dialog.apply();
    assertEquals(
        "Ctrl+Alt+R", controller.keymap().bindingsFor(CommandId.RELOAD).get(0).text(false));
    final String shown =
        controller.keymap().bindingsFor(CommandId.RELOAD).get(0).text(SwtKeys.mac());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return menuText(CommandId.RELOAD).contains(shown);
              }
            }));
    dialog.shell().dispose();
    shoot(window.shell(), "main.png");
  }

  // Tooltips after a shortcut change.

  @Test
  void searchRowTooltipsNameTheKeysAfterAShortcutChange() {
    final PreferencesDialog dialog = new PreferencesDialog(window.shell(), controller);
    dialog.open();
    TableItem[] items = dialog.commands().getItems();
    for (int i = 0; i < items.length; i++) {
      if (items[i].getText(1).equals(CommandId.TOGGLE_CASE.title())) {
        dialog.commands().setSelection(i);
      }
    }
    dialog.commands().notifyListeners(SWT.Selection, new Event());
    dialog.record(KeyStroke.parse("Ctrl+Alt+K"));
    dialog.addCaptured();
    assertTrue(dialog.apply());
    dialog.shell().dispose();
    List<Binding> bindings = controller.keymap().bindingsFor(CommandId.TOGGLE_CASE);
    final String keys = bindings.get(bindings.size() - 1).text(SwtKeys.mac());
    assertTrue(keys.contains("K"), keys);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.searchBar().toolTip(CommandId.TOGGLE_CASE).contains(keys);
              }
            }));
  }

  // Folding tab.

  @Test
  void theFoldingTabRefusesABadPatternAndAppliesAGoodOne() {
    final PreferencesDialog dialog = new PreferencesDialog(window.shell(), controller);
    dialog.open();
    assertEquals(
        FoldRulesText.continuationText(FoldRules.DEFAULTS),
        dialog.foldContinuation().getText().replace(Text.DELIMITER, "\n"));
    dialog.foldStart().setText("[unclosed");
    assertFalse(dialog.apply());
    assertTrue(dialog.foldError().getText().startsWith("Pattern [unclosed"));
    assertEquals(dialog.folder().getItemCount() - 1, dialog.folder().getSelectionIndex());
    assertEquals(FoldRules.DEFAULTS, controller.preferences().view().folds());
    shoot(dialog.shell(), "options-folding.png");

    dialog.foldStart().setText("^2026-");
    assertTrue(dialog.apply());
    assertEquals("", dialog.foldError().getText());
    FoldRules applied = controller.preferences().view().folds();
    assertEquals("^2026-", applied.entryStart());
    assertEquals(FoldRules.DEFAULTS.continuation(), applied.continuation());
    dialog.shell().dispose();
  }

  @Test
  void theAdvancedTabSetsKeepFileClosed() {
    final PreferencesDialog dialog = new PreferencesDialog(window.shell(), controller);
    dialog.open();
    assertFalse(dialog.keepClosed().getSelection());
    dialog.folder().setSelection(4);
    dialog.keepClosed().setSelection(true);
    shoot(dialog.shell(), "options-advanced.png");
    assertTrue(dialog.apply());
    assertTrue(controller.preferences().follow().keepClosed());
    dialog.shell().dispose();
  }

  private String menuText(CommandId command) {
    for (MenuItem header : window.shell().getMenuBar().getItems()) {
      for (MenuItem item : header.getMenu().getItems()) {
        if (item.getData() == command) {
          return item.getText();
        }
      }
    }
    return "";
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
      ImageData data = image.getImageData();
      loader.data = new ImageData[] {data};
      loader.save(Path.of(dir, name).toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  // View colors.

  @Test
  void theViewTabListsTheSchemesAndAppliesOneLive() {
    PreferencesDialog dialog = new PreferencesDialog(window.shell(), controller);
    dialog.open();
    Combo colors = dialog.viewColors();
    assertEquals(ColorSchemes.BUNDLED.size() + 3, colors.getItemCount());
    assertEquals("Follow theme", colors.getItem(0));
    assertEquals("Light", colors.getItem(1));
    assertEquals("Dark", colors.getItem(2));
    assertEquals(ColorSchemes.BUNDLED.get(0).name(), colors.getItem(3));
    assertEquals(0, colors.getSelectionIndex());
    colors.select(2);
    assertTrue(dialog.apply());
    assertEquals("dark", controller.preferences().view().colors());
    assertSame(Palette.DARK, window.palette());
    colors.select(colors.indexOf(ColorSchemes.NONE.find("phosphor").name()));
    assertTrue(dialog.apply());
    Palette phosphor = ColorSchemes.NONE.find("phosphor").palette();
    assertEquals("phosphor", controller.preferences().view().colors());
    assertSame(phosphor, window.palette());
    colors.select(0);
    assertTrue(dialog.apply());
    assertSame(Palette.LIGHT, window.palette());
    dialog.shell().dispose();
  }

  // Predefined Filters focus.

  @Test
  void predefinedFiltersOpensWithTheTableFocused() {
    final List<Shell> before = List.of(display.getShells());
    controller.execute(CommandId.PREDEFINED_FILTERS);
    final Shell[] dialog = new Shell[1];
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                for (Shell shell : display.getShells()) {
                  if (!before.contains(shell) && shell.isVisible()) {
                    dialog[0] = shell;
                    shell.forceActive();
                    return display.getFocusControl() instanceof Table;
                  }
                }
                return false;
              }
            }),
        "focus " + display.getFocusControl());
    dialog[0].dispose();
  }
}
