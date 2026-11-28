package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.widgets.Text;
import org.slf4j.LoggerFactory;

/**
 * win32: removes the dark theme from a {@link Text} in a window built under the light native
 * setting. SWT 3.135 gives a text field {@code DarkMode_Explorer} whenever a color is set on it
 * under the dark setting and never removes it; unfocused and holding text, the themed field draws
 * {@code #212121} under light colors or none (measured on Windows 11). {@link Win32ChromeColors}
 * calls {@link #clear} on such a field when the window, waiting for its rebuild, returns to light.
 * The members come from {@link Win32ListBox#resolve}; one that does not resolve logs one WARN and
 * leaves the theme. Confined to the UI thread.
 */
final class Win32FieldTheme {
  private static final Win32ListBox.Lazy MEMBERS =
      new Win32ListBox.Lazy(LoggerFactory.getLogger(Win32FieldTheme.class));

  private Win32FieldTheme() {}

  /**
   * Clears the field's window theme and redraws it.
   *
   * @param text the field
   * @return true when the theme was cleared, false when a member did not resolve
   */
  static boolean clear(Text text) {
    Win32ListBox.Members os = MEMBERS.get();
    if (os == null) {
      return false;
    }
    try {
      os.clearTheme(os.handle.getLong(text));
    } catch (ReflectiveOperationException | IllegalArgumentException e) {
      MEMBERS.failed(e);
      return false;
    }
    text.redraw();
    return true;
  }
}
