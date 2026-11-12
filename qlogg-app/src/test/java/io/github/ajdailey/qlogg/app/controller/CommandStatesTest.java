package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CommandStates} over {@code Preferences.DEFAULTS}: every check command, the file commands,
 * Move to New Window, the favorites and Close Multiple Tabs, with no document, with a document and
 * with its filtered view focused, in the notation of the SWT menu bar's {@code CommandStatesTest};
 * then the states following the controllers, clipboard text, a forced encoding and the listed
 * entries.
 */
class CommandStatesTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** Per command: no document, a document, the filtered view focused. */
  private static final Map<CommandId, String> EXPECTED =
      new EnumMap<CommandId, String>(CommandId.class);

  static {
    // The rows the menu bar recorded, plus the push items CommandStates enables.
    row(CommandId.RELOAD, "-- -e -e");
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
    row(CommandId.TOGGLE_RANGE, "-- -e -e");
    row(CommandId.RUN_SEARCH_ON_ADD, "ce ce ce");
    row(CommandId.FOLLOW, "-- -e -e");
    row(CommandId.FOLLOW_ON_LOAD, "-e -e -e");
    row(CommandId.FOLLOW_ON_OVERSCROLL, "ce ce ce");
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
    // Time spread.
    row(CommandId.TIME_SPREAD_OFF, "ce ce ce");
    row(CommandId.TIME_SPREAD_ACTIVITY, "-e -e -e");
    row(CommandId.TIME_SPREAD_PAUSES, "-e -e -e");
    // Always on Top.
    row(CommandId.ALWAYS_ON_TOP, "-e -e -e");
    row(CommandId.MOVE_TO_NEW_WINDOW, "-- -e -e");
    row(CommandId.ADD_FAVORITE, "-- -e -e");
    row(CommandId.REMOVE_FAVORITE, "-- -- --");
    // Close Multiple Tabs.
    row(CommandId.CLOSE_OTHERS, "-- -- --");
    row(CommandId.CLOSE_LEFT, "-- -- --");
    row(CommandId.CLOSE_RIGHT, "-- -- --");
  }

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private FakeView view;
  private ManualUiDispatcher ui;
  private Workers workers;
  private ApplicationController application;
  private CommandStates states;

  @BeforeEach
  void create() {
    view = new FakeView();
    ui = new ManualUiDispatcher();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    application =
        new ApplicationController(
            workers,
            ui,
            view,
            ApplicationControllerTest.services(workers, MemoryBudget.of(1L << 28)),
            ApplicationControllerTest.config(temp.resolve("settings")),
            Preferences.DEFAULTS);
    states = new CommandStates(application);
  }

  @AfterEach
  void shutDown() throws InterruptedException {
    ApplicationControllerTest.shutDown(application, ui, view, workers);
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void everyCheckAndFileCommandHasARow() {
    for (CommandId command : CommandId.values()) {
      if (command.check() || CommandStates.FILE_COMMANDS.contains(command)) {
        assertTrue(EXPECTED.containsKey(command), command.name());
      }
    }
  }

  @Test
  void statesFollowTheDocumentAndFocus() throws Exception {
    Map<CommandId, StringBuilder> recorded = new EnumMap<CommandId, StringBuilder>(CommandId.class);
    record(recorded);
    DocumentController document = open("states.log");
    application.setFocusedView(document);
    record(recorded);
    application.setFocusedView(document.filtered());
    record(recorded);
    Map<CommandId, String> actual = new EnumMap<CommandId, String>(CommandId.class);
    StringBuilder table = new StringBuilder();
    for (Map.Entry<CommandId, StringBuilder> entry : recorded.entrySet()) {
      String row = entry.getValue().toString();
      actual.put(entry.getKey(), row);
      table.append(String.format("    row(CommandId.%s, \"%s\");%n", entry.getKey(), row));
    }
    assertEquals(EXPECTED, actual, "recorded:\n" + table);
  }

  @Test
  void checksTrackTheControllers() throws Exception {
    open("track.log");
    List<CommandId> toggles =
        List.of(
            CommandId.FOLLOW,
            CommandId.TOGGLE_LINE_NUMBERS,
            CommandId.TOGGLE_FILTERED_LINE_NUMBERS,
            CommandId.TOGGLE_CASE,
            CommandId.TOGGLE_REGEX,
            CommandId.TOGGLE_INVERSE,
            CommandId.TOGGLE_WRAP,
            CommandId.SHOW_MATCHES,
            CommandId.RUN_SEARCH_ON_ADD,
            CommandId.FOLLOW_ON_LOAD,
            CommandId.TOGGLE_OVERVIEW,
            CommandId.TOGGLE_MATCH_HIGHLIGHT,
            CommandId.WRAP_ON_OPEN,
            CommandId.TOGGLE_WHITESPACE,
            CommandId.HIDE_ANSI,
            CommandId.BLOCK_SELECTION,
            CommandId.THEME_DARK);
    for (CommandId command : toggles) {
      boolean before = states.checked(command);
      application.execute(command);
      ui.runPending();
      assertNotEquals(before, states.checked(command), command.name());
    }
    assertFalse(states.checked(CommandId.THEME_LIGHT));
    assertFalse(states.checked(CommandId.SHOW_MARKS_AND_MATCHES));
  }

  @Test
  void favoritesSwapEnablement() throws Exception {
    open("favorite.log");
    application.execute(CommandId.ADD_FAVORITE);
    assertFalse(states.enabled(CommandId.ADD_FAVORITE));
    assertTrue(states.enabled(CommandId.REMOVE_FAVORITE));
    application.execute(CommandId.REMOVE_FAVORITE);
    assertTrue(states.enabled(CommandId.ADD_FAVORITE));
    assertFalse(states.enabled(CommandId.REMOVE_FAVORITE));
  }

  @Test
  void clipboardTextDisablesTheFileCommands() throws Exception {
    view.clipboard = "pasted one\npasted two\n";
    application.execute(CommandId.OPEN_CLIPBOARD);
    awaitShown();
    for (CommandId command : CommandStates.FILE_COMMANDS) {
      assertFalse(states.enabled(command), command.name());
    }
    assertFalse(states.checked(CommandId.ENCODING_AUTO));
    assertFalse(states.enabled(CommandId.ADD_FAVORITE));
    assertTrue(states.enabled(CommandId.MOVE_TO_NEW_WINDOW));
    assertTrue(states.enabled(CommandId.TOGGLE_WRAP));
    assertEquals("", states.forcedEncoding());
    CommandStates.OpenedFile pasted = states.openedFiles().get(0);
    assertEquals(application.tabTitle(application.activeDocument()), pasted.text());
    assertFalse(states.tabItems(application.activeDocument()).file());
  }

  @Test
  void aForcedEncodingUnchecksAuto() throws Exception {
    DocumentController document = open("forced.log");
    application.setEncoding(document, StandardCharsets.UTF_16LE);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = application.activeDocument();
                return shown != null
                    && shown.state().encodingForced()
                    && !shown.state().indexing()
                    && view.active.get() == 0;
              }
            },
            BOUND));
    assertEquals("UTF-16LE", states.forcedEncoding());
    assertFalse(states.checked(CommandId.ENCODING_AUTO));
    assertTrue(states.enabled(CommandId.ENCODING_AUTO));
  }

  @Test
  void encodingChoicesArriveOnTheUiThread() throws Exception {
    final List<EncodingChoices> received = new CopyOnWriteArrayList<EncodingChoices>();
    application.loadEncodingChoices(
        new EncodingChoices.Listener() {
          @Override
          public void loaded(EncodingChoices choices) {
            assertTrue(ui.isUiThread());
            received.add(choices);
          }
        });
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !received.isEmpty();
              }
            },
            BOUND));
    assertSame(EncodingChoices.loaded(), received.get(0));
  }

  @Test
  void listsSetsOpenFilesAndTabItems() throws Exception {
    HighlightSet first = HighlightSet.create("Mock one");
    HighlightSet second = HighlightSet.create("Mock two");
    application.setHighlightSettings(
        application
            .highlightSettings()
            .withSets(List.of(first, second))
            .withActive(List.of(second.id())));
    List<CommandStates.SetChoice> sets = states.highlightSets();
    assertEquals(2, sets.size());
    assertSame(first, sets.get(0).set());
    assertFalse(sets.get(0).active());
    assertTrue(sets.get(1).active());
    assertFalse(states.checked(CommandId.NO_HIGHLIGHTERS));

    DocumentController left = open("left.log");
    DocumentController right = open("right.log");
    List<String> texts = new ArrayList<String>();
    for (CommandStates.OpenedFile file : states.openedFiles()) {
      texts.add(file.text());
      assertEquals(file.document() == right, file.shown());
    }
    assertEquals(List.of(left.state().path(), right.state().path()), texts);
    assertEquals(new CommandStates.TabItems(true, false, true, true), states.tabItems(left));
    assertEquals(new CommandStates.TabItems(true, true, false, true), states.tabItems(right));
  }

  /** Opens a new file of a few lines and waits until it shows, indexed. */
  private DocumentController open(String name) throws Exception {
    Path file = Files.writeString(temp.resolve(name), "one\ntwo hit\nthree\n");
    application.open(List.of(new FileRequest(file, 0)));
    return awaitShown(file);
  }

  private DocumentController awaitShown() throws InterruptedException {
    return awaitShown(null);
  }

  /** Waits until a document, the one for {@code file} when given, shows and is indexed. */
  private DocumentController awaitShown(final Path file) throws InterruptedException {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = application.activeDocument();
                return shown != null
                    && (file == null
                        || shown
                            .state()
                            .path()
                            .equals(file.toAbsolutePath().normalize().toString()))
                    && !shown.state().indexing()
                    && view.active.get() == 0;
              }
            },
            BOUND));
    DocumentController shown = application.activeDocument();
    assertNotNull(shown);
    return shown;
  }

  /** Appends each row's state: {@code c} or {@code -} for checked, {@code e} or {@code -}. */
  private void record(Map<CommandId, StringBuilder> into) {
    for (CommandId command : EXPECTED.keySet()) {
      StringBuilder states = into.get(command);
      if (states == null) {
        states = new StringBuilder();
        into.put(command, states);
      } else {
        states.append(' ');
      }
      states.append(this.states.checked(command) ? 'c' : '-');
      states.append(this.states.enabled(command) ? 'e' : '-');
    }
  }

  private static void row(CommandId command, String states) {
    EXPECTED.put(command, states);
  }
}
