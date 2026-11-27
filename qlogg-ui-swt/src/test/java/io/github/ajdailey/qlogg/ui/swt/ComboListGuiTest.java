package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
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
 * The search row's read-only visibility combo under a dark start and after a switch each way, and a
 * dialog's read-only combo under a dark start beside one in a plain shell. On win32 x86-64 the open
 * list's unselected row reads at 4.5:1 or more, in the combo's colors where QLogg colors it, and
 * the selected row does under light; the selected row is logged in every case. On the {@code
 * windows-11-arm} runner a screen read does not reach the window, so the read is only logged there.
 * The closed field and the open list are saved to {@code target/diagnostics/combo-list/}.
 */
@Tag("swt")
class ComboListGuiTest {
  private static final boolean MEASURED =
      "win32".equals(SWT.getPlatform()) && !"aarch64".equals(System.getProperty("os.arch"));
  private static final Path DIAGNOSTICS = Path.of("target", "diagnostics", "combo-list");

  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;
  private ComboCapture capture;

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
  void theVisibilityListReadsUnderADarkStart() throws IOException {
    check(ThemeMode.DARK, null, "dark-start");
  }

  @Test
  void theVisibilityListReadsAfterASwitchToDark() throws IOException {
    check(ThemeMode.LIGHT, Boolean.TRUE, "light-to-dark");
  }

  @Test
  void theVisibilityListReadsAfterASwitchToLight() throws IOException {
    check(ThemeMode.DARK, Boolean.FALSE, "dark-to-light");
  }

  /** A read-only combo in a dialog under a dark start, beside one in a plain shell. */
  @Test
  void aDialogListReadsUnderADarkStart() throws IOException {
    display = new Display();
    capture = new ComboCapture(display, DIAGNOSTICS);
    windows = new SlotWindows(display, temp.resolve("config"), ThemeMode.DARK, null);
    windows.open(40, 40, 1000, 640);
    Shell owner = windows.slots.get(0).window().shell();
    windows.settle();
    Shell plain = new Shell(display, SWT.SHELL_TRIM);
    Shell dialog = Dialogs.shell(owner, "mock", true);
    try {
      measure(add(plain, "dialog-uncolored"), "dialog-uncolored", true, false);
      plain.dispose();
      measure(add(dialog, "dialog"), "dialog", true, true);
    } finally {
      plain.dispose();
      dialog.dispose();
    }
  }

  /** Opens a shell holding one read-only combo, logs its closed field and returns the combo. */
  private Combo add(Shell shell, String name) {
    shell.setLayout(new FillLayout());
    Combo combo = new Combo(shell, SWT.READ_ONLY | SWT.DROP_DOWN);
    combo.setItems("mock one", "mock two", "mock three", "mock four");
    combo.select(0);
    shell.setBounds(80, 80, 320, 120);
    ImageData field = capture.open(shell, combo);
    windows.settle();
    if (field != null) {
      capture.save(name + "-closed", field);
    }
    System.err.println(
        "ComboListGuiTest "
            + name
            + ": field "
            + (field == null ? "not seen" : Pixels.mode(field)));
    return combo;
  }

  private void check(ThemeMode start, Boolean switchTo, String name) throws IOException {
    display = new Display();
    capture = new ComboCapture(display, DIAGNOSTICS);
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
    if (switchTo != null) {
      windows.switchTo(controller, switchTo);
    }
    windows.settle();
    Combo combo = readOnly(slot.window().shell());
    assertNotNull(combo, "read-only combo");
    slot.window().shell().forceActive();
    windows.settle();
    ImageData field = capture.capture(combo, capture.field(combo));
    capture.save(name + "-closed", field);
    System.err.println("ComboListGuiTest " + name + ": field " + Pixels.mode(field));
    boolean dark = switchTo == null ? start == ThemeMode.DARK : switchTo;
    measure(combo, name, dark, dark);
  }

  /**
   * Saves the open list and logs both rows. On win32 x86-64 the unselected row reads at {@link
   * ComboCapture#READABLE}, on the combo's own background when {@code colored}; the selected row is
   * asserted only under light ({@code Win32ComboListTest} covers dark).
   */
  private void measure(Combo combo, String name, boolean dark, boolean colored) {
    int count = combo.getItemCount();
    int selected = Math.max(0, combo.getSelectionIndex());
    ImageData list = capture.openList(combo, capture.below(combo, count));
    try {
      if (list == null) {
        System.err.println("ComboListGuiTest " + name + ": list not seen");
        assertTrue(!MEASURED, name + ": list not seen");
        return;
      }
      capture.save(name + "-list", list);
      int row = combo.getItemHeight();
      ImageData selectedRow = ComboCapture.row(list, row, selected);
      ImageData otherRow = ComboCapture.row(list, row, (selected + 1) % count);
      System.err.println(
          "ComboListGuiTest "
              + name
              + ": selected "
              + ComboCapture.describe(selectedRow)
              + "; unselected "
              + ComboCapture.describe(otherRow));
      if (MEASURED) {
        assertTrue(
            ComboCapture.contrast(otherRow) >= ComboCapture.READABLE,
            name + " unselected: " + ComboCapture.describe(otherRow));
        if (colored) {
          assertEquals(
              combo.getBackground().getRGB(), Pixels.mode(otherRow), name + " list background");
        }
        if (!dark) {
          assertTrue(
              ComboCapture.contrast(selectedRow) >= ComboCapture.READABLE,
              name + " selected: " + ComboCapture.describe(selectedRow));
        }
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
}
