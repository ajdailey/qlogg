package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.highlight.ColorLabel;
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
import io.github.ajdailey.qlogg.core.model.TokenKind;
import io.github.ajdailey.qlogg.core.search.MatchMode;
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
 * Token color pixels: an {@code ERROR} on a plain row shows the palette's {@link TokenKind#INVALID}
 * color, under the gray color label the palette text color, under a span highlighter with a text
 * color that color, and on a row a whole-line rule colors that rule's color.
 */
@Tag("swt")
class SyntaxColorsGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final Rgb SPAN = Rgb.of(0x00a000);
  private static final Rgb LINE = Rgb.of(0x0000c0);

  /** The default label that keeps the text color. */
  private static final int GRAY_LABEL = ColorLabel.COUNT - 1;

  /** Sum of channel differences within which a pixel counts as a color. */
  private static final int NEAR = 60;

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

  private ImageData capture() {
    Image image = new Image(display, canvas().getSize().x, canvas().getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(canvas().print(gc));
      return Pixels.read(image, canvas());
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Reports whether a pixel of the first five columns of a row is near a color. */
  private boolean shows(ImageData data, int row, Rgb color) {
    LogCanvas view = window.canvas();
    int height = view.lineHeight();
    int from = view.gutterWidth();
    int to = from + view.columnOffset(5);
    for (int y = row * height; y < (row + 1) * height; y++) {
      for (int x = from; x < to; x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        int distance =
            Math.abs(rgb.red - color.red())
                + Math.abs(rgb.green - color.green())
                + Math.abs(rgb.blue - color.blue());
        if (distance <= NEAR) {
          return true;
        }
      }
    }
    return false;
  }

  private void waitFor(BooleanSupplier condition) {
    assertTrue(runUntil(condition));
  }

  @Test
  void tokenColorsYieldToLineColorsAndSpanForegrounds() throws Exception {
    Path file = temp.resolve("syntax.log");
    Files.writeString(
        file,
        "ERROR plain\nERROR labeled\nERROR spanned\nERROR whole\n",
        StandardCharsets.US_ASCII);
    controller.openFile(file, 0);
    waitFor(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = controller.activeDocument();
            return shown != null
                && !shown.state().indexing()
                && window.canvas() != null
                && window.canvas().document().owner() == shown
                && shown.lines().contains(3)
                && shown.lines().tokens(0).count() == 1;
          }
        });
    Rgb invalid = controller.palette().tokens().color(TokenKind.INVALID);
    Rgb text = controller.palette().text();
    ImageData plain = capture();
    assertTrue(shows(plain, 0, invalid), "token color on a plain row");
    assertFalse(shows(plain, 0, text), "text color inside the token");

    document().applyLabel(GRAY_LABEL, "ERROR labeled");
    HighlightRule rule = HighlightRule.matchOnly("ERROR spanned", MatchMode.LITERAL, SPAN, null);
    HighlightRule line =
        new HighlightRule("whole", MatchMode.LITERAL, true, true, LINE, null, 255, 0, true);
    HighlightSet set = new HighlightSet("mock-span", "Mock", List.of(rule, line));
    controller.setHighlightSettings(
        HighlightSettings.DEFAULTS.withSets(List.of(set)).withActive(List.of(set.id())));
    waitFor(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document().lines().contains(3)
                && document().lines().spans(1).count() == 1
                && document().lines().spans(2).count() == 1
                && document().lines().spans(3).lineForeground() != 0;
          }
        });
    ImageData colored = capture();
    assertTrue(shows(colored, 1, text), "text color under the gray label");
    assertFalse(shows(colored, 1, invalid), "token color under the gray label");
    assertTrue(shows(colored, 2, SPAN), "span color over a token");
    assertFalse(shows(colored, 2, invalid), "token color under a span");
    assertTrue(shows(colored, 3, LINE), "line color on a whole-line rule's row");
    assertFalse(shows(colored, 3, invalid), "token color on a whole-line rule's row");
    assertTrue(shows(colored, 0, invalid), "token color beside the colored rows");
  }
}
