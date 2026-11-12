package io.github.ajdailey.qlogg.ui.swt;

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
import org.eclipse.swt.widgets.Tree;

/**
 * Dark dialog surfaces on win32 when native controls started dark. The dark start draws the title
 * bar, push buttons and read-only combos dark and leaves every other surface light, so a dialog
 * body takes the title bar's color and fields a lighter one. Measured with SWT 3.135.0 on {@code
 * windows-2025}: the title bar draws {@code #202020} and a push button face {@code #333333}; {@code
 * setBackground} and {@code setForeground} reach composites, labels, links, check boxes, groups,
 * tab pages, tool bars, text fields, combos, spinners, lists and tables. A {@code TabFolder}'s tab
 * row, a table's grid lines and header dividers, a spinner's arrows, disabled text and a {@link
 * Browser} stay native.
 *
 * <p>A control is colored when {@link Dialogs#open} walks the shell and again at its {@code
 * SWT.Skin} event, so controls added after the dialog opens match. Only a default color is
 * replaced: a color the application set stays. The colors follow the startup setting, as win32
 * native controls do, so a theme change shows at the next start in open and new dialogs alike.
 */
final class Win32DialogColors implements DialogColors {
  /** Dialog background: the dark title bar. */
  static final Color BODY = new Color(0x20, 0x20, 0x20);

  /** Field and list background, between the body and the {@code #333333} push button face. */
  static final Color FIELD = new Color(0x2b, 0x2b, 0x2b);

  /** Text, as on the dark push buttons. */
  static final Color TEXT = new Color(0xff, 0xff, 0xff);

  /** Link text: 12.2 contrast on {@link #BODY}. */
  static final Color LINK = new Color(0x99, 0xeb, 0xff);

  /** Disabled table row text: 5.0 contrast on {@link #FIELD}. */
  static final Color DISABLED = new Color(0x9a, 0x9a, 0x9a);

  private final Display display;

  /**
   * Creates the colors and colors each new control of a prepared shell at its {@code SWT.Skin}
   * event.
   *
   * @param display the Display
   */
  Win32DialogColors(Display display) {
    this.display = display;
    display.addListener(
        SWT.Skin,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget instanceof Control control
                && !control.isDisposed()
                && DialogColors.colored(control.getShell())) {
              color(control);
            }
          }
        });
  }

  @Override
  public void prepare(Shell shell) {
    shell.setData(COLORED, Boolean.TRUE);
    shell.setBackground(BODY);
    shell.setForeground(TEXT);
    shell.setBackgroundMode(SWT.INHERIT_DEFAULT);
  }

  @Override
  public void apply(Control control) {
    if (!DialogColors.colored(control.getShell())) {
      return;
    }
    color(control);
    if (control instanceof Composite composite && !(control instanceof Browser)) {
      for (Control child : composite.getChildren()) {
        apply(child);
      }
    }
  }

  @Override
  public Color background(Shell owner) {
    return BODY;
  }

  @Override
  public Color disabledText(Display display) {
    return DISABLED;
  }

  private void color(Control control) {
    if (control instanceof Browser || control instanceof Shell) {
      return;
    }
    if (control instanceof Button && (control.getStyle() & SWT.PUSH) != 0) {
      return;
    }
    if (control instanceof Combo && (control.getStyle() & SWT.READ_ONLY) != 0) {
      return;
    }
    boolean field = field(control);
    if (field) {
      if (defaultBackground(control.getBackground())) {
        control.setBackground(FIELD);
      }
    } else if (light(control.getBackground())) {
      control.setBackground(BODY);
    }
    if (defaultForeground(control.getForeground())) {
      control.setForeground(TEXT);
      if (control instanceof Link link) {
        link.setLinkForeground(LINK);
      }
    }
    if (control instanceof Table table) {
      table.setHeaderBackground(FIELD);
      table.setHeaderForeground(TEXT);
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

  /** True for the window color a surface reports before it is colored. */
  private boolean light(Color color) {
    return same(color, SWT.COLOR_WIDGET_BACKGROUND);
  }

  /** True for a field color the application did not set. */
  private boolean defaultBackground(Color color) {
    RGB rgb = color.getRGB();
    return same(color, SWT.COLOR_LIST_BACKGROUND)
        || same(color, SWT.COLOR_WIDGET_BACKGROUND)
        || rgb.equals(BODY.getRGB());
  }

  /** True for a text color the application did not set. */
  private boolean defaultForeground(Color color) {
    return same(color, SWT.COLOR_WIDGET_FOREGROUND) || same(color, SWT.COLOR_LIST_FOREGROUND);
  }

  private boolean same(Color color, int system) {
    return color.getRGB().equals(display.getSystemColor(system).getRGB());
  }
}
