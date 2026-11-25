package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
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
 * target/diagnostics/combo-selection/}. The colorings QLogg applies, {@code Win32ChromeColors}'s
 * palette and {@code Win32DialogColors}'s field, read at 4.5:1 or more on win32 x86-64; the {@code
 * windows-11-arm} runner's screen read does not reach the window, so it only logs.
 */
@Tag("swt")
class ComboSelectionGuiTest {
  private static final Path DIAGNOSTICS = Path.of("target", "diagnostics", "combo-selection");

  /** Colorings: name, background, foreground (null keeps the default); the first two ship. */
  private static final Object[][] COLORINGS = {
    {"palette", new RGB(0x1b, 0x2e, 0x44), new RGB(0xdc, 0xe6, 0xf0)},
    {"field", new RGB(0x2b, 0x2b, 0x2b), new RGB(0xff, 0xff, 0xff)},
    {"background-only", new RGB(0x1b, 0x2e, 0x44), null},
    {"foreground-only", null, new RGB(0xdc, 0xe6, 0xf0)},
    {"native", null, null},
    {"black-gray", new RGB(0, 0, 0), new RGB(0x76, 0x76, 0x76)},
  };

  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;

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
    windows = new SlotWindows(display, temp.resolve("config"), ThemeMode.DARK, null);
    windows.open(40, 40, 1000, 640);
    Shell owner = windows.slots.get(0).window().shell();
    windows.settle();
    for (int i = 0; i < COLORINGS.length; i++) {
      Object[] coloring = COLORINGS[i];
      boolean shipped = i < 2;
      for (int style : new int[] {SWT.READ_ONLY | SWT.DROP_DOWN, SWT.DROP_DOWN}) {
        String name = coloring[0] + ((style & SWT.READ_ONLY) != 0 ? "-readonly" : "-editable");
        Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
        try {
          measure(shell, style, (RGB) coloring[1], (RGB) coloring[2], name, shipped);
        } finally {
          shell.dispose();
          windows.settle();
        }
      }
    }
  }

  private void measure(
      Shell shell, int style, RGB background, RGB foreground, String name, boolean shipped) {
    shell.setLayout(new FillLayout());
    Combo combo = new Combo(shell, style);
    combo.setItems("mock one", "mock two", "mock three", "mock four");
    combo.select(0);
    if (background != null) {
      combo.setBackground(new Color(background));
    }
    if (foreground != null) {
      combo.setForeground(new Color(foreground));
    }
    shell.setBounds(120, 120, 320, 80);
    shell.open();
    shell.forceActive();
    combo.setFocus();
    windows.settle();
    Rectangle closed = display.map(combo.getParent(), null, combo.getBounds());
    int row = combo.getItemHeight();
    combo.setListVisible(true);
    try {
      windows.settle();
      int top = closed.y + closed.height + 1;
      save(name, capture(new Rectangle(closed.x, top, closed.width, 4 * row + 4)));
      ImageData selectedRow = capture(rowArea(closed, top, row, 0));
      ImageData otherRow = capture(rowArea(closed, top, row, 2));
      System.err.println(
          "ComboSelectionGuiTest "
              + name
              + ": selected "
              + describe(selectedRow)
              + "; unselected "
              + describe(otherRow));
      if (shipped && !"aarch64".equals(System.getProperty("os.arch"))) {
        assertTrue(contrast(selectedRow) >= 4.5, name + " selected: " + describe(selectedRow));
        assertTrue(contrast(otherRow) >= 4.5, name + " unselected: " + describe(otherRow));
      }
    } finally {
      combo.setListVisible(false);
      windows.settle();
    }
  }

  private static Rectangle rowArea(Rectangle closed, int top, int row, int index) {
    return new Rectangle(closed.x + 3, top + index * row + 1, closed.width - 24, row - 2);
  }

  private static double contrast(ImageData data) {
    RGB ground = Pixels.mode(data);
    return Pixels.contrast(ground, Pixels.strongest(data, ground));
  }

  private static String describe(ImageData data) {
    RGB ground = Pixels.mode(data);
    RGB ink = Pixels.strongest(data, ground);
    return String.format(
        "ground %s ink %s contrast %.2f", ground, ink, Pixels.contrast(ground, ink));
  }

  private ImageData capture(Rectangle area) {
    Image image = new Image(display, Math.max(1, area.width), Math.max(1, area.height));
    GC gc = new GC(display);
    try {
      gc.copyArea(image, area.x, area.y);
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static void save(String name, ImageData data) {
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {data};
    try {
      Files.createDirectories(DIAGNOSTICS);
      loader.save(DIAGNOSTICS.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
