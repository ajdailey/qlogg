package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
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
 * The GTK traits follow the toolkit SWT loaded, in a child process with {@code SWT_GTK4=1} and an
 * empty {@code libgtk-4.so.1} first on {@code LD_LIBRARY_PATH}: SWT's GTK 4 library fails to load
 * and SWT falls back to GTK 3. Traits chosen from {@code SWT_GTK4} alone are the GTK 4 traits.
 */
@Tag("swt")
@EnabledOnOs(OS.LINUX)
class LoadedToolkitTest {
  private static final long BOUND_SECONDS = 60;

  @TempDir Path temp;

  @Test
  void gtk3TraitsAndOneWarnWhenGtk4FailsToLoad() throws Exception {
    Path libs = Files.createDirectories(temp.resolve("libs"));
    Files.createFile(libs.resolve("libgtk-4.so.1"));
    List<String> command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(LoadedToolkitMain.class.getName());
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    String path = env.get("LD_LIBRARY_PATH");
    env.put("LD_LIBRARY_PATH", path == null ? libs.toString() : libs + File.pathSeparator + path);
    env.put("SWT_GTK4", "1");
    Process process = builder.start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS), out);
    assertEquals(0, process.exitValue(), out);
    assertTrue(out.contains(LoadedToolkitMain.TRAITS + "Gtk3Traits"), out);
    assertTrue(out.contains(LoadedToolkitMain.TOOLKIT + "GTK 3."), out);
    String warn = "SWT_GTK4=1 is set but GTK 4 did not load";
    assertEquals(out.indexOf(warn), out.lastIndexOf(warn), out);
    assertTrue(out.contains(warn), out);
  }
}
