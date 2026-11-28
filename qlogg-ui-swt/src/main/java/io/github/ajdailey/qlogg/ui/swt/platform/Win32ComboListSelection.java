package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.slf4j.LoggerFactory;

/**
 * win32: under the dark native setting a drop-down combo's open list (the ComboLBox window),
 * read-only or editable, draws its selected row, which is also the hover row, in {@link
 * Palette#DARK}'s selection colors. Windows 11 draws that row white on white under SWT's dark list
 * theme, under a light or dark system setting (measured on build 26300), and neither {@code
 * SetWindowTheme} nor the {@code WM_CTLCOLORLISTBOX} answer changes it; {@code CBS_OWNERDRAWFIXED}
 * takes effect only at creation, inside SWT's {@code Combo}.
 *
 * <p>{@link #draw} subclasses the list window through {@link Win32ListBox}. The hook paints the
 * selected row over the native drawing after each {@code WM_PAINT} and {@code WM_TIMER}, after any
 * message that moves the selection (the list draws a move without one), and into the {@code
 * WM_PRINTCLIENT} DC: the opening slide renders the list through {@code WM_PRINTCLIENT} and shows
 * it without a {@code WM_PAINT} (measured on build 26300 at 150%). When the list shows, the hook
 * posts itself {@code SHOWN} and invalidates the list when it arrives, after the slide; a combo
 * {@code SWT.Selection} on the open list (a keyboard move) invalidates it too.
 *
 * <p>A dialog combo's side is fixed when {@link #draw} runs, as its dialog's colors are: in a
 * dialog {@link Win32DialogColors} colored it draws the row for its life; in a dialog created light
 * it draws the row in the system list selection colors ({@code SWT.COLOR_LIST_SELECTION}, {@code
 * #0078d7} with white text on Windows 11) while the native setting is dark, because the native list
 * takes the dark white-on-white row at a live switch. A main window combo follows the dark native
 * setting live, as the main window follows a theme switch. Under the light setting a combo outside
 * a colored dialog keeps the native drawing. A member that does not resolve logs one WARN and
 * leaves the native selection. Confined to the UI thread.
 */
final class Win32ComboListSelection implements ComboListSelection {
  /** Posted to the list when it shows; the hook repaints on it and keeps it from the list. */
  private static final int SHOWN = 0x8000 + 0x0f42;

  private final Win32ListBox.Lazy members =
      new Win32ListBox.Lazy(LoggerFactory.getLogger(Win32ComboListSelection.class));

  @Override
  public void draw(Combo combo) {
    if ((combo.getStyle() & SWT.SIMPLE) != 0) {
      return;
    }
    Side side = Side.LIVE;
    if (Win32DialogColors.INSTANCE.dialog(combo)) {
      side = Win32DialogColors.INSTANCE.colored(combo) ? Side.DARK : Side.LIGHT;
    }
    Win32ListBox.Members found = members.get();
    if (found == null) {
      return;
    }
    try {
      new ListHook(combo, found, side).install();
    } catch (ReflectiveOperationException | IllegalArgumentException e) {
      members.failed(e);
    }
  }

  /** The side a combo's list draws its selected row on. */
  private enum Side {
    /** Created in a colored dialog: {@link Palette#DARK}'s selection for life. */
    DARK,
    /** Created in a light dialog: the system list selection under the dark native setting. */
    LIGHT,
    /** In the main window: {@link Palette#DARK}'s selection under the dark native setting. */
    LIVE
  }

  /** One combo's subclassed list window. */
  private static final class ListHook extends Win32ListBox.Hook {
    private final Combo combo;

    private final Side side;

    private long painted = -1;
    private boolean shown;

    ListHook(Combo combo, Win32ListBox.Members os, Side side) {
      super(combo, os);
      this.combo = combo;
      this.side = side;
    }

    void install() throws ReflectiveOperationException {
      Object info = os.newInfo.newInstance();
      os.infoSize.setInt(info, os.infoSizeof);
      os.os(os.getComboBoxInfo, os.handle.getLong(combo), info);
      long list = os.hwndList.getLong(info);
      if (list == 0) {
        return;
      }
      subclass(list);
      combo.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (shown) {
                repaint();
              }
            }
          });
    }

    /** True for a colored dialog's combo; otherwise the native setting, read live. */
    @Override
    boolean dark() {
      return side == Side.DARK || DialogColors.nativeDark(combo.getDisplay());
    }

    @Override
    boolean consume(long msg) {
      if (msg == SHOWN) {
        repaint();
        return true;
      }
      return false;
    }

    @Override
    void after(long hwnd, long msg, long wParam, long selected)
        throws ReflectiveOperationException {
      boolean visible = os.os(os.visible, hwnd) != 0;
      if (msg == Win32ListBox.WM_PRINTCLIENT) {
        paint(hwnd, selected, wParam);
      } else if (msg == Win32ListBox.WM_PAINT
          || msg == Win32ListBox.WM_TIMER
          || selected != painted) {
        paintWindow(hwnd, selected);
      }
      if (visible && !shown) {
        os.os(os.post, hwnd, SHOWN, 0L, 0L);
      }
      shown = visible;
      painted = selected;
    }

    /**
     * Returns the system list selection colors for a light dialog's combo, otherwise {@link
     * Palette#DARK}'s selection and its text raised to {@link Contrast#LINK}.
     */
    @Override
    RGB[] colors(long hwnd) {
      if (side == Side.LIGHT) {
        Display display = combo.getDisplay();
        return new RGB[] {
          display.getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB(),
          display.getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT).getRGB()
        };
      }
      RGB background = rgb(Palette.DARK.selection());
      return new RGB[] {
        background, Contrast.readable(rgb(Palette.DARK.selectionText()), background)
      };
    }

    private static RGB rgb(Rgb color) {
      return new RGB(color.red(), color.green(), color.blue());
    }
  }
}
