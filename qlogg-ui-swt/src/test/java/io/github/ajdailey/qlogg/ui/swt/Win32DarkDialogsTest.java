package io.github.ajdailey.qlogg.ui.swt;

import static io.github.ajdailey.qlogg.ui.swt.Pixels.contrast;
import static io.github.ajdailey.qlogg.ui.swt.Pixels.luminance;
import static io.github.ajdailey.qlogg.ui.swt.Pixels.mode;
import static io.github.ajdailey.qlogg.ui.swt.Pixels.strongest;
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
import io.github.ajdailey.qlogg.ui.swt.platform.DialogColors;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On win32 under a dark startup setting, dialog bodies draw the dark dialog color with light text,
 * matching the dark title bar and push buttons; under a light one they draw as before. The check
 * reads screen pixels, since {@code getBackground} can differ from what win32 draws. Where a screen
 * read does not show the main window, as on the {@code windows-11-arm} runner, whose desktop lies
 * under the Windows setup screen, the test skips every body check and reads the first label through
 * {@code Control.print}, checking its text against its background; the same applies to a single
 * dialog a screen read does not show. On each tab of a tabbed dialog the tab row, the folder's edge
 * and the selected tab's text are read too: dark under a dark start. Each dialog is saved to {@code
 * target/diagnostics/win32-dark-dialogs/}, with the read of each check in the report there.
 */
@Tag("swt")
class Win32DarkDialogsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final RGB PROBE = new RGB(0x12, 0x9a, 0x56);

  /** {@code Win32DialogColors.WINDOW}. */
  private static final RGB DARK_WINDOW = new RGB(0x20, 0x20, 0x20);

  private static final int TOLERANCE = 8;

  /** Lightest hot tool item fill under a dark start; SWT draws {@code #363636} on the body. */
  private static final RGB HOT_LIMIT = new RGB(0x60, 0x60, 0x60);

  /** WCAG contrast an icon keeps on a hot tool item. */
  private static final double ICON_CONTRAST = 3.0;

  /** WCAG contrast a label's text keeps on the drawn body. */
  private static final double TEXT_CONTRAST = 4.5;

  /** Commands that open a dialog or tool window over the main window. */
  static final CommandId[] DIALOGS = {
    CommandId.ABOUT,
    CommandId.PREFERENCES,
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

  /** Whether a screen read shows the main window; probed once per test. */
  private boolean screenShows;

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
    screenShows = shows(window.shell());
    if (!screenShows) {
      String reason =
          "the screen does not show the test window on "
              + System.getProperty("os.arch")
              + ": body checks skipped, labels read through Control.print";
      System.err.println("Win32DarkDialogsTest: " + reason);
      report.append(reason).append('\n');
    }
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
    assertTrue(DialogColors.nativeDark(display));
    checkAll("system", true);
  }

  /**
   * Predefined Filters' Add item under the pointer keeps a dark fill and its icon readable on it
   * (OBERON rc1b: a {@code #d8d8d8} square, 1.04:1). The pointer move is posted; the test skips
   * where it does not reach the tool bar. The item is read on screen, or through {@code
   * Control.print} where a screen read does not show the dialog.
   */
  @Test
  void aHotToolItemDrawsDarkUnderADarkStart() throws IOException {
    open(ThemeMode.DARK);
    Shell dialog =
        openDialog(
            new Runnable() {
              @Override
              public void run() {
                controller.execute(CommandId.PREDEFINED_FILTERS);
              }
            });
    assertTrue(dialog != null, "Predefined Filters opened");
    try {
      ToolBar bar = firstToolBar(dialog);
      assertTrue(bar != null, "tool bar");
      boolean screen = screenShows && shows(dialog);
      final boolean[] moved = new boolean[1];
      bar.addListener(
          SWT.MouseMove,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              moved[0] = true;
            }
          });
      Rectangle item = bar.getItem(0).getBounds();
      Point center = bar.toDisplay(item.x + item.width / 2, item.y + item.height / 2);
      Event move = new Event();
      move.type = SWT.MouseMove;
      move.x = center.x;
      move.y = center.y;
      display.post(move);
      assumeTrue(
          EventLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return moved[0];
                }
              },
              Duration.ofSeconds(3)),
          "the posted pointer move reaches the tool bar");
      settle();
      ImageData data = screen ? capture(screenArea(bar)) : print(bar);
      RGB fill = mode(data, item);
      RGB icon = strongest(data, fill);
      report.append(
          String.format(
              "hot add item read=%s fill=%s icon=%s contrast=%.2f%n",
              screen ? "screen" : "print", hex(fill), hex(icon), contrast(icon, fill)));
      save("hot.txt", report.toString());
      assertTrue(luminance(fill) < luminance(HOT_LIMIT), "hot fill " + hex(fill));
      assertTrue(contrast(icon, fill) >= ICON_CONTRAST, "icon " + hex(icon) + " on " + hex(fill));
    } finally {
      dialog.dispose();
      settle();
    }
  }

  private static ToolBar firstToolBar(Composite parent) {
    for (Control child : parent.getChildren()) {
      if (child instanceof ToolBar bar && bar.getItemCount() > 0) {
        return bar;
      }
      if (child instanceof Composite composite) {
        ToolBar found = firstToolBar(composite);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
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
      Composite tabs = DialogTabReads.find(dialog);
      if (tabs == null) {
        check(dialog, name, dark, wrong);
      } else {
        for (int i = 0; i < DialogTabReads.count(tabs); i++) {
          DialogTabReads.select(tabs, i);
          check(dialog, name + "-tab" + i, dark, wrong);
          checkTabs(tabs, name + "-tab" + i, dark, wrong);
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
    boolean screen = screenShows && shows(dialog);
    // The bottom left corner of the client area lies in the layout margin, past any frame. A
    // printed Shell lays its children out off their places on windows-11-arm (measured), so a print
    // read checks the label alone.
    RGB body = null;
    if (screen) {
      Rectangle client = dialog.getClientArea();
      Point origin = dialog.toDisplay(client.x + 2, client.y + client.height - 10);
      body = mode(capture(new Rectangle(origin.x, origin.y, 6, 6)));
      RGB expected =
          dark ? DARK_WINDOW : display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND).getRGB();
      if (!near(body, expected)) {
        wrong.add(name + " body");
      }
    }
    Label label = firstLabel(dialog);
    String ink = "none";
    double contrast = 0;
    if (label != null) {
      ImageData data = screen ? capture(screenArea(label)) : printed(label);
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

  /**
   * Reads a tab folder on screen: the empty part of the tab row, the folder's left edge below the
   * row, where the native folder draws its frame, and the selected tab's text against its
   * background. Under a dark start the row and the edge draw the dark body.
   */
  private void checkTabs(Composite tabs, String name, boolean dark, List<String> wrong) {
    if (!screenShows || !shows(tabs.getShell())) {
      return;
    }
    DialogTabReads.Reading read =
        DialogTabReads.read(
            tabs,
            new DialogTabReads.Screen() {
              @Override
              public ImageData capture(Rectangle area) {
                return Win32DarkDialogsTest.this.capture(area);
              }
            });
    if (read.contrast() < TEXT_CONTRAST) {
      wrong.add(name + " selected tab");
    }
    if (dark
        && ((read.row() != null && !near(read.row(), DARK_WINDOW))
            || !near(read.edge(), DARK_WINDOW))) {
      wrong.add(name + " tab row or frame");
    }
    report.append(
        String.format(
            "%s %s row=%s edge=%s selected tab ink=%s on %s contrast=%.2f%n",
            name,
            tabs.getClass().getSimpleName(),
            read.row() == null ? "unread" : hex(read.row()),
            hex(read.edge()),
            hex(read.ink()),
            hex(read.ground()),
            read.contrast()));
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
      if (DialogTabReads.isTabs(child) && DialogTabReads.selection((Composite) child) >= 0) {
        Control page = DialogTabReads.selectedPage((Composite) child);
        if (page instanceof Composite composite) {
          Label found = firstLabel(composite);
          if (found != null) {
            return found;
          }
        }
      } else if (child instanceof Composite composite && !DialogTabReads.isTabs(child)) {
        Label found = firstLabel(composite);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /** Dispatches pending events without sleeping (see {@link EventLoop#runUntil}), then paints. */
  private void settle() {
    long deadline = System.nanoTime() + BOUND.toNanos();
    while (System.nanoTime() < deadline && display.readAndDispatch()) {
      // Runs queued work.
    }
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
      return Pixels.read(image, window.shell());
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
   * Prints a label, dispatching events for up to 2 s until the print holds more than one color. A
   * label printed before it paints reads one color, black on the {@code windows-11-arm} runner
   * (measured).
   */
  private ImageData printed(final Label label) {
    final ImageData[] data = new ImageData[1];
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            data[0] = print(label);
            RGB ground = mode(data[0]);
            return contrast(strongest(data[0], ground), ground) > 1;
          }
        },
        Duration.ofSeconds(2));
    return data[0];
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
      return Pixels.read(image, control);
    } finally {
      gc.dispose();
      image.dispose();
    }
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
