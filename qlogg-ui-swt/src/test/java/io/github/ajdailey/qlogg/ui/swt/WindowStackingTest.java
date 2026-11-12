package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Each platform's {@link WindowStacking}: the SWT internals it reflects on exist in this SWT, and a
 * toggle reaches the window. A failing resolution test after an SWT upgrade names the platform
 * class to update. Runs on its own platform; win32 and cocoa run in CI.
 */
@Tag("swt")
class WindowStackingTest {
  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display, SWT.SHELL_TRIM);
    shell.setText("qlogg-stacking-" + System.nanoTime());
    shell.setSize(300, 200);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  private static boolean x11() {
    return "x11".equals(System.getProperty(MainWindow.GDK_BACKEND));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void gtk3MembersResolve() {
    assertTrue(new GtkX11Stacking().supported());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void gtk3OnX11IsChosen() {
    assumeTrue(!Gtk4.active() && x11(), "GTK 3 on X11");
    assertTrue(WindowStacking.forPlatform() instanceof GtkX11Stacking);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void gtk4AndWaylandAreUnsupportedWithTheWindowManagerMenu() {
    assumeTrue(Gtk4.active() || !x11(), "GTK 4 or Wayland");
    WindowStacking stacking = WindowStacking.forPlatform();
    assertSame(WindowStacking.WINDOW_MANAGER, stacking);
    assertFalse(stacking.supported());
    assertTrue(stacking.windowManagerMenu());
  }

  /** GTK writes {@code _NET_WM_STATE_ABOVE} as it maps the window; Xvfb has no window manager. */
  @Test
  @EnabledOnOs(OS.LINUX)
  void gtk3OnX11SetsTheAboveStateEachWay() {
    assumeTrue(!Gtk4.active() && x11(), "GTK 3 on X11");
    WindowStacking stacking = new GtkX11Stacking();
    stacking.setOnTop(shell, true);
    shell.open();
    XWindows.awaitAbove(display, shell, true);
    shell.setVisible(false);
    stacking.setOnTop(shell, false);
    shell.setVisible(true);
    XWindows.awaitAbove(display, shell, false);
    shell.setVisible(false);
    stacking.setOnTop(shell, true);
    shell.setVisible(true);
    XWindows.awaitAbove(display, shell, true);
    assertTrue(stacking.supported());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void win32MembersResolveAndATogglesSetsTheTopmostStyle() throws Exception {
    Win32Stacking stacking = new Win32Stacking();
    assertTrue(stacking.supported());
    shell.open();
    stacking.setOnTop(shell, true);
    assertTrue(win32Topmost(shell));
    stacking.setOnTop(shell, false);
    assertFalse(win32Topmost(shell));
    assertTrue(stacking.supported());
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaMembersResolveAndATogglesSetsTheWindowLevel() throws Exception {
    CocoaStacking stacking = new CocoaStacking();
    assertTrue(stacking.supported());
    shell.open();
    stacking.setOnTop(shell, true);
    assertEquals(3, cocoaLevel(shell));
    stacking.setOnTop(shell, false);
    assertEquals(0, cocoaLevel(shell));
    assertTrue(stacking.supported());
  }

  /** A dialog opened under a floating window floats with it and returns to normal with it. */
  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaRaisesADialogOpenedWhileOnTop() throws Exception {
    CocoaStacking stacking = new CocoaStacking();
    shell.open();
    stacking.setOnTop(shell, true);
    Shell dialog = Dialogs.shell(shell, "qlogg-dialog", true);
    dialog.setSize(200, 100);
    dialog.open();
    Shell nested = Dialogs.shell(dialog, "qlogg-nested", false);
    nested.open();
    assertEquals(3, cocoaLevel(dialog));
    assertEquals(3, cocoaLevel(nested));
    stacking.setOnTop(shell, false);
    assertEquals(0, cocoaLevel(dialog));
    assertEquals(0, cocoaLevel(nested));
    assertTrue(stacking.raised().isEmpty());
    assertTrue(stacking.supported());
  }

  /** Reads the shell's {@code NSWindow} level through {@code objc_msgSend}. */
  private static long cocoaLevel(Shell shell) throws ReflectiveOperationException {
    Field window = Shell.class.getDeclaredField("window");
    window.setAccessible(true);
    Object nsWindow = window.get(shell);
    long id = nsWindow.getClass().getField("id").getLong(nsWindow);
    Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS");
    long selector = (Long) os.getMethod("sel_registerName", String.class).invoke(null, "level");
    return (Long) os.getMethod("objc_msgSend", long.class, long.class).invoke(null, id, selector);
  }

  /** Reads {@code WS_EX_TOPMOST} from the window's extended style. */
  private static boolean win32Topmost(Shell shell) throws ReflectiveOperationException {
    Class<?> os = Class.forName("org.eclipse.swt.internal.win32.OS");
    Method getWindowLong = os.getMethod("GetWindowLong", long.class, int.class);
    int exStyle = os.getField("GWL_EXSTYLE").getInt(null);
    int topmost = os.getField("WS_EX_TOPMOST").getInt(null);
    long handle = Control.class.getField("handle").getLong(shell);
    int style = (Integer) getWindowLong.invoke(null, handle, exStyle);
    return (style & topmost) != 0;
  }
}
