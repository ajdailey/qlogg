package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandGroup;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.EncodingChoices;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MainMenuBar}: showing a top menu syncs that menu only, and the Encoding menu's charset
 * items survive every show while the radio selection follows the shown file's forced charset, and
 * the Other cascade stays disabled and empty until the charset list arrives from its worker. The
 * command items carry their icons where the platform shows menu images: on GTK 3, and on no item on
 * GTK 4.
 */
@Tag("swt")
class MainMenuBarTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
            Preferences.DEFAULTS);
  }

  /** Attaches the window and opens it; a test that gates the encoding list sets it first. */
  private void attach() {
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
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void showingATopMenuSyncsThatMenuOnly() {
    attach();
    Menu file = window.recentMenu().getParentItem().getParent();
    Menu view = topMenu(CommandGroup.VIEW);
    Menu highlighters = topMenu(CommandGroup.HIGHLIGHT);
    MenuItem overview = item(view, CommandId.TOGGLE_OVERVIEW);
    show(view);
    assertTrue(overview.getSelection());
    HighlightSet set = HighlightSet.create("Mock set");
    controller.setHighlightSettings(controller.highlightSettings().withSets(List.of(set)));
    controller.execute(CommandId.TOGGLE_OVERVIEW);
    show(file);
    assertTrue(overview.getSelection());
    assertEquals(0, setItems(highlighters));
    show(view);
    assertFalse(overview.getSelection());
    assertEquals(0, setItems(highlighters));
    show(highlighters);
    assertEquals(1, setItems(highlighters));
  }

  @Test
  void encodingItemsAreBuiltOnceAndFollowTheForcedCharset() throws Exception {
    attach();
    Menu encoding = window.encodingMenu();
    MenuItem auto = item(encoding, CommandId.ENCODING_AUTO);
    MenuItem other = encoding.getItem(encoding.getItemCount() - 1);
    final Menu all = other.getMenu();
    assertNotNull(all);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return all.getItemCount() > 0;
              }
            },
            BOUND));
    int count = all.getItemCount();
    MenuItem utf16 = charsetItem(encoding, "UTF-16LE");
    show(encoding);
    assertFalse(auto.getEnabled());
    assertFalse(other.getEnabled());
    assertFalse(utf16.getEnabled());

    Path log = Files.writeString(temp.resolve("encoding.log"), "one\ntwo\n");
    controller.openFile(log, 0);
    awaitEncoding(null);
    show(encoding);
    assertTrue(auto.getSelection());
    assertTrue(other.getEnabled());
    assertEquals(List.of(), selected(encoding));

    controller.setEncoding(controller.activeDocument(), StandardCharsets.UTF_16LE);
    awaitEncoding("UTF-16LE");
    show(encoding);
    assertFalse(auto.getSelection());
    assertEquals(List.of("UTF-16LE"), selected(encoding));

    MenuItem chosen = all.getItem(0);
    chosen.setSelection(true);
    chosen.notifyListeners(SWT.Selection, new Event());
    awaitEncoding(((Charset) chosen.getData()).name());
    show(encoding);
    assertEquals(List.of(chosen.getText()), selected(encoding));

    assertSame(other, encoding.getItem(encoding.getItemCount() - 1));
    assertSame(all, other.getMenu());
    assertEquals(count, all.getItemCount());
    assertSame(utf16, charsetItem(encoding, "UTF-16LE"));
  }

  @Test
  void otherFillsInWhenTheCharsetListArrives() throws Exception {
    final List<EncodingChoices.Listener> waiting = new ArrayList<EncodingChoices.Listener>();
    window.setEncodingSource(
        new MainWindow.EncodingSource() {
          @Override
          public void load(EncodingChoices.Listener listener) {
            waiting.add(listener);
          }
        });
    attach();
    assertEquals(1, waiting.size());
    Menu encoding = window.encodingMenu();
    MenuItem other = encoding.getItem(encoding.getItemCount() - 1);
    Path log = Files.writeString(temp.resolve("gated.log"), "one\ntwo\n");
    controller.openFile(log, 0);
    awaitEncoding(null);
    controller.setEncoding(controller.activeDocument(), StandardCharsets.UTF_16LE);
    awaitEncoding("UTF-16LE");
    show(encoding);
    assertFalse(other.getEnabled());
    assertEquals(0, other.getMenu().getItemCount());
    assertTrue(charsetItem(encoding, "UTF-8").getEnabled());
    assertFalse(item(encoding, CommandId.ENCODING_AUTO).getSelection());
    assertEquals(List.of("UTF-16LE"), selected(encoding));

    Charset extra = Charset.forName("IBM437");
    EncodingChoices choices =
        new EncodingChoices(
            List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE), List.of(extra));
    waiting.get(0).loaded(choices);
    assertTrue(other.getEnabled());
    assertEquals(1, other.getMenu().getItemCount());
    assertEquals(extra, other.getMenu().getItem(0).getData());
    waiting.get(0).loaded(choices);
    assertEquals(1, other.getMenu().getItemCount());
    assertEquals(List.of("UTF-16LE"), selected(encoding));
  }

  // Menu item icons.

  @Test
  void commandItemsCarryTheirIconsWhereThePlatformShowsMenuImages() {
    attach();
    boolean images = PlatformTraits.PLATFORM.menuImages();
    if ("gtk".equals(SWT.getPlatform())) {
      assertEquals(!Gtk4.active(), images);
    }
    int iconed = 0;
    for (MenuItem header : window.shell().getMenuBar().getItems()) {
      Menu menu = header.getMenu();
      boolean any = false;
      for (MenuItem item : menu.getItems()) {
        any |= item.getData() instanceof CommandId command && MenuIcons.icon(command) != null;
      }
      for (MenuItem item : menu.getItems()) {
        if ((item.getStyle() & SWT.SEPARATOR) != 0) {
          continue;
        }
        boolean icon =
            item.getData() instanceof CommandId command && MenuIcons.icon(command) != null;
        iconed += icon ? 1 : 0;
        String name = header.getText() + " > " + item.getText();
        if (!images) {
          assertEquals(null, item.getImage(), name);
        } else if (icon) {
          assertNotNull(item.getImage(), name);
        } else if (any && PlatformTraits.PLATFORM.padsMenuImages()) {
          assertNotNull(item.getImage(), name + " holds the blank image");
        }
      }
    }
    assertTrue(iconed >= 13, "icon items on the menu bar: " + iconed);
  }

  /** Sends the menu's Show event, as opening it does. */
  private static void show(Menu menu) {
    menu.notifyListeners(SWT.Show, new Event());
  }

  private Menu topMenu(CommandGroup group) {
    for (MenuItem header : window.shell().getMenuBar().getItems()) {
      if (header.getText().equals(group.label())) {
        return header.getMenu();
      }
    }
    throw new AssertionError(group.name());
  }

  private static MenuItem item(Menu menu, CommandId command) {
    for (MenuItem item : menu.getItems()) {
      if (item.getData() == command) {
        return item;
      }
    }
    throw new AssertionError(command.name());
  }

  private static int setItems(Menu menu) {
    int count = 0;
    for (MenuItem item : menu.getItems()) {
      if (item.getData() instanceof HighlightSet) {
        count++;
      }
    }
    return count;
  }

  private static MenuItem charsetItem(Menu encoding, String name) {
    for (MenuItem item : encoding.getItems()) {
      if (item.getData() instanceof Charset charset && charset.name().equals(name)) {
        return item;
      }
    }
    throw new AssertionError(name);
  }

  /** Names of the selected charset items in the menu and its Other cascade. */
  private static List<String> selected(Menu encoding) {
    List<String> names = new ArrayList<String>();
    for (MenuItem item : encoding.getItems()) {
      if (item.getData() instanceof Charset && item.getSelection()) {
        names.add(item.getText());
      }
      if (item.getMenu() != null) {
        names.addAll(selected(item.getMenu()));
      }
    }
    return names;
  }

  /** Waits until the shown file is indexed with a forced charset, or detected when null. */
  private void awaitEncoding(final String forced) {
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                if (shown == null || shown.state().indexing() || window.canvas() == null) {
                  return false;
                }
                return forced == null
                    ? !shown.state().encodingForced()
                    : shown.state().encodingForced() && shown.state().encodingName().equals(forced);
              }
            },
            BOUND),
        String.valueOf(forced));
  }
}
