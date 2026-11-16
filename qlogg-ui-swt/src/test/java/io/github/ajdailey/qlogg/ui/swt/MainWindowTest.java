package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs under a display; the position assertions need X11 (Xvfb), not Wayland. */
@Tag("swt")
class MainWindowTest {
  private static final Duration BOUND = Duration.ofSeconds(10);
  private Display display;

  @BeforeEach
  void createDisplay() {
    display = new Display();
  }

  @AfterEach
  void disposeDisplay() {
    display.dispose();
  }

  @Test
  void opensAtTheRequestedClientSizeAndLocation() {
    MainWindow window = new MainWindow(display);
    window.place(new WindowGeometry.Request(50, 40, 900, 600), null);
    window.open();
    final Shell shell = window.shell();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                Rectangle area = shell.getClientArea();
                return shell.isVisible() && area.width == 900 && area.height == 600;
              }
            },
            BOUND),
        "client area " + shell.getClientArea());
    if (!Gtk4.active()) {
      assertEquals(new Point(50, 40), shell.getLocation(), Gtk4.NO_POSITION);
    }
    assertTrue(shell.getImages().length > 0);
    window.dispose();
    assertTrue(shell.isDisposed());
  }

  @Test
  void aRefusedClipboardWriteRetriesOnceThenShowsANotice() {
    MainWindow window = new MainWindow(display);
    window.open();
    final int[] writes = new int[1];
    final int[] refusals = {1};
    window.clipboardWriter(
        new MainWindow.ClipboardWriter() {
          @Override
          public void write(String text) {
            writes[0]++;
            if (refusals[0]-- > 0) {
              throw new SWTError(DND.ERROR_CANNOT_SET_CLIPBOARD);
            }
          }
        });
    window.setText("mock");
    assertEquals(2, writes[0]);
    assertEquals("", window.noticeText());
    refusals[0] = 2;
    window.setText("mock");
    assertEquals(4, writes[0]);
    assertEquals(UiText.NOTICE_COPY_FAILED.text(), window.noticeText());
    window.dispose();
  }

  @Test
  void anAmpersandShowsInNoticesAndErrorDialogs() {
    MainWindow window = new MainWindow(display);
    window.open();
    assertEquals(8000, MainWindow.NOTICE_MILLIS);
    window.showNotice("Cannot open a&b.log");
    assertEquals("Cannot open a&&b.log", window.noticeText());
    ErrorDialog dialog = new ErrorDialog(window.shell(), "Mock error", "Cannot open a&b.log");
    dialog.open();
    String shown = null;
    for (Control child : dialog.shell().getChildren()) {
      if (child instanceof Label label) {
        shown = label.getText();
      }
    }
    assertEquals("Cannot open a&&b.log", shown);
    dialog.shell().dispose();
    window.dispose();
  }

  @Test
  void hugeRequestClampsToTheWorkArea() {
    MainWindow window = new MainWindow(display);
    window.place(new WindowGeometry.Request(null, null, 100000, 100000), null);
    window.open();
    Rectangle work = display.getPrimaryMonitor().getClientArea();
    Point size = window.shell().getSize();
    assertTrue(size.x <= work.width && size.y <= work.height, size + " in " + work);
    window.dispose();
  }

  @Test
  void closeRequestGoesToTheHandler() {
    MainWindow window = new MainWindow(display);
    final int[] requests = new int[1];
    window.onCloseRequest(
        new Runnable() {
          @Override
          public void run() {
            requests[0]++;
          }
        });
    window.open();
    window.shell().close();
    assertEquals(1, requests[0]);
    assertFalse(window.shell().isDisposed());
    window.dispose();
  }

  @Test
  void dispatcherDropsAfterDisposal() {
    SwtUiDispatcher ui = new SwtUiDispatcher(display);
    final boolean[] ran = new boolean[1];
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            ran[0] = true;
          }
        });
    assertTrue(ui.isUiThread());
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return ran[0];
              }
            },
            BOUND));
    display.dispose();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            throw new AssertionError("ran after disposal");
          }
        });
    assertFalse(ui.isUiThread());
  }
}
