package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;

/**
 * Keeps every shell readable after the system appearance changes on cocoa. AppKit redraws native
 * text in the new appearance at once, while SWT keeps the widget colors it read when the Display
 * was created and paints composites, tabs, lists and fields with them. So while the appearance
 * differs from the one at startup, each shell's background is the window color of the current
 * appearance, forced onto its children. That inheritance stops at a {@link TabFolder}, so check
 * boxes turn transparent over its native panel, its titles get the text color, and lists and text
 * fields get the list colors. Menus stay native, and a {@link Canvas} that paints from the palette
 * keeps its colors, as does a {@link CTabFolder} with {@link #PALETTE_COLORED} set.
 *
 * <p>Each control then receives {@link SWT#Settings}, since the Display sends none for an
 * appearance change. Confined to the UI thread.
 */
final class CocoaChrome {
  /** Control data key: the owner colors this {@link CTabFolder} from the palette. */
  static final String PALETTE_COLORED = CocoaChrome.class.getName() + ".paletteColored";

  /** {@code COLOR_WIDGET_BACKGROUND} and {@code COLOR_LIST_BACKGROUND}, measured on macOS 15. */
  private static final Color DARK_WINDOW = new Color(0x32, 0x32, 0x32);

  private static final Color DARK_LIST = new Color(0x1e, 0x1e, 0x1e);
  private static final Color DARK_TEXT = new Color(0xff, 0xff, 0xff);
  private static final Color LIGHT_WINDOW = new Color(0xec, 0xec, 0xec);
  private static final Color LIGHT_LIST = new Color(0xff, 0xff, 0xff);
  private static final Color LIGHT_TEXT = new Color(0, 0, 0);
  private static final Color CLEAR = new Color(0, 0, 0, 0);

  private final Display display;
  private final boolean startDark;
  private boolean dark;

  /**
   * Creates the chrome and covers each shell as it is shown.
   *
   * @param display the Display
   * @param startDark the appearance SWT read its widget colors under
   */
  CocoaChrome(Display display, boolean startDark) {
    this.display = display;
    this.startDark = startDark;
    this.dark = startDark;
    display.addFilter(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget instanceof Shell shell && differs()) {
              restyle(shell);
            }
          }
        });
  }

  /**
   * Applies an appearance to every shell.
   *
   * @param value true for dark
   */
  void apply(boolean value) {
    dark = value;
    for (Shell shell : display.getShells()) {
      restyle(shell);
    }
  }

  private boolean differs() {
    return dark != startDark;
  }

  private void restyle(Shell shell) {
    shell.setBackgroundMode(differs() ? SWT.INHERIT_FORCE : SWT.INHERIT_NONE);
    shell.setBackground(differs() ? (dark ? DARK_WINDOW : LIGHT_WINDOW) : null);
    walk(shell);
  }

  private void walk(Control control) {
    Color window = differs() ? (dark ? DARK_WINDOW : LIGHT_WINDOW) : null;
    Color list = differs() ? (dark ? DARK_LIST : LIGHT_LIST) : null;
    Color text = differs() ? (dark ? DARK_TEXT : LIGHT_TEXT) : null;
    if (control instanceof CTabFolder tabs) {
      if (tabs.getData(PALETTE_COLORED) == null) {
        recolor(tabs, list, text);
      }
    } else if (control instanceof Table
        || control instanceof List
        || control instanceof Combo
        || control instanceof Text
        || control instanceof Spinner
        || control instanceof StyledText) {
      control.setBackground(list);
      control.setForeground(text);
    } else if (control instanceof TabFolder) {
      control.setForeground(text);
    } else if (control instanceof Button && (control.getStyle() & SWT.CHECK) != 0) {
      control.setBackground(CLEAR);
    }
    if (control instanceof Table table) {
      table.setHeaderBackground(window);
      table.setHeaderForeground(text);
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        walk(child);
      }
    }
    control.notifyListeners(SWT.Settings, new Event());
  }

  private static void recolor(CTabFolder tabs, Color list, Color text) {
    tabs.setForeground(text);
    tabs.setSelectionForeground(text);
    tabs.setSelectionBackground(list);
  }
}
