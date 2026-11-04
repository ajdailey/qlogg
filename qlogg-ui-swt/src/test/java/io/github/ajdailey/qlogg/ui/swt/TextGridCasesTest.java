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
import io.github.ajdailey.qlogg.core.model.LineBlock;
import io.github.ajdailey.qlogg.core.model.TextPosition;
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
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hit testing and wrap geometry of {@link LogCanvas}, recorded against the current {@code hit},
 * {@code wrappedRows}, {@code wrapFit} and {@code gutterWidth}. Points are given in grid units (a
 * column offset plus a quarter or three quarters of the advance), so the cases hold for any font.
 */
@Tag("swt")
class TextGridCasesTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int LINES = 100;

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
    controller.openFile(write(), 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return window.canvas() != null
                    && document != null
                    && !document.state().indexing()
                    && document.lines().contains(1)
                    && document.viewport().rows() > 5;
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
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** Line 0 is 400 columns of {@code x}; line 1 starts with a tab; the rest are short. */
  private Path write() throws IOException {
    StringBuilder text = new StringBuilder();
    text.append("x".repeat(400)).append('\n');
    text.append("\tat a.B\n");
    for (int i = 2; i < LINES; i++) {
      text.append("line ").append(i).append('\n');
    }
    Path file = temp.resolve("grid.log");
    Files.writeString(file, text.toString(), StandardCharsets.US_ASCII);
    return file;
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private DocumentController document() {
    return controller.activeDocument();
  }

  private LogCanvas view() {
    return window.canvas();
  }

  /** The x of a grid column plus a fraction of the advance. */
  private int x(long column, double fraction) {
    LogCanvas view = view();
    return view.gutterWidth() + view.columnOffset(column) + (int) (fraction * view.advance());
  }

  /** A y inside a row. */
  private int y(int row) {
    return row * view().lineHeight() + 1;
  }

  private TextPosition hit(int x, int y) {
    return view().hit(x, y);
  }

  @Test
  void hitsMapToTheNearestColumnBoundaryOfTheVisibleRow() {
    assertEquals(new TextPosition(0, 0), hit(x(0, 0.25), y(0)));
    assertEquals(new TextPosition(2, 5), hit(x(5, 0.25), y(2)));
    assertEquals(new TextPosition(2, 6), hit(x(5, 0.75), y(2)));
    // The gutter and a negative y map to column 0 and the top row.
    assertEquals(new TextPosition(3, 0), hit(view().gutterWidth() - 2, y(3)));
    assertEquals(new TextPosition(0, 7), hit(x(7, 0.25), -5));
    // Below the last line clamps to it.
    assertEquals(new TextPosition(LINES - 1, 3), hit(x(3, 0.25), LINES * 4 * view().lineHeight()));
    // Past the line's end the column is not clamped to its width.
    assertEquals(new TextPosition(2, 40), hit(x(40, 0.25), y(2)));
  }

  @Test
  void aTabRowHitsDisplayColumns() {
    LineBlock block = document().lines();
    assertEquals(14, block.width(1));
    assertEquals(new TextPosition(1, 8), hit(x(8, 0.25), y(1)));
    assertEquals(new TextPosition(1, 3), hit(x(3, 0.25), y(1)));
  }

  @Test
  void aScrolledViewAddsItsFirstColumn() {
    document().scrollColumns(10);
    assertEquals(10, document().viewport().firstColumn());
    assertEquals(new TextPosition(0, 10), hit(x(0, 0.25), y(0)));
    assertEquals(new TextPosition(4, 15), hit(x(5, 0.25), y(4)));
    assertEquals(new TextPosition(4, 10), hit(view().gutterWidth() - 2, y(4)));
  }

  @Test
  void theGutterHoldsTheBulletAndAtLeastFourDigits() {
    LogCanvas view = view();
    assertTrue(document().lineNumbers());
    assertEquals(view.bulletWidth() + (int) Math.ceil(4 * view.advance()) + 12, view.gutterWidth());
    controller.execute(CommandId.TOGGLE_LINE_NUMBERS);
    assertTrue(!document().lineNumbers());
    assertEquals(view.bulletWidth(), view().gutterWidth());
    assertEquals(new TextPosition(2, 5), hit(x(5, 0.25), y(2)));
    assertEquals(Math.max(8, view.lineHeight() * 3 / 4), view.bulletWidth());
  }

  @Test
  void wrappedRowsSplitLinesAtTheViewWidthAtTwoWidths() {
    controller.execute(CommandId.TOGGLE_WRAP);
    assertWrapGeometry();
    final int before = document().viewport().columns();
    window.shell().setSize(560, 400);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().viewport().columns() < before;
              }
            }));
    assertWrapGeometry();
  }

  /** The cases at the current width, waiting for the fitted row count first. */
  private void assertWrapGeometry() {
    final LogCanvas view = view();
    final int screen = view.control().getClientArea().height / view.lineHeight();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                int columns = document().viewport().columns();
                int segments = (400 + columns - 1) / columns;
                return document().lines().contains(0)
                    && document().viewport().rows() == screen - segments + 1;
              }
            }),
        "rows " + document().viewport().rows() + " of " + screen);
    LineBlock block = document().lines();
    int columns = document().viewport().columns();
    int segments = (400 + columns - 1) / columns;
    assertEquals(segments, view.wrappedRows(block, 0));
    assertEquals(1, view.wrappedRows(block, 1));
    assertEquals(1, view.wrappedRows(block, LINES + 5));
    assertEquals(screen - segments + 1, view.wrapFit());
    assertEquals(new TextPosition(0, 2), hit(x(2, 0.25), y(0)));
    assertEquals(new TextPosition(0, columns + 2L), hit(x(2, 0.25), y(1)));
    assertEquals(
        new TextPosition(0, (segments - 1L) * columns + 1), hit(x(1, 0.25), y(segments - 1)));
    // A point past the row's right edge clamps to the segment's end column.
    assertEquals(new TextPosition(0, columns), hit(x(columns + 5L, 0.25), y(0)));
    assertEquals(new TextPosition(1, 4), hit(x(4, 0.25), y(segments)));
    assertEquals(new TextPosition(2, 0), hit(view.gutterWidth() - 2, y(segments + 1)));
  }
}
