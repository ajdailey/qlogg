package io.github.ajdailey.qlogg.ui.swt;

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
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.time.SpreadMode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes the user guide's screenshots to {@code -Dqlogg.guide.shots=<dir>} ({@code
 * tools/screenshots.sh}): the main window with search results and marks, a selection's time
 * difference, collapsed stack traces, the highlighters dialog, the Options Folding tab and Keyboard
 * Shortcuts and both time-spread modes, each painted through {@code Shell.print} from a generated
 * mock log, written to {@code -Dqlogg.screenshots.logs=<dir>} when given, so the path field shows
 * that path.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qlogg.guide.shots", matches = ".+")
class GuideScreenshotsTest {
  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final String[] COMPONENTS = {"gateway", "ingest", "scheduler", "store"};

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private MainWindow window;
  private ApplicationController controller;
  private Path out;

  @Test
  void guideScreenshots() throws Exception {
    out = Files.createDirectories(Path.of(System.getProperty("qlogg.guide.shots")));
    display = new Display();
    try (Workers workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            })) {
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
              Preferences.DEFAULTS
                  .withHighlights(highlights())
                  .withTime(new TimeSettings(ZoneOffset.UTC, ZoneOffset.UTC, List.of())));
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 960, 600), null);
      window.open();
      shoot();
      controller.shutdown();
      runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.shell().isDisposed();
            }
          });
    } finally {
      display.dispose();
    }
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private void shoot() throws Exception {
    String logs = System.getProperty("qlogg.screenshots.logs", "");
    Path dir = logs.isEmpty() ? temp : Files.createDirectories(Path.of(logs));
    Path log = mockLog(dir.resolve("service.log"));
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
    final DocumentController document = controller.activeDocument();
    document.search().setText("ERROR", SearchOptions.DEFAULTS.withCaseSensitive(true));
    document.search().run();
    document.goToLine(60);
    document.select(new Selection(new TextPosition(55, 0), new TextPosition(56, 5)));
    controller.setFocusedView(document);
    controller.execute(CommandId.TOGGLE_MARK);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.search().state().phase() != SearchViewState.Phase.RUNNING
                    && !document.markingRunning();
              }
            }));
    document.select(Selection.caretAt(new TextPosition(58, 0)));
    settle();
    save(window.shell(), "main.png");

    // Timestamps: the caret time and the time across a selection.
    document.select(new Selection(new TextPosition(50, 0), new TextPosition(66, 4)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.positionText().contains("+00:") && !window.timeText().isEmpty();
              }
            }));
    settle();
    save(window.shell(), "timestamps.png");
    document.select(Selection.caretAt(new TextPosition(58, 0)));
    settle();

    controller.execute(CommandId.COLLAPSE_ALL_FOLDS);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.folded(59);
              }
            }));
    settle();
    save(window.shell(), "folds.png");
    controller.execute(CommandId.EXPAND_ALL_FOLDS);

    controller.execute(CommandId.EDIT_HIGHLIGHTERS);
    saveDialog("highlighters.png");

    OptionsDialog options = new OptionsDialog(window.shell(), controller);
    options.open();
    options.folder().setSelection(options.folder().getItemCount() - 1);
    save(options.shell(), "options-folding.png");
    options.shell().dispose();

    controller.execute(CommandId.KEYBOARD_SHORTCUTS);
    saveDialog("shortcuts.png");

    controller.open(List.of(new FileRequest(spreadLog(dir.resolve("batch.log")), 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != document && !shown.state().indexing() && window.canvas() != null;
              }
            }));
    spreadShot(CommandId.TIME_SPREAD_ACTIVITY, SpreadMode.ACTIVITY, "time-spread-activity.png");
    spreadShot(CommandId.TIME_SPREAD_PAUSES, SpreadMode.PAUSES, "time-spread-pauses.png");
    controller.execute(CommandId.TIME_SPREAD_OFF);
  }

  /** Chooses a time-spread mode and saves the window once the bar shows it. */
  private void spreadShot(CommandId command, final SpreadMode mode, String name) {
    controller.execute(command);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.activeDocument().overview().spread().mode() == mode;
              }
            }));
    settle();
    save(window.shell(), name);
  }

  /** Runs the event loop for about half a second, so queued view updates reach the widgets. */
  private void settle() {
    final long until = System.nanoTime() + 500_000_000L;
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
  }

  /** Saves the newest shell other than the main window, then closes it. */
  private void saveDialog(String name) {
    Shell dialog = null;
    for (Shell shell : display.getShells()) {
      if (shell != window.shell() && shell.isVisible()) {
        dialog = shell;
      }
    }
    assertTrue(dialog != null, name);
    save(dialog, name);
    dialog.dispose();
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  /** Paints a shell into an image through the public print API and writes a PNG. */
  private void save(Shell shell, String name) {
    while (display.readAndDispatch()) {
      // Flush pending paints so the image shows the latest rows.
    }
    Point size = shell.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      shell.print(gc);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(out.resolve(name).toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static HighlightSettings highlights() {
    HighlightSet set =
        new HighlightSet(
            "mock-service",
            "Mock service",
            List.of(
                new HighlightRule(
                    "ERROR", MatchMode.LITERAL, true, true, null, Rgb.of(0xf2b8b5), 160, 0, true),
                HighlightRule.matchOnly("WARN", MatchMode.LITERAL, Rgb.of(0x7a4f00), null),
                HighlightRule.matchOnly("ID-(\\d{3})", MatchMode.REGEX, null, Rgb.of(0x9fd3ff))
                    .withAlphaAndVariance(200, 25),
                HighlightRule.matchOnly("\\[(\\w+)", MatchMode.REGEX, Rgb.of(0x2f7d32), null)));
    return HighlightSettings.DEFAULTS.withSets(List.of(set)).withActive(List.of(set.id()));
  }

  /** Mock batch runs: steady lines, then a burst, with two long pauses between. */
  private static Path spreadLog(Path file) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      long millis = 0;
      for (int i = 0; i < 2400; i++) {
        boolean burst = i >= 900 && i < 1500;
        millis += i == 600 ? 2_400_000 : i == 1900 ? 1_200_000 : burst ? 20 : 1000;
        writer.write(
            String.format(
                Locale.ROOT,
                "2026-11-01 %02d:%02d:%02d.%03d INFO  [batch    ] record %d written%n",
                8 + millis / 3_600_000,
                millis / 60_000 % 60,
                millis / 1000 % 60,
                millis % 1000,
                i));
      }
    }
    return file;
  }

  /** Mock entries; every ERROR entry carries an indented stack trace to fold. */
  private static Path mockLog(Path file) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (int i = 0; i < 600; i++) {
        boolean error = i % 23 == 5;
        String level = error ? "ERROR" : i % 11 == 3 ? "WARN " : "INFO ";
        String component = COMPONENTS[i % COMPONENTS.length];
        String message =
            error
                ? "request ID-" + (100 + i % 900) + "-42-0001 failed"
                : switch (i % 5) {
                  case 0 -> "request ID-" + (100 + i % 900) + "-42-0001 accepted";
                  case 1 -> "batch " + i / 5 + " written, 128 records";
                  case 2 -> "flagword check passed for EXAMPLE item " + i;
                  case 3 -> "cache hit ratio 0." + (50 + i % 50);
                  default -> "heartbeat ok";
                };
        writer.write(
            String.format(
                Locale.ROOT,
                "2026-11-01 08:%02d:%02d.%03d %s [%-9s] %s%n",
                (i / 60) % 60,
                i % 60,
                (i * 37) % 1000,
                level,
                component,
                message));
        if (error) {
          writer.write("    java.lang.IllegalStateException: mock BLOCKTERM response\n");
          writer.write("    at com.example.mock.Client.send(Client.java:118)\n");
          writer.write("    at com.example.mock.Batch.flush(Batch.java:64)\n");
          writer.write("    at com.example.mock.Worker.run(Worker.java:31)\n");
        }
      }
    }
    return file;
  }
}
