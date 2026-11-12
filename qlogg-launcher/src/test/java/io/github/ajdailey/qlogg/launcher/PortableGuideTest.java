package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qlogg.app.about.UserGuide;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help > User Guide in portable mode, in a child process with {@code HOME} an empty folder: the
 * guide opens outside the embedded browser, so neither WebKitGTK's {@code QLogg} folders nor Mesa's
 * shader cache appear under {@code HOME}. Fontconfig writes its cache there because the test's data
 * folders hold the fonts under another path, and GTK's input method module creates empty {@code
 * .config/ibus/bus} folders.
 */
@Tag("swt")
@EnabledOnOs(OS.LINUX)
class PortableGuideTest {
  private static final long BOUND_SECONDS = 60;

  @TempDir Path temp;

  @Test
  void theGuideInPortableModeWritesNothingUnderHome() throws Exception {
    assumeFalse(
        "1".equals(System.getenv("SWT_GTK4")),
        "GTK 4's renderer writes .cache/mesa_shader_cache under HOME in every run");
    Path bundle = Files.createDirectories(temp.resolve("bundle"));
    Files.createFile(bundle.resolve(SettingsLocation.MARKER));
    Path home = Files.createDirectories(temp.resolve("home"));
    // The system data folders without their applications folder: no application handles HTML,
    // so the guide's fallback is the notice, not a launched browser.
    Path noApplications = Files.createDirectories(temp.resolve("no-applications"));
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(Path.of("/usr/share"))) {
      for (Path entry : entries) {
        if (!entry.getFileName().toString().equals("applications")) {
          Files.createSymbolicLink(noApplications.resolve(entry.getFileName()), entry);
        }
      }
    }
    List<String> command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Duser.home=" + home);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(PortableGuideMain.class.getName());
    command.add(bundle.toString());
    command.add("portable");
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    env.put("HOME", home.toString());
    env.put("XDG_DATA_DIRS", noApplications.toString());
    env.put("GDK_BACKEND", "x11");
    for (String name : List.of("WAYLAND_DISPLAY", "XDG_CACHE_HOME", "XDG_DATA_HOME")) {
      env.remove(name);
    }
    Process process = builder.start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS), out);
    assertTrue(out.contains(PortableGuideMain.EXIT + "0"), out);
    assertTrue(out.contains(PortableGuideMain.GUIDE + "false"), out);
    assertTrue(Files.isRegularFile(bundle.resolve(UserGuide.FOLDER).resolve(UserGuide.FILE)), out);
    for (Path written :
        List.of(
            home.resolve(".cache").resolve("QLogg"),
            home.resolve(".cache").resolve("mesa_shader_cache"),
            home.resolve(".local").resolve("share").resolve("QLogg"))) {
      assertFalse(Files.exists(written), written.toString());
    }
  }
}
