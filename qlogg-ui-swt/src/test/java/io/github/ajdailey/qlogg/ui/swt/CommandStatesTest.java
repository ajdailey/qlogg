package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Records the checked and enabled state of every check item and file command in the menu bar, read
 * after {@link MainWindow#syncMenu} with no document, with a document and with its filtered view
 * focused. Each state is two characters: {@code c} or {@code -} for checked, {@code e} or {@code -}
 * for enabled. A mismatch prints the whole recorded table.
 */
@Tag("swt")
class CommandStatesTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** Per command: no document, a document, the filtered view focused. */
  private static final Map<CommandId, String> EXPECTED =
      new EnumMap<CommandId, String>(CommandId.class);

  static {
    // Recorded from MainWindow.syncMenu over Preferences.DEFAULTS.
    row(CommandId.COPY_PATH, "-- -e -e");
    row(CommandId.COPY_FILE_NAME, "-- -e -e");
    row(CommandId.OPEN_FOLDER, "-- -e -e");
    row(CommandId.OPEN_IN_EDITOR, "-- -e -e");
    row(CommandId.TOGGLE_CASE, "-- ce ce");
    row(CommandId.TOGGLE_REGEX, "-- ce ce");
    row(CommandId.TOGGLE_INVERSE, "-- -e -e");
    row(CommandId.TOGGLE_COMBINING, "-- -e -e");
    row(CommandId.TOGGLE_AUTO_REFRESH, "-- -e -e");
    row(CommandId.TOGGLE_KEEP_RESULTS, "-- -e -e");
    row(CommandId.RUN_SEARCH_ON_ADD, "ce ce ce");
    row(CommandId.FOLLOW, "-- -e -e");
    row(CommandId.FOLLOW_ON_LOAD, "-e -e -e");
    row(CommandId.FOLLOW_ON_OVERSCROLL, "ce ce ce");
    row(CommandId.RELOAD, "-- -e -e");
    row(CommandId.TOGGLE_OVERVIEW, "ce ce ce");
    row(CommandId.TOGGLE_MATCH_HIGHLIGHT, "ce ce ce");
    row(CommandId.TOGGLE_LINE_NUMBERS, "-- ce ce");
    row(CommandId.TOGGLE_FILTERED_LINE_NUMBERS, "-- ce ce");
    row(CommandId.TOGGLE_WRAP, "-- -e -e");
    row(CommandId.WRAP_ON_OPEN, "-e -e -e");
    row(CommandId.TOGGLE_WHITESPACE, "-e -e -e");
    row(CommandId.HIDE_ANSI, "-e -e -e");
    row(CommandId.SHOW_MARKS_AND_MATCHES, "-- ce ce");
    row(CommandId.SHOW_MARKS, "-- -e -e");
    row(CommandId.SHOW_MATCHES, "-- -e -e");
    row(CommandId.THEME_LIGHT, "ce ce ce");
    row(CommandId.THEME_DARK, "-e -e -e");
    row(CommandId.THEME_SYSTEM, "-e -e -e");
    row(CommandId.NO_HIGHLIGHTERS, "ce ce ce");
    row(CommandId.ENCODING_AUTO, "-- ce ce");
    row(CommandId.DEBUG_LOG_FILE, "-e -e -e");
    row(CommandId.BLOCK_SELECTION, "-e -e -e");
  }

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
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
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
  void checkAndFileItemsFollowTheDocumentAndFocus() throws Exception {
    Map<CommandId, StringBuilder> recorded = new EnumMap<CommandId, StringBuilder>(CommandId.class);
    record(recorded);
    Path file = Files.writeString(temp.resolve("states.log"), "one\ntwo\n");
    controller.openFile(file, 0);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != null && !shown.state().indexing() && window.canvas() != null;
              }
            },
            BOUND));
    record(recorded);
    controller.setFocusedView(controller.activeDocument().filtered());
    record(recorded);
    Map<CommandId, String> actual = new EnumMap<CommandId, String>(CommandId.class);
    StringBuilder table = new StringBuilder();
    for (Map.Entry<CommandId, StringBuilder> entry : recorded.entrySet()) {
      String states = entry.getValue().toString();
      actual.put(entry.getKey(), states);
      table.append(String.format("    row(CommandId.%s, \"%s\");%n", entry.getKey(), states));
    }
    assertEquals(EXPECTED, actual, "recorded:\n" + table);
  }

  /** Appends each item's state after a menu sync. */
  private void record(Map<CommandId, StringBuilder> into) {
    window.syncMenu();
    collect(window.shell().getMenuBar(), into);
  }

  private static void collect(Menu menu, Map<CommandId, StringBuilder> into) {
    for (MenuItem item : menu.getItems()) {
      if (item.getMenu() != null) {
        collect(item.getMenu(), into);
      }
      if (!(item.getData() instanceof CommandId command)) {
        continue;
      }
      boolean check = (item.getStyle() & SWT.CHECK) != 0;
      if (!check && !FILE_ITEMS.contains(command)) {
        continue;
      }
      StringBuilder states = into.get(command);
      if (states == null) {
        states = new StringBuilder();
        into.put(command, states);
      } else {
        states.append(' ');
      }
      states.append(check && item.getSelection() ? 'c' : '-');
      states.append(item.getEnabled() ? 'e' : '-');
    }
  }

  private static final List<CommandId> FILE_ITEMS =
      List.of(
          CommandId.RELOAD,
          CommandId.COPY_PATH,
          CommandId.COPY_FILE_NAME,
          CommandId.OPEN_FOLDER,
          CommandId.OPEN_IN_EDITOR);

  private static void row(CommandId command, String states) {
    EXPECTED.put(command, states);
  }
}
