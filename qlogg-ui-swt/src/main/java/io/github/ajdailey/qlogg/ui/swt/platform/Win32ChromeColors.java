package io.github.ajdailey.qlogg.ui.swt.platform;

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
 * dark, each control under a surface takes {@link #BACKGROUND} and {@link #TEXT}, the neutral grays
 * of {@link Win32DialogColors}, whatever the {@code view.colors} scheme; a {@link Link} takes its
 * link color raised to 4.5 contrast, and each composite passes its background to push button
 * corners ({@code SWT.INHERIT_DEFAULT}). A {@link Text} or an editable {@link Combo} takes {@link
 * #FIELD}, and a read-only {@code Combo} {@link #FACE}, whose drop-down list draws in those colors
 * while the dark native theme draws its closed field. While it resolves to light every such color
 * is null and composites take {@code SWT.INHERIT_NONE}. Both directions apply live.
 *
 * <p>A check box is created under the native setting, so its box draws dark under the dark one; on
 * the light side under that setting its label takes the system widget text, since the dark native
 * box draws it near-white without colors.
 *
 * <p>While native controls are dark ({@link DialogColors#nativeDark}) a field holding text keeps
 * the dark colors on the light side until its window is rebuilt, because the dark native field
 * draws near-black under any light colors. An empty field takes the system list colors, set
 * explicitly, since a field with no colors draws the dark native field; {@link Win32EmptyField}
 * paints an empty {@link Text} in its background while unfocused, on either side, since the dark
 * native field draws {@code #212121} there under any colors. A field is colored again when it
 * becomes empty or takes text ({@code SWT.Modify}).
 *
 * <p>In a window built under the light native setting, a {@link Text} colored under the dark
 * setting, which happens while the window waits for its rebuild, takes the dark theme from SWT;
 * when the window returns to light before the rebuild, {@link Win32FieldTheme#clear} removes it,
 * since the themed field draws {@code #212121} under light colors.
 *
 * <p>Push and arrow buttons, lists, tables, trees, spinners, progress bars, a {@link StyledText}, a
 * {@link Browser}, a {@link CTabFolder} and a control with {@link #PALETTE_COLORED} keep their
 * colors, and the walk does not descend into them. A control created later under a surface takes
 * the colors at its {@link SWT#Skin} event.
 *
 * <p>Each {@link #apply} colors the surfaces first and then re-inks every icon set, since the ink
 * is read from a colored tool bar. Under dark a set's disabled items show faded copies. A checked
 * tool item draws the checked background of the native theme its window was built under, light
 * under the light one, so a checked item takes the light ink only under the dark native theme.
 */
final class Win32ChromeColors implements ChromeColors {
  /** Background of every surface under dark: {@link Win32DialogColors#WINDOW}. */
  static final Color BACKGROUND = Win32DialogColors.WINDOW;

  /** Text and field text under dark: {@link Win32DialogColors#TEXT}. */
  static final Color TEXT = Win32DialogColors.TEXT;

  /** Text field and editable combo background under dark: {@link Win32DialogColors#FIELD}. */
  static final Color FIELD = Win32DialogColors.FIELD;

  /** Read-only combo background under dark: {@link Win32DialogColors#FACE}. */
  static final Color FACE = Win32DialogColors.FACE;

  /** Control data key: set on a field once it follows {@code SWT.Modify}. */
  private static final String FOLLOWED = Win32ChromeColors.class.getName() + ".followed";

  /** Control data key: set on a light-built window's text field colored under the dark setting. */
  private static final String THEMED = Win32ChromeColors.class.getName() + ".themed";

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

  /**
   * {@link Win32EmptyField} fills an empty field with its background: under dark with a hint in
   * {@link Win32DialogColors#HINT}; on the light side under the dark native setting with a hint in
   * the system gray raised to {@link Contrast#LINK}.
   */
  private final Win32EmptyField.Ink ink =
      new Win32EmptyField.Ink() {
        @Override
        public Color hint(Text text) {
          if (dark) {
            return Win32DialogColors.HINT;
          }
          if (!nativeDark()) {
            return null;
          }
          RGB gray = display.getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND).getRGB();
          return new Color(Contrast.readable(gray, text.getBackground().getRGB()));
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
    // Read-only combo list.
    if (control instanceof Combo && !field(control)) {
      control.setBackground(dark ? FACE : null);
      control.setForeground(dark ? TEXT : null);
      return;
    }
    if (keeps(control)) {
      return;
    }
    if (field(control)) {
      colorField(control);
      return;
    }
    control.setBackground(dark ? BACKGROUND : null);
    control.setForeground(dark ? TEXT : lightText(control));
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
    boolean nativeDark = nativeDark();
    boolean darkField = dark || (nativeDark && length(field) > 0);
    // An empty text field with no colors draws the dark native field under the dark setting.
    boolean listColors = nativeDark && field instanceof Text;
    Color light = listColors ? display.getSystemColor(SWT.COLOR_LIST_BACKGROUND) : null;
    Color lightText = listColors ? display.getSystemColor(SWT.COLOR_LIST_FOREGROUND) : null;
    field.setBackground(darkField ? FIELD : light);
    field.setForeground(darkField ? TEXT : lightText);
    if (!nativeDark && field instanceof Text text) {
      if (darkField && DialogColors.nativeDark(display)) {
        text.setData(THEMED, Boolean.TRUE);
      } else if (!darkField && !DialogColors.nativeDark(display) && text.getData(THEMED) != null) {
        text.setData(THEMED, null);
        Win32FieldTheme.clear(text);
      }
    }
    if (field.getData(FOLLOWED) != null) {
      return;
    }
    field.setData(FOLLOWED, Boolean.TRUE);
    if (field instanceof Text text) {
      Win32EmptyField.draw(text, ink);
    }
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

  /**
   * Returns a control's text color on the light side: the system widget text for a check box or
   * radio button under the dark native setting, which draws its label near-white without colors;
   * otherwise null.
   */
  private Color lightText(Control control) {
    if (control instanceof Button && nativeDark()) {
      return display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
    }
    return null;
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
   * Returns the system link color raised to {@link Contrast#LINK} on {@link #BACKGROUND}.
   *
   * @param display the Display
   * @return the color
   */
  static Color linkColor(Display display) {
    RGB theme = display.getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB();
    return new Color(Contrast.readable(theme, BACKGROUND.getRGB()));
  }
}
