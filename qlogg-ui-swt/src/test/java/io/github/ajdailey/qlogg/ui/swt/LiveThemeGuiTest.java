package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.FolderRequest;
import io.github.ajdailey.qlogg.app.view.FolderSearchState;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.folder.NamePattern;
import io.github.ajdailey.qlogg.ui.swt.platform.DialogColors;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.DeviceData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * View > Theme rebuilding each main window over its controllers. On win32 the switch goes through
 * {@code Win32ThemeFollow}; elsewhere the test asks {@link WindowViews} for the rebuild after the
 * switch, so the carry runs on every platform. Checks the state a rebuild carries, the tool
 * windows, a running search and folder search, the busy count, full screen and Always on Top, a
 * modal dialog holding its window back, flat shell, graphics resource and controller listener
 * counts over 10 switches with 1, 5 and 20 tabs, and on win32 the menu bar and title bar colors
 * after each direction. Rebuild times, counts and screenshots go to {@code
 * target/diagnostics/livew/}.
 */
@Tag("swt")
class LiveThemeGuiTest {
  private static final int SWITCHES = 10;
  private static final long LARGE_LINES = 200_000;
  private static final boolean WIN32 = "win32".equals(SWT.getPlatform());
  private static final Path DIAGNOSTICS = Path.of("target", "diagnostics", "livew");
  private static final RGB PROBE = new RGB(0x12, 0x9a, 0x56);

  @TempDir Path temp;

  private final StringBuilder report = new StringBuilder();
  private Display display;
  private SlotWindows windows;
  private ApplicationController controller;
  private MainWindowSlot slot;
  private boolean boundsKnown;

  @AfterEach
  void close() {
    closeWindows();
    save("report-" + System.nanoTime() + ".txt", report.toString());
  }

  private void closeWindows() {
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
      windows = null;
      display = null;
    }
  }

  private void start(Display created, String name, int width, int height) throws IOException {
    display = created;
    windows = new SlotWindows(display, temp.resolve("config-" + name), ThemeMode.LIGHT, null);
    controller = windows.open(40, 40, width, height);
    slot = windows.slots.get(0);
    windows.settle();
  }

  @Test
  void stateAndCountsHoldOverTenSwitches() throws IOException {
    Path large = temp.resolve("large.log");
    try (BufferedWriter out = Files.newBufferedWriter(large, StandardCharsets.UTF_8)) {
      for (long i = 0; i < LARGE_LINES; i++) {
        out.write("2026-11-01 12:00:00.000 INFO mock.service entry ");
        out.write(Long.toString(i));
        out.write(" flagword payload\n");
      }
    }
    List<String> mismatches = new ArrayList<String>();
    List<String> growth = new ArrayList<String>();
    for (int tabs : new int[] {1, 5, 20}) {
      DeviceData data = new DeviceData();
      data.tracking = true;
      start(new Display(data), "tabs-" + tabs, 1100, 700);
      try {
        scenario(large, tabs, mismatches, growth);
      } finally {
        closeWindows();
      }
    }
    assertTrue(mismatches.isEmpty(), "state not carried " + mismatches + "\n" + report);
    assertTrue(growth.isEmpty(), "counts grew " + growth + "\n" + report);
  }

  private void scenario(Path large, int tabs, List<String> mismatches, List<String> growth)
      throws IOException {
    line("== %d tabs, %s", tabs, SWT.getPlatform());
    openFiles(large, tabs);
    prepare(tabs);
    boundsKnown = slot.window().windowBounds().width() != null;
    String before = snapshot();
    line("state: %s", before);
    String counts0 = counts();
    String counts2 = null;
    List<Double> times = new ArrayList<Double>();
    for (int i = 1; i <= SWITCHES; i++) {
      boolean dark = i % 2 == 1;
      double millis = switchTo(dark);
      times.add(millis);
      windows.settle();
      String after = snapshot();
      if (!after.equals(before)) {
        mismatches.add(tabs + " tabs, switch " + i);
        line("state after %d: %s", i, after);
      }
      if (tabs == 5 && i <= 2) {
        shot((dark ? "to-dark" : "to-light") + "-5tabs");
        chrome(dark);
      }
      if (i == 2) {
        counts2 = counts();
      }
    }
    String counts10 = counts();
    line("counts before %s", counts0);
    line("counts after 2 %s", counts2);
    line("counts after %d %s", SWITCHES, counts10);
    line("rebuild turn ms: %s", times);
    if (!counts10.equals(counts2)) {
      growth.add(tabs + " tabs: " + counts2 + " -> " + counts10);
    }
  }

  private void openFiles(Path large, final int tabs) throws IOException {
    List<FileRequest> files = new ArrayList<FileRequest>();
    files.add(new FileRequest(large, 0));
    for (int t = 1; t < tabs; t++) {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < 400; i++) {
        text.append("2026-11-01 12:00:").append(10 + i % 50).append(" INFO mock entry ");
        text.append(i).append('\n');
      }
      files.add(
          new FileRequest(
              Files.writeString(temp.resolve("small-" + tabs + "-" + t + ".log"), text), 0));
    }
    controller.open(files);
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                if (controller.documents().size() != tabs) {
                  return false;
                }
                for (DocumentController document : controller.documents()) {
                  if (document.state().indexing()) {
                    return false;
                  }
                }
                return slot.window().canvas() != null;
              }
            }),
        "files open");
  }

  /** Puts state in the controllers and the view that a rebuild must carry. */
  private void prepare(int tabs) {
    final DocumentController big = controller.documents().get(0);
    controller.activate(big);
    windows.settle();
    big.goToLine(LARGE_LINES / 2);
    big.execute(CommandId.TOGGLE_MARK);
    search(big, "entry 7");
    controller.execute(CommandId.TOGGLE_KEEP_RESULTS);
    search(big, "entry 3");
    big.search().show(0);
    assertEquals(1, big.search().kept().size());
    controller.setTabName(big, "renamed");
    if (tabs > 1) {
      controller.documents().get(1).setFollowing(true);
      controller.documents().get(tabs - 1).goToLine(200);
    }
    controller.quickFind().open(true);
    controller.quickFind().setText("payload");
    // The incremental find selects its match on a later UI turn; the first capture must include it.
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !controller.quickFind().state().running();
              }
            }),
        "quick find");
    // GTK 4 under Xvfb drops a maximize requested before the new shell maps.
    if (tabs == 20 && !Gtk4.active()) {
      slot.window().shell().setMaximized(true);
    }
    if (tabs == 5) {
      slot.showMarks();
    }
    windows.settle();
    slot.focusView(true);
    windows.settle();
  }

  private void search(final DocumentController document, String text) {
    document.search().run(text, document.search().options());
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.search().state().phase().name().equals("DONE");
              }
            }),
        "search " + text);
  }

  private double switchTo(boolean dark) {
    return windows.switchTo(controller, dark);
  }

  /** The view and controller state a rebuild must keep, as text. */
  private String snapshot() {
    MainWindow window = slot.window();
    StringBuilder out = new StringBuilder();
    CTabFolder strip = window.tabStrip().tabs();
    out.append("tabs=").append(strip.getItemCount());
    out.append(" selected=").append(strip.getSelectionIndex());
    out.append(" titles=[");
    for (int i = 0; i < strip.getItemCount(); i++) {
      out.append(strip.getItem(i).getText()).append(i + 1 < strip.getItemCount() ? "," : "");
    }
    out.append("] title=").append(window.shell().getText());
    // A window maximized when it opened (a small macOS screen) has no normal bounds to carry.
    if (boundsKnown || !window.shell().getMaximized()) {
      out.append(" bounds=").append(window.windowBounds());
    }
    out.append(" max=").append(window.shell().getMaximized());
    out.append(" full=").append(window.fullScreen());
    out.append(" split=").append(window.splitPercent());
    // cocoa never rebuilds a window; its overflowing strip there grows by 4 px after a rebuild.
    if (!"cocoa".equals(SWT.getPlatform())) {
      out.append(" layout=").append(RebuildLayoutGuiTest.layout(window));
    }
    out.append(" focus=")
        .append(RebuildState.focusOf(window, window.shell().getDisplay().getFocusControl()));
    out.append(" quickfind=")
        .append(window.quickFindBar().control().isVisible())
        .append('/')
        .append(controller.quickFind().state().open())
        .append('/')
        .append(controller.quickFind().state().text())
        .append('/')
        .append(controller.quickFind().state().ignoreCase())
        .append('/')
        .append(controller.quickFind().state().regex())
        .append('/')
        .append(controller.quickFind().state().incremental());
    out.append(" marksWindow=").append(window.marks() != null && !window.marks().isDisposed());
    out.append(" resultTabs=").append(window.results().sets().getItemCount());
    out.append(" resultTab=").append(window.results().sets().getSelectionIndex());
    for (DocumentController document : controller.documents()) {
      out.append("\n  ").append(document.state().title());
      out.append(" lines=").append(document.state().lineCount());
      out.append(" top=").append(document.viewport().topLine());
      out.append(" sel=").append(document.selection());
      out.append(" ftop=").append(document.filtered().viewport().topLine());
      out.append(" fsel=").append(document.filtered().selection());
      out.append(" marks=").append(document.marks().size());
      out.append(" follow=").append(document.following());
      out.append(" search=").append(document.search().fieldText());
      out.append('/').append(document.search().state().matchCount());
      out.append(" kept=").append(document.search().kept().size());
      out.append(" shown=").append(document.search().shown());
    }
    return out.toString();
  }

  /** Shells, tracked graphics resources by type, and controller-side listeners. */
  private String counts() {
    Map<String, Integer> kinds = new TreeMap<String, Integer>();
    for (Object object : display.getDeviceData().objects) {
      if (object != null) {
        String kind = object.getClass().getSimpleName();
        Integer n = kinds.get(kind);
        kinds.put(kind, n == null ? 1 : n + 1);
      }
    }
    int listeners = listenerCount(controller);
    listeners += listenerCount(controller.theme());
    listeners += listenerCount(controller.quickFind());
    listeners += listenerCount(controller.tabData());
    listeners += listenerCount(controller.windowSet());
    int documentListeners = 0;
    for (DocumentController document : controller.documents()) {
      documentListeners += listenerCount(document);
      documentListeners += listenerCount(document.filtered());
      documentListeners += listenerCount(document.search());
      documentListeners += listenerCount(document.overview());
    }
    return "shells="
        + display.getShells().length
        + " resources="
        + kinds
        + " windowListeners="
        + listeners
        + " documentListeners="
        + documentListeners;
  }

  /** Sums the sizes of every collection field whose name holds "listener". */
  private static int listenerCount(Object target) {
    int total = 0;
    for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field field : c.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())
            || !Collection.class.isAssignableFrom(field.getType())
            || !field.getName().toLowerCase(Locale.ROOT).contains("listener")) {
          continue;
        }
        try {
          field.setAccessible(true);
          Collection<?> value = (Collection<?>) field.get(target);
          total += value == null ? 0 : value.size();
        } catch (ReflectiveOperationException e) {
          throw new AssertionError(field.toString(), e);
        }
      }
    }
    return total;
  }

  @Test
  void toolWindowsBusyCountAndRunningWorkCarry() throws IOException {
    start(new Display(), "tools", 900, 600);
    Path folder = Files.createDirectories(temp.resolve("folder"));
    for (int i = 0; i < 300; i++) {
      Files.writeString(folder.resolve("mock-" + i + ".log"), "flagword " + i + "\n".repeat(200));
    }
    Path guideFile = Files.writeString(temp.resolve("guide.html"), guidePage());
    slot.showScratchpad("first mock text", false);
    slot.window().scratchpad().newTab().setText("second mock text");
    ScratchpadWindow.Tabs scratch = slot.window().scratchpad().tabsContent();
    slot.showLog("mock log text");
    slot.showKeyboardShortcuts();
    slot.window().shortcuts().filter().setText("find");
    slot.showMultiSearch();
    slot.showFolderSearch();
    // WebKitGTK 6.0 aborts the JVM under GTK 4 (NoticeGuideDisplay).
    boolean guide = !Gtk4.active() && slot.window().showGuideAt(guideFile, null);
    windows.settle();
    Rectangle scratchBounds = slot.window().scratchpad().shell().getBounds();
    controller.quickFind().open(true);
    controller.quickFind().setText("payload");
    boolean onTop = controller.alwaysOnTop().supported();
    if (onTop) {
      controller.execute(CommandId.ALWAYS_ON_TOP);
    }
    slot.setActive();
    // XWindows finds the window by a unique title.
    slot.setTitle("qlogg-live-" + System.nanoTime());
    windows.settle();
    int shells = display.getShells().length;
    String title = slot.window().shell().getText();
    controller
        .folderSearch()
        .run(
            new FolderRequest(
                folder.toString(),
                NamePattern.DEFAULT,
                false,
                "flagword",
                SearchOptions.DEFAULTS,
                ""));
    MainWindow old = slot.window();
    switchTo(true);
    line("folder search after the rebuild: %s", controller.folderSearch().state().phase());
    final MainWindow next = slot.window();
    assertNotSame(old, next);
    assertTrue(old.shell().isDisposed());
    assertEquals(shells, display.getShells().length, "shells");
    assertEquals(title, next.shell().getText());
    assertEquals(scratch, next.scratchpad().tabsContent());
    assertEquals(scratchBounds, next.scratchpad().shell().getBounds());
    assertEquals("mock log text", next.logWindow().content());
    assertEquals("find", next.shortcuts().filter().getText());
    assertFalse(next.multiSearch().isDisposed());
    assertFalse(next.folderSearch().isDisposed());
    assertEquals(guide, next.guideWindow() != null && !next.guideWindow().shell().isDisposed());
    assertTrue(next.quickFindBar().control().isVisible());
    assertEquals(onTop, controller.alwaysOnTop().shown());
    if (onTop && !Gtk4.active() && "x11".equals(System.getProperty(PlatformTraits.GDK_BACKEND))) {
      XWindows.awaitAbove(display, next.shell(), true);
    }
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.folderSearch().state().phase() != FolderSearchState.Phase.RUNNING;
              }
            }),
        "folder search ends");
    assertEquals(FolderSearchState.Phase.DONE, controller.folderSearch().state().phase());
    assertEquals(300, controller.folderSearch().files().size());
    windows.settle();
    assertEquals(1, slot.busy().active());
    assertSame(
        display.getSystemCursor(PlatformTraits.PLATFORM.busyCursor()), next.shell().getCursor());
    slot.setIdle();
    windows.settle();
    assertEquals(0, slot.busy().active());
    assertNull(next.shell().getCursor());
  }

  @Test
  void fullScreenAndASearchRunningCarry() throws IOException {
    start(new Display(), "full", 900, 600);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 300_000; i++) {
      text.append("mock entry ").append(i).append(i % 3 == 0 ? " flagword\n" : "\n");
    }
    Path file = Files.writeString(temp.resolve("full.log"), text);
    controller.open(List.of(new FileRequest(file, 0)));
    final DocumentController document = awaitDocument();
    slot.setFullScreen(true);
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return slot.fullScreen();
              }
            }),
        "full screen");
    document.search().run("flagword", SearchOptions.DEFAULTS);
    switchTo(true);
    assertTrue(slot.fullScreen(), "full screen after the rebuild");
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.search().state().phase().name().equals("DONE");
              }
            }),
        "search ends");
    assertEquals(100_000, document.search().state().matchCount());
    slot.setFullScreen(false);
    windows.settle();
  }

  @Test
  void aMinimizedWindowStaysMinimizedWithItsRestoreBounds() throws IOException {
    start(new Display(), "minimized", 900, 600);
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
    // Brings the window to the front: the macOS runners left a background window unminimized.
    old.shell().forceActive();
    windows.settle();
    old.shell().setMinimized(true);
    boolean minimized =
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return old.shell().getMinimized();
              }
            },
            Duration.ofSeconds(10));
    line("minimized before the rebuild: %s", minimized);
    // Skips where setMinimized does not take; GTK 3 under Xvfb minimizes.
    assumeTrue(minimized, "the platform did not minimize the window");
    switchTo(true);
    final MainWindow next = slot.window();
    assertNotSame(old, next);
    // cocoa reports the window minimized only once its miniaturize animation ends.
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return next.shell().getMinimized();
              }
            },
            Duration.ofSeconds(5)),
        "minimized after the rebuild");
    assertEquals(normal, next.windowBounds());
    next.shell().setMinimized(false);
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !next.shell().getMinimized();
          }
        },
        Duration.ofSeconds(5));
    assertEquals(normal, next.windowBounds(), "restored to the old bounds");
  }

  @Test
  void aWindowWithAModalDialogIsRebuiltWhenItCloses() throws IOException {
    start(new Display(), "modal", 900, 600);
    final List<String> named = new ArrayList<String>();
    slot.askTabName(
        "mock",
        new MainView.NameListener() {
          @Override
          public void nameChosen(String name) {
            named.add(name);
          }
        });
    windows.settle();
    Shell dialog = null;
    for (Shell owned : slot.window().shell().getShells()) {
      if ((owned.getStyle() & SWT.APPLICATION_MODAL) != 0) {
        dialog = owned;
      }
    }
    assertNotNull(dialog, "modal name dialog");
    final MainWindow old = slot.window();
    controller.setTheme(ThemeMode.DARK);
    if (!WIN32) {
      display.setData(DialogColors.NATIVE_DARK, true);
      windows.views.rebuild();
    }
    BooleanSupplier rebuilt =
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return slot.window() != old;
          }
        };
    assertFalse(EventLoop.runUntil(display, rebuilt, Duration.ofMillis(800)), "waited");
    assertFalse(dialog.isDisposed());
    dialog.dispose();
    assertTrue(windows.await(rebuilt), "rebuilt after the dialog closed");
    assertTrue(old.shell().isDisposed());
    assertEquals(List.of(), named);
  }

  private DocumentController awaitDocument() {
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.documents().size() == 1
                    && !controller.documents().get(0).state().indexing();
              }
            }),
        "document open");
    return controller.documents().get(0);
  }

  private static String guidePage() {
    StringBuilder page = new StringBuilder("<html><body>");
    for (int i = 0; i < 40; i++) {
      page.append("<h2 id=\"s").append(i).append("\">Mock ").append(i).append("</h2><p>mock</p>");
    }
    return page.append("</body></html>").toString();
  }

  /** Logs the title bar and menu bar colors; on win32 checks they follow the side. */
  private void chrome(boolean dark) {
    if (Gtk4.active()) {
      return;
    }
    if (!screenShowsWindow()) {
      line("a screen read does not show the window; chrome colors not checked");
      return;
    }
    Shell shell = slot.window().shell();
    Rectangle outer = shell.getBounds();
    Rectangle client = shell.getClientArea();
    Point origin = shell.toDisplay(client.x, client.y);
    RGB title =
        Pixels.mode(capture(new Rectangle(origin.x + client.width / 2, outer.y + 10, 40, 6)));
    RGB menu =
        Pixels.mode(capture(new Rectangle(origin.x + client.width - 120, origin.y - 12, 80, 6)));
    line(
        "after rebuild to %s: title %s, menu bar %s",
        dark ? "dark" : "light", hex(title), hex(menu));
    if (WIN32) {
      assertEquals(dark, luminance(menu) < 0.2, "menu bar " + hex(menu));
      assertEquals(dark, luminance(title) < 0.2, "title bar " + hex(title));
    }
  }

  /**
   * Reports whether a screen read shows the main window, from a child of a known color above its
   * other children: the windows-11-arm runner's desktop lies under a full-screen first-run page.
   */
  private boolean screenShowsWindow() {
    Shell shell = slot.window().shell();
    Composite probe = new Composite(shell, SWT.NONE);
    GridData excluded = new GridData();
    excluded.exclude = true;
    probe.setLayoutData(excluded);
    try {
      probe.setBackground(new Color(PROBE));
      // The bottom-left corner, clear of the Marks window.
      probe.setBounds(8, shell.getClientArea().height - 72, 64, 64);
      probe.moveAbove(null);
      Point center = probe.toDisplay(32, 32);
      for (int i = 0; i < 5; i++) {
        windows.settle();
        RGB seen = Pixels.mode(capture(new Rectangle(center.x, center.y, 4, 4)));
        if (Math.abs(seen.red - PROBE.red) <= 8
            && Math.abs(seen.green - PROBE.green) <= 8
            && Math.abs(seen.blue - PROBE.blue) <= 8) {
          return true;
        }
      }
      return false;
    } finally {
      probe.dispose();
    }
  }

  private static double luminance(RGB rgb) {
    return (0.2126 * rgb.red + 0.7152 * rgb.green + 0.0722 * rgb.blue) / 255;
  }

  private void shot(String name) {
    if (Gtk4.active()) {
      return;
    }
    windows.settle();
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {capture(slot.window().shell().getBounds())};
    try {
      Files.createDirectories(DIAGNOSTICS);
      loader.save(DIAGNOSTICS.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private ImageData capture(Rectangle area) {
    Image image = new Image(display, Math.max(1, area.width), Math.max(1, area.height));
    GC gc = new GC(display);
    try {
      gc.copyArea(image, area.x, area.y);
      return Pixels.read(image, slot.window().shell());
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static String hex(RGB rgb) {
    return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
  }

  private void line(String format, Object... args) {
    String text = String.format(format, args);
    report.append(text).append('\n');
    System.err.println("LIVEW " + text);
  }

  private static void save(String file, String text) {
    try {
      Files.createDirectories(DIAGNOSTICS);
      Files.writeString(DIAGNOSTICS.resolve(file), text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
