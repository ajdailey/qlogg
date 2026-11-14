package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The main window's busy cursor over its shell and the shells it owns, read through {@code
 * getCursor}: screenshots do not show the pointer.
 */
@Tag("swt")
class WindowBusyCursorTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  private Display display;
  private MainWindow window;
  private Cursor busy;

  @BeforeEach
  void open() {
    display = new Display();
    window = new MainWindow(display);
    window.place(new WindowGeometry.Request(null, null, 600, 400), null);
    window.open();
    busy = display.getSystemCursor(PlatformTraits.PLATFORM.busyCursor());
  }

  @AfterEach
  void close() {
    window.dispose();
    display.dispose();
  }

  private Shell dialog(Shell owner, boolean modal) {
    Shell shell = Dialogs.shell(owner, "Mock", modal);
    shell.setSize(200, 120);
    shell.open();
    drain();
    return shell;
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private void count(final int expected) {
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.busy().active() == expected;
              }
            },
            BOUND));
    drain();
  }

  @Test
  void busyReachesOwnedShellsAndIdleClearsThem() {
    Shell tool = dialog(window.shell(), false);
    Shell nested = dialog(tool, true);
    Label link = new Label(tool, SWT.NONE);
    Cursor hand = display.getSystemCursor(SWT.CURSOR_HAND);
    link.setCursor(hand);

    window.setActive();
    count(1);
    assertEquals(busy, window.shell().getCursor());
    assertEquals(busy, tool.getCursor());
    assertEquals(busy, nested.getCursor());
    assertEquals(hand, link.getCursor(), "a control with its own cursor keeps it");

    window.setIdle();
    count(0);
    assertNull(window.shell().getCursor());
    assertNull(tool.getCursor());
    assertNull(nested.getCursor());
    assertEquals(hand, link.getCursor());
  }

  @Test
  void aShellOpenedWhileBusyStartsBusyUntilIdle() {
    window.setActive();
    count(1);
    Shell tool = dialog(window.shell(), false);
    Shell other = dialog(null, false);
    assertEquals(busy, tool.getCursor());
    assertNull(other.getCursor(), "a shell the window does not own");

    Shell closed = dialog(window.shell(), true);
    closed.dispose();
    window.setIdle();
    count(0);
    assertNull(tool.getCursor());
    Shell later = dialog(window.shell(), false);
    assertNull(later.getCursor(), "idle: the Show filter is removed");
    other.dispose();
  }
}
