package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
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
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edit > Search in Folder driven through its widgets: a run from the fields, the file rows with
 * their counts and statuses, a file's lines, and a chosen line opened in a tab. With {@code
 * -Dqlogg.shots=<dir>} it also writes PNGs of the window and of the opened line.
 */
@Tag("swt")
class FolderSearchGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() {
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

  @Test
  void aRunListsFilesAndAChosenLineOpensInATab() throws Exception {
    Path logs = Files.createDirectory(temp.resolve("logs"));
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      text.append(i % 10 == 4 ? "hit\t" : "line ").append(i).append('\n');
    }
    Files.writeString(logs.resolve("app.log"), text.toString(), StandardCharsets.US_ASCII);
    Files.write(logs.resolve("app.log.1.gz"), new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 'h'});
    controller.execute(CommandId.SEARCH_FOLDER);
    final FolderSearchWindow search = window.folderSearch();
    assertNotNull(search);
    assertEquals(UiText.FOLDER_IDLE.text(), search.summary().getText());
    assertEquals("*.log*", search.names().getText());
    search.folder().setText(logs.toString());
    search.text().setText("hit");
    search.shell().getDefaultButton().notifyListeners(SWT.Selection, new Event());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.folderSearch().state().phase() == FolderSearchState.Phase.DONE
                    && search.files().getItemCount() == 2;
              }
            }));
    assertEquals(UiText.FOLDER_DONE.format("hit", 4L, 1L, 2), search.summary().getText());
    assertEquals("4", search.files().getItem(0).getText(1));
    assertEquals(UiText.FOLDER_STATUS_DONE.text(), search.files().getItem(0).getText(2));
    assertEquals(UiText.FOLDER_STATUS_NOT_TEXT.text(), search.files().getItem(1).getText(2));

    search.files().setSelection(0);
    search.files().notifyListeners(SWT.Selection, new Event());
    assertEquals(4, search.lines().getItemCount());
    assertEquals("15", search.lines().getItem(1).getText(0));
    assertEquals("hit 14", search.lines().getItem(1).getText(1));

    shoot(search.shell(), "folder-search.png");
    search.lines().setSelection(1);
    search.lines().notifyListeners(SWT.DefaultSelection, new Event());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != null
                    && shown.state().title().equals("app.log")
                    && shown.selection().caret().line() == 14;
              }
            }));
    shoot(window.shell(), "folder-search-opened.png");
    search.shell().dispose();
    assertEquals(FolderSearchState.Phase.DONE, controller.folderSearch().state().phase());
    assertEquals(0, controller.folderSearch().files().size(), "closing drops the results");
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

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }
}
