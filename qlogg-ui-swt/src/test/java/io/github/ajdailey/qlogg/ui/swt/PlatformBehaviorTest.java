package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.ui.swt.platform.DialogColors;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qlogg.ui.swt.platform.ToolItemSizing;
import io.github.ajdailey.qlogg.ui.swt.platform.WindowRebuild;
import io.github.ajdailey.qlogg.ui.swt.platform.WindowStacking;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What each view-layer platform branch does on the running platform, with the expected value worked
 * out here from {@link SWT#getPlatform} and the GTK version SWT loaded, so a run on each CI
 * platform pins its own row.
 */
@Tag("swt")
class PlatformBehaviorTest {
  private static final String PLATFORM = SWT.getPlatform();
  private static final boolean WIN32 = "win32".equals(PLATFORM);
  private static final boolean COCOA = "cocoa".equals(PLATFORM);

  private Display display;

  @BeforeEach
  void open() {
    display = new Display();
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void monospaceDefaults() {
    assertEquals(WIN32 ? "Consolas" : COCOA ? "Menlo" : "Monospace", Dialogs.monospaceName());
    assertEquals(COCOA ? 12 : 10, Dialogs.monospaceSize());
  }

  @Test
  void macKeysAndApplicationMenu() {
    assertEquals(COCOA, SwtKeys.mac());
    assertEquals(COCOA, !PlatformTraits.PLATFORM.menus().systemCommands(display).isEmpty());
  }

  @Test
  void mainWindowAnswers() {
    MainWindow window = new MainWindow(display);
    try {
      assertTrue(
          window.toolkitDescription().startsWith("SWT " + SWT.getVersion() + " " + PLATFORM),
          window.toolkitDescription());
    } finally {
      window.dispose();
    }
  }

  @Test
  void aSwitchRebuildsTheWindowsOnWin32OnlyAndRecordsTheSideOnWin32AndCocoa() {
    final int[] rebuilds = {0};
    try {
      SwtThemeService service = SwtThemeService.open(display, ThemeMode.LIGHT);
      service.setWindows(
          new WindowRebuild() {
            @Override
            public void rebuild() {
              rebuilds[0]++;
            }
          });
      service.preferDark(true);
      service.preferDark(true);
      assertEquals(WIN32 ? 1 : 0, rebuilds[0]);
      assertEquals(WIN32 || COCOA, DialogColors.nativeDark(display));
    } finally {
      display.setDarkThemePreferred(false);
      display.setData(DialogColors.NATIVE_DARK, null);
    }
  }

  @Test
  void systemModeSetsNativeThemeAtStartOnWin32AndCocoa() {
    boolean system = Display.isSystemDarkTheme();
    try {
      SwtThemeService.open(display, ThemeMode.SYSTEM);
      assertEquals(
          WIN32 || COCOA ? Boolean.valueOf(system) : null,
          display.getData(DialogColors.NATIVE_DARK));
    } finally {
      display.setDarkThemePreferred(false);
      display.setData(DialogColors.NATIVE_DARK, null);
    }
  }

  @Test
  void darkModeSetsNativeThemeOnEveryPlatform() {
    try {
      SwtThemeService.open(display, ThemeMode.DARK);
      assertTrue(DialogColors.nativeDark(display));
      assertEquals(
          WIN32 ? "Win32DialogColors" : "NativeDialogColors",
          DialogColors.of(display).getClass().getSimpleName());
    } finally {
      display.setDarkThemePreferred(false);
    }
  }

  @Test
  void toolItemSizingAndStacking() {
    assertEquals(
        Gtk4.active() ? "Gtk4ToolItemSizing" : "NativeToolItemSizing",
        ToolItemSizing.platform().getClass().getSimpleName());
    WindowStacking stacking = WindowStacking.forPlatform();
    if (WIN32) {
      assertEquals("Win32Stacking", stacking.getClass().getSimpleName());
    } else if (COCOA) {
      assertEquals("CocoaStacking", stacking.getClass().getSimpleName());
    } else if (Gtk4.active() || !"x11".equals(System.getProperty(PlatformTraits.GDK_BACKEND))) {
      assertEquals(WindowStacking.WINDOW_MANAGER, stacking);
    } else {
      assertEquals("GtkX11Stacking", stacking.getClass().getSimpleName());
    }
  }

  @Test
  void dialogsShellIsUncoloredWithoutADarkWin32Start() {
    Shell owner = new Shell(display);
    Shell shell = Dialogs.shell(owner, "Mock", true);
    assertFalse(DialogColors.colored(shell));
    assertEquals(owner.getBackground().getRGB(), Dialogs.background(owner).getRGB());
  }

  // Menu item icons.

  @Test
  void menuInkIsFixedOnWin32() {
    Shell shell = new Shell(display);
    ToolBar bar = new ToolBar(shell, SWT.FLAT);
    bar.setBackground(new Color(240, 240, 240));
    MenuIcons icons = new MenuIcons(bar);
    bar.setBackground(new Color(30, 30, 30));
    icons.reink();
    assertEquals(!WIN32, icons.icons().darkBackground());
  }
}
