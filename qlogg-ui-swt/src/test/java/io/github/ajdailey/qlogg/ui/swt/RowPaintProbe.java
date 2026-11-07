package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
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
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Paint time of the main view on rows with CJK, fullwidth and combining characters (laid out by
 * {@code RowPainter} in a {@code TextLayout}) against ASCII rows of the same length (drawn as
 * strings). Runs only with {@code -Dqlogg.bench.rowPaint=true}. Each file is opened in the main
 * window (1100 x 700) and its first screen repainted {@value #FRAMES} times; a frame is the
 * canvas's own paint listeners alone. The non-ASCII screen is timed again with highlight spans and
 * a selection, then with whitespace markers as well; each non-ASCII case is also timed scrolling a
 * screen per frame, so no row repeats from the paint before. Prints the results on standard output.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qlogg.bench.rowPaint", matches = "true")
class RowPaintProbe {
  private static final Duration BOUND = Duration.ofSeconds(60);
  private static final int FRAMES = 500;
  private static final int LINES = 2000;

  /** CJK ideographs, Hangul, fullwidth Latin and e/a with combining acute and diaeresis. */
  private static final String WIDE = "日本語のログ 한국어 ＡＢＣ１２ café näive ";

  /** Colored on every line of the marked screen. */
  private static final String SPANNED = "mock";

  /** The Hangul word of {@link #WIDE}, colored on the marked screen. */
  private static final String HANGUL = "한국어";

  @TempDir Path temp;

  private long paintStart;
  private long paintNanos;
  private long paints;
  private boolean counted;
  private long countedTop;

  @Test
  void paint() throws Exception {
    Path ascii = write("ascii.log", false);
    Path wide = write("wide.log", true);
    Display display = new Display();
    Workers workers =
        new Workers(
            Runtime.getRuntime().availableProcessors(),
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                error.printStackTrace();
              }
            });
    MemoryBudget budget = MemoryBudget.fromRuntime();
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
            new SearchEngine(
                workers,
                StandardMatchers.INSTANCE,
                SearchSettings.defaults(workers.cpuThreads(), budget)),
            BlockCache.fromBudget(budget),
            budget,
            StandardCharsets.ISO_8859_1);
    MainWindow window = new MainWindow(display);
    final ApplicationController controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
            Preferences.DEFAULTS);
    try {
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
      window.open();
      System.out.println("probe.platform=" + SWT.getPlatform() + " " + SWT.getVersion());
      System.out.println("probe.gtk4=" + System.getenv("SWT_GTK4"));
      double asciiMean = measure(display, window, controller, ascii, "ascii", false);
      double wideMean = measure(display, window, controller, wide, "nonAscii", true);
      time(display, window, controller.activeDocument(), "nonAsciiScroll", true);
      measureMarked(display, window, controller);
      System.out.println(
          String.format(Locale.ROOT, "probe.rowPaint.ratio=%.2f", wideMean / asciiMean));
    } finally {
      controller.shutdown();
      final MainWindow closing = window;
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return closing.shell().isDisposed();
            }
          },
          BOUND);
      display.dispose();
      workers.close();
    }
  }

  /** Lines of about 150 columns; with {@code wide}, each starts with {@link #WIDE}. */
  private Path write(String name, boolean wide) throws IOException {
    Path file = temp.resolve(name);
    try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (int i = 0; i < LINES; i++) {
        StringBuilder line = new StringBuilder();
        line.append(String.format(Locale.ROOT, "2026-11-04 10:00:%02d.%03d INFO ", i % 60, i));
        if (wide) {
          line.append(WIDE);
        }
        while (line.length() < 150) {
          line.append("mock record ").append(i).append(' ');
        }
        out.write(line.toString());
        out.write('\n');
      }
    }
    return file;
  }

  /** Opens the file, repaints its first screen and prints the paint times; returns the mean ms. */
  private double measure(
      Display display,
      final MainWindow window,
      final ApplicationController controller,
      final Path file,
      String name,
      boolean wide) {
    controller.openFile(file, 0);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return window.canvas() != null
                    && document != null
                    && document.state().title().equals(file.getFileName().toString())
                    && !document.state().indexing()
                    && document.lines().contains(document.viewport().topLine());
              }
            },
            BOUND));
    DocumentController document = controller.activeDocument();
    String top = document.lines().text(document.viewport().topLine());
    assertTrue(!wide || top.indexOf('日') >= 0, "not read as UTF-8: " + top);
    return time(display, window, document, name);
  }

  /**
   * Colors {@link #SPANNED} and the Hangul word and selects columns 20-60 of the top line, then
   * shows whitespace; repaints the screen in each state and prints the paint times.
   */
  private void measureMarked(Display display, MainWindow window, ApplicationController controller) {
    final DocumentController document = controller.activeDocument();
    HighlightSet set =
        new HighlightSet(
            "probe-spans",
            "Probe",
            List.of(
                HighlightRule.matchOnly(SPANNED, MatchMode.LITERAL, null, Rgb.of(0xd02020)),
                HighlightRule.matchOnly(HANGUL, MatchMode.LITERAL, Rgb.of(0x2020d0), null)));
    controller.setHighlightSettings(
        HighlightSettings.DEFAULTS.withSets(List.of(set)).withActive(List.of(set.id())));
    final long top = document.viewport().topLine();
    document.select(new Selection(new TextPosition(top, 20), new TextPosition(top, 60)));
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.lines().contains(top) && !document.lines().spans(top).isEmpty();
              }
            },
            BOUND));
    time(display, window, document, "nonAsciiSpans");
    time(display, window, document, "nonAsciiSpansScroll", true);
    document.setWhitespaceBoth(true);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.lines().contains(top)
                    && !document.lines().spans(top).isEmpty()
                    && document.lines().whitespace(top) != null;
              }
            },
            BOUND));
    time(display, window, document, "nonAsciiMarked");
    time(display, window, document, "nonAsciiMarkedScroll", true);
  }

  /** Repaints the current screen {@value #FRAMES} times; see the other overload. */
  private double time(
      Display display, MainWindow window, DocumentController document, String name) {
    return time(display, window, document, name, false);
  }

  /**
   * Times {@value #FRAMES} paints. With {@code scroll}, each frame scrolls a screen down or back up
   * and only the first paint of the new screen with all its lines read counts, so every counted
   * paint draws rows the last paint did not. Prints the paint times and returns the mean ms. With
   * {@code -Dqlogg.shots=<dir>}, then writes the window to {@code rows-<name>.png}, except on GTK
   * 4, where {@code Control.print} draws nothing.
   */
  private double time(
      Display display,
      MainWindow window,
      final DocumentController document,
      String name,
      final boolean scroll) {
    Canvas canvas = window.canvas().control();
    final long[] frames = new long[FRAMES];
    Listener[] own = canvas.getListeners(SWT.Paint);
    for (Listener listener : own) {
      canvas.removeListener(SWT.Paint, listener);
    }
    Listener start =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            long top = document.viewport().topLine();
            counted =
                !scroll
                    || top != countedTop
                        && document.lines().contains(top)
                        && document.lines().contains(top + document.viewport().rows() - 1);
            if (counted) {
              countedTop = top;
            }
            paintStart = System.nanoTime();
          }
        };
    canvas.addListener(SWT.Paint, start);
    for (Listener listener : own) {
      canvas.addListener(SWT.Paint, listener);
    }
    Listener end =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            long nanos = System.nanoTime() - paintStart;
            if (!counted) {
              return;
            }
            if (paints < FRAMES) {
              frames[(int) paints] = nanos;
            }
            paintNanos += nanos;
            paints++;
          }
        };
    canvas.addListener(SWT.Paint, end);
    paints = 0;
    paintNanos = 0;
    countedTop = document.viewport().topLine();
    for (int i = 0; i < FRAMES; i++) {
      final long before = paints;
      if (scroll) {
        int rows = document.viewport().rows();
        document.scrollLines(i % 2 == 0 ? rows : -rows);
      } else {
        canvas.redraw();
      }
      assertTrue(
          EventLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return paints > before;
                }
              },
              BOUND),
          "no paint");
    }
    canvas.removeListener(SWT.Paint, start);
    canvas.removeListener(SWT.Paint, end);
    int count = (int) Math.min(paints, FRAMES);
    long[] sorted = Arrays.copyOf(frames, count);
    Arrays.sort(sorted);
    double mean = paintNanos / 1e6 / Math.max(1, paints);
    System.out.println(
        String.format(
            Locale.ROOT,
            "probe.rowPaint.%s rows=%d frames=%d p50=%.2fms p90=%.2fms p99=%.2fms max=%.2fms"
                + " mean=%.2fms",
            name,
            document.viewport().rows(),
            count,
            sorted[count / 2] / 1e6,
            sorted[count * 9 / 10] / 1e6,
            sorted[count * 99 / 100] / 1e6,
            sorted[count - 1] / 1e6,
            mean));
    String shots = System.getProperty("qlogg.shots");
    if (shots != null && !Gtk4.active()) {
      Image image = new Image(display, window.shell().getSize().x, window.shell().getSize().y);
      GC gc = new GC(image);
      try {
        Gtk4.assertPrinted(window.shell().print(gc));
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] {image.getImageData()};
        loader.save(Path.of(shots, "rows-" + name + ".png").toString(), SWT.IMAGE_PNG);
      } finally {
        gc.dispose();
        image.dispose();
      }
    }
    return mean;
  }
}
