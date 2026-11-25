package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.ui.swt.EventLoop;
import io.github.ajdailey.qlogg.ui.swt.Gtk4;
import io.github.ajdailey.qlogg.ui.swt.XWindows;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
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
  private static final Duration BOUND = Duration.ofSeconds(10);

  /** SWT GTK's window trim cache, restored after each test: this shell records a 94 px width. */
  private static final Path TRIMS = Path.of(System.getProperty("user.home"), ".swt", "trims.prefs");

  private Display display;
  private Shell shell;
  private byte[] trims;

  @BeforeEach
  void open() throws IOException {
    trims = Files.isRegularFile(TRIMS) ? Files.readAllBytes(TRIMS) : null;
    display = new Display();
    shell = new Shell(display, SWT.SHELL_TRIM);
    shell.setText("qlogg-stacking-" + System.nanoTime());
    shell.setSize(300, 200);
  }

  @AfterEach
  void close() throws IOException {
    display.dispose();
    if (trims == null) {
      Files.deleteIfExists(TRIMS);
    } else {
      Files.write(TRIMS, trims);
    }
  }

  private static boolean x11() {
    return "x11".equals(System.getProperty(PlatformTraits.GDK_BACKEND));
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
    Shell dialog = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
    dialog.setSize(200, 100);
    dialog.open();
    Shell nested = new Shell(dialog, SWT.SHELL_TRIM);
    nested.open();
    assertEquals(3, cocoaLevel(dialog));
    assertEquals(3, cocoaLevel(nested));
    stacking.setOnTop(shell, false);
    assertEquals(0, cocoaLevel(dialog));
    assertEquals(0, cocoaLevel(nested));
    assertTrue(stacking.raised().isEmpty());
    assertTrue(stacking.supported());
  }

  /**
   * Each SWT member {@link CocoaStacking} reflects on, by name, so an upgrade names the one lost.
   */
  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaReflectedMembersExist() throws Exception {
    ClassLoader loader = Shell.class.getClassLoader();
    Class<?> nsWindow = Class.forName("org.eclipse.swt.internal.cocoa.NSWindow", false, loader);
    Class<?> id = Class.forName("org.eclipse.swt.internal.cocoa.id", false, loader);
    assertEquals(nsWindow, Shell.class.getDeclaredField("window").getType(), "Shell.window");
    assertNotNull(nsWindow.getMethod("setLevel", long.class), "setLevel");
    assertNotNull(nsWindow.getMethod("removeChildWindow", nsWindow), "removeChildWindow");
    assertNotNull(nsWindow.getMethod("addChildWindow", nsWindow, long.class), "addChildWindow");
    assertNotNull(nsWindow.getMethod("orderFront", id), "orderFront");
    assertTrue(new CocoaStacking().supported());
  }

  /**
   * The title bar's minimize button on a tool window took its owner off screen; the Iconify filter
   * brings the owner back and the tool window rejoins its children when restored.
   */
  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaKeepsTheOwnerWhenTheTitleBarMinimizesAToolWindow() throws Exception {
    CocoaStacking stacking = new CocoaStacking();
    shell.open();
    final Shell tool = new Shell(shell, SWT.SHELL_TRIM);
    tool.setSize(200, 120);
    tool.open();
    assumeTrue(occlusion(shell, true), "the owner never shows on screen (screen locked?)");
    cocoaSend(tool, "performMiniaturize:");
    awaitCocoa(tool, "isMiniaturized", true);
    awaitOcclusion(tool, false);
    awaitOcclusion(shell, true);
    assertEquals(0, cocoaParentId(tool), "out of the owner's children while minimized");
    tool.setMinimized(false);
    awaitCocoa(tool, "isMiniaturized", false);
    assertEquals(cocoaId(shell), cocoaParentId(tool), "a child window again once restored");
    assertTrue(stacking.supported());
  }

  /** Sends a one-argument selector, with a nil argument, to the shell's {@code NSWindow}. */
  private static void cocoaSend(Shell shell, String selector) throws ReflectiveOperationException {
    long id = cocoaId(shell);
    Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS");
    long sel = (Long) os.getMethod("sel_registerName", String.class).invoke(null, selector);
    os.getMethod("objc_msgSend", long.class, long.class, long.class).invoke(null, id, sel, 0L);
  }

  /** Minimize on a tool window took its owner off screen until the tool window was restored. */
  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaMinimizesAToolWindowWithoutItsOwner() throws Exception {
    CocoaStacking stacking = new CocoaStacking();
    shell.open();
    final Shell tool = new Shell(shell, SWT.SHELL_TRIM);
    tool.setSize(200, 120);
    tool.open();
    assertEquals(cocoaId(shell), cocoaParentId(tool), "a child window of its owner");
    assumeTrue(occlusion(shell, true), "the owner never shows on screen (screen locked?)");
    stacking.minimize(tool);
    awaitCocoa(tool, "isMiniaturized", true);
    // NSWindowOcclusionStateVisible: isVisible stays true while AppKit hides the owner.
    awaitOcclusion(tool, false);
    assertTrue(cocoaVisibleOnScreen(shell), "the owner stays on screen");
    assertFalse(cocoaFlag(shell, "isMiniaturized"));
    assertEquals(0, cocoaParentId(tool), "out of the owner's children while minimized");
    tool.setMinimized(false);
    awaitCocoa(tool, "isMiniaturized", false);
    assertEquals(cocoaId(shell), cocoaParentId(tool), "a child window again once restored");
    assertTrue(stacking.supported());
  }

  /** Waits for a no-argument boolean {@code NSWindow} method to return a value. */
  private void awaitCocoa(final Shell target, final String method, final boolean value) {
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                try {
                  return cocoaFlag(target, method) == value;
                } catch (ReflectiveOperationException e) {
                  throw new IllegalStateException(e);
                }
              }
            },
            BOUND),
        method + " " + value);
  }

  private void awaitOcclusion(Shell target, boolean visible) {
    assertTrue(occlusion(target, visible), "occlusion visible " + visible);
  }

  /** Waits for the window's on-screen state; false when it does not reach it in time. */
  private boolean occlusion(final Shell target, final boolean visible) {
    return EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try {
              return cocoaVisibleOnScreen(target) == visible;
            } catch (ReflectiveOperationException e) {
              throw new IllegalStateException(e);
            }
          }
        },
        BOUND);
  }

  /** Reads {@code NSWindowOcclusionStateVisible} from {@code occlusionState}. */
  private static boolean cocoaVisibleOnScreen(Shell shell) throws ReflectiveOperationException {
    long id = cocoaId(shell);
    Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS");
    long selector =
        (Long) os.getMethod("sel_registerName", String.class).invoke(null, "occlusionState");
    long state =
        (Long) os.getMethod("objc_msgSend", long.class, long.class).invoke(null, id, selector);
    return (state & 2) != 0;
  }

  private static Object cocoaWindow(Shell shell) throws ReflectiveOperationException {
    Field window = Shell.class.getDeclaredField("window");
    window.setAccessible(true);
    return window.get(shell);
  }

  private static long cocoaId(Shell shell) throws ReflectiveOperationException {
    Object nsWindow = cocoaWindow(shell);
    return nsWindow.getClass().getField("id").getLong(nsWindow);
  }

  /** Returns the id of the shell's parent {@code NSWindow}, or 0 with none. */
  private static long cocoaParentId(Shell shell) throws ReflectiveOperationException {
    Object nsWindow = cocoaWindow(shell);
    Object parent = nsWindow.getClass().getMethod("parentWindow").invoke(nsWindow);
    return parent == null ? 0 : parent.getClass().getField("id").getLong(parent);
  }

  private static boolean cocoaFlag(Shell shell, String method) throws ReflectiveOperationException {
    Object nsWindow = cocoaWindow(shell);
    return (Boolean) nsWindow.getClass().getMethod(method).invoke(nsWindow);
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
