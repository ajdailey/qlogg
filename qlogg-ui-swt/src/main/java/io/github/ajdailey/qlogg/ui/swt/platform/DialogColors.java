package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Colors of dialog and tool window surfaces, one implementation per platform. {@code Dialogs.shell}
 * calls {@link #prepare} on each new shell and {@code Dialogs.open} calls {@link #apply} before it
 * shows. Confined to the UI thread.
 */
public interface DialogColors {
  /** Display data key holding the Display's instance. */
  String KEY = DialogColors.class.getName();

  /** Display data key: {@code Boolean.TRUE} when native controls started dark. */
  String NATIVE_DARK = DialogColors.class.getName() + ".nativeDark";

  /** Shell data key: set on a shell whose surfaces {@link Win32DialogColors} colors. */
  String COLORED = DialogColors.class.getName() + ".colored";

  /**
   * Returns the Display's instance: {@link Win32DialogColors} on win32 when native controls started
   * dark, {@link NativeDialogColors} otherwise.
   *
   * @param display the Display
   * @return the instance, created on first use
   */
  static DialogColors of(Display display) {
    Object held = display.getData(KEY);
    if (held instanceof DialogColors colors) {
      return colors;
    }
    DialogColors colors = PlatformTraits.PLATFORM.dialogColors(display);
    display.setData(KEY, colors);
    return colors;
  }

  /**
   * Reports whether native controls started dark, as {@link #NATIVE_DARK} records.
   *
   * @param display the Display
   * @return true when the theme service set the dark native theme at startup
   */
  static boolean nativeDark(Display display) {
    return Boolean.TRUE.equals(display.getData(NATIVE_DARK));
  }

  /**
   * Returns whether a shell's surfaces take {@link Win32DialogColors}.
   *
   * @param shell the shell
   * @return true for a dialog prepared under a dark win32 start
   */
  static boolean colored(Shell shell) {
    return Boolean.TRUE.equals(shell.getData(COLORED));
  }

  /**
   * Sets a new dialog shell's colors before its controls exist.
   *
   * @param shell the shell
   */
  void prepare(Shell shell);

  /**
   * Colors a control of a prepared shell and its descendants. Colors the application set keep.
   *
   * @param control the control
   */
  void apply(Control control);

  /**
   * Returns the background a dialog opened over an owner shows.
   *
   * @param owner the owning shell
   * @return the dialog background
   */
  Color background(Shell owner);

  /**
   * Returns the text color for a disabled table row in a dialog.
   *
   * @param display the Display
   * @return the color
   */
  Color disabledText(Display display);
}
