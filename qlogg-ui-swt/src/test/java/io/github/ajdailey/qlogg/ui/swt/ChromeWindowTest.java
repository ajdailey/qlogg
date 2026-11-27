package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.ui.swt.platform.ChromeColors;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rows and fields {@link MainWindow} gives the win32 {@link ChromeColors} on any platform, the
 * empty view areas, and the search row's Predefined filters button.
 */
@Tag("swt")
class ChromeWindowTest {
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final PlatformTraits WIN32 = PlatformTraits.of("win32", false);
  private static final RGB OWN = new RGB(0x10, 0x20, 0x30);
  // The neutral grays of the dark win32 chrome.
  private static final RGB BACKGROUND = new RGB(0x20, 0x20, 0x20);
  private static final RGB TEXT = new RGB(0xff, 0xff, 0xff);
  private static final RGB FIELD = new RGB(0x2b, 0x2b, 0x2b);
  private static final RGB FACE = new RGB(0x33, 0x33, 0x33);

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
    window = new MainWindow(display, Keymap.defaults(), Palette.LIGHT, WIN32.chromeColors(display));
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS.withTheme(ThemeMode.LIGHT),
            ThemeService.LIGHT);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 900, 500), null);
    window.open();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.sash() != null;
              }
            },
            BOUND),
        "the split lays out its sash");
    assertFalse(
        window.searchBar().icons().darkBackground(),
        "native controls start light; a test left them dark");
  }

  @AfterEach
  void close() {
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
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private static RGB rgb(Rgb rgb) {
    return new RGB(rgb.red(), rgb.green(), rgb.blue());
  }

  private Composite topRow() {
    return window.toolBar().getParent();
  }

  private List<Control> surfaces() {
    return List.of(
        topRow(),
        window.searchBar().control(),
        window.quickFindBar().control(),
        window.statusBar(),
        window.sash());
  }

  /** The surfaces' controls the walk colors: all but fields, combos and push buttons. */
  private List<Control> colored() {
    List<Control> controls = new ArrayList<Control>();
    for (Control surface : surfaces()) {
      add(surface, controls);
    }
    return controls;
  }

  private static void add(Control control, List<Control> controls) {
    if (control instanceof Text || control instanceof Combo) {
      return;
    }
    if (control instanceof Button && (control.getStyle() & SWT.PUSH) != 0) {
      return;
    }
    controls.add(control);
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        add(child, controls);
      }
    }
  }

  private static List<RGB> colors(List<Control> controls) {
    List<RGB> colors = new ArrayList<RGB>();
    for (Control control : controls) {
      colors.add(control.getBackground().getRGB());
      colors.add(control.getForeground().getRGB());
    }
    return colors;
  }

  /** The text fields and editable combos under the surfaces. */
  private List<Control> fields() {
    List<Control> fields = new ArrayList<Control>();
    for (Control surface : surfaces()) {
      addFields(surface, fields);
    }
    return fields;
  }

  private static void addFields(Control control, List<Control> fields) {
    if (control instanceof Text
        || (control instanceof Combo && (control.getStyle() & SWT.READ_ONLY) == 0)) {
      fields.add(control);
    } else if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        addFields(child, fields);
      }
    }
  }

  @Test
  void eachRowAndFieldTakesTheDarkColors() {
    List<Control> controls = colored();
    List<RGB> system = colors(controls);
    List<Control> fields = fields();
    assertEquals(6, fields.size(), "path, pattern, range end, two context counts, quick find");
    assertTrue(
        fields.containsAll(
            List.of(
                window.pathField(),
                window.searchBar().pattern(),
                window.searchBar().rangeEnd(),
                window.quickFindBar().field())),
        fields.toString());
    List<RGB> systemFields = colors(fields);
    List<Control> natives = List.of(window.tabStrip().buttons().control());
    // The read-only visibility combo, whose drop-down list takes the button face under dark.
    Control visibility = window.searchBar().visibility();
    List<RGB> visibilityColors = colors(List.of(visibility));
    // Own colors, so a control that reports its parent's inherited color on GTK still shows a
    // change.
    natives.get(0).setBackground(new Color(OWN));
    List<RGB> nativeColors = colors(natives);
    ChromeColors chrome = window.chrome();
    for (boolean dark : new boolean[] {true, false, true}) {
      chrome.apply(dark);
      if (dark) {
        for (Control control : controls) {
          assertEquals(BACKGROUND, control.getBackground().getRGB(), "" + control);
          assertEquals(TEXT, control.getForeground().getRGB(), "" + control);
        }
        for (Control field : fields) {
          assertEquals(FIELD, field.getBackground().getRGB(), "" + field);
          assertEquals(TEXT, field.getForeground().getRGB(), "" + field);
        }
        assertEquals(List.of(FACE, TEXT), colors(List.of(visibility)), "visibility");
        assertTrue(window.searchBar().icons().darkBackground(), "search row ink");
        for (ToolItem item : window.toolBar().getItems()) {
          assertNotNull(item.getDisabledImage(), item.getToolTipText());
        }
      } else {
        assertEquals(system, colors(controls));
        assertEquals(systemFields, colors(fields));
        assertEquals(visibilityColors, colors(List.of(visibility)), "visibility");
        assertFalse(window.searchBar().icons().darkBackground(), "search row ink");
      }
      assertEquals(nativeColors, colors(natives));
    }
  }

  /**
   * With no file open, the log and results areas show the palette's background, as the views do.
   */
  @Test
  void theEmptyViewAreasTakeThePaletteBackground() {
    Composite split = window.sash().getParent();
    Composite main = (Composite) split.getChildren()[0];
    Composite results = (Composite) ((Composite) split.getChildren()[1]).getChildren()[1];
    for (ThemeMode mode : new ThemeMode[] {ThemeMode.DARK, ThemeMode.LIGHT}) {
      controller.theme().setMode(mode);
      RGB expected = rgb(controller.theme().palette().background());
      assertEquals(expected, main.getBackground().getRGB(), "main, " + mode);
      assertEquals(expected, results.getBackground().getRGB(), "results, " + mode);
    }
  }

  /**
   * win32 and cocoa show a popup menu in a modal loop at the next event dispatch, so the test hides
   * it before dispatching; GTK shows it at once, so there the test also waits for {@code SWT.Show}.
   */
  @Test
  void theFiltersButtonIsAPushItemWithItsArrowInTheIconAndOpensTheMenuUnderIt() {
    final ToolItem filters = window.searchBar().filtersButton();
    assertEquals(SWT.PUSH, filters.getStyle() & (SWT.PUSH | SWT.DROP_DOWN));
    assertNotNull(filters.getImage());
    assertNotEquals("", filters.getToolTipText());
    ToolBar bar = filters.getParent();
    assertEquals(bar, window.searchBar().control().getChildren()[2]);
    final Menu menu = window.searchBar().filterMenu();
    final boolean[] shown = new boolean[1];
    menu.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            shown[0] = true;
          }
        });
    filters.notifyListeners(SWT.Selection, new Event());
    try {
      assertTrue(menu.getItemCount() > 0, "the menu is built");
      assertTrue(menu.getVisible(), "the menu opens");
      Rectangle bounds = filters.getBounds();
      assertEquals(
          bar.toDisplay(bounds.x, bounds.y + bounds.height),
          window.searchBar().filterMenuAt(),
          "under the button");
      if ("gtk".equals(SWT.getPlatform())) {
        assertTrue(
            EventLoop.runUntil(
                display,
                new BooleanSupplier() {
                  @Override
                  public boolean getAsBoolean() {
                    return shown[0];
                  }
                },
                BOUND),
            "GTK shows the menu");
      }
    } finally {
      menu.setVisible(false);
    }
  }
}
