package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/**
 * Colors of dialog and tool window surfaces, one implementation per platform from {@link
 * PlatformTraits#dialogColors}. {@code Dialogs.shell} calls {@link #apply} on each new shell.
 * Confined to the UI thread.
 */
public interface DialogColors {
  /**
   * Display data key: {@code Boolean.TRUE} while native controls are dark, set at startup and by a
   * theme switch on win32.
   */
  String NATIVE_DARK = DialogColors.class.getName() + ".nativeDark";

  /**
   * Reports whether native controls are dark, as {@link #NATIVE_DARK} records.
   *
   * @param display the Display
   * @return true when the theme service set the dark native theme
   */
  static boolean nativeDark(Display display) {
    return Boolean.TRUE.equals(display.getData(NATIVE_DARK));
  }

  /**
   * Returns the running platform's implementation.
   *
   * @return {@link PlatformTraits#dialogColors} of {@link PlatformTraits#PLATFORM}
   */
  static DialogColors platform() {
    return PlatformTraits.PLATFORM.dialogColors();
  }

  /**
   * Colors a dialog shell and every control created in it. Call right after creating the shell,
   * before its children.
   *
   * @param shell a dialog or tool window shell, never a main window
   */
  void apply(Shell shell);

  /**
   * Reports whether a control sits in a shell {@link #apply} colored.
   *
   * @param control the control
   * @return true for a control of a dialog created under a dark win32 start
   */
  boolean colored(Control control);

  /**
   * Returns the background a dialog opened over an owner shows.
   *
   * @param owner the owning shell
   * @return the dialog background
   */
  Color background(Shell owner);

  /**
   * Shows a dialog table's grid lines. Use in place of {@link Table#setLinesVisible}: win32 draws
   * native grid lines in the light {@code COLOR_3DFACE} under the dark native theme, so {@link
   * Win32DialogColors} hides them in a colored dialog and draws its own.
   *
   * @param table a table in a dialog shell
   */
  void gridLines(Table table);

  /**
   * Returns the text color for a disabled table row in a dialog.
   *
   * @param display the Display
   * @return the color
   */
  Color disabledText(Display display);
}
