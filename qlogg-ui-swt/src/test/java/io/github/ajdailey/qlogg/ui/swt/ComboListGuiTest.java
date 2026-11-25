package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The search row's read-only visibility combo under a dark start and after a switch to dark, and a
 * dialog's read-only combo under a dark start: each drop-down list reads dark on win32 x86-64; on
 * the {@code windows-11-arm} runner a screen read does not reach the window, so the read is only
 * logged there. The closed combo and the open list are saved to {@code
 * target/diagnostics/combo-list/}.
 */
@Tag("swt")
class ComboListGuiTest {
  private static final boolean WIN32 = "win32".equals(SWT.getPlatform());
  private static final Path DIAGNOSTICS = Path.of("target", "diagnostics", "combo-list");

  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;

  @AfterEach
  void close() {
    try {
      if (windows != null) {
        windows.close();
        assertTrue(windows.failures.isEmpty(), windows.failures.toString());
      }
    } finally {
      if (display != null && !display.isDisposed()) {
        display.setDarkThemePreferred(false);
        display.dispose();
      }
    }
  }

  @Test
  void theVisibilityListIsDarkUnderADarkStart() throws IOException {
    check(ThemeMode.DARK, "dark-start");
  }

  @Test
  void theVisibilityListIsDarkAfterASwitchToDark() throws IOException {
    check(ThemeMode.LIGHT, "light-to-dark");
  }

  /**
   * A read-only combo in a dialog under a dark start: {@code Win32DialogColors} gives it the field
   * colors, so its list reads dark. The list of an uncolored combo in a plain shell is logged as
   * the native drawing.
   */
  @Test
  void aDialogListIsDarkUnderADarkStart() throws IOException {
    display = new Display();
    windows = new SlotWindows(display, temp.resolve("config"), ThemeMode.DARK, null);
    windows.open(40, 40, 1000, 640);
    Shell owner = windows.slots.get(0).window().shell();
    windows.settle();
    Shell plain = new Shell(display, SWT.SHELL_TRIM);
    Shell dialog = Dialogs.shell(owner, "mock", true);
    try {
      RGB uncolored = list(plain, "dialog-uncolored");
      System.err.println("ComboListGuiTest dialog-uncolored: list " + uncolored);
      plain.dispose();
      RGB seen = list(dialog, "dialog");
      System.err.println("ComboListGuiTest dialog: list " + seen);
      if (WIN32 && !"aarch64".equals(System.getProperty("os.arch"))) {
        assertEquals(true, luminance(seen) < 0.2, "dialog: list " + seen);
      }
    } finally {
      plain.dispose();
      dialog.dispose();
    }
  }

  /** Opens a shell holding one read-only combo, shows its list and returns the list's color. */
  private RGB list(Shell shell, String name) {
    shell.setLayout(new FillLayout());
    Combo combo = new Combo(shell, SWT.READ_ONLY | SWT.DROP_DOWN);
    combo.setItems("mock one", "mock two", "mock three", "mock four");
    combo.select(0);
    shell.setBounds(80, 80, 320, 120);
    shell.open();
    shell.forceActive();
    windows.settle();
    Rectangle closed = display.map(combo.getParent(), null, combo.getBounds());
    save(name + "-closed", capture(closed));
    combo.setListVisible(true);
    try {
      windows.settle();
      Point below = new Point(closed.x + 8, closed.y + 2 * closed.height + 4);
      ImageData list = capture(new Rectangle(below.x, below.y, closed.width - 16, closed.height));
      save(name + "-list", list);
      return Pixels.mode(list);
    } finally {
      combo.setListVisible(false);
      windows.settle();
    }
  }

  private void check(ThemeMode start, String name) throws IOException {
    display = new Display();
    windows = new SlotWindows(display, temp.resolve("config"), start, null);
    final ApplicationController controller = windows.open(40, 40, 1000, 640);
    final MainWindowSlot slot = windows.slots.get(0);
    Path file = Files.writeString(temp.resolve("combo.log"), "mock line\n".repeat(40));
    controller.open(List.of(new FileRequest(file, 0)));
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.activeDocument() != null && slot.window().canvas() != null;
              }
            }),
        "document open");
    if (start == ThemeMode.LIGHT) {
      windows.switchTo(controller, true);
    }
    windows.settle();
    Combo combo = readOnly(slot.window().shell());
    assertNotNull(combo, "read-only combo");
    slot.window().shell().forceActive();
    windows.settle();
    Rectangle closed = display.map(combo.getParent(), null, combo.getBounds());
    save(name + "-closed", capture(closed));
    combo.setListVisible(true);
    try {
      windows.settle();
      // Past the first row, which shows the selection.
      Point below = new Point(closed.x + 8, closed.y + 2 * closed.height + 4);
      ImageData list = capture(new Rectangle(below.x, below.y, closed.width - 16, closed.height));
      save(name + "-list", list);
      RGB seen = Pixels.mode(list);
      System.err.println("ComboListGuiTest " + name + ": list " + seen);
      if (WIN32 && !"aarch64".equals(System.getProperty("os.arch"))) {
        assertEquals(true, luminance(seen) < 0.2, name + ": list " + seen);
      }
    } finally {
      combo.setListVisible(false);
      windows.settle();
    }
  }

  private static Combo readOnly(Control control) {
    if (control instanceof Combo combo && (combo.getStyle() & SWT.READ_ONLY) != 0) {
      return combo;
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        Combo found = readOnly(child);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
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

  private static double luminance(RGB rgb) {
    return (0.2126 * rgb.red + 0.7152 * rgb.green + 0.0722 * rgb.blue) / 255;
  }
}
