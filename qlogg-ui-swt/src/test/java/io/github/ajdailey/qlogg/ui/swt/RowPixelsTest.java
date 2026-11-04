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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Span and selection pixels: on the second row of a wrapped line, and a span over CJK glyphs on an
 * unwrapped row. Each compares the colored extent with the glyph ink of an uncolored capture.
 */
@Tag("swt")
class RowPixelsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final Rgb SPAN = Rgb.of(0xd02020);
  private static final RGB SPAN_RGB = new RGB(0xd0, 0x20, 0x20);

  /** Every 20 columns, so one occurrence falls whole in a wrapped line's second row. */
  private static final String TOKEN = "MARK";

  private static final int PERIOD = 20;
  private static final String WIDE = "日本語";

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
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
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private DocumentController document() {
    return controller.activeDocument();
  }

  private Canvas canvas() {
    return window.canvas().control();
  }

  private void openText(final String name, String line) throws IOException {
    Path file = temp.resolve(name);
    Files.writeString(file, line + "\nsecond\nthird\n", StandardCharsets.UTF_8);
    controller.openFile(file, 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != null
                    && shown.state().title().equals(name)
                    && !shown.state().indexing()
                    && window.canvas() != null
                    && window.canvas().document().owner() == shown
                    && shown.lines().contains(0);
              }
            }));
  }

  /** Colors {@code pattern} with {@link #SPAN}, or removes the highlighter with null. */
  private void highlight(String pattern) {
    HighlightSettings settings = HighlightSettings.DEFAULTS;
    if (pattern != null) {
      HighlightSet set =
          new HighlightSet(
              "mock-span",
              "Mock",
              List.of(HighlightRule.matchOnly(pattern, MatchMode.LITERAL, null, SPAN)));
      settings = settings.withSets(List.of(set)).withActive(List.of(set.id()));
    }
    controller.setHighlightSettings(settings);
    final boolean colored = pattern != null;
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().lines().contains(0)
                    && document().lines().spans(0).isEmpty() != colored;
              }
            }));
  }

  private ImageData capture() {
    Image image = new Image(display, canvas().getSize().x, canvas().getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(canvas().print(gc));
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static RGB rgbAt(ImageData data, int x, int y) {
    return data.palette.getRGB(data.getPixel(x, y));
  }

  /** The leftmost and rightmost x in {@code [from, to)} of row {@code row} that differ from bg. */
  private int[] ink(ImageData data, int row, int from, int to) {
    int height = window.canvas().lineHeight();
    RGB background = rgbAt(data, to - 1, row * height + height - 1);
    int left = -1;
    int right = -1;
    for (int x = from; x < to; x++) {
      for (int y = row * height; y < (row + 1) * height; y++) {
        if (!rgbAt(data, x, y).equals(background)) {
          left = left < 0 ? x : left;
          right = x;
          break;
        }
      }
    }
    return new int[] {left, right};
  }

  /** The leftmost and rightmost x on a row's bottom pixel line whose color is {@code color}. */
  private int[] extent(ImageData data, int row, RGB color) {
    int height = window.canvas().lineHeight();
    int y = row * height + height - 1;
    int left = -1;
    int right = -1;
    for (int x = window.canvas().gutterWidth(); x < data.width; x++) {
      if (rgbAt(data, x, y).equals(color)) {
        left = left < 0 ? x : left;
        right = x;
      }
    }
    return new int[] {left, right};
  }

  /** The leftmost and rightmost x on a row's bottom pixel line where two captures differ. */
  private int[] changed(ImageData before, ImageData after, int row) {
    int height = window.canvas().lineHeight();
    int y = row * height + height - 1;
    int left = -1;
    int right = -1;
    for (int x = window.canvas().gutterWidth(); x < before.width; x++) {
      if (!rgbAt(before, x, y).equals(rgbAt(after, x, y))) {
        left = left < 0 ? x : left;
        right = x;
      }
    }
    return new int[] {left, right};
  }

  /** Asserts that {@code cover} spans {@code ink} and overhangs it by at most {@code slack}. */
  private static void assertCovers(int[] cover, int[] ink, int slack, String what) {
    String found = what + ": cover " + cover[0] + "-" + cover[1] + ", ink " + ink[0] + "-" + ink[1];
    assertTrue(ink[0] >= 0 && cover[0] >= 0, found);
    assertTrue(cover[0] <= ink[0] + 1 && cover[1] >= ink[1] - 1, found);
    assertTrue(ink[0] - cover[0] <= slack && cover[1] - ink[1] <= slack, found);
  }

  @Test
  void aSpanAndTheSelectionOnAWrappedRowSitOnTheirGlyphs() throws IOException {
    openText("wrapped.log", (TOKEN + " ".repeat(PERIOD - TOKEN.length())).repeat(20));
    controller.execute(CommandId.TOGGLE_WRAP);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().wrap() && document().viewport().rows() > 1;
              }
            }));
    LogCanvas view = window.canvas();
    int columns = document().viewport().columns();
    int token = (columns + PERIOD - 1) / PERIOD * PERIOD;
    assertTrue(token + TOKEN.length() <= 2 * columns, "token at " + token + " of " + columns);
    int at = token - columns;
    int gutter = view.gutterWidth();
    int slack = (int) Math.ceil(view.advance() / 2);
    int[] glyphs =
        ink(capture(), 1, gutter + view.columnOffset(at - 1), gutter + view.columnOffset(at + 5));
    highlight(TOKEN);
    ImageData colored = capture();
    assertCovers(extentNear(colored, 1, gutter + view.columnOffset(at)), glyphs, slack, "span");
    highlight(null);
    ImageData plain = capture();
    document()
        .select(new Selection(new TextPosition(0, token - 2), new TextPosition(0, token + 6)));
    ImageData selected = capture();
    int[] band = changed(plain, selected, 1);
    assertEquals(gutter + view.columnOffset(at - 2), band[0], "selection left");
    assertEquals(gutter + view.columnOffset(at + 6) - 1, band[1], "selection right");
    assertEquals(-1, changed(plain, selected, 0)[0], "selection on the first row");
  }

  /** The span extent on a row, limited to the period around {@code x}. */
  private int[] extentNear(ImageData data, int row, int x) {
    int[] all = extent(data, row, SPAN_RGB);
    int period = window.canvas().columnOffset(PERIOD);
    int left = -1;
    int right = -1;
    int height = window.canvas().lineHeight();
    int y = row * height + height - 1;
    for (int i = Math.max(all[0], x - period / 2); i >= 0 && i <= all[1] && i < x + period; i++) {
      if (rgbAt(data, i, y).equals(SPAN_RGB)) {
        left = left < 0 ? i : left;
        right = i;
      }
    }
    return new int[] {left, right};
  }

  /** CJK glyphs start the row; the grid advance would end the span short of their right edge. */
  @Test
  void aSpanOnACjkRowCoversItsGlyphs() throws IOException {
    openText("wide.log", WIDE + "    ");
    assertEquals(WIDE + "    ", document().lines().text(0));
    LogCanvas view = window.canvas();
    int gutter = view.gutterWidth();
    int[] glyphs = ink(capture(), 0, gutter, canvas().getClientArea().width - 2);
    highlight(WIDE);
    int[] span = extent(capture(), 0, SPAN_RGB);
    assertCovers(span, glyphs, (int) Math.ceil(view.advance()), "CJK span");
  }
}
