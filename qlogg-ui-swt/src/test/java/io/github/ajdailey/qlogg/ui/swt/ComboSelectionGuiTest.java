package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * win32 under a dark start: the selected and an unselected row of a read-only and an editable
 * combo's open list, for each coloring below, logged with their text contrast and saved to {@code
 * target/diagnostics/combo-selection/}. Every coloring is measured before the assertions. The
 * unselected row of the two colorings QLogg ships reads at 4.5:1 or more on win32 x86-64. The
 * selected row is only logged: under dark at a fractional scale Windows draws it white on white
 * whatever the combo's colors. The {@code windows-11-arm} runner's screen read does not reach the
 * window, so it only logs.
 */
@Tag("swt")
class ComboSelectionGuiTest {
  private static final Path DIAGNOSTICS = Path.of("target", "diagnostics", "combo-selection");

  /**
   * Colorings: name, background, foreground (null keeps the default). The first two ship: {@code
   * Win32ChromeColors}'s palette and {@code Win32DialogColors}'s field. {@code native} is an
   * uncolored combo and {@code light-created} one created while the dark theme is not preferred.
   */
  private static final Object[][] COLORINGS = {
    {"palette", new RGB(0x1b, 0x2e, 0x44), new RGB(0xdc, 0xe6, 0xf0)},
    {"field", new RGB(0x2b, 0x2b, 0x2b), new RGB(0xff, 0xff, 0xff)},
    {"native", null, null},
    {"light-created", null, null},
  };

  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;
  private ComboCapture capture;
  private final List<String> failures = new ArrayList<String>();

  @AfterEach
  void close() {
    try {
      if (windows != null) {
        windows.close();
      }
    } finally {
      if (display != null && !display.isDisposed()) {
        display.setDarkThemePreferred(false);
        display.dispose();
      }
    }
  }

  @Test
  void selectedRowsUnderADarkStart() throws IOException {
    assumeTrue("win32".equals(SWT.getPlatform()), "win32 measurement");
    display = new Display();
    capture = new ComboCapture(display, DIAGNOSTICS);
    windows = new SlotWindows(display, temp.resolve("config"), ThemeMode.DARK, null);
    windows.open(40, 40, 1000, 640);
    Shell owner = windows.slots.get(0).window().shell();
    windows.settle();
    for (int i = 0; i < COLORINGS.length; i++) {
      Object[] coloring = COLORINGS[i];
      for (int style : new int[] {SWT.READ_ONLY | SWT.DROP_DOWN, SWT.DROP_DOWN}) {
        String name = coloring[0] + ((style & SWT.READ_ONLY) != 0 ? "-readonly" : "-editable");
        Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
        try {
          measure(shell, style, coloring, name, i < 2);
        } finally {
          shell.dispose();
          windows.settle();
        }
      }
    }
    assertTrue(failures.isEmpty(), String.join("; ", failures));
  }

  private void measure(Shell shell, int style, Object[] coloring, String name, boolean shipped) {
    shell.setLayout(new FillLayout());
    Combo combo = create(shell, style, "light-created".equals(coloring[0]));
    combo.setItems("mock one", "mock two", "mock three", "mock four");
    combo.select(0);
    if (coloring[1] != null) {
      combo.setBackground(new Color((RGB) coloring[1]));
    }
    if (coloring[2] != null) {
      combo.setForeground(new Color((RGB) coloring[2]));
    }
    shell.setBounds(120, 120, 320, 80);
    ImageData closed = capture.open(shell, combo);
    windows.settle();
    int row = combo.getItemHeight();
    if (closed == null) {
      System.err.println("ComboSelectionGuiTest " + name + ": closed field not seen");
    } else {
      capture.save(name + "-closed", closed);
      System.err.println("ComboSelectionGuiTest " + name + ": field " + Pixels.mode(closed));
    }
    ImageData list = capture.openList(combo, capture.below(combo, 4));
    try {
      boolean measured = shipped && !"aarch64".equals(System.getProperty("os.arch"));
      if (list == null) {
        System.err.println("ComboSelectionGuiTest " + name + ": list not seen");
        if (measured) {
          failures.add(name + ": list not seen");
        }
        return;
      }
      capture.save(name, list);
      ImageData selectedRow = ComboCapture.row(list, row, 0);
      ImageData otherRow = ComboCapture.row(list, row, 2);
      System.err.println(
          "ComboSelectionGuiTest "
              + name
              + ": selected "
              + ComboCapture.describe(selectedRow)
              + "; unselected "
              + ComboCapture.describe(otherRow));
      if (measured && ComboCapture.contrast(otherRow) < ComboCapture.READABLE) {
        failures.add(name + " unselected: " + ComboCapture.describe(otherRow));
      }
    } finally {
      combo.setListVisible(false);
      windows.settle();
    }
  }

  private Combo create(Shell shell, int style, boolean light) {
    if (!light) {
      return new Combo(shell, style);
    }
    display.setDarkThemePreferred(false);
    try {
      return new Combo(shell, style);
    } finally {
      display.setDarkThemePreferred(true);
    }
  }
}
