package io.github.ajdailey.qlogg.ui.swt.platform;

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
import org.eclipse.swt.widgets.Tree;

/**
 * Keeps every shell readable after the system appearance changes on cocoa. AppKit redraws native
 * text in the new appearance at once, while SWT keeps the widget colors it read when the Display
 * was created and paints composites, tabs, lists and fields with them. So while the appearance
 * differs from the one at startup, each shell's background is the window color of the current
 * appearance, forced onto its children. That inheritance stops at a {@link TabFolder}, so check
 * boxes turn transparent over its native panel, its titles get the text color, and lists and text
 * fields get the list colors. Menus stay native, and a {@link Canvas} that paints from the palette
 * keeps its colors, as does a {@link CTabFolder} with {@link ChromeColors#PALETTE_COLORED} set.
 *
 * <p>Table and Tree headers take explicit colors of the current appearance on every change, never
 * null. Each control then receives {@link SWT#Settings}, since the Display sends none for an
 * appearance change. Confined to the UI thread.
 */
final class CocoaChrome {
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
    Color list = differs() ? (dark ? DARK_LIST : LIGHT_LIST) : null;
    Color text = differs() ? (dark ? DARK_TEXT : LIGHT_TEXT) : null;
    if (control instanceof CTabFolder tabs) {
      if (tabs.getData(ChromeColors.PALETTE_COLORED) == null) {
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
      table.setHeaderBackground(headerBackground(dark));
      table.setHeaderForeground(headerForeground(dark));
      // A Table repaints new header colors, the area right of the last column included, only when
      // its header is shown again; redraw() leaves that area stale.
      if (table.getHeaderVisible()) {
        table.setHeaderVisible(false);
        table.setHeaderVisible(true);
      }
    } else if (control instanceof Tree tree) {
      tree.setHeaderBackground(headerBackground(dark));
      tree.setHeaderForeground(headerForeground(dark));
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        walk(child);
      }
    }
    control.notifyListeners(SWT.Settings, new Event());
  }

  /**
   * Returns the header background of an appearance. A header always gets explicit colors: with null
   * colors cocoa draws blank labels in dark appearance and keeps the earlier header drawing.
   *
   * @param dark true for dark
   * @return the color, never null
   */
  static Color headerBackground(boolean dark) {
    return dark ? DARK_WINDOW : LIGHT_WINDOW;
  }

  /**
   * Returns the header text color of an appearance.
   *
   * @param dark true for dark
   * @return the color, never null
   */
  static Color headerForeground(boolean dark) {
    return dark ? DARK_TEXT : LIGHT_TEXT;
  }

  private static void recolor(CTabFolder tabs, Color list, Color text) {
    tabs.setForeground(text);
    tabs.setSelectionForeground(text);
    tabs.setSelectionBackground(list);
  }
}
