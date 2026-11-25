package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a theme rebuild keeps of a main window's layout and window state: the file and result tab
 * strips at their computed height with the split in place, a maximized window maximized over its
 * restore bounds, and a folder search's busy cursor on the main window and the Search in Folder
 * window until the search ends. Screenshots go to {@code target/diagnostics/rebuild-layout/}.
 */
@Tag("swt")
class RebuildLayoutGuiTest {
  private static final Path DIAGNOSTICS = Path.of("target", "diagnostics", "rebuild-layout");

  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;
  private ApplicationController controller;
  private MainWindowSlot slot;

  @AfterEach
  void close() {
    try {
      if (windows != null) {
        windows.close();
        assertTrue(windows.failures.isEmpty(), windows.failures.toString());
      }
    } finally {
      if (display != null && !display.isDisposed()) {
        display.setDarkThemePreferred(false);
        display.dispose();
      }
    }
  }

  private void start(String name, int x, int y, int width, int height) throws IOException {
    display = new Display();
    windows = new SlotWindows(display, temp.resolve("config-" + name), ThemeMode.LIGHT, null);
    controller = windows.open(x, y, width, height);
    slot = windows.slots.get(0);
    windows.settle();
  }

  /**
   * Returns the bounds of the tab strips, the log view and the filtered view in shell coordinates,
   * with the strips' client heights, which the folders' {@code GridData} hint sets to 1.
   *
   * @param window a main window showing a document
   * @return the layout as text
   */
  static String layout(MainWindow window) {
    CTabFolder tabs = window.tabStrip().tabs();
    CTabFolder sets = window.results().sets();
    return "tabs="
        + place(tabs)
        + "/"
        + tabs.getClientArea().height
        + " view="
        + place(window.canvas().control())
        + " sets="
        + place(sets)
        + "/"
        + sets.getClientArea().height
        + " filtered="
        + place(window.results().canvas().control());
  }

  private static Rectangle place(Control control) {
    return control.getDisplay().map(control.getParent(), control.getShell(), control.getBounds());
  }

  @Test
  void stripsAndSplitKeepTheirPlaceAfterARebuild() throws IOException {
    start("strips", 40, 40, 1000, 640);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      text.append("2026-11-01 12:00:00 INFO mock line ").append(i).append('\n');
    }
    Path file = Files.writeString(temp.resolve("strips.log"), text);
    Path second = Files.writeString(temp.resolve("second.log"), text);
    controller.open(List.of(new FileRequest(file, 0), new FileRequest(second, 0)));
    final DocumentController document = awaitDocuments(2);
    controller.activate(document);
    windows.settle();
    search(document, "mock line 1");
    controller.execute(CommandId.TOGGLE_KEEP_RESULTS);
    search(document, "mock line 2");
    controller.quickFind().open(true);
    windows.settle();
    assertEquals(2, slot.window().results().sets().getItemCount(), "a kept result tab");
    String before = layout(slot.window());
    assertTrue(stripClients(slot.window()), "empty band under a strip: " + before);
    shot("before");
    for (boolean dark : new boolean[] {true, false}) {
      MainWindow old = slot.window();
      windows.switchTo(controller, dark);
      assertNotSame(old, slot.window());
      windows.settle();
      shot(dark ? "after-dark" : "after-light");
      assertEquals(before, layout(slot.window()), "layout after a rebuild to " + dark);
    }
  }

  private void search(final DocumentController document, String text) {
    document.search().run(text, document.search().options());
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.search().state().phase() == SearchViewState.Phase.DONE;
              }
            }),
        "search " + text);
  }

  /** Reports whether both strips have the client height their hint sets: no empty band. */
  private static boolean stripClients(MainWindow window) {
    return window.tabStrip().tabs().getClientArea().height == 1
        && window.results().sets().getClientArea().height == 1;
  }

  @Test
  void aMaximizedWindowStaysMaximizedWithItsRestoreBounds() throws IOException {
    start("maximized", 60, 20, 700, 450);
    Path file = Files.writeString(temp.resolve("max.log"), "mock line\n".repeat(400));
    controller.open(List.of(new FileRequest(file, 0)));
    awaitDocuments(1);
    final MainWindow old = slot.window();
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return old.windowBounds().width() != null;
              }
            }),
        "bounds tracked");
    WindowGeometry.Request normal = old.windowBounds();
    old.shell().forceActive();
    old.shell().setMaximized(true);
    // GTK 4 under Xvfb does not maximize.
    assumeTrue(maximized(old.shell(), true), "the platform did not maximize the window");
    for (boolean dark : new boolean[] {true, false}) {
      windows.switchTo(controller, dark);
      MainWindow next = slot.window();
      assertTrue(maximized(next.shell(), true), "maximized after a rebuild to " + dark);
      assertEquals(normal, next.windowBounds());
    }
    shot("maximized");
    MainWindow next = slot.window();
    next.shell().setMaximized(false);
    assertTrue(maximized(next.shell(), false), "restored");
    windows.settle();
    assertEquals(normal, next.windowBounds(), "restored to the old bounds");
  }

  private boolean maximized(final Shell shell, final boolean state) {
    return EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shell.getMaximized() == state;
          }
        },
        Duration.ofSeconds(10));
  }

  @Test
  void aFolderSearchShowsTheBusyCursorUntilItEndsAcrossARebuild() throws IOException {
    start("folder", 40, 40, 900, 600);
    Path folder = Files.createDirectories(temp.resolve("folder"));
    Files.writeString(folder.resolve("runaway.log"), "a".repeat(60) + "\n");
    final FolderRequest runaway =
        new FolderRequest(
            folder.toString(), "runaway.log", false, "(.*a){25}x", SearchOptions.DEFAULTS, "");
    slot.showFolderSearch();
    windows.settle();
    controller.folderSearch().run(runaway);
    assertTrue(windows.await(busyCursors()), "busy cursor on both windows: " + folderState());
    windows.switchTo(controller, true);
    assertTrue(windows.await(busyCursors()), "busy cursor after the rebuild: " + folderState());
    assertEquals(FolderSearchState.Phase.RUNNING, controller.folderSearch().state().phase());
    controller.folderSearch().cancel();
    awaitEnded();
    windows.settle();
    assertNull(slot.window().shell().getCursor());
    assertNull(slot.window().folderSearch().shell().getCursor());
    controller.folderSearch().run(runaway);
    assertTrue(windows.await(busyCursors()), "busy cursor on the second run: " + folderState());
    slot.window().folderSearch().shell().dispose();
    awaitEnded();
    windows.settle();
    assertNull(slot.window().shell().getCursor(), "cleared when the window closes");
  }

  private String folderState() {
    MainWindow window = slot.window();
    FolderSearchWindow folder = window.folderSearch();
    return controller.folderSearch().state()
        + " busy "
        + slot.busy().active()
        + " system "
        + display.getSystemCursor(PlatformTraits.PLATFORM.busyCursor())
        + " main cursor "
        + window.shell().getCursor()
        + " folder window "
        + (folder == null || folder.isDisposed() ? "closed" : folder.shell().getCursor());
  }

  /** The busy cursor on both windows. */
  private BooleanSupplier busyCursors() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        Cursor cursor = display.getSystemCursor(PlatformTraits.PLATFORM.busyCursor());
        MainWindow window = slot.window();
        return window.shell().getCursor() == cursor
            && window.folderSearch() != null
            && !window.folderSearch().isDisposed()
            && window.folderSearch().shell().getCursor() == cursor;
      }
    };
  }

  private void awaitEnded() {
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.folderSearch().state().phase() != FolderSearchState.Phase.RUNNING;
              }
            }),
        "folder search ends");
  }

  private DocumentController awaitDocuments(final int count) {
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                if (controller.documents().size() != count || slot.window().canvas() == null) {
                  return false;
                }
                for (DocumentController document : controller.documents()) {
                  if (document.state().indexing()) {
                    return false;
                  }
                }
                return true;
              }
            }),
        "documents open");
    return controller.documents().get(0);
  }

  private void shot(String name) {
    if (Gtk4.active()) {
      return;
    }
    windows.settle();
    Rectangle area = slot.window().shell().getBounds();
    Image image = new Image(display, Math.max(1, area.width), Math.max(1, area.height));
    GC gc = new GC(display);
    try {
      gc.copyArea(image, area.x, area.y);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {Pixels.read(image, slot.window().shell())};
      Files.createDirectories(DIAGNOSTICS);
      loader.save(DIAGNOSTICS.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }
}
