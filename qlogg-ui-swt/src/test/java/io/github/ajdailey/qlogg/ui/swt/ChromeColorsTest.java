package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.ui.swt.platform.ChromeColors;
import io.github.ajdailey.qlogg.ui.swt.platform.DialogColors;
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
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
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

/**
 * The win32 {@link ChromeColors} and the others, from {@link PlatformTraits#chromeColors}, on any
 * platform.
 */
@Tag("swt")
class ChromeColorsTest {
  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final PlatformTraits WIN32 = PlatformTraits.of("win32", false);
  private static final RGB OWN_BACKGROUND = new RGB(0x10, 0x20, 0x30);
  private static final RGB OWN_TEXT = new RGB(0x40, 0x50, 0x60);
  private static final RGB LIGHT_INK = new RGB(0xd4, 0xd4, 0xd4);
  private static final RGB DARK_INK = new RGB(0x3a, 0x3a, 0x3a);
  // The neutral grays of the dark win32 chrome.
  private static final RGB BACKGROUND = new RGB(0x20, 0x20, 0x20);
  private static final RGB TEXT = new RGB(0xff, 0xff, 0xff);
  private static final RGB FIELD = new RGB(0x2b, 0x2b, 0x2b);
  private static final RGB FACE = new RGB(0x33, 0x33, 0x33);
  private static final double LINK_CONTRAST = 4.5;

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
  private ToolItem toggle;
  private List<Control> fields;
  private Combo readOnly;

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
    marked.setData(ChromeColors.PALETTE_COLORED, Boolean.TRUE);
    kept =
        List.of(
            new Button(surface, SWT.PUSH),
            new Button(surface, SWT.ARROW | SWT.DOWN),
            new Tree(surface, SWT.NONE),
            new StyledText(surface, SWT.NONE),
            tabs,
            new Label(tabs, SWT.NONE),
            marked,
            new Label(marked, SWT.NONE));
    fields =
        List.of(
            new Text(surface, SWT.SINGLE),
            new Text(surface, SWT.SINGLE | SWT.READ_ONLY),
            new Combo(surface, SWT.DROP_DOWN));
    readOnly = new Combo(surface, SWT.READ_ONLY);
    icons = new ToolIcons(bar);
    items = new ArrayList<ToolItem>();
    for (String name : names()) {
      ToolItem item = new ToolItem(bar, SWT.PUSH);
      assertTrue(icons.apply(item, name), name);
      items.add(item);
    }
    // The search row's set: owned by the row, inked from one of its tool bars.
    siblingIcons = new ToolIcons(surface, sibling);
    toggle = new ToolItem(sibling, SWT.CHECK);
    siblingIcons.apply(toggle, ToolIcons.CASE);
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
    ChromeColors chrome = traits.chromeColors(display);
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
      assertEquals(BACKGROUND, control.getBackground().getRGB(), "" + control);
      assertEquals(TEXT, control.getForeground().getRGB(), "" + control);
    }
    for (Control control : kept) {
      assertEquals(OWN_BACKGROUND, control.getBackground().getRGB(), "" + control);
      assertEquals(OWN_TEXT, control.getForeground().getRGB(), "" + control);
    }
    assertEquals(SWT.INHERIT_DEFAULT, surface.getBackgroundMode());
    assertTrue(contrast(link.getLinkForeground().getRGB(), BACKGROUND) >= LINK_CONTRAST);
  }

  @Test
  void aLiveSwitchRestoresTheSystemColorsAndPaintsAgain() {
    List<RGB> system = colors(painted());
    RGB systemLink = link.getLinkForeground().getRGB();
    ChromeColors chrome = chrome(WIN32);
    for (boolean dark : new boolean[] {true, false, true, false}) {
      chrome.apply(dark);
      if (dark) {
        assertEquals(BACKGROUND, surface.getBackground().getRGB());
        assertEquals(TEXT, label.getForeground().getRGB());
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
      chrome.apply(false);
      chrome.dispose();
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
    final Label inTabs = new Label((Composite) kept.get(4), SWT.NONE);
    final RGB tabsLabel = inTabs.getForeground().getRGB();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return added.getForeground().getRGB().equals(TEXT);
              }
            },
            BOUND));
    assertEquals(BACKGROUND, added.getBackground().getRGB());
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
    RGB ground = BACKGROUND;
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
  void aDisposedSurfaceIsSkipped() {
    ChromeColors chrome = chrome(WIN32);
    Composite other = new Composite(shell, SWT.NONE);
    chrome.add(other);
    surface.dispose();
    chrome.apply(true);
    assertEquals(BACKGROUND, other.getBackground().getRGB());
  }

  private List<RGB> fieldColors() {
    return colors(fields);
  }

  private static List<RGB> dark(int count, RGB background, RGB text) {
    List<RGB> colors = new ArrayList<RGB>();
    for (int i = 0; i < count; i++) {
      colors.add(background);
      colors.add(text);
    }
    return colors;
  }

  private static void setText(Control field, String text) {
    if (field instanceof Text value) {
      value.setText(text);
    } else {
      ((Combo) field).setText(text);
    }
  }

  @Test
  void fieldsTakeThePaletteBackgroundUnderDarkAndTheSystemColorsUnderLight() {
    List<RGB> system = fieldColors();
    ChromeColors chrome = chrome(WIN32);
    for (boolean dark : new boolean[] {true, false, true, false}) {
      chrome.apply(dark);
      assertEquals(dark ? dark(3, FIELD, TEXT) : system, fieldColors(), "dark " + dark);
    }
    setText(fields.get(0), "mock");
    chrome.apply(false);
    assertEquals(system, fieldColors(), "a light start keeps no field dark");
  }

  /** A read-only combo, whose list the theme leaves white, takes the button face under dark. */
  @Test
  void aReadOnlyComboTakesTheButtonFaceUnderDark() {
    RGB background = readOnly.getBackground().getRGB();
    RGB foreground = readOnly.getForeground().getRGB();
    ChromeColors chrome = chrome(WIN32);
    for (boolean dark : new boolean[] {true, false}) {
      chrome.apply(dark);
      assertEquals(dark ? FACE : background, readOnly.getBackground().getRGB(), "dark " + dark);
      assertEquals(dark ? TEXT : foreground, readOnly.getForeground().getRGB(), "dark " + dark);
    }
  }

  /**
   * After a dark start a field holding text keeps the dark colors on the light side, an empty text
   * field takes the system list colors, set explicitly, and an empty combo the system colors; a
   * field turns when it empties or takes text. With no colors an empty text field draws the dark
   * native field.
   */
  @Test
  void afterADarkStartAFieldHoldingTextStaysDarkOnTheLightSide() {
    List<RGB> system = new ArrayList<RGB>();
    for (int i = 0; i < 2; i++) {
      system.add(display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB());
      system.add(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND).getRGB());
    }
    system.addAll(fieldColors().subList(4, 6));
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    ChromeColors chrome = chrome(WIN32);
    setText(fields.get(0), "mock");
    setText(fields.get(2), "mock");
    for (boolean dark : new boolean[] {true, false, true, false}) {
      chrome.apply(dark);
      assertEquals(FIELD, fields.get(0).getBackground().getRGB(), "holding text, dark " + dark);
      assertEquals(TEXT, fields.get(0).getForeground().getRGB(), "holding text, dark " + dark);
      assertEquals(FIELD, fields.get(2).getBackground().getRGB(), "combo, dark " + dark);
      assertEquals(
          dark ? FIELD : system.get(2), fields.get(1).getBackground().getRGB(), "empty " + dark);
    }
    setText(fields.get(0), "");
    assertEquals(system.subList(0, 2), fieldColors().subList(0, 2), "emptied");
    setText(fields.get(1), "mock");
    assertEquals(FIELD, fields.get(1).getBackground().getRGB(), "took text");
    chrome.apply(true);
    setText(fields.get(1), "");
    assertEquals(FIELD, fields.get(1).getBackground().getRGB(), "emptied under dark");
  }

  /**
   * After a dark start a check box's label takes the system widget text on the light side, since
   * the dark native box draws it near-white without colors; after a light start it takes none.
   */
  @Test
  void afterADarkStartACheckBoxLabelTakesTheSystemTextOnTheLightSide() {
    RGB system = display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND).getRGB();
    RGB plain = check.getForeground().getRGB();
    ChromeColors light = chrome(WIN32);
    light.apply(true);
    light.apply(false);
    assertEquals(plain, check.getForeground().getRGB(), "light start");
    light.dispose();
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    ChromeColors chrome = chrome(WIN32);
    for (boolean dark : new boolean[] {true, false, true, false}) {
      chrome.apply(dark);
      assertEquals(dark ? TEXT : system, check.getForeground().getRGB(), "dark " + dark);
    }
  }

  /**
   * After a dark start an empty, unfocused text field is filled with its background, the system one
   * on the light side and the field gray on the dark side, which the dark native field draws {@code
   * #212121} under any colors; a field holding text keeps the native drawing.
   */
  @Test
  void afterADarkStartAnEmptyFieldIsPaintedInItsBackground() {
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    Text find = (Text) fields.get(0);
    find.setSize(200, 30);
    ChromeColors chrome = chrome(WIN32);
    chrome.apply(true);
    chrome.apply(false);
    RGB system = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
    assertEquals(1, find.getListeners(SWT.Paint).length, "one fill listener");
    assertEquals(system, paintedCorner(find));
    chrome.apply(true);
    assertEquals(FIELD, paintedCorner(find), "dark side");
    chrome.apply(false);
    setText(find, "mock");
    assertEquals(OWN_BACKGROUND, paintedCorner(find), "holding text");
  }

  /** Sends a Paint event over an image filled with {@link #OWN_BACKGROUND} and reads a corner. */
  private RGB paintedCorner(Text text) {
    Rectangle area = text.getClientArea();
    Image image = new Image(display, Math.max(2, area.width), Math.max(2, area.height));
    GC gc = new GC(image);
    try {
      gc.setBackground(new Color(OWN_BACKGROUND));
      gc.fillRectangle(0, 0, area.width, area.height);
      Event event = new Event();
      event.gc = gc;
      event.setBounds(new Rectangle(0, 0, area.width, area.height));
      text.notifyListeners(SWT.Paint, event);
    } finally {
      gc.dispose();
    }
    try {
      ImageData data = image.getImageData();
      return data.palette.getRGB(data.getPixel(1, 1));
    } finally {
      image.dispose();
    }
  }

  /** The start path records a dark win32 start for the chrome and dialog colors to read. */
  @Test
  void aDarkWin32StartRecordsTheStartupSetting() {
    try {
      SwtThemeService.open(display, ThemeMode.DARK, WIN32.themeFollow(display));
      assertTrue(DialogColors.nativeDark(display));
    } finally {
      display.setDarkThemePreferred(false);
    }
  }

  /** Chrome colors created before the start path records a dark start follow the field rule. */
  @Test
  void chromeCreatedBeforeTheDarkStartKeepsAFieldHoldingTextDark() {
    ChromeColors chrome = chrome(WIN32);
    try {
      SwtThemeService.open(display, ThemeMode.DARK, WIN32.themeFollow(display));
      setText(fields.get(0), "mock");
      chrome.apply(true);
      chrome.apply(false);
      assertEquals(FIELD, fields.get(0).getBackground().getRGB(), "holding text");
      assertNotEquals(FIELD, fields.get(1).getBackground().getRGB(), "empty");
    } finally {
      display.setDarkThemePreferred(false);
    }
  }

  /**
   * A window waiting for its rebuild keeps the native setting its controls were created under: a
   * switch that records the dark setting leaves a field holding text in the system colors on the
   * light side and the checked ink dark.
   */
  @Test
  void aWindowWaitingForItsRebuildKeepsTheSettingItWasBuiltUnder() {
    show();
    display.setData(DialogColors.NATIVE_DARK, Boolean.FALSE);
    List<RGB> system = fieldColors();
    ChromeColors chrome = chrome(WIN32);
    chrome.apply(false);
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    setText(fields.get(0), "mock");
    chrome.apply(true);
    assertEquals(FIELD, fields.get(0).getBackground().getRGB(), "dark side");
    chrome.apply(false);
    assertEquals(system.subList(0, 2), fieldColors().subList(0, 2), "light side, built light");
    toggle.setSelection(true);
    toggle.notifyListeners(SWT.Selection, new Event());
    chrome.apply(true);
    assertInk(DARK_INK, toggle.getImage().getImageData());
  }

  /**
   * A text field holding text, as a context count field does, in a window built light and waiting
   * for its rebuild through six alternating switches, takes {@link #FIELD} and {@link #TEXT} under
   * dark and the system colors under light; {@code Win32ContextFieldTest} reads the drawn field.
   */
  @Test
  void aTextFieldInAWaitingLightWindowFollowsAlternatingSwitches() {
    display.setData(DialogColors.NATIVE_DARK, Boolean.FALSE);
    setText(fields.get(0), "0");
    List<RGB> system = fieldColors();
    ChromeColors chrome = chrome(WIN32);
    chrome.apply(false);
    for (int i = 0; i < 6; i++) {
      boolean dark = i % 2 == 0;
      display.setData(DialogColors.NATIVE_DARK, dark);
      chrome.apply(dark);
      assertEquals(dark ? FIELD : system.get(0), fields.get(0).getBackground().getRGB(), "" + i);
      assertEquals(dark ? TEXT : system.get(1), fields.get(0).getForeground().getRGB(), "" + i);
    }
  }

  /**
   * A checked tool item draws the startup native theme's checked background: dark after a dark
   * start and light after a light start, whatever the row. Its icon takes the light ink only when
   * both the start and the theme are dark, on check and uncheck; its faded disabled copy keeps the
   * row's ink.
   */
  @Test
  void aCheckedItemTakesTheInkOfTheStartupCheckedBackground() {
    show();
    for (boolean nativeDark : new boolean[] {false, true}) {
      display.setData(DialogColors.NATIVE_DARK, nativeDark);
      ChromeColors chrome = chrome(WIN32);
      for (boolean dark : new boolean[] {true, false}) {
        chrome.apply(dark);
        String at = "start dark " + nativeDark + ", theme dark " + dark;
        toggle.setSelection(true);
        toggle.notifyListeners(SWT.Selection, new Event());
        RGB checked = nativeDark && dark ? LIGHT_INK : DARK_INK;
        assertInk(checked, toggle.getImage().getImageData());
        if (dark) {
          assertInk(LIGHT_INK, toggle.getDisabledImage().getImageData());
        }
        toggle.setSelection(false);
        toggle.notifyListeners(SWT.Selection, new Event());
        assertInk(dark ? LIGHT_INK : DARK_INK, toggle.getImage().getImageData());
        siblingIcons.check(toggle, true);
        assertInk(checked, toggle.getImage().getImageData());
        siblingIcons.check(toggle, false);
      }
      chrome.dispose();
    }
  }

  private static double contrast(RGB first, RGB second) {
    double a = luminance(first);
    double b = luminance(second);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }

  private static double luminance(RGB rgb) {
    return 0.2126 * linear(rgb.red) + 0.7152 * linear(rgb.green) + 0.0722 * linear(rgb.blue);
  }

  private static double linear(int channel) {
    double value = channel / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
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
    return contrast(drawn, ground);
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
