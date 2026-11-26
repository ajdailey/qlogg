package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/**
 * The view layer's platform differences: {@link Win32Traits}, {@link CocoaTraits}, {@link
 * Gtk3Traits} and {@link Gtk4Traits} each answer every value a view class reads and create every
 * seam whose behavior differs. No view class outside this package reads {@link SWT#getPlatform},
 * {@code SWT_GTK4} or {@link #GTK_VERSION}.
 */
public interface PlatformTraits {
  /** System property SWT sets on GTK to {@code x11}, {@code wayland} or {@code unknown}. */
  String GDK_BACKEND = "org.eclipse.swt.internal.gdk.backend";

  /** System property SWT sets on GTK to the GTK version. */
  String GTK_VERSION = "org.eclipse.swt.internal.gtk.version";

  /** The running platform's traits, for the GTK version SWT loaded ({@link LoadedToolkit}). */
  PlatformTraits PLATFORM = LoadedToolkit.traits();

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
   * Returns the busy cursor's {@link SWT} cursor style, an arrow with a progress mark that keeps
   * the window clickable. cocoa maps {@code CURSOR_APPSTARTING} to a plain arrow.
   *
   * @return {@code CURSOR_WAIT} on cocoa, else {@code CURSOR_APPSTARTING}
   */
  int busyCursor();

  /**
   * Returns the dialog colors.
   *
   * @return {@link Win32DialogColors} on win32, else {@link NativeDialogColors}
   */
  DialogColors dialogColors();

  /**
   * Creates a window's chrome colors.
   *
   * @param display the Display
   * @return {@link Win32ChromeColors} on win32, else {@link NativeChromeColors}
   */
  ChromeColors chromeColors(Display display);

  /**
   * Creates a dialog's tab folder. On win32 this reads {@link DialogColors#nativeDark}.
   *
   * @param parent the dialog composite holding it
   * @return {@link Win32DialogTabs} on win32 under the dark native side, {@link CocoaDialogTabs} on
   *     cocoa, else {@link NativeDialogTabs}
   */
  DialogTabs dialogTabs(Composite parent);

  /**
   * Creates the images of a table column other than the first.
   *
   * @param table the table
   * @param column the column's index
   * @return {@link Win32CellImages} on win32, else {@link NativeCellImages}
   */
  CellImages cellImages(Table table, int column);

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

  /**
   * Creates the native controls' theme handling for a Display.
   *
   * @param display the Display
   * @return {@link Win32ThemeFollow}, {@link CocoaThemeFollow} or {@link GtkThemeFollow}
   */
  ThemeFollow themeFollow(Display display);

  /**
   * Creates a main window's full screen state.
   *
   * @param shell the window
   * @param ui schedules the cocoa retries
   * @return {@link CocoaFullScreen} on cocoa, else {@link NativeFullScreen}
   */
  FullScreen fullScreen(Shell shell, UiDispatcher ui);

  /**
   * Returns the menu bar behavior.
   *
   * @return {@link Win32MenuBehavior}, {@link CocoaMenuBehavior}, {@link Gtk3MenuBehavior} or
   *     {@link Gtk4MenuBehavior}
   */
  MenuBehavior menuBehavior();

  /**
   * Returns how a launch receives its files.
   *
   * @return {@link CocoaLaunchDocuments} on cocoa, else {@link NativeLaunchDocuments}
   */
  LaunchDocuments launchDocuments();

  /**
   * Returns how a screen reader takes a custom control's label.
   *
   * @return {@link CocoaAccessibleLabels} on cocoa, else {@link NativeAccessibleLabels}
   */
  AccessibleLabels accessibleLabels();

  /**
   * Returns how a main window is placed.
   *
   * @return {@link Win32WindowPlacement}, {@link NativeWindowPlacement} on cocoa, {@link
   *     GtkWindowPlacement} on GTK 3, or {@link Gtk4WindowPlacement}
   */
  WindowPlacement windowPlacement();

  /**
   * Returns where the user guide shows.
   *
   * @return {@link NoticeGuideDisplay} on GTK 4, else {@link BrowserGuideDisplay}
   */
  GuideDisplay guideDisplay();

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
