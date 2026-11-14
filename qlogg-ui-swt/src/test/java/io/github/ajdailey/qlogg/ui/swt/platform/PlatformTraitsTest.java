package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.ui.swt.Gtk4;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;

/**
 * Every answer of every platform's traits that needs no Display or SWT internals, as the view
 * classes' platform branches gave them, so a Linux run checks the win32 and cocoa classes too. The
 * running platform's choice is checked on its own OS; win32 and cocoa run in CI.
 */
class PlatformTraitsTest {
  /** Rows in the order of {@link #answers}. */
  private static final String[] QUESTIONS = {
    "name",
    "monospaceFamily",
    "monospaceSize",
    "macKeys",
    "systemAppMenu",
    "remeasuresTrim",
    "launchesByExtension",
    "opensBrowser",
    "switchesThemeLive",
    "followsSystemAppearance",
    "themesAtCreation",
    "lightChecksUnderDark",
    "busyCursor",
    // Menu item icons.
    "menuImages",
    "padsMenuImages",
    "fixedMenuInk",
    "fadesDisabledIcons"
  };

  private static List<Object> answers(PlatformTraits traits) {
    List<Object> answers = new ArrayList<Object>();
    answers.add(traits.name());
    answers.add(traits.monospaceFamily());
    answers.add(traits.monospaceSize());
    answers.add(traits.macKeys());
    answers.add(traits.systemAppMenu());
    answers.add(traits.remeasuresTrim());
    answers.add(traits.launchesByExtension());
    answers.add(traits.opensBrowser());
    answers.add(traits.switchesThemeLive());
    answers.add(traits.followsSystemAppearance());
    answers.add(traits.themesAtCreation());
    answers.add(traits.lightChecksUnderDark());
    answers.add(traits.busyCursor());
    // Menu item icons.
    answers.add(traits.menuImages());
    answers.add(traits.padsMenuImages());
    answers.add(traits.fixedMenuInk());
    answers.add(traits.fadesDisabledIcons());
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
        false,
        true,
        true,
        true,
        false,
        false,
        true,
        true,
        SWT.CURSOR_APPSTARTING,
        true,
        false,
        true,
        false);
    assertTrue(PlatformTraits.of("win32", false).positionsWindows());
  }

  @Test
  void cocoa() {
    check(
        PlatformTraits.of("cocoa", false),
        "cocoa",
        "Menlo",
        12,
        true,
        true,
        false,
        false,
        true,
        false,
        true,
        false,
        false,
        SWT.CURSOR_WAIT,
        false,
        false,
        false,
        false);
    assertTrue(PlatformTraits.of("cocoa", false).positionsWindows());
  }

  @Test
  void gtk3() {
    check(
        PlatformTraits.of("gtk", false),
        "gtk",
        "Monospace",
        10,
        false,
        false,
        false,
        false,
        true,
        true,
        false,
        false,
        false,
        SWT.CURSOR_APPSTARTING,
        true,
        true,
        false,
        true);
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
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        false,
        SWT.CURSOR_APPSTARTING,
        false,
        true,
        false,
        true);
    assertSame(WindowStacking.WINDOW_MANAGER, PlatformTraits.of("gtk", true).stacking());
  }

  @Test
  void gtkPositionsWindowsExceptUnderWayland() {
    String backend = System.getProperty(PlatformTraits.GDK_BACKEND);
    assertEquals(
        !"wayland".equals(backend), PlatformTraits.of("gtk", false).positionsWindows(), backend);
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
  void gtkIsChosenOnLinuxFollowingSwtGtk4() {
    assertEquals("gtk", SWT.getPlatform());
    assertEquals(
        Gtk4.active() ? Gtk4Traits.class : Gtk3Traits.class, PlatformTraits.PLATFORM.getClass());
  }
}
