package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.cli.SessionMode;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.theme.ColorScheme;
import io.github.ajdailey.qlogg.app.theme.ColorSchemes;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.ui.swt.MainWindow;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes the README screenshots: the composed window on a generated mock log, once per palette, at
 * 1100 x 700 unless {@code -Dqlogg.screenshots.width} and {@code -Dqlogg.screenshots.height} say
 * otherwise, painted through {@code Shell.print}. Runs only with {@code -Dqlogg.screenshots=<dir>}
 * ({@code tools/screenshots.sh}). The log is written to {@code -Dqlogg.screenshots.logs=<dir>} when
 * given, so the path field shows that path, else to a test temporary directory. With {@code
 * -Dqlogg.screenshots.tabs=2} a second mock log opens first, so the tab row holds two tabs. The
 * highlighter fixture has light and dark colors, as a user would choose per palette.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qlogg.screenshots", matches = ".+")
class ScreenshotsTest {
  private static final int WIDTH = Integer.getInteger("qlogg.screenshots.width", 1100);
  private static final int HEIGHT = Integer.getInteger("qlogg.screenshots.height", 700);
  private static final int TABS = Integer.getInteger("qlogg.screenshots.tabs", 1);
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(30);
  private static final String[] COMPONENTS = {"gateway", "ingest", "scheduler", "store"};

  @TempDir Path temp;

  @Test
  void lightAndDark() throws Exception {
    Path out = Path.of(System.getProperty("qlogg.screenshots"));
    Files.createDirectories(out);
    Files.createDirectories(temp.resolve("settings"));
    String logs = System.getProperty("qlogg.screenshots.logs", "");
    Path dir = logs.isEmpty() ? temp : Files.createDirectories(Path.of(logs));
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (int i = TABS - 1; i >= 1; i--) {
      files.add(new FileRequest(mockLog(dir.resolve("worker-" + i + ".log")), 0));
    }
    files.add(new FileRequest(mockLog(dir.resolve("service.log")), 0));
    try {
      shoot(files, ThemeMode.LIGHT, out.resolve("qlogg-light.png"));
      shoot(files, ThemeMode.DARK, out.resolve("qlogg-dark.png"));
    } finally {
      for (FileRequest file : files) {
        Files.deleteIfExists(file.path());
      }
    }
  }

  // View colors.

  /**
   * Writes {@code qlogg-light-chrome-nightwater.png}, {@code qlogg-dark-chrome-sandstone.png} and
   * {@code qlogg-light-chrome-dark.png} to {@code -Dqlogg.screenshots.colors=<dir>}.
   */
  @Test
  @EnabledIfSystemProperty(named = "qlogg.screenshots.colors", matches = ".+")
  void viewColorsInTheOtherChrome() throws Exception {
    Path out = Files.createDirectories(Path.of(System.getProperty("qlogg.screenshots.colors")));
    Files.createDirectories(temp.resolve("settings"));
    List<FileRequest> files = List.of(new FileRequest(mockLog(temp.resolve("service.log")), 0));
    shoot(files, ThemeMode.LIGHT, "nightwater", out.resolve("qlogg-light-chrome-nightwater.png"));
    shoot(files, ThemeMode.DARK, "sandstone", out.resolve("qlogg-dark-chrome-sandstone.png"));
    shoot(files, ThemeMode.LIGHT, "dark", out.resolve("qlogg-light-chrome-dark.png"));
  }

  private static boolean dark(ThemeMode theme, String colors) {
    ColorScheme scheme = ColorSchemes.NONE.find(colors);
    return scheme == null ? theme == ThemeMode.DARK : scheme.palette().dark();
  }

  private static Path mockLog(Path file) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (int i = 0; i < 5000; i++) {
        String level = i % 37 == 5 ? "ERROR" : i % 11 == 3 ? "WARN " : "INFO ";
        String component = COMPONENTS[i % COMPONENTS.length];
        String message =
            switch (i % 6) {
              case 0 -> "request ID-" + (100 + i % 900) + "-42-0001 accepted";
              case 1 -> "batch " + i / 6 + " written, 128 records";
              case 2 -> "flagword check passed for EXAMPLE item " + i;
              case 3 -> "retry 2 of 5 after BLOCKTERM response";
              case 4 -> "cache hit ratio 0." + (50 + i % 50);
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
      }
    }
    return file;
  }

  /** Colors per rule: ERROR line, WARN text, ID background, component text. */
  private static final int[] LIGHT_COLORS = {0xf2b8b5, 0x7a4f00, 0x9fd3ff, 0x2f7d32};

  private static final int[] DARK_COLORS = {0x6e2522, 0xe0b04a, 0x1f4a6b, 0x7fc77f};

  private static HighlightSettings highlights(boolean dark) {
    int[] c = dark ? DARK_COLORS : LIGHT_COLORS;
    HighlightSet set =
        new HighlightSet(
            "mock-service",
            "Mock service",
            List.of(
                new HighlightRule(
                    "ERROR", MatchMode.LITERAL, true, true, null, Rgb.of(c[0]), 160, 0, true),
                HighlightRule.matchOnly("WARN", MatchMode.LITERAL, Rgb.of(c[1]), null),
                HighlightRule.matchOnly("ID-(\\d{3})", MatchMode.REGEX, null, Rgb.of(c[2]))
                    .withAlphaAndVariance(200, 25),
                HighlightRule.matchOnly("\\[(\\w+)", MatchMode.REGEX, Rgb.of(c[3]), null)));
    return HighlightSettings.DEFAULTS.withSets(List.of(set)).withActive(List.of(set.id()));
  }

  private void shoot(List<FileRequest> files, ThemeMode theme, final Path png) throws Exception {
    shoot(files, theme, ColorSchemes.FOLLOW_THEME, png);
  }

  private void shoot(List<FileRequest> files, ThemeMode theme, final String colors, final Path png)
      throws Exception {
    StartupRequest request =
        new StartupRequest(files, false, SessionMode.DEFAULT, WIDTH, HEIGHT, 0, 0, null, true);
    final boolean[] saved = new boolean[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers workers) {
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            final int[] step = new int[1];
            display.timerExec(
                50,
                new Runnable() {
                  @Override
                  public void run() {
                    DocumentController document = controller.activeDocument();
                    if (System.nanoTime() > deadline) {
                      controller.shutdown();
                      return;
                    }
                    if (document == null || document.state().indexing()) {
                      display.timerExec(50, this);
                      return;
                    }
                    if (step[0] == 0) {
                      step[0] = 1;
                      SearchOptions options = SearchOptions.DEFAULTS.withCaseSensitive(true);
                      document.search().setText("ERROR", options);
                      document.search().run();
                      document.goToLine(180);
                      document.select(
                          new Selection(new TextPosition(176, 0), new TextPosition(177, 5)));
                      controller.setFocusedView(document);
                      controller.execute(CommandId.TOGGLE_MARK);
                    } else if (step[0] < 30
                        || document.search().state().phase() == SearchViewState.Phase.RUNNING
                        || document.markingRunning()) {
                      step[0]++;
                    } else if (document.selection().caret().line() != 180) {
                      // Ten more ticks, so the time field reads the new caret line.
                      document.select(Selection.caretAt(new TextPosition(180, 0)));
                      step[0] = 20;
                    } else {
                      save(window, png, !ColorSchemes.FOLLOW_THEME.equals(colors));
                      saved[0] = true;
                      controller.shutdown();
                      return;
                    }
                    display.timerExec(50, this);
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    Preferences preferences =
        Preferences.DEFAULTS
            .withTheme(theme)
            .withView(Preferences.DEFAULTS.view().withColors(colors))
            .withHighlights(highlights(dark(theme, colors)));
    assertEquals(
        0,
        new Gui(
                request,
                new ConfigStore(
                    new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
                preferences,
                err,
                observer)
            .run());
    assertTrue(saved[0], "screenshot not taken");
  }

  /** Paints the shell into an image through the public print API and writes a PNG. */
  private static void save(MainWindow window, Path png, boolean screen) {
    Display display = window.shell().getDisplay();
    while (display.readAndDispatch()) {
      // Flush pending paints so the image shows the latest rows.
    }
    Point size = window.shell().getSize();
    if (screen) {
      // Shell.print draws GTK native controls light under a dark theme; the screen shows them.
      Rectangle area = window.shell().getClientArea();
      size = new Point(area.width, area.height);
    }
    Image image = new Image(display, size.x, size.y);
    GC gc = screen ? new GC(display) : new GC(image);
    try {
      if (screen) {
        Point origin = window.shell().toDisplay(0, 0);
        gc.copyArea(image, origin.x, origin.y);
      } else {
        window.shell().print(gc);
      }
      ImageData data = image.getImageData();
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {data};
      loader.save(png.toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }
}
