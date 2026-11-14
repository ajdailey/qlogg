package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Reads X11 window properties through {@code xprop} and {@code xwininfo}, for GTK 3 tests under
 * Xvfb. A missing tool skips the test.
 */
public final class XWindows {
  private static final Duration BOUND = Duration.ofSeconds(10);

  private XWindows() {}

  /**
   * Waits until the shell's {@code _NET_WM_STATE} has or lacks {@code _NET_WM_STATE_ABOVE}.
   *
   * @param display the Display
   * @param shell a shell whose title is unique
   * @param above true to wait for the state
   */
  public static void awaitAbove(Display display, final Shell shell, final boolean above) {
    final String[] state = {""};
    boolean held =
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                String id = windowId(shell.getText());
                state[0] = id == null ? "" : run("xprop", "-id", id, "_NET_WM_STATE");
                return state[0].startsWith("_NET_WM_STATE")
                    && state[0].contains("_NET_WM_STATE_ABOVE") == above;
              }
            },
            BOUND);
    assertTrue(held, "_NET_WM_STATE above=" + above + ": " + state[0]);
  }

  /**
   * Waits until the dialog's {@code WM_TRANSIENT_FOR} names the owner's window.
   *
   * @param display the Display
   * @param dialog a dialog whose title is unique
   * @param owner its owner, whose title is unique
   */
  static void awaitTransient(Display display, final Shell dialog, Shell owner) {
    final String parent = windowId(owner.getText());
    final String[] property = {""};
    boolean held =
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                String id = windowId(dialog.getText());
                property[0] = id == null ? "" : run("xprop", "-id", id, "WM_TRANSIENT_FOR");
                return parent != null && property[0].strip().endsWith(parent);
              }
            },
            BOUND);
    assertTrue(held, "WM_TRANSIENT_FOR " + property[0] + ", owner " + parent);
  }

  /**
   * Waits until a viewable override-redirect window appears that was not in the list.
   *
   * @param display the Display
   * @param before the top-level windows before the popup opened
   */
  static void awaitOverrideRedirectPopup(Display display, final List<String> before) {
    final String[] seen = {""};
    boolean held =
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                for (String id : topLevels()) {
                  String info = run("xwininfo", "-id", id);
                  if (!before.contains(id) && info.contains("Map State: IsViewable")) {
                    seen[0] = info;
                    if (info.contains("Override Redirect State: yes")) {
                      return true;
                    }
                  }
                }
                return false;
              }
            },
            BOUND);
    assertTrue(held, "popup: " + seen[0]);
  }

  /**
   * Returns the ids of the root window's children.
   *
   * @return the ids, {@code 0x} hexadecimal
   */
  static List<String> topLevels() {
    List<String> ids = new ArrayList<String>();
    for (String line : run("xwininfo", "-root", "-children").split("\n")) {
      String word = line.strip().split(" ", 2)[0];
      if (word.startsWith("0x")) {
        ids.add(word);
      }
    }
    return ids;
  }

  /** Returns the id of the top-level window whose name starts with the title, or null. */
  private static String windowId(String title) {
    for (String line : run("xwininfo", "-root", "-children").split("\n")) {
      String[] words = line.strip().split(" ", 2);
      if (words.length == 2 && words[1].startsWith("\"" + title)) {
        return words[0];
      }
    }
    return null;
  }

  /** Runs an X tool and returns its output; skips the test when the tool is missing. */
  private static String run(String... command) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String out;
      try (InputStream in = process.getInputStream()) {
        out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      process.waitFor();
      return out;
    } catch (IOException e) {
      assumeTrue(false, "no " + command[0] + ": " + e.getMessage());
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
