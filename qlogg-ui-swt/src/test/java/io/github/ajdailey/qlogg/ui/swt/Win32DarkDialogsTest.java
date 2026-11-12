package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.MainView;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On win32 under a dark startup setting, dialog bodies draw the dark dialog color with light text,
 * matching the dark title bar and push buttons; under a light one they draw as before. The check
 * reads screen pixels, since {@code getBackground} can differ from what win32 draws. Where a screen
 * read does not show the dialog, as on the {@code windows-11-arm} runner, whose desktop lies under
 * the Windows setup screen, it reads the first label through {@code Control.print} and checks its
 * text against its background. Each dialog is saved to {@code
 * target/diagnostics/win32-dark-dialogs/}.
 */
@Tag("swt")
class Win32DarkDialogsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final RGB PROBE = new RGB(0x12, 0x9a, 0x56);
  private static final int TOLERANCE = 8;

  /** WCAG contrast a label's text keeps on the drawn body. */
  private static final double TEXT_CONTRAST = 4.5;

  /** Commands that open a dialog or tool window over the main window. */
  private static final CommandId[] DIALOGS = {
    CommandId.ABOUT,
    CommandId.OPTIONS,
    CommandId.PREDEFINED_FILTERS,
    CommandId.EDIT_HIGHLIGHTERS,
    CommandId.AUTO_HIGHLIGHTERS,
    CommandId.GO_TO_LINE,
    CommandId.GO_TO_TIME,
    CommandId.TIME_OFFSET,
    CommandId.TIME_ZONE,
    CommandId.TIMESTAMP_FORMATS,
    CommandId.SWITCH_FILE,
    CommandId.KEYBOARD_SHORTCUTS,
    CommandId.MARKS_LIST,
    CommandId.SCRATCHPAD,
    CommandId.COMMAND_PALETTE,
    CommandId.EDIT_HISTORY,
    CommandId.COMMAND_LINE_OPTIONS,
  };

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final StringBuilder report = new StringBuilder();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  private void open(ThemeMode mode) throws IOException {
    assumeTrue("win32".equals(SWT.getPlatform()), "win32 colors dialogs under a dark start");
    display = new Display();
    SwtThemeService system = SwtThemeService.open(display, mode);
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
            Preferences.DEFAULTS.withTheme(mode),
            system);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      text.append("2026-11-01 12:00:").append(10 + i).append(" INFO mock entry ").append(i);
      text.append('\n');
    }
    Path log = Files.writeString(temp.resolve("service.log"), text.toString());
    controller.open(List.of(new FileRequest(log, 0)));
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != null && !shown.state().indexing() && window.canvas() != null;
              }
            },
            BOUND));
  }

  @AfterEach
  void close() {
    if (display == null) {
      return;
    }
    try {
      controller.shutdown();
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.shell().isDisposed();
            }
          },
          BOUND);
      display.setDarkThemePreferred(false);
      display.dispose();
    } finally {
      assertTrue(failures.isEmpty(), failures.toString());
    }
  }

  @Test
  void dialogsDrawDarkUnderADarkStart() throws IOException {
    open(ThemeMode.DARK);
    checkAll("dark", true);
  }

  @Test
  void dialogsDrawLightUnderALightStart() throws IOException {
    open(ThemeMode.LIGHT);
    checkAll("light", false);
  }

  @Test
  void dialogsFollowADarkSystemUnderASystemStart() throws IOException {
    assumeTrue(
        "win32".equals(SWT.getPlatform()) && Display.isSystemDarkTheme(),
        "a system start on a dark win32 system");
    open(ThemeMode.SYSTEM);
    assertTrue(SwtThemeService.nativeDark(display));
    checkAll("system", true);
  }

  /** Opens each dialog, saves it and checks its body and label colors on screen. */
  private void checkAll(String theme, boolean dark) {
    List<String> wrong = new ArrayList<String>();
    for (final CommandId command : DIALOGS) {
      checkOne(
          theme + "-" + command.name().toLowerCase(Locale.ROOT),
          new Runnable() {
            @Override
            public void run() {
              controller.execute(command);
            }
          },
          dark,
          wrong);
    }
    checkOne(
        theme + "-error",
        new Runnable() {
          @Override
          public void run() {
            window.showError("Mock error", "mock message");
          }
        },
        dark,
        wrong);
    // Search All Files and Save as Filter need a search; their windows open directly.
    checkOne(
        theme + "-search_all_files",
        new Runnable() {
          @Override
          public void run() {
            window.showMultiSearch();
          }
        },
        dark,
        wrong);
    checkOne(
        theme + "-name",
        new Runnable() {
          @Override
          public void run() {
            window.askTabName(
                "mock",
                new MainView.NameListener() {
                  @Override
                  public void nameChosen(String name) {}
                });
          }
        },
        dark,
        wrong);
    save(theme + ".txt", report.toString());
    assertTrue(wrong.isEmpty(), "wrong colors " + wrong + "\n" + report);
  }

  private void checkOne(String name, Runnable action, boolean dark, List<String> wrong) {
    Shell dialog = openDialog(action);
    if (dialog == null) {
      report.append(name).append(" opened no shell\n");
      return;
    }
    try {
      TabFolder tabs = tabs(dialog);
      if (tabs == null) {
        check(dialog, name, dark, wrong);
      } else {
        for (int i = 0; i < tabs.getItemCount(); i++) {
          tabs.setSelection(i);
          check(dialog, name + "-tab" + i, dark, wrong);
        }
      }
    } finally {
      dialog.dispose();
      settle();
    }
  }

  private Shell openDialog(Runnable action) {
    final List<Shell> before = List.of(display.getShells());
    action.run();
    final Shell[] found = new Shell[1];
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (Shell shell : display.getShells()) {
              if (!before.contains(shell) && shell.isVisible()) {
                found[0] = shell;
                return true;
              }
            }
            return false;
          }
        },
        Duration.ofSeconds(3));
    if (found[0] != null) {
      found[0].forceActive();
      settle();
    }
    return found[0];
  }

  private void check(Shell dialog, String name, boolean dark, List<String> wrong) {
    settle();
    boolean screen = shows(dialog);
    // The bottom left corner of the client area lies in the layout margin, past any frame. A
    // printed Shell lays its children out off their places on windows-11-arm (measured), so a print
    // read checks the label alone.
    RGB body = null;
    if (screen) {
      Rectangle client = dialog.getClientArea();
      Point origin = dialog.toDisplay(client.x + 2, client.y + client.height - 10);
      body = mode(capture(new Rectangle(origin.x, origin.y, 6, 6)));
      RGB expected =
          dark
              ? Win32DialogColors.BODY.getRGB()
              : display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND).getRGB();
      if (!near(body, expected)) {
        wrong.add(name + " body");
      }
    }
    Label label = firstLabel(dialog);
    String ink = "none";
    double contrast = 0;
    if (label != null) {
      ImageData data = screen ? capture(screenArea(label)) : print(label);
      RGB ground = mode(data);
      RGB strongest = strongest(data, ground);
      ink = hex(strongest) + " on " + hex(ground);
      contrast = contrast(strongest, ground);
      boolean light = luminance(strongest) > luminance(ground);
      if (contrast < TEXT_CONTRAST || light != dark) {
        wrong.add(name + " label");
      }
    }
    report.append(
        String.format(
            "%s read=%s body=%s label ink=%s contrast=%.2f%n",
            name, screen ? "screen" : "print", body == null ? "unread" : hex(body), ink, contrast));
    try {
      Path dir = Files.createDirectories(Path.of("target", "diagnostics", "win32-dark-dialogs"));
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {screen ? capture(dialog.getBounds()) : print(dialog)};
      loader.save(dir.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Returns whether a screen read shows the dialog: a probe of a known color reads back. */
  private boolean shows(Shell dialog) {
    Composite probe = new Composite(dialog, SWT.NONE);
    GridData excluded = new GridData();
    excluded.exclude = true;
    probe.setLayoutData(excluded);
    try {
      probe.setBackground(new Color(PROBE));
      probe.setBounds(0, 0, 8, 8);
      probe.moveAbove(null);
      final Point corner = probe.toDisplay(4, 4);
      return EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              display.update();
              ImageData data = capture(new Rectangle(corner.x, corner.y, 1, 1));
              return near(data.palette.getRGB(data.getPixel(0, 0)), PROBE);
            }
          },
          Duration.ofSeconds(2));
    } finally {
      probe.dispose();
      settle();
    }
  }

  private static TabFolder tabs(Composite parent) {
    for (Control child : parent.getChildren()) {
      if (child instanceof TabFolder folder) {
        return folder;
      }
    }
    return null;
  }

  private static Label firstLabel(Composite parent) {
    for (Control child : parent.getChildren()) {
      if (!child.isVisible()) {
        continue;
      }
      if (child instanceof Label label
          && (label.getStyle() & SWT.SEPARATOR) == 0
          && label.getImage() == null
          && !label.getText().isEmpty()) {
        return label;
      }
      if (child instanceof TabFolder folder && folder.getSelectionIndex() >= 0) {
        Control page = folder.getItem(folder.getSelectionIndex()).getControl();
        if (page instanceof Composite composite) {
          Label found = firstLabel(composite);
          if (found != null) {
            return found;
          }
        }
      } else if (child instanceof Composite composite && !(child instanceof TabFolder)) {
        Label found = firstLabel(composite);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private void settle() {
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !display.readAndDispatch();
          }
        },
        BOUND);
    display.update();
  }

  private static Rectangle screenArea(Control control) {
    Point size = control.getSize();
    Point origin = control.toDisplay(0, 0);
    return new Rectangle(origin.x, origin.y, size.x, size.y);
  }

  private ImageData capture(Rectangle area) {
    Image image = new Image(display, Math.max(1, area.width), Math.max(1, area.height));
    GC gc = new GC(display);
    try {
      gc.copyArea(image, area.x, area.y);
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static void save(String file, String text) {
    try {
      Path dir = Files.createDirectories(Path.of("target", "diagnostics", "win32-dark-dialogs"));
      Files.writeString(dir.resolve(file), text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads a control's pixels through {@code Control.print}, which on win32 equals the screen
   * (measured in {@code TabColorsGuiTest}) where another window covers it.
   */
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

  /** Returns the most frequent color. */
  private static RGB mode(ImageData data) {
    return mode(data, new Rectangle(0, 0, data.width, data.height));
  }

  /** Returns the most frequent color inside an area. */
  private static RGB mode(ImageData data, Rectangle area) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    int best = 0;
    int bestCount = -1;
    for (int y = Math.max(0, area.y); y < Math.min(data.height, area.y + area.height); y++) {
      for (int x = Math.max(0, area.x); x < Math.min(data.width, area.x + area.width); x++) {
        int pixel = data.getPixel(x, y);
        Integer count = counts.get(pixel);
        int next = count == null ? 1 : count + 1;
        counts.put(pixel, next);
        if (next > bestCount) {
          best = pixel;
          bestCount = next;
        }
      }
    }
    return data.palette.getRGB(best);
  }

  private static RGB strongest(ImageData data, RGB ground) {
    RGB best = ground;
    double max = 1;
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        double value = contrast(rgb, ground);
        if (value > max) {
          max = value;
          best = rgb;
        }
      }
    }
    return best;
  }

  private static double contrast(RGB a, RGB b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  private static double luminance(RGB rgb) {
    return 0.2126 * channel(rgb.red) + 0.7152 * channel(rgb.green) + 0.0722 * channel(rgb.blue);
  }

  private static double channel(int value) {
    double c = value / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static boolean near(RGB a, RGB b) {
    return Math.abs(a.red - b.red) <= TOLERANCE
        && Math.abs(a.green - b.green) <= TOLERANCE
        && Math.abs(a.blue - b.blue) <= TOLERANCE;
  }

  private static String hex(RGB rgb) {
    return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
  }
}
