package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * {@link Win32FieldTheme}: clearing a field themed dark under the dark setting resolves and calls
 * every member it uses; fails after an SWT upgrade that removes one. {@code Win32ContextFieldTest}
 * reads the cleared field from the screen.
 */
@Tag("swt")
@EnabledOnOs(OS.WINDOWS)
class Win32FieldThemeTest {
  @Test
  void aFieldThemedDarkIsCleared() {
    Display display = new Display();
    try {
      display.setDarkThemePreferred(true);
      Shell shell = new Shell(display);
      Text text = new Text(shell, SWT.SINGLE | SWT.BORDER);
      text.setText("0");
      text.setBackground(Win32DialogColors.FIELD);
      display.setDarkThemePreferred(false);
      text.setBackground(null);
      assertTrue(Win32FieldTheme.clear(text));
      shell.dispose();
    } finally {
      display.setDarkThemePreferred(false);
      display.dispose();
    }
  }
}
