package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.LineViewController;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.io.BufferedWriter;
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
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pointer drags in the main and filtered views: a press, two or more moves and a release, then the
 * controller's selection and the painted selection band. A drag that ends above or left of its
 * press keeps the press as the anchor.
 */
@Tag("swt")
class DragSelectionGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int LINES = 200;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private PostedPointer pointer;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
    pointer = new PostedPointer(display);
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
            Preferences.DEFAULTS.withSplitPercent(50));
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 900, 600), null);
    window.open();
    controller.openFile(write(), 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return window.results() != null
                    && document != null
                    && !document.state().indexing()
                    && document.lines().contains(8)
                    && document.viewport().rows() > 8
                    && document.filtered().viewport().rows() > 4;
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
    for (Shell shell : display.getShells()) {
      shell.dispose();
    }
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** Every tenth line holds {@code event}; every line is 29 columns. */
  private Path write() throws IOException {
    Path file = temp.resolve("drag.log");
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
      for (int i = 1; i <= LINES; i++) {
        out.write(String.format("%s id_%03d alpha beta gamma", i % 10 == 0 ? "event" : "line ", i));
        out.write('\n');
      }
    }
    return file;
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private DocumentController document() {
    return controller.activeDocument();
  }

  /** Shows the {@code event} lines in the filtered view and waits for its first five rows. */
  private LineViewController filtered() {
    final SearchBar bar = window.searchBar();
    bar.pattern().setText("event");
    bar.searchButton().notifyListeners(SWT.Selection, new Event());
    final LineViewController filtered = document().filtered();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return filtered.viewport().lineCount() == LINES / 10
                    && filtered.lines().contains(4)
                    && window.busy().active() == 0;
              }
            }));
    return filtered;
  }

  /**
   * Presses at the first point, moves through the rest with an event loop pass after each, and
   * releases at the last. Rows count from the view's top line, which is line 0.
   */
  private void drag(LogCanvas view, TextPosition... points) {
    for (int i = 0; i < points.length; i++) {
      int type = i == 0 ? SWT.MouseDown : SWT.MouseMove;
      mouse(view, type, points[i]);
      while (display.readAndDispatch()) {
        // Drain.
      }
    }
    mouse(view, SWT.MouseUp, points[points.length - 1]);
  }

  private void mouse(LogCanvas view, int type, TextPosition at) {
    Event event = new Event();
    event.button = 1;
    event.x = view.gutterWidth() + view.columnOffset(at.column());
    event.y = (int) at.line() * view.lineHeight() + 1;
    pointer.post(view.control(), type, event);
  }

  /**
   * Drags from {@code press} through {@code via} to {@code release}, then checks the controller's
   * selection and the painted band.
   */
  private void assertDrag(
      LogCanvas view,
      LineViewController lines,
      TextPosition press,
      TextPosition via,
      TextPosition release) {
    drag(view, press, via, release);
    assertEquals(new Selection(press, release), lines.selection(), "controller selection");
    assertPainted(view, lines);
  }

  /**
   * Compares the view painted with the controller's selection against the same caret with no
   * selection: rows from the selection's start to its end differ, from its start column on the
   * first row to its end column on the last, and no other row of the first ten differs.
   */
  private void assertPainted(LogCanvas view, LineViewController lines) {
    Selection selection = lines.selection();
    ImageData selected = capture(view);
    lines.select(Selection.caretAt(selection.caret()));
    ImageData plain = capture(view);
    lines.select(selection);
    int gutter = view.gutterWidth();
    TextPosition start = selection.start();
    TextPosition end = selection.end();
    for (int row = 0; row < 10; row++) {
      int[] band = changed(view, plain, selected, row);
      String where = "row " + row + " of " + selection;
      if (row < start.line() || row > end.line()) {
        assertEquals(-1, band[0], where);
        continue;
      }
      long from = row == start.line() ? start.column() : 0;
      assertEquals(gutter + view.columnOffset(from), band[0], where + " left");
      if (row == end.line()) {
        assertEquals(gutter + view.columnOffset(end.column()) - 1, band[1], where + " right");
      }
    }
  }

  private ImageData capture(LogCanvas view) {
    Canvas canvas = view.control();
    Image image = new Image(display, canvas.getSize().x, canvas.getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(canvas.print(gc));
      return Pixels.read(image, canvas);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** The leftmost and rightmost x on a row's bottom pixel line where two captures differ. */
  private static int[] changed(LogCanvas view, ImageData before, ImageData after, int row) {
    int height = view.lineHeight();
    int y = row * height + height - 1;
    int left = -1;
    int right = -1;
    for (int x = view.gutterWidth(); x < before.width; x++) {
      if (before.getPixel(x, y) != after.getPixel(x, y)) {
        left = left < 0 ? x : left;
        right = x;
      }
    }
    return new int[] {left, right};
  }

  @Test
  void anUpwardDragInTheMainViewKeepsThePressAsTheAnchor() {
    assertDrag(
        window.canvas(),
        document(),
        new TextPosition(6, 12),
        new TextPosition(4, 20),
        new TextPosition(2, 5));
  }

  @Test
  void aDownwardDragInTheMainViewSpansPressToRelease() {
    assertDrag(
        window.canvas(),
        document(),
        new TextPosition(2, 5),
        new TextPosition(4, 20),
        new TextPosition(6, 12));
  }

  @Test
  void aLeftwardDragWithinOneLineKeepsThePressAsTheAnchor() {
    assertDrag(
        window.canvas(),
        document(),
        new TextPosition(3, 20),
        new TextPosition(3, 14),
        new TextPosition(3, 6));
  }

  @Test
  void anUpwardDragInTheFilteredViewKeepsThePressAsTheAnchor() {
    LineViewController filtered = filtered();
    assertDrag(
        window.results().canvas(),
        filtered,
        new TextPosition(4, 12),
        new TextPosition(3, 20),
        new TextPosition(1, 5));
  }

  @Test
  void aDownwardDragInTheFilteredViewSpansPressToRelease() {
    LineViewController filtered = filtered();
    assertDrag(
        window.results().canvas(),
        filtered,
        new TextPosition(1, 5),
        new TextPosition(3, 20),
        new TextPosition(4, 12));
  }
}
