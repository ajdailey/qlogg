package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
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
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.Tree;

/**
 * Dialog surfaces on win32: dark for a dialog created while native controls are dark ({@link
 * DialogColors#nativeDark}), native otherwise. The dark native theme draws the title bar, push
 * buttons and read-only combos dark and leaves every other surface light, so a dialog body takes
 * the title bar's color, fields a lighter one and read-only combos the push button face. Measured
 * with SWT 3.135.0 on {@code windows-2025}: the title bar draws {@code #202020} and a push button
 * face {@code #333333}; {@code setBackground} and {@code setForeground} reach composites, labels,
 * links, check boxes, groups, tab pages, tool bars, text fields, combos, spinners, lists and
 * tables. A {@code TabFolder}'s tab row, a spinner's arrows, disabled text and a {@link Browser}
 * stay native. Native grid lines and header dividers draw {@code #f0f0f0}, so {@link #gridLines}
 * hides the lines and {@link Win32GridLines} draws {@link #GRID} rules, and {@link #apply} sets the
 * dividers to {@link #GRID} through {@link Win32HeaderLines}. A read-only {@link Combo} needs
 * colors set: the dark native theme draws its closed field and leaves its drop-down list white
 * without them. A {@link Link} takes the system link color raised to {@link Contrast#LINK} on
 * {@link #BODY}.
 *
 * <p>A single-line {@link Text}'s hint draws {@code #6d6d6d} when read-only (SWT draws it in {@code
 * COLOR_GRAYTEXT}) and {@code #575757} when editable, 2.74:1 and 1.96:1 on {@link #FIELD} (measured
 * on {@code windows-2025}); {@link Win32EmptyField} draws it in {@link #HINT} over that.
 *
 * <p>A {@link ToolBar} takes {@link #BODY} as its own background, not only through inheritance:
 * under the dark native theme SWT fills a hot or checked tool item with a shade of the bar's own
 * background, {@code #d8d8d8} from the default {@code #f0f0f0}, which hides a light icon.
 *
 * <p>{@link #apply} colors the shell before its children exist, walks it when it shows, and colors
 * each later control at its {@code SWT.Skin} event. Only a default color is replaced: a color the
 * application set stays. A dialog keeps the side it was created under; one opened after a theme
 * switch takes the new side.
 */
final class Win32DialogColors implements DialogColors {
  /** Dialog background: the dark title bar. */
  static final Color BODY = new Color(0x20, 0x20, 0x20);

  /** Field and list background, between the body and {@link #FACE}. */
  static final Color FIELD = new Color(0x2b, 0x2b, 0x2b);

  /** Read-only combo background: the dark push button face. */
  static final Color FACE = new Color(0x33, 0x33, 0x33);

  /** Text, as on the dark push buttons. */
  static final Color TEXT = new Color(0xff, 0xff, 0xff);

  /** Disabled table row text: 5.0 contrast on {@link #FIELD}. */
  static final Color DISABLED = new Color(0x9a, 0x9a, 0x9a);

  /**
   * Table grid lines and header dividers: 1.37:1 on {@link #FIELD}; native lines on a light table
   * are 1.14:1.
   */
  static final Color GRID = new Color(0x40, 0x40, 0x40);

  /** Hint text of an empty single-line field: 5.0 contrast on {@link #FIELD}. */
  static final Color HINT = DISABLED;

  private static final Win32EmptyField.Ink DIALOG_INK =
      new Win32EmptyField.Ink() {
        @Override
        public Color hint(Text text) {
          return HINT;
        }
      };

  static final Win32DialogColors INSTANCE = new Win32DialogColors();

  /** Shell data key: set on a shell whose surfaces this colors. */
  private static final String COLORED = Win32DialogColors.class.getName() + ".colored";

  /** Display data key: set once the {@code SWT.Skin} listener is installed. */
  private static final String SKIN = Win32DialogColors.class.getName() + ".skin";

  private Win32DialogColors() {}

  @Override
  public void apply(final Shell shell) {
    Display display = shell.getDisplay();
    headerLines(display);
    if (!DialogColors.nativeDark(display)) {
      return;
    }
    shell.setData(COLORED, Boolean.TRUE);
    shell.setBackground(BODY);
    shell.setForeground(TEXT);
    shell.setBackgroundMode(SWT.INHERIT_DEFAULT);
    shell.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget == shell) {
              walk(shell);
            }
          }
        });
    if (display.getData(SKIN) == null) {
      display.setData(SKIN, Boolean.TRUE);
      display.addListener(
          SWT.Skin,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (event.widget instanceof Control control
                  && !control.isDisposed()
                  && colored(control)) {
                color(control);
              }
            }
          });
    }
  }

  /**
   * Sets the header divider color for the current side: {@link #GRID} under dark; under light, the
   * native color once no colored dialog is open, since only a colored dialog's tables have header
   * colors, which make SWT draw the dividers.
   */
  private static void headerLines(Display display) {
    if (DialogColors.nativeDark(display)) {
      Win32HeaderLines.set(display, GRID);
      return;
    }
    for (Shell open : display.getShells()) {
      if (!open.isDisposed() && open.getData(COLORED) != null) {
        return;
      }
    }
    Win32HeaderLines.set(display, null);
  }

  @Override
  public boolean colored(Control control) {
    return control.getShell().getData(COLORED) != null;
  }

  private static void walk(Control control) {
    color(control);
    if (control instanceof Composite composite && !(control instanceof Browser)) {
      for (Control child : composite.getChildren()) {
        if (!(child instanceof Shell)) {
          walk(child);
        }
      }
    }
  }

  @Override
  public Color background(Shell owner) {
    return DialogColors.nativeDark(owner.getDisplay()) ? BODY : owner.getBackground();
  }

  @Override
  public Button check(Composite parent) {
    Display display = parent.getDisplay();
    if (!DialogColors.nativeDark(display) || colored(parent)) {
      return new Button(parent, SWT.CHECK);
    }
    display.setDarkThemePreferred(false);
    try {
      return new Button(parent, SWT.CHECK);
    } finally {
      display.setDarkThemePreferred(true);
    }
  }

  @Override
  public void gridLines(Table table) {
    if (colored(table)) {
      Win32GridLines.draw(table, GRID);
    } else {
      table.setLinesVisible(true);
    }
  }

  @Override
  public Color disabledText(Display display) {
    return DialogColors.nativeDark(display)
        ? DISABLED
        : display.getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND);
  }

  private static void color(Control control) {
    if (control instanceof Browser || control instanceof Shell) {
      return;
    }
    if (control instanceof Button && (control.getStyle() & SWT.PUSH) != 0) {
      return;
    }
    if (field(control)) {
      if (defaultBackground(control)) {
        control.setBackground(face(control) ? FACE : FIELD);
      }
    } else if (same(control, control.getBackground(), SWT.COLOR_WIDGET_BACKGROUND)
        || (control instanceof ToolBar && control.getBackground().getRGB().equals(BODY.getRGB()))) {
      control.setBackground(BODY);
    }
    if (defaultForeground(control)) {
      control.setForeground(TEXT);
      if (control instanceof Link link) {
        link.setLinkForeground(link(link.getDisplay()));
      }
    }
    if (control instanceof Table table) {
      table.setHeaderBackground(FIELD);
      table.setHeaderForeground(TEXT);
      Win32HeaderText.follow(table, true);
    }
    if (control instanceof Text text) {
      Win32EmptyField.draw(text, DIALOG_INK);
    }
  }

  private static boolean field(Control control) {
    return control instanceof Text
        || control instanceof Combo
        || control instanceof List
        || control instanceof Table
        || control instanceof Tree
        || control instanceof Spinner
        || control instanceof StyledText;
  }

  /** Reports whether a control is a read-only combo, which takes {@link #FACE}. */
  private static boolean face(Control control) {
    return control instanceof Combo && (control.getStyle() & SWT.READ_ONLY) != 0;
  }

  /**
   * Returns the system link color raised to {@link Contrast#LINK} on {@link #BODY}.
   *
   * @param display the Display
   * @return the color
   */
  static Color link(Display display) {
    RGB theme = display.getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB();
    return new Color(Contrast.readable(theme, BODY.getRGB()));
  }

  /** True for a field color the application did not set. */
  private static boolean defaultBackground(Control control) {
    Color color = control.getBackground();
    return same(control, color, SWT.COLOR_LIST_BACKGROUND)
        || same(control, color, SWT.COLOR_WIDGET_BACKGROUND)
        || color.getRGB().equals(BODY.getRGB());
  }

  /** True for a text color the application did not set. */
  private static boolean defaultForeground(Control control) {
    Color color = control.getForeground();
    return same(control, color, SWT.COLOR_WIDGET_FOREGROUND)
        || same(control, color, SWT.COLOR_LIST_FOREGROUND);
  }

  private static boolean same(Control control, Color color, int system) {
    return color.getRGB().equals(control.getDisplay().getSystemColor(system).getRGB());
  }
}
