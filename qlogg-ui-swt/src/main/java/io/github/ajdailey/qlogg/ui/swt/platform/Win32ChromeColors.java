package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
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
 * The main window's rows on win32, where the dark native theme leaves a {@code ToolBar}, {@code
 * Composite}, {@code Label}, {@code Link} and every field light. While {@code ui.theme} resolves to
 * dark, each control under a surface takes {@link Palette#DARK}'s gutter, the tab strip's
 * background, and its text; a {@link Link} takes its link color raised to 4.5 contrast, and each
 * composite passes its background to push button corners ({@code SWT.INHERIT_DEFAULT}). A {@link
 * Text} or an editable {@link Combo} takes {@link Palette#DARK}'s background and text. While it
 * resolves to light every such color is null and composites take {@code SWT.INHERIT_NONE}. Both
 * directions apply live.
 *
 * <p>While native controls are dark ({@link DialogColors#nativeDark}) a field holding text keeps
 * the dark colors on the light side, because the dark native field draws near-black under any light
 * colors; an empty field takes the system colors. A field is colored again when it becomes empty or
 * takes text ({@code SWT.Modify}).
 *
 * <p>Push and arrow buttons, read-only combos, lists, tables, trees, spinners, progress bars, a
 * {@link StyledText}, a {@link Browser}, a {@link CTabFolder} and a control with {@link
 * #PALETTE_COLORED} keep their colors, and the walk does not descend into them. A control created
 * later under a surface takes the colors at its {@link SWT#Skin} event.
 *
 * <p>Each {@link #apply} colors the surfaces first and then re-inks every icon set, since the ink
 * is read from a colored tool bar. Under dark a set's disabled items show faded copies. A checked
 * tool item draws the checked background of the native theme its window was built under, light
 * under the light one, so a checked item takes the light ink only under the dark native theme.
 */
final class Win32ChromeColors implements ChromeColors {
  /** Background of every surface under dark. */
  static final Rgb BACKGROUND = Palette.DARK.gutter();

  /** Text under dark. */
  static final Rgb TEXT = Palette.DARK.text();

  /** Field background under dark. */
  static final Rgb FIELD = Palette.DARK.background();

  /** Field text under dark. */
  static final Rgb FIELD_TEXT = Palette.DARK.text();

  /** Lowest contrast of link text on {@link #BACKGROUND}. */
  static final double LINK_CONTRAST = 4.5;

  /** Control data key: set on a field once it follows {@code SWT.Modify}. */
  private static final String FOLLOWED = Win32ChromeColors.class.getName() + ".followed";

  private static final int LINK_STEP_PERCENT = 5;
  private static final RGB WHITE = new RGB(0xff, 0xff, 0xff);

  private final Display display;
  private final Listener skin =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          if (painted
              && event.widget instanceof Control control
              && !control.isDisposed()
              && under(control)) {
            walk(control);
          }
        }
      };
  private final List<Control> surfaces = new ArrayList<Control>();
  private final List<Icons> icons = new ArrayList<Icons>();
  private boolean dark;
  private boolean painted;
  private Boolean built;

  /**
   * Creates the colors for a window.
   *
   * @param display the Display
   */
  Win32ChromeColors(Display display) {
    this.display = display;
    display.addListener(SWT.Skin, skin);
  }

  /**
   * Returns the native setting at the first {@link #apply} and keeps it: the setting the window's
   * controls were created under, also while the window waits for a rebuild after a switch. {@link
   * #add(Icons)} reads it without keeping it, so the theme service may record it after the window
   * is built.
   */
  private boolean nativeDark() {
    if (built == null) {
      built = DialogColors.nativeDark(display);
    }
    return built;
  }

  @Override
  public void dispose() {
    if (!display.isDisposed()) {
      display.removeListener(SWT.Skin, skin);
    }
  }

  @Override
  public void add(final Control surface) {
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
    if (painted) {
      walk(surface);
    }
  }

  @Override
  public void add(Icons set) {
    icons.add(set);
    set.lightChecked(built == null ? DialogColors.nativeDark(display) : built);
    set.fadeDisabled(dark);
  }

  @Override
  public void apply(boolean value) {
    nativeDark();
    if (!value && !painted) {
      return;
    }
    dark = value;
    painted = true;
    for (Control surface : new ArrayList<Control>(surfaces)) {
      if (!surface.isDisposed()) {
        walk(surface);
      }
    }
    for (Icons set : icons) {
      set.lightChecked(nativeDark());
      set.fadeDisabled(value);
      set.reink();
    }
  }

  private void walk(Control control) {
    if (keeps(control)) {
      return;
    }
    if (field(control)) {
      colorField(control);
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

  private void colorField(final Control field) {
    boolean darkField = dark || (nativeDark() && length(field) > 0);
    field.setBackground(darkField ? color(FIELD) : null);
    field.setForeground(darkField ? color(FIELD_TEXT) : null);
    if (field.getData(FOLLOWED) != null) {
      return;
    }
    field.setData(FOLLOWED, Boolean.TRUE);
    field.addListener(
        SWT.Modify,
        new Listener() {
          private boolean empty = length(field) == 0;

          @Override
          public void handleEvent(Event event) {
            boolean now = length(field) == 0;
            if (now != empty) {
              empty = now;
              colorField(field);
            }
          }
        });
  }

  private static int length(Control field) {
    return field instanceof Text text ? text.getCharCount() : ((Combo) field).getText().length();
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

  /** Reports whether a control takes the field colors: a text field or an editable combo. */
  private static boolean field(Control control) {
    return control instanceof Text
        || (control instanceof Combo && (control.getStyle() & SWT.READ_ONLY) == 0);
  }

  /** Reports whether a control keeps its own colors. */
  private static boolean keeps(Control control) {
    if (control.getData(PALETTE_COLORED) != null) {
      return true;
    }
    if (control instanceof Button) {
      return (control.getStyle() & (SWT.CHECK | SWT.RADIO)) == 0;
    }
    return (control instanceof Combo && !field(control))
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

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }
}
