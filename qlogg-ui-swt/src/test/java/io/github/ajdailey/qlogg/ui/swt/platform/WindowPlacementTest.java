package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.ui.swt.EventLoop;
import io.github.ajdailey.qlogg.ui.swt.Gtk4;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Each {@link WindowPlacement} class on any platform: win32 places again with the measured trim and
 * fits a shell into a work area, GTK 3 sets no location under Wayland, and only {@link
 * Gtk4WindowPlacement} maximizes a shell again after {@code open}.
 */
@Tag("swt")
class WindowPlacementTest {
  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void win32PlacesAgainWithTheMeasuredTrim() {
    shell.setMenuBar(new Menu(shell, SWT.BAR));
    final List<int[]> placed = new ArrayList<int[]>();
    new Win32WindowPlacement()
        .place(
            shell,
            new WindowPlacement.Placer() {
              @Override
              public void place(int trimWidth, int trimHeight, boolean positioned) {
                assertTrue(positioned);
                placed.add(new int[] {trimWidth, trimHeight});
                shell.setSize(400 + trimWidth, 300 + trimHeight);
              }
            });
    Point size = shell.getSize();
    int[] last = placed.get(placed.size() - 1);
    assertTrue(placed.size() <= 2, String.valueOf(placed.size()));
    assertEquals(size.x - shell.getClientArea().width, last[0]);
    assertEquals(size.y - shell.getClientArea().height, last[1]);
  }

  @Test
  void win32FitsAWindowBelowTheWorkAreaInsideIt() {
    shell.setBounds(114, 114, 600, 500);
    Win32WindowPlacement.fit(shell, new Rectangle(0, 0, 800, 400));
    Rectangle bounds = shell.getBounds();
    assertEquals(600, bounds.width);
    assertEquals(400, bounds.height);
    if (!Gtk4.active()) {
      assertEquals(new Rectangle(114, 0, 600, 400), bounds);
    }
    Win32WindowPlacement.fit(shell, new Rectangle(0, 0, 1600, 900));
    assertEquals(bounds, shell.getBounds());
  }

  @Test
  void gtkPositionsWindowsExceptUnderWayland() {
    final boolean[] positioned = new boolean[1];
    new GtkWindowPlacement()
        .place(
            shell,
            new WindowPlacement.Placer() {
              @Override
              public void place(int trimWidth, int trimHeight, boolean value) {
                positioned[0] = value;
              }
            });
    String backend = System.getProperty(PlatformTraits.GDK_BACKEND);
    assertEquals(!"wayland".equals(backend), positioned[0], backend);
  }

  @Test
  void onlyGtk4MaximizesAgainAfterOpen() {
    Shell probe = new Shell(display);
    probe.setSize(300, 200);
    probe.open();
    probe.setMaximized(true);
    assumeTrue(maximized(probe, true), "the platform did not maximize a shell");
    probe.dispose();
    shell.setSize(300, 200);
    shell.open();
    assertTrue(maximized(shell, false));
    WindowPlacement[] none = {
      new Win32WindowPlacement(), new NativeWindowPlacement(), new GtkWindowPlacement()
    };
    for (WindowPlacement placement : none) {
      placement.afterOpen(shell, true);
      assertFalse(shell.getMaximized(), placement.getClass().getSimpleName());
    }
    Gtk4WindowPlacement gtk4 = new Gtk4WindowPlacement();
    gtk4.afterOpen(shell, false);
    assertFalse(shell.getMaximized());
    gtk4.afterOpen(shell, true);
    assertTrue(maximized(shell, true), "Gtk4WindowPlacement maximizes a restored shell");
  }

  private boolean maximized(final Shell target, final boolean state) {
    return EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return target.getMaximized() == state;
          }
        },
        Duration.ofSeconds(5));
  }
}
