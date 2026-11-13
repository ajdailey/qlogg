package io.github.ajdailey.qlogg.ui.swt;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The toolbar, toggle and menu icons, rendered at build time from the SVG set into {@code
 * icons/tool/NAME-light-16.png} (dark ink) and {@code NAME-dark-16.png} (light ink), each with a 32
 * px copy for 200% zoom. The ink follows the native widget background, not the QLogg palette,
 * because toolbars and buttons are native; on a palette-painted tab strip it follows the palette.
 * GTK refreshes {@code getBackground} and sends {@code SWT.Settings} only on the first {@code
 * Display.sleep} after {@code setDarkThemePreferred}, so {@link #reink} reads the background
 * through {@link PlatformTraits#shownBackground}, a drawn pixel on GTK. GTK's disabled effect
 * lightens a menu image, which on a dark background brightens it, so there {@link #showState} shows
 * a disabled menu item a faded copy. Images are created on first use on the UI thread and disposed
 * with the owner control.
 */
final class ToolIcons {
  /** Opens files. */
  static final String OPEN = "open";

  /** Reloads the file. */
  static final String RELOAD = "reload";

  /** Follows the file. */
  static final String FOLLOW = "follow";

  /** Stops loading or searching. */
  static final String STOP = "stop";

  /** Match case. */
  static final String CASE = "case";

  /** Regular expression. */
  static final String REGEX = "regex";

  /** Whole word. */
  static final String WORD = "word";

  /** Inverse match. */
  static final String INVERSE = "inverse";

  /** Combined expressions. */
  static final String COMBINE = "combine";

  /** Search in the selection. */
  static final String SELECTION = "selection";

  /** Auto-refresh. */
  static final String REFRESH = "refresh";

  /** Keep results. */
  static final String KEEP = "keep";

  /** Clears the search text. */
  static final String CLEAR = "clear";

  /** Runs the search. */
  static final String SEARCH = "search";

  /** Adds a row. */
  static final String ADD = "add";

  /** Removes a row. */
  static final String REMOVE = "remove";

  /** Moves a row up. */
  static final String UP = "up";

  /** Moves a row down. */
  static final String DOWN = "down";

  // Context lines and range filter.
  /** Range search. */
  static final String RANGE = "range";

  /** A checked box. */
  static final String CHECKED = "checked";

  /** An empty box. */
  static final String UNCHECKED = "unchecked";

  // Tab strip buttons.
  /** Opens files from the tab strip. */
  static final String TAB_NEW = "tab-new";

  /** Closes the current tab. */
  static final String TAB_CLOSE = "tab-close";

  /** Lists every tab. */
  static final String TAB_LIST = "tab-list-down";

  // Menu item icons.
  /** Closes the file. */
  static final String CLOSE = "close";

  /** Quits the application. */
  static final String QUIT = "quit";

  /** Copies the selection. */
  static final String COPY = "copy";

  /** Selects the whole file. */
  static final String SELECT_ALL = "select-all";

  /** Selects the next match. */
  static final String FIND_NEXT = "find-next";

  /** Selects the previous match. */
  static final String FIND_PREVIOUS = "find-previous";

  /** Searches the files of a folder. */
  static final String SEARCH_FOLDER = "search-folder";

  /** Moves the caret to a line. */
  static final String GO_TO_LINE = "go-to-line";

  /** Opens Preferences. */
  static final String PREFERENCES = "preferences";

  /** Opens the user guide. */
  static final String HELP = "help";

  /** Opens About. */
  static final String ABOUT = "about";

  /** Suffix of a faded copy's key in the image cache. */
  private static final String FADED = "#faded";

  /** Percent of its opacity a faded copy keeps. */
  private static final int FADE_PERCENT = 40;

  /** Side of an icon at 100% zoom. */
  private static final int SIDE = 16;

  /** Background luminance, 0-255, below which the light ink is used. */
  private static final int DARK_BACKGROUND = 128;

  /** Gives each item room for its icon on GTK 4. */
  private static final ToolItemSizing SIZING = ToolItemSizing.platform();

  private final Display display;
  private final Control owner;
  private final Control source;
  private final Map<String, Image> images = new HashMap<String, Image>();
  private final Map<ToolItem, String> items = new LinkedHashMap<ToolItem, String>();
  private boolean darkBackground;
  // Tab strip buttons.
  private final boolean fixedInk;
  // Menu item icons.
  private final Map<MenuItem, String> menuItems = new LinkedHashMap<MenuItem, String>();
  private final boolean faded = PlatformTraits.PLATFORM.fadesDisabledIcons();
  private Image blank;

  /**
   * Creates the set for controls with the owner's background; the images are disposed with it.
   *
   * @param owner a control whose background the icons are drawn on
   */
  ToolIcons(Control owner) {
    this(owner, owner);
  }

  /**
   * Creates the set for controls on an owner that draws no background of its own, such as a plain
   * {@code Composite}, whose GTK print leaves the sample white in either theme. The ink is read
   * from the source, a control that draws the same native background. The images are disposed with
   * the owner.
   *
   * @param owner the control that disposes the images
   * @param source a control whose background the icons' background matches, such as a tool bar
   */
  ToolIcons(Control owner, Control source) {
    this(owner, source, false, dark(source.getBackground()));
  }

  // Tab strip buttons.

  /**
   * Creates the set for a surface QLogg paints from the palette, such as a tab strip. The ink
   * changes only through {@link #follow}, not with native settings. The images are disposed with
   * the owner.
   *
   * @param owner the control that disposes the images
   * @param background the surface's color
   */
  ToolIcons(Control owner, Color background) {
    this(owner, owner, true, dark(background));
  }

  // Menu item icons.

  /**
   * Creates the set for a background no control shows and no theme change reaches; the images are
   * disposed with the owner.
   *
   * @param owner the control that disposes the images
   * @param dark true for the light ink
   */
  ToolIcons(Control owner, boolean dark) {
    this(owner, owner, true, dark);
  }

  private ToolIcons(Control owner, Control source, boolean fixedInk, boolean dark) {
    this.display = owner.getDisplay();
    this.owner = owner;
    this.source = source;
    this.fixedInk = fixedInk;
    this.darkBackground = dark;
    final Listener settings =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            reink();
          }
        };
    display.addListener(SWT.Settings, settings);
    owner.addListener(SWT.Settings, settings);
    owner.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            display.removeListener(SWT.Settings, settings);
            dispose();
          }
        });
  }

  /**
   * Shows an icon on a tool item and keeps it in the owner's ink across {@link #reink}.
   *
   * @param item the item
   * @param name one of the name constants
   * @return false when the PNG is missing from the jar and the item shows no image
   */
  boolean apply(ToolItem item, String name) {
    Image image = get(name);
    if (image == null) {
      return false;
    }
    SIZING.keepIconSize(item);
    item.setImage(image);
    items.put(item, name);
    return true;
  }

  // Menu item icons.

  /**
   * Shows an icon on a menu item and keeps it in the owner's ink across {@link #reink} until the
   * item is disposed.
   *
   * @param item the item
   * @param name one of the name constants
   * @return false when the PNG is missing from the jar and the item shows no image
   */
  boolean apply(final MenuItem item, String name) {
    if (get(name) == null) {
      return false;
    }
    menuItems.put(item, name);
    item.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            menuItems.remove(item);
          }
        });
    showState(item);
    return true;
  }

  /**
   * Shows the transparent image on a menu item, so its label lines up with items that show icons.
   *
   * @param item the item
   */
  void applyBlank(MenuItem item) {
    item.setImage(blank());
  }

  /**
   * Returns a transparent image of an icon's size, created on first use.
   *
   * @return the image, disposed with the owner
   */
  Image blank() {
    if (blank == null) {
      blank =
          new Image(
              display,
              new ImageDataProvider() {
                @Override
                public ImageData getImageData(int zoom) {
                  return zoom == 100
                      ? transparent(SIDE)
                      : zoom == 200 ? transparent(2 * SIDE) : null;
                }
              });
    }
    return blank;
  }

  /**
   * Shows the image of a menu item's enabled state; call after changing it. An item not given to
   * {@link #apply} keeps its image.
   *
   * @param item the item
   */
  void showState(MenuItem item) {
    String name = menuItems.get(item);
    if (name == null || item.isDisposed()) {
      return;
    }
    Image image = get(faded && darkBackground && !item.getEnabled() ? name + FADED : name);
    if (item.getImage() != image) {
      item.setImage(image);
    }
  }

  /**
   * Reads the ink source's background again and, when it calls for the other ink, replaces the
   * image of every item given to {@link #apply}. Native controls change background with the theme.
   * A set of fixed ink keeps it.
   */
  void reink() {
    if (fixedInk || owner.isDisposed() || source.isDisposed()) {
      return;
    }
    swap(dark(PlatformTraits.PLATFORM.shownBackground(source)));
  }

  // Tab strip buttons.

  /**
   * Takes the ink a palette-painted surface's new color calls for; call when the palette changes.
   *
   * @param background the surface's color
   */
  void follow(Color background) {
    if (!owner.isDisposed()) {
      swap(dark(background));
    }
  }

  /** Replaces the image of every applied item when the ink changes. */
  private void swap(boolean now) {
    if (now == darkBackground) {
      return;
    }
    darkBackground = now;
    List<Image> old = new ArrayList<Image>(images.values());
    images.clear();
    for (Map.Entry<ToolItem, String> entry : items.entrySet()) {
      if (!entry.getKey().isDisposed()) {
        entry.getKey().setImage(get(entry.getValue()));
      }
    }
    // Menu item icons.
    for (MenuItem item : menuItems.keySet()) {
      showState(item);
    }
    for (Image image : old) {
      if (image != null) {
        image.dispose();
      }
    }
  }

  /**
   * Reports whether a background calls for the light ink.
   *
   * @param background the background color
   * @return true when its luminance is below the midpoint
   */
  static boolean dark(Color background) {
    int luminance =
        (background.getRed() * 299 + background.getGreen() * 587 + background.getBlue() * 114)
            / 1000;
    return luminance < DARK_BACKGROUND;
  }

  /**
   * Returns an icon, created on first use.
   *
   * @param name one of the name constants
   * @return the image, or null when its PNG is missing from the jar
   */
  Image get(String name) {
    Image image = images.get(name);
    if (image == null && !images.containsKey(name)) {
      image = create(name);
      images.put(name, image);
    }
    return image;
  }

  /**
   * Reports which ink the icons use.
   *
   * @return true for the light ink drawn on a dark background
   */
  boolean darkBackground() {
    return darkBackground;
  }

  private Image create(String key) {
    // Menu item icons.
    final boolean fade = key.endsWith(FADED);
    String name = fade ? key.substring(0, key.length() - FADED.length()) : key;
    final String prefix = "icons/tool/" + name + (darkBackground ? "-dark-" : "-light-");
    if (data(prefix + 16) == null) {
      return null;
    }
    return new Image(
        display,
        new ImageDataProvider() {
          @Override
          public ImageData getImageData(int zoom) {
            ImageData data =
                zoom == 100 ? data(prefix + 16) : zoom == 200 ? data(prefix + 32) : null;
            return fade && data != null ? fade(data) : data;
          }
        });
  }

  // Menu item icons.

  private static ImageData transparent(int side) {
    ImageData data = new ImageData(side, side, 32, new PaletteData(0xff0000, 0xff00, 0xff));
    data.alphaData = new byte[side * side];
    return data;
  }

  private static ImageData fade(ImageData data) {
    byte[] alpha = data.alphaData;
    if (alpha != null) {
      for (int i = 0; i < alpha.length; i++) {
        alpha[i] = (byte) ((alpha[i] & 0xff) * FADE_PERCENT / 100);
      }
    }
    return data;
  }

  private static ImageData data(String resource) {
    try (InputStream in = ToolIcons.class.getResourceAsStream(resource + ".png")) {
      return in == null ? null : new ImageData(in);
    } catch (IOException | SWTException e) {
      return null;
    }
  }

  private void dispose() {
    for (Image image : images.values()) {
      if (image != null) {
        image.dispose();
      }
    }
    images.clear();
    // Menu item icons.
    if (blank != null) {
      blank.dispose();
    }
  }
}
