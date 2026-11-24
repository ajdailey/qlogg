package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link ChromeColors} with each platform's traits, on any platform. */
@Tag("swt")
class ChromeColorsTest {
  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final PlatformTraits WIN32 = PlatformTraits.of("win32", false);
  private static final RGB OWN_BACKGROUND = new RGB(0x10, 0x20, 0x30);
  private static final RGB OWN_TEXT = new RGB(0x40, 0x50, 0x60);
  private static final RGB LIGHT_INK = new RGB(0xd4, 0xd4, 0xd4);
  private static final RGB DARK_INK = new RGB(0x3a, 0x3a, 0x3a);

  /** Lowest contrast of a faded disabled icon's strongest pixel on the dark rows. */
  private static final double DISABLED_MIN = 2.5;

  private Display display;
  private Shell shell;
  private Composite surface;
  private Label label;
  private Link link;
  private ToolBar bar;
  private ToolBar sibling;
  private Button check;
  private Canvas gauge;
  private List<Control> kept;
  private ToolIcons icons;
  private ToolIcons siblingIcons;
  private List<ToolItem> items;

  @BeforeEach
  void create() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(new RowLayout());
    surface = new Composite(shell, SWT.NONE);
    surface.setLayout(new RowLayout());
    label = new Label(surface, SWT.NONE);
    label.setText("Label");
    link = new Link(surface, SWT.NONE);
    link.setText("<a>Warnings</a>");
    bar = new ToolBar(surface, SWT.FLAT);
    sibling = new ToolBar(surface, SWT.FLAT);
    check = new Button(surface, SWT.CHECK);
    gauge = new Canvas(surface, SWT.NONE);
    CTabFolder tabs = new CTabFolder(surface, SWT.NONE);
    Composite marked = new Composite(surface, SWT.NONE);
    marked.setData(CocoaChrome.PALETTE_COLORED, Boolean.TRUE);
    kept =
        List.of(
            new Button(surface, SWT.PUSH),
            new Button(surface, SWT.ARROW | SWT.DOWN),
            new Text(surface, SWT.SINGLE),
            new Combo(surface, SWT.DROP_DOWN),
            new Tree(surface, SWT.NONE),
            new StyledText(surface, SWT.NONE),
            tabs,
            new Label(tabs, SWT.NONE),
            marked,
            new Label(marked, SWT.NONE));
    icons = new ToolIcons(bar);
    items = new ArrayList<ToolItem>();
    for (String name : names()) {
      ToolItem item = new ToolItem(bar, SWT.PUSH);
      assertTrue(icons.apply(item, name), name);
      items.add(item);
    }
    // The search row's set: owned by the row, inked from one of its tool bars.
    siblingIcons = new ToolIcons(surface, sibling);
    siblingIcons.apply(new ToolItem(sibling, SWT.CHECK), ToolIcons.CASE);
    // A kept control carries its own colors, as the log views, tab strips and fields do.
    for (Control control : kept) {
      control.setBackground(new Color(OWN_BACKGROUND));
      control.setForeground(new Color(OWN_TEXT));
    }
    assertFalse(icons.darkBackground(), "native controls start light; a test left them dark");
  }

  @AfterEach
  void dispose() {
    display.dispose();
  }

  /** Every icon name {@link ToolIcons} declares. */
  static List<String> names() {
    List<String> names = new ArrayList<String>();
    for (Field field : ToolIcons.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (field.getType() == String.class
          && Modifier.isStatic(modifiers)
          && !Modifier.isPrivate(modifiers)) {
        try {
          names.add((String) field.get(null));
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
      }
    }
    return names;
  }

  private static RGB rgb(Rgb rgb) {
    return new RGB(rgb.red(), rgb.green(), rgb.blue());
  }

  private List<Control> painted() {
    return List.of(surface, label, link, bar, sibling, check, gauge);
  }

  private static List<RGB> colors(List<Control> controls) {
    List<RGB> colors = new ArrayList<RGB>();
    for (Control control : controls) {
      colors.add(control.getBackground().getRGB());
      colors.add(control.getForeground().getRGB());
    }
    return colors;
  }

  private ChromeColors chrome(PlatformTraits traits) {
    ChromeColors chrome = new ChromeColors(traits, display);
    chrome.add(surface);
    chrome.add(icons);
    chrome.add(siblingIcons);
    return chrome;
  }

  /** Lets GTK draw the shell, so {@link PlatformTraits#shownBackground} reads drawn pixels. */
  private void show() {
    shell.pack();
    shell.open();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return false;
          }
        },
        Duration.ofMillis(300));
  }

  @Test
  void aDarkThemeOnWin32ColorsTheSurfacesAndKeepsNativeControls() {
    chrome(WIN32).apply(true);
    for (Control control : painted()) {
      assertEquals(rgb(ChromeColors.BACKGROUND), control.getBackground().getRGB(), "" + control);
      assertEquals(rgb(ChromeColors.TEXT), control.getForeground().getRGB(), "" + control);
    }
    for (Control control : kept) {
      assertEquals(OWN_BACKGROUND, control.getBackground().getRGB(), "" + control);
      assertEquals(OWN_TEXT, control.getForeground().getRGB(), "" + control);
    }
    assertEquals(SWT.INHERIT_DEFAULT, surface.getBackgroundMode());
    assertTrue(
        ChromeColors.contrast(link.getLinkForeground().getRGB(), rgb(ChromeColors.BACKGROUND))
            >= ChromeColors.LINK_CONTRAST);
  }

  @Test
  void aLiveSwitchRestoresTheSystemColorsAndPaintsAgain() {
    List<RGB> system = colors(painted());
    RGB systemLink = link.getLinkForeground().getRGB();
    ChromeColors chrome = chrome(WIN32);
    for (boolean dark : new boolean[] {true, false, true, false}) {
      chrome.apply(dark);
      assertEquals(dark, chrome.dark());
      if (dark) {
        assertEquals(rgb(ChromeColors.BACKGROUND), surface.getBackground().getRGB());
        assertEquals(rgb(ChromeColors.TEXT), label.getForeground().getRGB());
      } else {
        assertEquals(system, colors(painted()));
        assertEquals(systemLink, link.getLinkForeground().getRGB());
        assertEquals(SWT.INHERIT_NONE, surface.getBackgroundMode());
      }
    }
  }

  @Test
  void aLightThemeAndOtherPlatformsChangeNothing() {
    show();
    List<RGB> system = colors(painted());
    chrome(WIN32).apply(false);
    for (PlatformTraits traits :
        List.of(
            PlatformTraits.of("cocoa", false),
            PlatformTraits.of("gtk", false),
            PlatformTraits.of("gtk", true))) {
      ChromeColors chrome = chrome(traits);
      chrome.apply(true);
      assertFalse(chrome.dark(), traits.name());
      assertEquals(List.of(surface), chrome.surfaces(), "listed on " + traits.name());
    }
    assertEquals(system, colors(painted()));
    assertEquals(SWT.INHERIT_NONE, surface.getBackgroundMode());
    assertFalse(icons.darkBackground());
    for (ToolItem item : items) {
      assertNull(item.getDisabledImage());
    }
  }

  @Test
  void aControlCreatedLaterUnderASurfaceTakesTheColors() {
    chrome(WIN32).apply(true);
    final Label added = new Label(surface, SWT.NONE);
    final Label inTabs = new Label((Composite) kept.get(6), SWT.NONE);
    final RGB tabsLabel = inTabs.getForeground().getRGB();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return added.getForeground().getRGB().equals(rgb(ChromeColors.TEXT));
              }
            },
            BOUND));
    assertEquals(rgb(ChromeColors.BACKGROUND), added.getBackground().getRGB());
    assertEquals(tabsLabel, inTabs.getForeground().getRGB());
  }

  /**
   * The icons take the light ink on the painted tool bars, and every item a faded copy as its
   * disabled image; a light switch returns the dark ink and SWT's own disabled image. The search
   * row's set reads its ink from a sibling tool bar, so a re-ink before the walk reads the light
   * bar and keeps the dark ink: this fails if {@link ChromeColors#apply} re-inks first.
   */
  @Test
  void theIconsTakeTheirInkFromTheColoredToolBars() {
    show();
    ChromeColors chrome = chrome(WIN32);
    for (int round = 0; round < 2; round++) {
      chrome.apply(true);
      assertTrue(icons.darkBackground(), "own bar, round " + round);
      assertTrue(siblingIcons.darkBackground(), "sibling bar, round " + round);
      for (ToolItem item : items) {
        Image disabled = item.getDisabledImage();
        assertNotNull(disabled, item.getToolTipText());
        assertInk(LIGHT_INK, item.getImage().getImageData());
        int full = maxAlpha(item.getImage().getImageData());
        assertEquals(full * 40 / 100, maxAlpha(disabled.getImageData()), 1, "faded");
      }
      chrome.apply(false);
      assertFalse(icons.darkBackground());
      assertFalse(siblingIcons.darkBackground());
      for (ToolItem item : items) {
        assertNull(item.getDisabledImage());
        assertInk(DARK_INK, item.getImage().getImageData());
      }
    }
  }

  /**
   * The disabled icons' contrast on the dark rows: SWT's disabled copy is no dimmer than the
   * enabled icon, so a disabled item would look enabled; the faded copy is dimmer and readable.
   */
  @Test
  void theFadedCopyIsDimmerThanTheEnabledIconAndSwtsOwnIsNot() {
    chrome(WIN32).apply(true);
    RGB ground = rgb(ChromeColors.BACKGROUND);
    double enabledMin = Double.MAX_VALUE;
    double swtMin = Double.MAX_VALUE;
    double fadedMin = Double.MAX_VALUE;
    double fadedMax = 0;
    for (ToolItem item : items) {
      ImageData enabled = item.getImage().getImageData();
      Image swtCopy = new Image(display, item.getImage(), SWT.IMAGE_DISABLE);
      try {
        double on = contrast(enabled, ground);
        double swt = contrast(swtCopy.getImageData(), ground);
        double faded = contrast(item.getDisabledImage().getImageData(), ground);
        assertTrue(swt >= on, "SWT's copy " + swt + " against " + on);
        assertTrue(faded < on, "faded " + faded + " against " + on);
        enabledMin = Math.min(enabledMin, on);
        swtMin = Math.min(swtMin, swt);
        fadedMin = Math.min(fadedMin, faded);
        fadedMax = Math.max(fadedMax, faded);
      } finally {
        swtCopy.dispose();
      }
    }
    System.out.printf(
        "chrome icon contrast on %s: enabled min %.2f, SWT disabled min %.2f, faded %.2f-%.2f%n",
        ground, enabledMin, swtMin, fadedMin, fadedMax);
    assertTrue(fadedMin >= DISABLED_MIN, "faded " + fadedMin);
  }

  @Test
  void aDisposedSurfaceLeavesTheList() {
    ChromeColors chrome = chrome(WIN32);
    surface.dispose();
    chrome.apply(true);
    assertTrue(chrome.dark());
    assertEquals(List.of(), chrome.surfaces());
  }

  private static void assertInk(RGB ink, ImageData data) {
    RGB actual = ink(data);
    boolean near =
        Math.abs(actual.red - ink.red) <= 2
            && Math.abs(actual.green - ink.green) <= 2
            && Math.abs(actual.blue - ink.blue) <= 2;
    assertTrue(near, actual + " against " + ink);
  }

  /** The WCAG contrast of the icon's most opaque pixel, drawn over the ground, on the ground. */
  private static double contrast(ImageData data, RGB ground) {
    RGB ink = ink(data);
    int alpha = maxAlpha(data);
    RGB drawn =
        new RGB(
            blend(ink.red, ground.red, alpha),
            blend(ink.green, ground.green, alpha),
            blend(ink.blue, ground.blue, alpha));
    return ChromeColors.contrast(drawn, ground);
  }

  private static int blend(int ink, int ground, int alpha) {
    return (ink * alpha + ground * (255 - alpha) + 127) / 255;
  }

  private static RGB ink(ImageData data) {
    int[] at = strongest(data);
    return data.palette.getRGB(data.getPixel(at[0], at[1]));
  }

  private static int maxAlpha(ImageData data) {
    int[] at = strongest(data);
    return data.getAlpha(at[0], at[1]);
  }

  private static int[] strongest(ImageData data) {
    int[] best = {0, 0};
    int max = -1;
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        int alpha = data.getAlpha(x, y);
        if (alpha > max) {
          max = alpha;
          best = new int[] {x, y};
        }
      }
    }
    return best;
  }
}
