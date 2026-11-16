package io.github.ajdailey.qlogg.core.util.os;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class RunnableTypesTest {
  @Test
  void everyWindowsExtensionRuns() {
    for (String extension : RunnableTypes.WINDOWS) {
      assertTrue(RunnableTypes.windows("file" + extension, null), extension);
      assertTrue(
          RunnableTypes.windows("FILE" + extension.toUpperCase(Locale.ROOT), null), extension);
    }
    assertFalse(RunnableTypes.windows("app.log", null));
    assertFalse(RunnableTypes.windows("app.wsx", null));
  }

  @Test
  void windowsDropsTrailingDotsAndSpaces() {
    assertTrue(RunnableTypes.windows("tool.jar.", null));
    assertTrue(RunnableTypes.windows("run.bat . ", null));
    assertFalse(RunnableTypes.windows(". .", null));
  }

  @Test
  void pathextAddsItsEntries() {
    String pathext = ".COM;.EXE; .FOO ;;LOG;.";
    assertTrue(RunnableTypes.windows("script.foo", pathext));
    assertTrue(RunnableTypes.windows("script.FOO", pathext));
    assertFalse(RunnableTypes.windows("script.foo", null));
    assertFalse(RunnableTypes.windows("app.log", pathext));
    assertFalse(RunnableTypes.windows("app.txt", pathext));
  }

  @Test
  void everyMacExtensionRuns() {
    for (String extension : RunnableTypes.MAC) {
      assertTrue(RunnableTypes.mac("file" + extension), extension);
      assertTrue(RunnableTypes.mac("FILE" + extension.toUpperCase(Locale.ROOT)), extension);
    }
    assertFalse(RunnableTypes.mac("app.log"));
    assertFalse(RunnableTypes.mac("setup.exe"));
  }
}
