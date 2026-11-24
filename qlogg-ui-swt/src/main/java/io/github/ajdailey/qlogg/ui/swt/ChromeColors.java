package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;

/**
 * The main window's own rows where the native theme leaves them light under a dark theme ({@link
 * PlatformTraits#colorsChromeUnderDark}). While {@code ui.theme} resolves to dark, each control
 * under a surface given to {@link #add(Control)} takes {@link Palette#DARK}'s gutter, the tab
 * strip's background, and its text as foreground; a {@link Link} takes its link color raised to 4.5
 * contrast, and each composite passes its background to push button corners ({@code
 * SWT.INHERIT_DEFAULT}). While it resolves to light every such color is null and composites take
 * {@code SWT.INHERIT_NONE}. Own colors need no restart, so both directions apply live.
 *
 * <p>Push and arrow buttons, fields, combos, lists, tables, trees, spinners, progress bars, a
 * {@link StyledText}, a {@link Browser}, a {@link CTabFolder} and a control with {@link
 * CocoaChrome#PALETTE_COLORED} keep their colors, and the walk does not descend into them. A
 * control created later under a surface takes the colors at its {@link SWT#Skin} event. Each {@link
 * #apply} colors the surfaces first and then re-inks every {@link ToolIcons} given to {@link
 * #add(ToolIcons)}, since the ink is read from a colored tool bar, and gives disabled tool items
 * faded copies under dark. On other platforms nothing changes. Confined to the UI thread.
 */
final class ChromeColors {
  /** Background of every surface under dark. */
  static final Rgb BACKGROUND = Palette.DARK.gutter();

  /** Text under dark. */
  static final Rgb TEXT = Palette.DARK.text();

  /** Lowest contrast of link text on {@link #BACKGROUND}. */
  static final double LINK_CONTRAST = 4.5;

  private static final int LINK_STEP_PERCENT = 5;
  private static final RGB WHITE = new RGB(0xff, 0xff, 0xff);

  private final PlatformTraits traits;
  private final Display display;
  private final Listener skin =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          if (dark
              && event.widget instanceof Control control
              && !control.isDisposed()
              && under(control)) {
            walk(control);
          }
        }
      };
  private final List<Control> surfaces = new ArrayList<Control>();
  private final List<ToolIcons> icons = new ArrayList<ToolIcons>();
  private boolean dark;
  private boolean painted;

  /**
   * Creates the colors for a window.
   *
   * @param traits the platform
   * @param display the Display
   */
  ChromeColors(PlatformTraits traits, Display display) {
    this.traits = traits;
    this.display = display;
    if (traits.colorsChromeUnderDark()) {
      display.addListener(SWT.Skin, skin);
    }
  }

  /** Stops coloring new controls; call when the window is disposed. */
  void dispose() {
    if (!display.isDisposed()) {
      display.removeListener(SWT.Skin, skin);
    }
  }

  /**
   * Colors a surface and its controls for the current side, and again at each {@link #apply}, until
   * the surface is disposed. The surface is listed on every platform; adding it twice lists it
   * once.
   *
   * @param surface the surface
   */
  void add(final Control surface) {
    if (surfaces.contains(surface)) {
      return;
    }
    surfaces.add(surface);
    surface.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            surfaces.remove(surface);
          }
        });
    if (dark) {
      walk(surface);
    }
  }

  /**
   * Returns the surfaces given to {@link #add(Control)} and not disposed.
   *
   * @return a copy, in the order added
   */
  List<Control> surfaces() {
    return new ArrayList<Control>(surfaces);
  }

  /**
   * Re-inks an icon set after each {@link #apply} and gives its disabled tool items faded copies
   * under dark. The set is listed on every platform.
   *
   * @param set the icons, inked from a tool bar under a surface
   */
  void add(ToolIcons set) {
    icons.add(set);
    if (traits.colorsChromeUnderDark()) {
      set.fadeDisabled(dark);
    }
  }

  /**
   * Returns the icon sets given to {@link #add(ToolIcons)}.
   *
   * @return a copy, in the order added
   */
  List<ToolIcons> icons() {
    return new ArrayList<ToolIcons>(icons);
  }

  /**
   * Colors every surface for a side of the theme, then re-inks the icons.
   *
   * @param value true when {@code ui.theme} resolves to dark
   */
  void apply(boolean value) {
    if (!traits.colorsChromeUnderDark() || (!value && !painted)) {
      return;
    }
    dark = value;
    painted |= value;
    for (Control surface : new ArrayList<Control>(surfaces)) {
      if (!surface.isDisposed()) {
        walk(surface);
      }
    }
    for (ToolIcons set : icons) {
      set.fadeDisabled(value);
      set.reink();
    }
  }

  /**
   * Reports whether the surfaces carry the dark colors.
   *
   * @return true under dark where the platform colors them
   */
  boolean dark() {
    return dark;
  }

  private void walk(Control control) {
    if (keeps(control)) {
      return;
    }
    control.setBackground(dark ? color(BACKGROUND) : null);
    control.setForeground(dark ? color(TEXT) : null);
    if (control instanceof Link link) {
      link.setLinkForeground(dark ? linkColor(display) : null);
    }
    if (control instanceof Composite composite) {
      composite.setBackgroundMode(dark ? SWT.INHERIT_DEFAULT : SWT.INHERIT_NONE);
      for (Control child : composite.getChildren()) {
        walk(child);
      }
    }
  }

  /** Reports whether a new control sits under a surface with no kept control between. */
  private boolean under(Control control) {
    for (Control at = control; at != null && !(at instanceof Shell); at = at.getParent()) {
      if (keeps(at)) {
        return false;
      }
      if (surfaces.contains(at)) {
        return true;
      }
    }
    return false;
  }

  /** Reports whether a control keeps its own colors. */
  private static boolean keeps(Control control) {
    if (control.getData(CocoaChrome.PALETTE_COLORED) != null) {
      return true;
    }
    if (control instanceof Button) {
      return (control.getStyle() & (SWT.CHECK | SWT.RADIO)) == 0;
    }
    return control instanceof Text
        || control instanceof Combo
        || control instanceof org.eclipse.swt.widgets.List
        || control instanceof Table
        || control instanceof Tree
        || control instanceof Spinner
        || control instanceof ProgressBar
        || control instanceof StyledText
        || control instanceof Browser
        || control instanceof CTabFolder;
  }

  /**
   * Returns the system link color moved toward white until it reaches {@link #LINK_CONTRAST} on
   * {@link #BACKGROUND}.
   *
   * @param display the Display
   * @return the color
   */
  static Color linkColor(Display display) {
    RGB text = display.getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB();
    RGB ground = color(BACKGROUND).getRGB();
    RGB result = text;
    for (int percent = LINK_STEP_PERCENT;
        percent <= 100 && contrast(result, ground) < LINK_CONTRAST;
        percent += LINK_STEP_PERCENT) {
      result =
          new RGB(
              mix(text.red, WHITE.red, percent),
              mix(text.green, WHITE.green, percent),
              mix(text.blue, WHITE.blue, percent));
    }
    return new Color(result);
  }

  /**
   * Returns the WCAG contrast of two colors.
   *
   * @param first a color
   * @param second the other color
   * @return 1 to 21
   */
  static double contrast(RGB first, RGB second) {
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

  private static int mix(int from, int to, int percent) {
    return from + (to - from) * percent / 100;
  }

  static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }
}
