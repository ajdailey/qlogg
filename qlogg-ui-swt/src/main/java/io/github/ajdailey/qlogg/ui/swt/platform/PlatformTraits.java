package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * The view layer's platform differences: {@link Win32Traits}, {@link CocoaTraits}, {@link
 * Gtk3Traits} and {@link Gtk4Traits} each answer every question a view class asks. No view class
 * outside this package reads {@link SWT#getPlatform} or {@code SWT_GTK4}.
 */
public interface PlatformTraits {
  /** System property SWT sets on GTK to {@code x11}, {@code wayland} or {@code unknown}. */
  String GDK_BACKEND = "org.eclipse.swt.internal.gdk.backend";

  /** System property SWT sets on GTK to the GTK version. */
  String GTK_VERSION = "org.eclipse.swt.internal.gtk.version";

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
   * Returns the native toolkit and its version for the About text.
   *
   * @return {@code GTK} and the {@link #GTK_VERSION} SWT records on GTK, empty elsewhere or before
   *     the Display exists
   */
  String toolkitVersion();

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
   * Reports whether the system hands a launch its files as {@code SWT.OpenDocument} events after
   * the Display exists rather than as arguments: Finder Open With can start a second copy with
   * none.
   *
   * @return true on cocoa
   */
  boolean opensFilesByEvent();

  /**
   * Reports whether the Window menu carries Minimize ({@code Cmd+M}) and Zoom, which SWT does not
   * add to an application-defined Window menu.
   *
   * @return true on cocoa
   */
  boolean windowMenuControls();

  /**
   * Reports whether a screen reader takes a custom control's label from its accessible description
   * and needs an explicit role: VoiceOver reads a {@code Canvas} without a role as {@code
   * SWTComposite} with a blank description and does not read the accessible name.
   *
   * @return true on cocoa
   */
  boolean labelsByDescription();

  /**
   * Reports whether the theme service polls {@code Display.isSystemDarkTheme()} for a change: cocoa
   * sends no {@code SWT.Settings} for an appearance change, and win32 sends it 2 s after {@code
   * WM_SETTINGCHANGE}.
   *
   * @return true on cocoa and win32
   */
  boolean pollsSystemTheme();

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
   * Returns the busy cursor's {@link SWT} cursor style, an arrow with a progress mark that keeps
   * the window clickable. cocoa maps {@code CURSOR_APPSTARTING} to a plain arrow.
   *
   * @return {@code CURSOR_WAIT} on cocoa, else {@code CURSOR_APPSTARTING}
   */
  int busyCursor();

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
