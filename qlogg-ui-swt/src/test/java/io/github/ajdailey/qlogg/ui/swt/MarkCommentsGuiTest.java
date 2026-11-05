package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The commented-mark bullet and tooltip, the comment dialog and the marks list under Xvfb. */
@Tag("swt")
class MarkCommentsGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
    controller.openFile(write("marks.log", 100), 0);
    waitForDocument("marks.log");
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

  private Path write(String name, int lines) throws IOException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      text.append("line ").append(i).append(i % 7 == 0 ? " flagword" : "").append('\n');
    }
    Path file = temp.resolve(name);
    Files.writeString(file, text.toString(), StandardCharsets.US_ASCII);
    return file;
  }

  private void waitForDocument(final String name) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return window.canvas() != null
                    && document != null
                    && document.state().title().equals(name)
                    && !document.state().indexing()
                    && document.lines().contains(document.viewport().topLine())
                    && document.viewport().rows() > 10;
              }
            }));
  }

  private DocumentController document() {
    return controller.activeDocument();
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private void flush() {
    while (display.readAndDispatch()) {
      // Pending paints and posted results.
    }
  }

  /** Paints the log view into an image and returns its pixels. */
  private ImageData pixels() {
    flush();
    Control canvas = window.canvas().control();
    Image image = new Image(display, canvas.getSize().x, canvas.getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(canvas.print(gc));
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private void move(int x, int y) {
    Event event = new Event();
    event.x = x;
    event.y = y;
    window.canvas().control().notifyListeners(SWT.MouseMove, event);
  }

  private static void key(Control control, int keyCode) {
    Event event = new Event();
    event.keyCode = keyCode;
    control.notifyListeners(SWT.KeyDown, event);
  }

  /** Writes a PNG of a shell when {@code qlogg.shots} names a directory. */
  private void shoot(Shell shell, String name) {
    String dir = System.getProperty("qlogg.shots");
    if (dir == null) {
      return;
    }
    final long until = System.nanoTime() + 500_000_000L;
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
    Point size = shell.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      shell.print(gc);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(Path.of(dir, name).toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  @Test
  void aCommentedMarkIsASquareWithTheCommentAsTooltip() {
    document().setMarkComment(4, "mock note");
    document().setMarkComment(6, "");
    LogCanvas view = window.canvas();
    int bullet = view.bulletWidth();
    int height = view.lineHeight();
    int diameter = Math.max(4, Math.min(bullet, height) - 6);
    int corner = (bullet - diameter) / 2;
    int top = (height - diameter) / 2;
    ImageData image = pixels();
    int center = bullet / 2;
    int squareCorner = image.getPixel(corner, 4 * height + top);
    assertEquals(image.getPixel(center, 4 * height + height / 2), squareCorner);
    assertNotEquals(
        image.getPixel(center, 6 * height + height / 2), image.getPixel(corner, 6 * height + top));
    move(1, 4 * height + height / 2);
    assertEquals("mock note", view.control().getToolTipText());
    shoot(window.shell(), "gutter.png");
    move(1, 6 * height + height / 2);
    assertNull(view.control().getToolTipText());
    move(1, 4 * height + height / 2);
    move(view.gutterWidth() + 20, 4 * height + height / 2);
    assertNull(view.control().getToolTipText());
  }

  @Test
  void editMarkCommentOpensADialogForTheCaretLine() {
    document().select(Selection.caretAt(new TextPosition(9, 0)));
    controller.setFocusedView(document());
    controller.execute(CommandId.EDIT_MARK_COMMENT);
    Shell dialog = null;
    for (Shell shell : window.shell().getShells()) {
      if (UiText.MARK_COMMENT_TITLE.text().equals(shell.getText())) {
        dialog = shell;
      }
    }
    assertNotNull(dialog);
    shoot(dialog, "mark-comment.png");
    Text field = null;
    for (Control child : dialog.getChildren()) {
      if (child instanceof Text text) {
        field = text;
      }
    }
    Button ok = dialog.getDefaultButton();
    assertNotNull(field);
    field.setText("checked flagword");
    ok.notifyListeners(SWT.Selection, new Event());
    assertTrue(dialog.isDisposed());
    assertEquals("checked flagword", document().markComment(9));
    assertTrue(document().marked(9));
  }

  @Test
  void theMarksListGoesToEditsRemovesAndFollowsTheTab() throws Exception {
    final DocumentController first = document();
    first.setMarkComment(3, "mock note");
    first.setMarkComment(40, "");
    first.setMarkComment(70, "");
    controller.execute(CommandId.MARKS_LIST);
    final MarksWindow marks = window.marks();
    assertNotNull(marks);
    final Table table = marks.table();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return table.getItemCount() == 3 && !marks.marks().reading();
              }
            }));
    assertEquals("4", table.getItem(0).getText(0));
    assertEquals("mock note", table.getItem(0).getText(1));
    assertEquals("line 3", table.getItem(0).getText(2));
    assertEquals(UiText.MARKS_SUMMARY.format("marks.log", 3L), marks.summary().getText());
    shoot(marks.shell(), "marks-list.png");

    table.setSelection(1);
    table.notifyListeners(SWT.DefaultSelection, new Event());
    assertEquals(40, first.selection().caret().line());

    table.setSelection(2);
    key(table, SWT.F2);
    Text field = marks.field();
    assertNotNull(field);
    field.setText("edited in the list");
    field.notifyListeners(SWT.DefaultSelection, new Event());
    assertNull(marks.field());
    assertEquals("edited in the list", first.markComment(70));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !marks.marks().reading()
                    && "edited in the list".equals(table.getItem(2).getText(1));
              }
            }));

    table.setSelection(new int[] {0, 1});
    key(table, SWT.DEL);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return table.getItemCount() == 1 && !marks.marks().reading();
              }
            }));
    assertEquals(1, first.marks().size());
    assertNull(first.markComment(3));

    controller.openFile(write("other.log", 10), 0);
    waitForDocument("other.log");
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return table.getItemCount() == 0;
              }
            }));
    assertEquals(UiText.MARKS_SUMMARY.format("other.log", 0L), marks.summary().getText());
  }
}
