package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;

/**
 * Every value of every platform's traits that needs no Display or SWT internals, and the class of
 * each seam it creates, so a Linux run checks the win32 and cocoa choices too. The running
 * platform's choice is checked on its own OS; win32 and cocoa run in CI.
 */
class PlatformTraitsTest {
  /** The boolean value traits; any other boolean method would be a behavior switch. */
  private static final List<String> BOOLEAN_VALUES = List.of("macKeys");

  /** Rows in the order of {@link #answers}. */
  private static final String[] QUESTIONS = {
    "name",
    "monospaceFamily",
    "monospaceSize",
    "macKeys",
    "busyCursor",
    "themeFollow",
    "fullScreen",
    "menus",
    "launchDocuments",
    "accessibleLabels",
    "windowPlacement",
    "guideDisplay"
  };

  private static List<Object> answers(PlatformTraits traits) {
    List<Object> answers = new ArrayList<Object>();
    answers.add(traits.name());
    answers.add(traits.monospaceFamily());
    answers.add(traits.monospaceSize());
    answers.add(traits.macKeys());
    answers.add(traits.busyCursor());
    answers.add(traits.themeFollow(null).getClass());
    answers.add(traits.fullScreen(null, null).getClass());
    answers.add(traits.menuBehavior().getClass());
    answers.add(traits.launchDocuments().getClass());
    answers.add(traits.accessibleLabels().getClass());
    answers.add(traits.windowPlacement().getClass());
    answers.add(traits.guideDisplay().getClass());
    return answers;
  }

  private static void check(PlatformTraits traits, Object... expected) {
    List<Object> actual = answers(traits);
    for (int i = 0; i < QUESTIONS.length; i++) {
      assertEquals(expected[i], actual.get(i), traits.name() + " " + QUESTIONS[i]);
    }
  }

  @Test
  void win32() {
    check(
        PlatformTraits.of("win32", false),
        "win32",
        "Consolas",
        10,
        false,
        SWT.CURSOR_APPSTARTING,
        Win32ThemeFollow.class,
        NativeFullScreen.class,
        Win32MenuBehavior.class,
        NativeLaunchDocuments.class,
        NativeAccessibleLabels.class,
        Win32WindowPlacement.class,
        BrowserGuideDisplay.class);
  }

  @Test
  void cocoa() {
    check(
        PlatformTraits.of("cocoa", false),
        "cocoa",
        "Menlo",
        12,
        true,
        SWT.CURSOR_WAIT,
        CocoaThemeFollow.class,
        CocoaFullScreen.class,
        CocoaMenuBehavior.class,
        CocoaLaunchDocuments.class,
        CocoaAccessibleLabels.class,
        NativeWindowPlacement.class,
        BrowserGuideDisplay.class);
  }

  @Test
  void gtk3() {
    check(
        PlatformTraits.of("gtk", false),
        "gtk",
        "Monospace",
        10,
        false,
        SWT.CURSOR_APPSTARTING,
        GtkThemeFollow.class,
        NativeFullScreen.class,
        Gtk3MenuBehavior.class,
        NativeLaunchDocuments.class,
        NativeAccessibleLabels.class,
        GtkWindowPlacement.class,
        BrowserGuideDisplay.class);
    assertSame(NativeToolItemSizing.INSTANCE, PlatformTraits.of("gtk", false).toolItemSizing());
  }

  @Test
  void gtk4() {
    check(
        PlatformTraits.of("gtk", true),
        "gtk",
        "Monospace",
        10,
        false,
        SWT.CURSOR_APPSTARTING,
        GtkThemeFollow.class,
        NativeFullScreen.class,
        Gtk4MenuBehavior.class,
        NativeLaunchDocuments.class,
        NativeAccessibleLabels.class,
        Gtk4WindowPlacement.class,
        NoticeGuideDisplay.class);
    assertTrue(PlatformTraits.of("gtk", true).stacking() instanceof WindowManagerStacking);
  }

  @Test
  void onlyWin32PaintsTheComboListSelection() {
    assertInstanceOf(
        Win32ComboListSelection.class, PlatformTraits.of("win32", false).comboListSelection());
    assertInstanceOf(
        NativeComboListSelection.class, PlatformTraits.of("cocoa", false).comboListSelection());
    assertInstanceOf(
        NativeComboListSelection.class, PlatformTraits.of("gtk", false).comboListSelection());
    assertInstanceOf(
        NativeComboListSelection.class, PlatformTraits.of("gtk", true).comboListSelection());
  }

  @Test
  void onlyGtkNamesAToolkitVersion() {
    assertEquals("", PlatformTraits.of("win32", false).toolkitVersion());
    assertEquals("", PlatformTraits.of("cocoa", false).toolkitVersion());
    String version = System.getProperty(PlatformTraits.GTK_VERSION);
    for (boolean gtk4 : new boolean[] {false, true}) {
      assertEquals(
          version == null ? "" : "GTK " + version, PlatformTraits.of("gtk", gtk4).toolkitVersion());
    }
  }

  /**
   * Behavior that differs by platform sits behind a seam: every boolean {@code PlatformTraits}
   * method is a value trait. A new boolean that switches behavior fails here.
   */
  @Test
  void noTraitSwitchesBehavior() {
    for (Method method : PlatformTraits.class.getMethods()) {
      if (method.getReturnType() == boolean.class) {
        assertTrue(BOOLEAN_VALUES.contains(method.getName()), method.getName());
      }
    }
  }

  @Test
  void anUnknownPlatformIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            PlatformTraits.of("motif", false);
          }
        });
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void win32IsChosenOnWindows() {
    assertEquals(Win32Traits.class, PlatformTraits.PLATFORM.getClass());
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaIsChosenOnMacOs() {
    assertEquals(CocoaTraits.class, PlatformTraits.PLATFORM.getClass());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void gtkIsChosenOnLinuxFollowingTheLoadedGtk() {
    assertEquals("gtk", SWT.getPlatform());
    PlatformTraits chosen = PlatformTraits.PLATFORM;
    assertEquals(
        LoadedToolkit.isGtk4(System.getProperty(PlatformTraits.GTK_VERSION))
            ? Gtk4Traits.class
            : Gtk3Traits.class,
        chosen.getClass());
  }

  @Test
  void theGtkVersionSwtLoadedChoosesTheTraits() {
    assertEquals(Gtk4Traits.class, LoadedToolkit.choose("gtk", "4.14.5", true).getClass());
    assertEquals(Gtk3Traits.class, LoadedToolkit.choose("gtk", "3.24.41", true).getClass());
    assertEquals(Gtk3Traits.class, LoadedToolkit.choose("gtk", "3.24.41", false).getClass());
    assertEquals(Gtk4Traits.class, LoadedToolkit.choose("gtk", "4.14.5", false).getClass());
    assertEquals(Win32Traits.class, LoadedToolkit.choose("win32", null, true).getClass());
    assertEquals(CocoaTraits.class, LoadedToolkit.choose("cocoa", null, true).getClass());
  }

  @Test
  void onlyMajorVersionFourIsGtk4() {
    assertTrue(LoadedToolkit.isGtk4("4.0.0"));
    assertFalse(LoadedToolkit.isGtk4("3.24.32"));
    assertFalse(LoadedToolkit.isGtk4("40.1.0"));
    assertFalse(LoadedToolkit.isGtk4(""));
    assertFalse(LoadedToolkit.isGtk4(null));
  }
}
