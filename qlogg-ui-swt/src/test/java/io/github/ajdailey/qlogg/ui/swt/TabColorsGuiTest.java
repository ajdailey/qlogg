package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.theme.ColorSchemes;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.theme.ThemeListener;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tab strip's screen pixels across theme changes, under Xvfb, with focus outside and inside the
 * folder: each tab is readable and the strip follows the palette, which a fixed color scheme sets
 * apart from the theme; the log views' background matches the scheme. With {@code
 * -Dqlogg.qc.shots=<dir>} each step also writes the window as a PNG. Skipped on GTK 4. Where a
 * screen read does not show the windows, win32 reads through {@code Control.print} and other
 * platforms fail.
 */
@Tag("swt")
class TabColorsGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final double READABLE = 4.5;
  private static final double DARK_LUMINANCE = 0.2;
  private static final double LIGHT_LUMINANCE = 0.5;
  private static final boolean WIN32 = "win32".equals(SWT.getPlatform());
  private static final RGB PROBE = new RGB(0x12, 0x9a, 0x56);
  private static final int PROBE_TOLERANCE = 8;
  private static final int PROBE_READS = 6;
  private static final String NO_SCREEN = "A screen read does not show the main window";

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<String> faults = new ArrayList<String>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private boolean screenShowsWindows;

  private void open(ThemeMode mode) throws Exception {
    open(mode, ColorSchemes.FOLLOW_THEME);
  }

  private void open(ThemeMode mode, String colors) throws Exception {
    assumeFalse(Gtk4.active(), Gtk4.NO_SCREEN_READ);
    display = new Display();
    final SwtThemeService system = SwtThemeService.open(display, mode);
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
            Preferences.DEFAULTS
                .withTheme(mode)
                .withView(Preferences.DEFAULTS.view().withColors(colors)),
            new ThemeService() {
              @Override
              public boolean systemDark() {
                return system.systemDark();
              }

              @Override
              public void addListener(ThemeListener listener) {
                system.addListener(listener);
              }

              @Override
              public void preferDark(boolean dark) {
                system.preferDark(dark);
              }

              /** True, so win32 opens no modal restart notice in the test. */
              @Override
              public boolean showsNow(boolean dark) {
                return true;
              }
            });
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
    Path first = Files.writeString(temp.resolve("first.log"), "a\nb\nc\n");
    Path second = Files.writeString(temp.resolve("second.log"), "x\ny\n");
    controller.open(List.of(new FileRequest(first, 0), new FileRequest(second, 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabStrip().tabs().getItemCount() == 2
                    && window.tabStrip().tabs().getSelection() != null
                    && !controller.documents().get(0).state().indexing()
                    && !controller.documents().get(1).state().indexing();
              }
            }));
    screenShowsWindows = screenShowsWindows();
    assertTrue(screenShowsWindows || WIN32, NO_SCREEN);
  }

  /**
   * Reports whether a screen read shows the main window, from a child of a known color above its
   * other children, so that no window stacking decides the answer. The windows-11-arm runner's
   * desktop lies under a full-screen first-run page, so a read there returns that page.
   */
  private boolean screenShowsWindows() {
    Composite probe = new Composite(window.shell(), SWT.NONE);
    GridData excluded = new GridData();
    excluded.exclude = true;
    probe.setLayoutData(excluded);
    try {
      probe.setBackground(new Color(PROBE));
      probe.setBounds(100, 100, 64, 64);
      probe.moveAbove(null);
      window.shell().forceActive();
      Point center = probe.toDisplay(32, 32);
      for (int i = 0; i < PROBE_READS; i++) {
        settle();
        ImageData data = capture(new Rectangle(center.x, center.y, 4, 4));
        if (near(data.palette.getRGB(data.getPixel(2, 2)))) {
          return true;
        }
      }
      return false;
    } finally {
      probe.dispose();
    }
  }

  private static boolean near(RGB seen) {
    return Math.abs(seen.red - PROBE.red) <= PROBE_TOLERANCE
        && Math.abs(seen.green - PROBE.green) <= PROBE_TOLERANCE
        && Math.abs(seen.blue - PROBE.blue) <= PROBE_TOLERANCE;
  }

  @AfterEach
  void close() {
    if (controller != null) {
      controller.shutdown();
      runUntil(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.shell().isDisposed();
            }
          });
    }
    if (display != null) {
      display.dispose();
    }
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  @Test
  void tabsFollowLightDarkLight() throws Exception {
    open(ThemeMode.LIGHT);
    check("light-start", false);
    controller.setTheme(ThemeMode.DARK);
    settle();
    check("light-then-dark", true);
    controller.setTheme(ThemeMode.LIGHT);
    settle();
    check("light-dark-light", false);
    assertTrue(faults.isEmpty(), faults.toString());
  }

  @Test
  void tabsFollowDarkLightDark() throws Exception {
    open(ThemeMode.DARK);
    check("dark-start", true);
    controller.setTheme(ThemeMode.LIGHT);
    settle();
    check("dark-then-light", false);
    controller.setTheme(ThemeMode.DARK);
    settle();
    check("dark-light-dark", true);
    assertTrue(faults.isEmpty(), faults.toString());
  }

  @Test
  void scratchpadTabsFollowTheTheme() throws Exception {
    open(ThemeMode.LIGHT);
    window.showScratchpad("mock", false);
    settle();
    CTabFolder tabs = window.scratchpad().tabs();
    controller.setTheme(ThemeMode.DARK);
    settle();
    tabs.getShell().forceActive();
    settle();
    tabs.getShell().forceFocus();
    inspectStrip(tabs, "scratchpad-dark-unfocused", true);
    tabs.forceFocus();
    inspectStrip(tabs, "scratchpad-dark-focused", true);
    assertTrue(faults.isEmpty(), faults.toString());
  }

  // View colors.

  @Test
  void aDarkSchemeInLightChromeAndALiveSwitchBothWays() throws Exception {
    open(ThemeMode.LIGHT, "nightwater");
    checkScheme("light-chrome-nightwater", "nightwater");
    setColors("paper");
    checkScheme("light-chrome-then-paper", "paper");
    setColors("graphite");
    checkScheme("light-chrome-then-graphite", "graphite");
    setColors("dark");
    checkScheme("light-chrome-then-dark", "dark");
    setColors(ColorSchemes.FOLLOW_THEME);
    check("light-chrome-then-theme", false);
    assertSame(Palette.LIGHT, window.canvas().palette());
    assertTrue(faults.isEmpty(), faults.toString());
  }

  @Test
  void aLightSchemeInDarkChromeAndALiveSwitchBothWays() throws Exception {
    open(ThemeMode.DARK, "sandstone");
    checkScheme("dark-chrome-sandstone", "sandstone");
    setColors("midnight");
    checkScheme("dark-chrome-then-midnight", "midnight");
    setColors("legal-pad");
    checkScheme("dark-chrome-then-legal-pad", "legal-pad");
    setColors("light");
    checkScheme("dark-chrome-then-light", "light");
    setColors("legal-pad");
    controller.setTheme(ThemeMode.LIGHT);
    settle();
    checkScheme("light-chrome-keeps-legal-pad", "legal-pad");
    assertTrue(faults.isEmpty(), faults.toString());
  }

  private void setColors(String id) {
    controller.setViewOptions(controller.preferences().view().withColors(id));
    settle();
  }

  /** Checks both strips, the main view and the filtered view against a listed scheme. */
  private void checkScheme(String step, String id) throws Exception {
    Palette palette = ColorSchemes.NONE.find(id).palette();
    assertSame(palette, window.canvas().palette(), step);
    assertSame(palette, window.results().canvas().palette(), step);
    check(step, palette.dark());
    inspectView(step + "-main", window.canvas().control(), palette);
    inspectView(step + "-filtered", window.results().canvas().control(), palette);
  }

  /** Reads the view's lower right corner, below the text, which shows the scheme background. */
  private void inspectView(String step, Canvas view, Palette palette) throws Exception {
    view.redraw();
    view.update();
    settle();
    Rectangle size = view.getClientArea();
    int x = size.width - 24;
    int y = size.height - 8;
    ImageData data;
    if (screenShowsWindows) {
      Point at = view.toDisplay(x, y);
      data = capture(new Rectangle(at.x, at.y, 1, 1));
      x = 0;
      y = 0;
    } else {
      data = print(view);
    }
    RGB seen = data.palette.getRGB(data.getPixel(x, y));
    RGB expected =
        new RGB(
            palette.background().red(), palette.background().green(), palette.background().blue());
    if (Math.abs(seen.red - expected.red) > 2
        || Math.abs(seen.green - expected.green) > 2
        || Math.abs(seen.blue - expected.blue) > 2) {
      faults.add(step + ": background " + seen + ", expected " + expected);
    }
  }

  /** About opened after a live switch either way is as large as its content wants. */
  @Test
  void aboutFitsItsTextAfterASwitchEachWay() throws Exception {
    open(ThemeMode.SYSTEM);
    boolean dark = controller.palette().equals(Palette.DARK);
    controller.setTheme(dark ? ThemeMode.LIGHT : ThemeMode.DARK);
    settle();
    assertAboutFits();
    controller.setTheme(dark ? ThemeMode.DARK : ThemeMode.LIGHT);
    settle();
    assertAboutFits();
  }

  private void assertAboutFits() {
    controller.execute(CommandId.ABOUT);
    settle();
    Shell about = null;
    for (Shell shell : display.getShells()) {
      if (shell.getText().equals("About QLogg")) {
        about = shell;
      }
    }
    assertTrue(about != null, "no About window");
    Point size = about.getSize();
    Point wanted = about.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
    about.dispose();
    assertTrue(size.x >= wanted.x && size.y >= wanted.y, size + " < " + wanted);
  }

  /** Runs the event loop for a fixed turn: nothing signals that GTK finished restyling. */
  private void settle() {
    final long end = System.nanoTime() + Duration.ofMillis(500).toNanos();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() >= end;
          }
        });
  }

  private void check(String step, boolean dark) throws Exception {
    window.shell().forceActive();
    settle();
    window.shell().forceFocus();
    inspectStrip(window.tabStrip().tabs(), step + "-tabs-unfocused", dark);
    inspectStrip(window.results().sets(), step + "-results-unfocused", dark);
    window.tabStrip().tabs().forceFocus();
    inspectStrip(window.tabStrip().tabs(), step + "-tabs-focused", dark);
    window.results().sets().forceFocus();
    inspectStrip(window.results().sets(), step + "-results-focused", dark);
  }

  private void inspectStrip(CTabFolder tabs, String step, boolean dark) throws Exception {
    tabs.redraw();
    tabs.update();
    settle();
    ImageData data;
    int dx = 0;
    int dy = 0;
    if (screenShowsWindows) {
      Rectangle shellArea = tabs.getShell().getBounds();
      data = capture(shellArea);
      Point origin = tabs.toDisplay(0, 0);
      dx = origin.x - shellArea.x;
      dy = origin.y - shellArea.y;
    } else {
      data = print(tabs);
    }
    String shots = System.getProperty("qlogg.qc.shots");
    if (shots != null) {
      System.out.println("QC " + step + " read " + (screenShowsWindows ? "screen" : "print"));
      save(data, shots, step);
    }
    CTabItem selected = tabs.getSelection();
    for (CTabItem item : tabs.getItems()) {
      Rectangle bounds = item.getBounds();
      Rectangle area = new Rectangle(bounds.x + dx, bounds.y + dy, bounds.width, bounds.height);
      inspect(step + (item == selected ? " selected" : " unselected"), data, area, dark);
    }
  }

  private void save(ImageData data, String shots, String name) throws Exception {
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {data};
    Files.createDirectories(Path.of(shots));
    loader.save(Path.of(shots, name + ".png").toString(), SWT.IMAGE_PNG);
  }

  /** Reads a covered control's pixels on win32, where they equal the screen's (measured). */
  private ImageData print(Control control) {
    Rectangle size = control.getBounds();
    Image image = new Image(display, size.width, size.height);
    GC gc = new GC(image);
    try {
      assertTrue(control.print(gc), "print");
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Reads pixels from the screen: on GTK {@code Control.print} draws what the screen does not. */
  private ImageData capture(Rectangle area) {
    Image image = new Image(display, area.width, area.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, area.x, area.y);
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private void inspect(String label, ImageData data, Rectangle area, boolean dark) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    for (int y = area.y + 3; y < area.y + area.height - 3; y++) {
      for (int x = area.x + 3; x < area.x + area.width - 3; x++) {
        counts.merge(data.getPixel(x, y), 1, Integer::sum);
      }
    }
    RGB ground = null;
    int most = 0;
    for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
      if (entry.getValue() > most) {
        most = entry.getValue();
        ground = data.palette.getRGB(entry.getKey());
      }
    }
    double best = 0;
    RGB text = ground;
    for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
      RGB rgb = data.palette.getRGB(entry.getKey());
      double contrast = contrast(rgb, ground);
      if (entry.getValue() >= 3 && contrast > best) {
        best = contrast;
        text = rgb;
      }
    }
    double luminance = luminance(ground);
    String seen = String.format("%s: ground %s text %s contrast %.2f", label, ground, text, best);
    if (System.getProperty("qlogg.qc.shots") != null) {
      System.out.println("QC " + seen);
    }
    if (best < READABLE) {
      faults.add("unreadable " + seen);
    }
    if (dark ? luminance > DARK_LUMINANCE : luminance < LIGHT_LUMINANCE) {
      faults.add("wrong theme " + seen);
    }
  }

  private static double contrast(RGB a, RGB b) {
    double high = Math.max(luminance(a), luminance(b));
    double low = Math.min(luminance(a), luminance(b));
    return (high + 0.05) / (low + 0.05);
  }

  private static double luminance(RGB rgb) {
    return 0.2126 * linear(rgb.red) + 0.7152 * linear(rgb.green) + 0.0722 * linear(rgb.blue);
  }

  private static double linear(int channel) {
    double value = channel / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }
}
