package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.ColorScheme;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Open, tab list and Close buttons at the end of the main tab strip, with 30 tabs in a 700 px
 * window: one list control, the hidden count, the list menu, the wheel over the strip, Close, the
 * icon ink under every color scheme in light and dark chrome, and the About logo's variant. Events
 * go to the controls through {@code notifyListeners}. {@code -Dqlogg.tabstrip.shots=<dir>} writes
 * the window with the list open in light and dark chrome.
 */
@Tag("swt")
class TabStripButtonsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int TABS = 30;

  /** Icon contrast against the strip, the non-text minimum of WCAG 2.1 (1.4.11). */
  private static final double ICON_CONTRAST = 3.0;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private CTabFolder tabs;
  private TabStripButtons buttons;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
    final SwtThemeService system = SwtThemeService.open(display, ThemeMode.LIGHT);
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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS.withTheme(ThemeMode.LIGHT),
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

              @Override
              public boolean showsNow(boolean dark) {
                return true;
              }
            });
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 700, 500), null);
    window.open();
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (int i = 0; i < TABS; i++) {
      files.add(
          new FileRequest(
              Files.writeString(
                  temp.resolve("file-" + (100 + i) + ".log"), "line\n", StandardCharsets.US_ASCII),
              0));
    }
    controller.open(files);
    tabs = window.tabStrip().tabs();
    buttons = window.tabStrip().buttons();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.getItemCount() == TABS
                    && window.canvas() != null
                    && buttons.hiddenCount() == hidden();
              }
            }),
        "tabs " + tabs.getItemCount() + ", count " + buttons.hiddenCount());
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
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private int hidden() {
    int count = 0;
    for (CTabItem item : tabs.getItems()) {
      if (!item.isShowing()) {
        count++;
      }
    }
    return count;
  }

  private int first() {
    for (int i = 0; i < tabs.getItemCount(); i++) {
      if (tabs.getItem(i).isShowing()) {
        return i;
      }
    }
    return -1;
  }

  private static void click(Canvas button) {
    for (int type : new int[] {SWT.MouseEnter, SWT.MouseDown, SWT.MouseUp}) {
      Event event = new Event();
      event.button = 1;
      event.x = 2;
      event.y = 2;
      button.notifyListeners(type, event);
    }
  }

  private static void wheel(Control over, int count) {
    Event event = new Event();
    event.count = count;
    over.notifyListeners(SWT.MouseVerticalWheel, event);
  }

  private void select(final int index) {
    buttons.tabList().choose(tabs.getItem(index));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.getSelectionIndex() == index && tabs.getItem(index).isShowing();
              }
            }));
  }

  private static List<MenuItem> entries(Menu menu) {
    List<MenuItem> list = new ArrayList<MenuItem>();
    for (MenuItem item : menu.getItems()) {
      if ((item.getStyle() & SWT.SEPARATOR) == 0) {
        list.add(item);
      }
    }
    return list;
  }

  private void settle() {
    final long end = System.nanoTime() + Duration.ofMillis(300).toNanos();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() >= end;
          }
        });
  }

  @Test
  void theThreeButtonsShowAndSwtsChevronTakesNoWidth() {
    assertTrue(hidden() > 0, "30 tabs overflow a 700 px window");
    assertTrue(buttons.control().isVisible());
    assertEquals(3, buttons.buttons().size());
    for (Canvas button : buttons.buttons()) {
      Point size = button.getSize();
      assertTrue(button.isVisible());
      assertTrue(size.x >= 16 && size.y >= 16, "button size " + size);
    }
    Rectangle group = buttons.control().getBounds();
    Rectangle last = null;
    for (CTabItem item : tabs.getItems()) {
      if (item.isShowing()) {
        last = item.getBounds();
      }
    }
    assertNotNull(last);
    assertTrue(last.x + last.width <= group.x, "tabs " + last + " end before " + group);
    for (Control child : tabs.getChildren()) {
      if (child instanceof ToolBar bar) {
        assertEquals(0, bar.getSize().x, "SWT's chevron shows: " + bar.getBounds());
      }
    }
    assertTrue(buttons.buttons().get(0).getToolTipText().startsWith("Open"));
    assertEquals(
        UiText.TAB_LIST_HIDDEN.format(hidden()), buttons.buttons().get(1).getToolTipText());
    assertTrue(buttons.buttons().get(2).getToolTipText().startsWith("Close"));
  }

  @Test
  void theCountFollowsTheHiddenTabs() {
    final int before = buttons.hiddenCount();
    assertTrue(before > 0);
    window.shell().setSize(1400, 500);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return buttons.hiddenCount() == hidden() && buttons.hiddenCount() < before;
              }
            }),
        "count " + buttons.hiddenCount() + ", hidden " + hidden());
  }

  /** Appends to the first tab's file and waits for its dot. */
  private void growFirstTab() throws IOException {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                for (DocumentController document : controller.documents()) {
                  if (document.state().indexing()) {
                    return false;
                  }
                }
                return true;
              }
            }));
    Files.writeString(
        temp.resolve("file-100.log"),
        "more\n",
        StandardCharsets.US_ASCII,
        StandardOpenOption.APPEND);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.getItem(0).getImage() != null;
              }
            }),
        "the grown background tab shows its dot");
  }

  @Test
  void theMenuListsEveryTabWithItsDotAndAChoiceSelectsAndShowsIt() throws IOException {
    growFirstTab();
    click(buttons.listButton());
    Menu menu = buttons.tabList().menu();
    menu.setVisible(false);
    List<MenuItem> entries = entries(menu);
    assertEquals(TABS, entries.size());
    for (int i = 0; i < TABS; i++) {
      assertEquals(tabs.getItem(i).getText(), entries.get(i).getText());
      // GTK 4 SWT keeps no image on a radio menu item.
      if (!Gtk4.active()) {
        assertSame(tabs.getItem(i).getImage(), entries.get(i).getImage());
      }
      assertEquals(i == TABS - 1, entries.get(i).getSelection());
    }
    assertTrue(Gtk4.active() || entries.get(0).getImage() != null, "the dot");
    assertTrue(menu.getItemCount() > TABS, "a separator sets the shown tabs apart");
    assertFalse(tabs.getItem(0).isShowing());
    MenuItem target = entries.get(0);
    entries.get(TABS - 1).setSelection(false);
    target.setSelection(true);
    target.notifyListeners(SWT.Selection, new Event());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.getSelectionIndex() == 0
                    && tabs.getItem(0).isShowing()
                    && controller.activeDocument() == tabs.getItem(0).getData()
                    && window.canvas().document() == tabs.getItem(0).getData();
              }
            }));
    assertNull(tabs.getItem(0).getImage(), "showing the tab clears its dot");
  }

  @Test
  void swtsListRequestOpensTheSameMenu() {
    Event event = new Event();
    event.x = 0;
    event.y = 0;
    // CTabFolder turns a chevron press into showList for its CTabFolder2Listeners.
    for (Control child : tabs.getChildren()) {
      if (child instanceof ToolBar bar && bar.getItemCount() > 0) {
        bar.getItem(0).notifyListeners(SWT.Selection, event);
      }
    }
    Menu menu = buttons.tabList().menu();
    assertNotNull(menu, "the chevron opened the tab list");
    menu.setVisible(false);
    assertEquals(TABS, entries(menu).size());
  }

  @Test
  void theWheelShowsAHiddenTabAndKeepsTheSelection() {
    select(15);
    final int start = first();
    assertTrue(start > 0, "tabs hidden left of the selection");
    wheel(tabs, 3);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return first() == start - 1;
              }
            }),
        "first shown " + first());
    assertEquals(15, tabs.getSelectionIndex());
    assertTrue(tabs.getItem(15).isShowing());
    wheel(buttons.listButton(), -3);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return first() == start;
              }
            }),
        "first shown " + first());
    assertEquals(15, tabs.getSelectionIndex());
  }

  @Test
  void theWheelLeavesTheSelectedTabShown() {
    select(15);
    for (int i = 0; i < TABS; i++) {
      wheel(tabs, -3);
      settle();
    }
    assertEquals(15, first(), "the selected tab stays the first shown");
    assertTrue(tabs.getItem(15).isShowing());
  }

  /**
   * {@code CTabFolder} appends a new item last in its show order; when the selected new tab fits
   * the run already shown, the folder keeps that order, measures no width for the tab and leaves it
   * hidden. {@code TabList.showSelection} repairs it.
   */
  @Test
  void aNewTabShowsAtEveryWidth() throws IOException {
    Point size = window.shell().getSize();
    Path extra = Files.writeString(temp.resolve("extra.log"), "x\n", StandardCharsets.US_ASCII);
    List<Integer> hiddenAt = new ArrayList<Integer>();
    for (int width = 600; width <= 720; width += 6) {
      window.shell().setSize(width, size.y);
      controller.open(List.of(new FileRequest(extra, 0)));
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return tabs.getItemCount() == TABS + 1
                      && "extra.log".equals(tabs.getSelection().getText());
                }
              }));
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return tabs.getSelection().isShowing();
            }
          },
          Duration.ofSeconds(2));
      if (!tabs.getSelection().isShowing()) {
        hiddenAt.add(width);
      }
      controller.closeDocument();
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return tabs.getItemCount() == TABS;
                }
              }));
    }
    assertTrue(hiddenAt.isEmpty(), "new tab hidden at widths " + hiddenAt);
  }

  @Test
  void closeClosesTheCurrentTabThroughTheCommand() {
    DocumentController shown = controller.activeDocument();
    click(buttons.buttons().get(2));
    assertEquals(TABS - 1, tabs.getItemCount());
    assertTrue(shown.isClosed());
    assertTrue(controller.activeDocument() != shown);
  }

  @Test
  void oneTabShowsTheStripAndItsCloseLeavesAnEmptyWindow() {
    controller.closeOthers(controller.activeDocument());
    assertEquals(1, tabs.getItemCount());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return tabs.isVisible() && buttons.control().isVisible();
              }
            }),
        "one tab shows the strip and its buttons");
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return buttons.hiddenCount() == 0;
              }
            }));
    assertEquals(UiText.TAB_LIST.text(), buttons.listButton().getToolTipText());
    click(buttons.buttons().get(2));
    assertEquals(0, tabs.getItemCount());
    assertFalse(tabs.isVisible(), "no tab hides the strip");
    assertFalse(window.shell().isDisposed());
  }

  /**
   * SWT 3.135.0 on GTK 4 laid out an image-only tool item at its padding alone, 12 px wide, and
   * drew no icon.
   */
  @Test
  void everyToolItemIsLargerThanItsIcon() {
    List<ToolItem> items = new ArrayList<ToolItem>();
    collect(window.shell(), items);
    assertTrue(items.size() >= 15, "tool items " + items.size());
    for (ToolItem item : items) {
      Image image = item.getImage();
      Rectangle bounds = item.getBounds();
      assertTrue(
          bounds.width > image.getBounds().width && bounds.height > image.getBounds().height,
          item.getToolTipText() + " " + bounds);
    }
  }

  private static void collect(Control control, List<ToolItem> items) {
    // A CTabFolder's own chevron bar is held at zero width.
    if (control instanceof ToolBar bar && !(bar.getParent() instanceof CTabFolder)) {
      for (ToolItem item : bar.getItems()) {
        if (item.getImage() != null) {
          items.add(item);
        }
      }
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        collect(child, items);
      }
    }
  }

  /**
   * Under every color scheme in light and dark chrome, the icons take the ink the strip's color
   * calls for, and where {@code Control.print} reads pixels the darkest or lightest icon pixel
   * stands out from the strip by {@link #ICON_CONTRAST}.
   */
  @Test
  void theIconsTakeTheStripsInkUnderEverySchemeAndChrome() {
    List<String> ids = new ArrayList<String>();
    ids.add(ColorSchemes.FOLLOW_THEME);
    for (ColorScheme scheme : controller.theme().schemes().all()) {
      ids.add(scheme.id());
    }
    List<String> report = new ArrayList<String>();
    List<String> faults = new ArrayList<String>();
    for (ThemeMode mode : new ThemeMode[] {ThemeMode.LIGHT, ThemeMode.DARK}) {
      controller.setTheme(mode);
      for (String id : ids) {
        controller.setViewOptions(controller.preferences().view().withColors(id));
        settle();
        Palette palette = controller.palette();
        Color strip =
            new Color(palette.gutter().red(), palette.gutter().green(), palette.gutter().blue());
        String step = mode + "/" + id;
        if (buttons.lightInk() != ToolIcons.dark(strip)) {
          faults.add(step + ": ink " + buttons.lightInk());
        }
        if (!strip.equals(buttons.control().getBackground())) {
          faults.add(step + ": group " + buttons.control().getBackground());
        }
        ImageData data = print(buttons.buttons().get(2));
        if (data != null) {
          double contrast = contrast(data);
          report.add(String.format("%s %.2f", step, contrast));
          if (contrast < ICON_CONTRAST) {
            faults.add(String.format("%s: contrast %.2f", step, contrast));
          }
        }
      }
    }
    System.out.println("Tab strip icon contrast: " + report);
    assertTrue(faults.isEmpty(), faults.toString());
  }

  private ImageData print(Control control) {
    control.redraw();
    control.update();
    Point size = control.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      return control.print(gc) ? image.getImageData() : null;
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Contrast between the corner pixel, the strip, and the pixel farthest from it. */
  private static double contrast(ImageData data) {
    double base = luminance(data.palette.getRGB(data.getPixel(0, 0)));
    double farthest = base;
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        double l = luminance(data.palette.getRGB(data.getPixel(x, y)));
        if (Math.abs(l - base) > Math.abs(farthest - base)) {
          farthest = l;
        }
      }
    }
    return (Math.max(base, farthest) + 0.05) / (Math.min(base, farthest) + 0.05);
  }

  private static double luminance(RGB rgb) {
    return 0.2126 * linear(rgb.red) + 0.7152 * linear(rgb.green) + 0.0722 * linear(rgb.blue);
  }

  private static double linear(int channel) {
    double c = channel / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  /** The About logo takes the variant of the dialog background it sits on. */
  @Test
  void theAboutLogoFollowsTheDialogBackground() {
    for (ThemeMode mode : new ThemeMode[] {ThemeMode.DARK, ThemeMode.LIGHT}) {
      controller.setTheme(mode);
      settle();
      controller.execute(io.github.ajdailey.qlogg.app.command.CommandId.ABOUT);
      // About opens after its details are read on a worker.
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return about() != null;
                }
              }),
          "About opens");
      Shell about = about();
      Label logo = (Label) about.getChildren()[0];
      Icons.Variant expected =
          ToolIcons.dark(logo.getBackground()) ? Icons.Variant.DARK : Icons.Variant.LIGHT;
      Object shown = logo.getData(AboutDialog.LOGO_VARIANT);
      System.out.println(
          "About " + mode + ": background " + logo.getBackground() + ", logo " + shown);
      about.dispose();
      assertEquals(expected, shown, mode.name());
    }
  }

  private Shell about() {
    for (Shell shell : display.getShells()) {
      if (!shell.isDisposed() && shell.getText().equals("About QLogg")) {
        return shell;
      }
    }
    return null;
  }

  @Test
  void screenshotsWithTheMenuOpen() throws Exception {
    String folder = System.getProperty("qlogg.tabstrip.shots");
    assumeTrue(folder != null, "qlogg.tabstrip.shots unset");
    window.shell().setSize(1100, 700);
    growFirstTab();
    select(15);
    for (ThemeMode mode : new ThemeMode[] {ThemeMode.LIGHT, ThemeMode.DARK}) {
      controller.setTheme(mode);
      settle();
      click(buttons.listButton());
      for (int i = 0; i < 5; i++) {
        settle();
      }
      System.out.println("Tab list menu visible: " + buttons.tabList().menu().isVisible());
      String name =
          "tab-list-" + (Gtk4.active() ? "gtk4-" : "gtk3-") + mode.name().toLowerCase() + ".png";
      shoot(Path.of(folder, name));
      buttons.tabList().menu().setVisible(false);
    }
  }

  /** Reads the screen; GTK 4 reads white through SWT, so the X server's root window is read. */
  private void shoot(Path file) throws Exception {
    if (Gtk4.active()) {
      Process process =
          new ProcessBuilder("import", "-window", "root", file.toString())
              .redirectErrorStream(true)
              .start();
      process.getInputStream().readAllBytes();
      assertEquals(0, process.waitFor());
      return;
    }
    Rectangle area = display.getBounds();
    Image image = new Image(display, area.width, area.height);
    GC gc = new GC(display);
    gc.copyArea(image, area.x, area.y);
    gc.dispose();
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {image.getImageData()};
    loader.save(file.toString(), SWT.IMAGE_PNG);
    image.dispose();
  }
}
