package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.theme.Palette;
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
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Control box pixels: the outline and digits in the text color blended over the ground on the light
 * and the dark palette, no pixel in the full text color inside the box, and a highlight span under
 * both of its columns.
 */
@Tag("swt")
class ControlBoxPixelsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final RGB SPAN_RGB = new RGB(0xd0, 0x20, 0x20);

  /**
   * Row 1 holds ESC at columns 5-6 between spaces; row 0 holds the caret, so row 1 has the base
   * ground.
   */
  private static final String TEXT = "first\nmock \u001b mock\nthird\n";

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

  private void openText(final String name, String text) throws IOException {
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
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
                    && shown.lines().contains(2);
              }
            }));
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

  private static RGB rgbAt(ImageData data, int x, int y) {
    return data.palette.getRGB(data.getPixel(x, y));
  }

  private static RGB rgb(int packed) {
    return new RGB(packed >> 16 & 0xff, packed >> 8 & 0xff, packed & 0xff);
  }

  private static RGB rgb(Rgb color) {
    return new RGB(color.red(), color.green(), color.blue());
  }

  @Test
  void theBoxDrawsInTheBlendedTextColorOnBothPalettes() throws IOException {
    openText("controls.log", TEXT);
    for (Palette palette : new Palette[] {Palette.LIGHT, Palette.DARK}) {
      window.canvas().setPalette(palette);
      ImageData data = capture();
      LogCanvas view = window.canvas();
      int height = view.lineHeight();
      int left = view.gutterWidth() + view.columnOffset(5);
      int right = view.gutterWidth() + view.columnOffset(7) - 1;
      int top = height + 1;
      int bottom = 2 * height - 2;
      int ground = PaletteColors.packed(palette.background());
      RGB box =
          rgb(
              PaletteColors.blend(
                  ControlBoxes.ALPHA << 24 | PaletteColors.packed(palette.text()), ground));
      String where = palette == Palette.LIGHT ? "light" : "dark";
      assertEquals(box, rgbAt(data, left, (top + bottom) / 2), where + " left edge");
      assertEquals(box, rgbAt(data, right, (top + bottom) / 2), where + " right edge");
      assertEquals(box, rgbAt(data, (left + right) / 2, top), where + " top edge");
      assertEquals(box, rgbAt(data, (left + right) / 2, bottom), where + " bottom edge");
      assertEquals(rgb(ground), rgbAt(data, left - 1, (top + bottom) / 2), where + " outside");
      int ink = 0;
      for (int x = left + 1; x < right; x++) {
        for (int y = top + 1; y < bottom; y++) {
          RGB pixel = rgbAt(data, x, y);
          assertTrue(!pixel.equals(rgb(palette.text())), where + " text color at " + x + "," + y);
          ink += pixel.equals(rgb(ground)) ? 0 : 1;
        }
      }
      assertTrue(ink > 0, where + " no digits");
      assertTrue(Pixels.contrast(box, rgb(ground)) >= 3, where + " contrast");
    }
  }

  @Test
  void aHighlightSpanCoversBothColumnsOfTheBox() throws IOException {
    openText("highlight.log", TEXT);
    HighlightSet set =
        new HighlightSet(
            "mock-box",
            "Mock",
            List.of(HighlightRule.matchOnly("\u001b", MatchMode.LITERAL, null, Rgb.of(0xd02020))));
    controller.setHighlightSettings(
        HighlightSettings.DEFAULTS.withSets(List.of(set)).withActive(List.of(set.id())));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !document().lines().spans(1).isEmpty();
              }
            }));
    ImageData data = capture();
    LogCanvas view = window.canvas();
    int y = 2 * view.lineHeight() - 1;
    int left = -1;
    int right = -1;
    for (int x = view.gutterWidth(); x < data.width; x++) {
      if (rgbAt(data, x, y).equals(SPAN_RGB)) {
        left = left < 0 ? x : left;
        right = x;
      }
    }
    assertEquals(view.gutterWidth() + view.columnOffset(5), left);
    assertEquals(view.gutterWidth() + view.columnOffset(7) - 1, right);
  }

  /**
   * With {@code -Dqlogg.shots=<dir>}, writes the window at 1100 x 700 on the light palette ({@code
   * controls-light.png}, a selection across a box on row 2) and the dark ({@code
   * controls-dark.png}).
   */
  @Test
  void screenshots() throws IOException {
    final String dir = System.getProperty("qlogg.shots");
    Assumptions.assumeTrue(dir != null, "qlogg.shots not set");
    window.shell().setSize(1100, 700);
    openText(
        "controls-shots.log",
        "2026-11-25 10:00:00 INFO  mock\u001b[31mcolored\u001b[0m text\n"
            + "2026-11-25 10:00:01 WARN  nul\u0000 bell\u0007 del\u007f\n"
            + "2026-11-25 10:00:02 INFO  c1\u0081\u008d\u008f\u0090\u009d end\n"
            + "2026-11-25 10:00:03 INFO  lone\rcr and\ttab\n");
    document().select(new Selection(new TextPosition(1, 29), new TextPosition(1, 40)));
    window.canvas().setPalette(Palette.LIGHT);
    shoot(dir, "controls-light.png");
    window.canvas().setPalette(Palette.DARK);
    shoot(dir, "controls-dark.png");
  }

  private void shoot(String dir, String name) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().lines().contains(3);
              }
            }));
    // The shell's print shows the canvas as last painted, so wait for a paint of this state.
    final boolean[] painted = new boolean[1];
    Listener paint =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            painted[0] = true;
          }
        };
    canvas().addListener(SWT.Paint, paint);
    canvas().redraw();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return painted[0];
              }
            }));
    canvas().removeListener(SWT.Paint, paint);
    Image image = new Image(display, window.shell().getSize().x, window.shell().getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(window.shell().print(gc));
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(Path.of(dir, name).toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }
}
