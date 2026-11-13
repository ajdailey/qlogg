package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * The view layer's platform differences: {@link Win32Traits}, {@link CocoaTraits}, {@link
 * Gtk3Traits} and {@link Gtk4Traits} each answer every question a view class asks. No other view
 * class reads {@link SWT#getPlatform} or {@code SWT_GTK4}.
 */
interface PlatformTraits {
  /** The running platform's traits; GTK 4 is the {@code SWT_GTK4=1} environment SWT reads. */
  PlatformTraits PLATFORM = of(SWT.getPlatform(), "1".equals(System.getenv("SWT_GTK4")));

  /**
   * Returns a platform's traits.
   *
   * @param platform an {@link SWT#getPlatform} value
   * @param gtk4 true for GTK 4 when the platform is {@code gtk}
   * @return the traits
   * @throws IllegalArgumentException for a platform SWT does not build
   */
  static PlatformTraits of(String platform, boolean gtk4) {
    return switch (platform) {
      case "win32" -> new Win32Traits();
      case "cocoa" -> new CocoaTraits();
      case "gtk" -> gtk4 ? new Gtk4Traits() : new Gtk3Traits();
      default -> throw new IllegalArgumentException(platform);
    };
  }

  /**
   * Returns the SWT platform name.
   *
   * @return {@code win32}, {@code cocoa} or {@code gtk}
   */
  String name();

  /**
   * Returns the monospace font family of text tool windows.
   *
   * @return Consolas on win32, Menlo on cocoa, Monospace on GTK
   */
  String monospaceFamily();

  /**
   * Returns the default monospace size.
   *
   * @return points: 12 on cocoa, 10 elsewhere
   */
  int monospaceSize();

  /**
   * Reports whether key names follow macOS.
   *
   * @return true on cocoa
   */
  boolean macKeys();

  /**
   * Reports whether the system can supply the application menu.
   *
   * @return true on cocoa
   */
  boolean systemAppMenu();

  /**
   * Reports whether a shell can be moved to a location; read at each placement.
   *
   * @return false on GTK under Wayland
   */
  boolean positionsWindows();

  /**
   * Reports whether a placement is made again with the trim measured from the sized shell. win32's
   * {@code computeTrim} overstates the height once a menu bar is set.
   *
   * @return true on win32
   */
  boolean remeasuresTrim();

  /**
   * Reports whether the system opens a file in its application by file extension.
   *
   * @return true on win32
   */
  boolean launchesByExtension();

  /**
   * Reports whether a {@code Browser} can show the user guide. WebKitGTK 6.0 aborts the JVM when
   * the browser starts under GTK 4 ("Failed to fully launch dbus-proxy").
   *
   * @return false on GTK 4
   */
  boolean opensBrowser();

  /**
   * Reports whether {@link Display#setDarkThemePreferred} restyles existing native controls.
   *
   * @return true on GTK
   */
  boolean switchesThemeLive();

  /**
   * Reports whether native controls always follow the system appearance, a change SWT reports with
   * no {@code SWT.Settings} event.
   *
   * @return true on cocoa
   */
  boolean followsSystemAppearance();

  /**
   * Reports whether native controls read the dark theme setting only when created.
   *
   * @return true on win32
   */
  boolean themesAtCreation();

  /**
   * Reports whether the dark native theme draws a check box label too light for a light page.
   *
   * @return true on win32
   */
  boolean lightChecksUnderDark();

  /**
   * Creates the Display's dialog colors.
   *
   * @param display the Display
   * @return {@link Win32DialogColors} on win32 when native controls started dark, else {@link
   *     NativeDialogColors}
   */
  DialogColors dialogColors(Display display);

  /**
   * Creates the stacking for View > Always on Top. On GTK this reads the backend the Display
   * records, so call it after the Display exists.
   *
   * @return the stacking
   */
  WindowStacking stacking();

  /**
   * Creates the tool item sizing.
   *
   * @return {@link Gtk4ToolItemSizing} on GTK 4, else {@link NativeToolItemSizing}
   */
  ToolItemSizing toolItemSizing();

  // Menu item icons.

  /**
   * Reports whether menu items show images. cocoa menus show none, and GTK 4 SWT ignores {@code
   * MenuItem.setImage}.
   *
   * @return false on cocoa and GTK 4
   */
  boolean menuImages();

  /**
   * Reports whether every other item of a menu with an icon holds a blank image. GTK 3 reserves no
   * image column, so a label moves right when its item has an image.
   *
   * @return true on GTK
   */
  boolean padsMenuImages();

  /**
   * Reports whether menu icon ink comes from the native theme's startup setting. win32 menus take
   * that setting and keep it, and the toolbar stays light under a dark one.
   *
   * @return true on win32
   */
  boolean fixedMenuInk();

  /**
   * Reports whether a disabled menu icon on a dark background takes a faded copy. GTK's disabled
   * effect brightens an image there.
   *
   * @return true on GTK
   */
  boolean fadesDisabledIcons();

  /**
   * Returns the background a control shows now. GTK refreshes {@code getBackground} only on the
   * first {@code Display.sleep} after a theme change, so a visible control is read from a pixel it
   * draws.
   *
   * @param source the control
   * @return the color
   */
  Color shownBackground(Control source);
}
