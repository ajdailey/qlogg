package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.Binding;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.config.FontSettings;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.ui.swt.platform.MenuBehavior;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pending two-stroke sequence ({@code Ctrl+E}, {@code Cmd+E} on macOS) clears the menu
 * accelerators so its second stroke reaches the log view, and every way the sequence ends restores
 * them, in a launcher-composed window.
 */
@Tag("swt")
class KeySequenceGuiTest {
  /** How long a posted key may take to arrive before the test skips. */
  private static final Duration POSTED_BOUND = Duration.ofSeconds(5);

  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;
  private ApplicationController controller;
  private DocumentController document;

  @BeforeEach
  void open() throws IOException {
    display = new Display();
    windows = new SlotWindows(display, temp.resolve("config"), ThemeMode.LIGHT, null);
    controller = windows.open(40, 40, 900, 600);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      text.append("mock entry ").append(i).append('\n');
    }
    Path file = Files.writeString(temp.resolve("mock.log"), text);
    controller.open(List.of(new FileRequest(file, 0)));
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != null
                    && !shown.state().indexing()
                    && shown.state().lineCount() == 200
                    && window().canvas() != null;
              }
            }),
        "file open");
    document = controller.activeDocument();
    windows.settle();
  }

  @AfterEach
  void close() {
    try {
      windows.close();
      assertTrue(windows.failures.isEmpty(), windows.failures.toString());
    } finally {
      display.dispose();
    }
  }

  private MainWindow window() {
    return windows.slots.get(0).window();
  }

  private Canvas canvas() {
    return window().canvas().control();
  }

  /** Sends a key press with the primary modifier to the main log view. */
  private void press(int keyCode) {
    Event event = new Event();
    event.stateMask = SWT.MOD1;
    event.keyCode = keyCode;
    event.character = keyCode < 0x7f ? (char) keyCode : '\0';
    canvas().notifyListeners(SWT.KeyDown, event);
  }

  /** The menu bar's and the application menu's items with an accelerator, by accelerator. */
  private Map<Integer, MenuItem> accelerators() {
    Map<Integer, MenuItem> found = new HashMap<Integer, MenuItem>();
    collect(window().shell().getMenuBar(), found);
    for (MenuItem item : MenuBehavior.PLATFORM.applicationMenuItems(display)) {
      if (item.getAccelerator() != 0) {
        found.put(item.getAccelerator(), item);
      }
    }
    return found;
  }

  private static void collect(Menu menu, Map<Integer, MenuItem> found) {
    for (MenuItem item : menu.getItems()) {
      if (item.getAccelerator() != 0) {
        found.put(item.getAccelerator(), item);
      }
      if (item.getMenu() != null) {
        collect(item.getMenu(), found);
      }
    }
  }

  /** The second strokes of sequences that a window binding also holds, as characters. */
  private Set<Character> collisions() {
    Set<Character> found = new TreeSet<Character>();
    List<Binding> bindings = controller.keymap().bindings();
    for (Binding sequence : bindings) {
      if (sequence.second() == null) {
        continue;
      }
      for (Binding window : bindings) {
        if (window.scope() == Binding.Scope.WINDOW
            && window.second() == null
            && window.first().equals(sequence.second())) {
          found.add((char) sequence.second().key());
        }
      }
    }
    return found;
  }

  private void selectLine(long line) {
    document.select(Selection.caretAt(new TextPosition(line, 0)));
  }

  /** Marks lines one at a time: a toggle runs on a worker, and none starts while one runs. */
  private void mark(long... lines) {
    for (long line : lines) {
      final long count = document.marks().size() + 1;
      selectLine(line);
      document.toggleMarks();
      assertTrue(
          windows.await(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return document.marks().size() == count && !document.markingRunning();
                }
              }),
          "marked " + line);
    }
  }

  private long caretLine() {
    return document.selection().caret().line();
  }

  @Test
  void theCollisionsAreThePreferencesKeyAndOnMacOsMinimize() {
    Set<Character> expected = new TreeSet<Character>();
    expected.add(',');
    if (SwtKeys.mac()) {
      expected.add('m');
    }
    assertEquals(expected, collisions());
    Map<Integer, MenuItem> accelerators = accelerators();
    for (char key : expected) {
      int accelerator = SwtKeys.accelerator(KeyStroke.of(KeyStroke.PRIMARY, key));
      assertTrue(accelerators.containsKey(accelerator), "no menu item on " + key);
    }
  }

  @Test
  void aPendingSequenceClearsEveryAcceleratorUntilItCompletes() {
    Map<Integer, MenuItem> before = accelerators();
    assertTrue(before.size() > 10, before.toString());
    boolean numbers = document.lineNumbers();
    press('e');
    assertEquals(Map.of(), accelerators());
    press('t');
    assertEquals(!numbers, document.lineNumbers());
    assertEquals(before, accelerators());
  }

  @Test
  void eachCollidingSequenceRunsItsCommand() {
    Shell[] shells = display.getShells();
    mark(20, 60);
    selectLine(100);
    press('e');
    press(',');
    assertEquals(60, caretLine(), "Previous Mark");
    selectLine(0);
    press('e');
    press('.');
    assertEquals(20, caretLine(), "Next Mark");
    selectLine(150);
    long marks = document.marks().size();
    press('e');
    press('m');
    final long more = marks + 1;
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.marks().size() == more && !document.markingRunning();
              }
            }),
        "Toggle Mark");
    windows.settle();
    assertFalse(window().shell().getMinimized());
    assertEquals(shells.length, display.getShells().length, "no Preferences dialog");
  }

  @Test
  void aWindowKeyDuringASequenceCancelsIt() {
    FontSettings font = controller.preferences().options().font();
    Map<Integer, MenuItem> before = accelerators();
    press('e');
    // Zoom In's second key runs from the window's key filter, not from a menu accelerator.
    press(SWT.KEYPAD_ADD);
    assertEquals(font, controller.preferences().options().font());
    assertEquals(before, accelerators());
    press(SWT.KEYPAD_ADD);
    assertNotEquals(font, controller.preferences().options().font(), "Zoom In with none pending");
  }

  @Test
  void cancelFocusLossAndTimeoutRestoreTheAccelerators() {
    Map<Integer, MenuItem> before = accelerators();
    press('e');
    Event escape = new Event();
    escape.keyCode = SWT.ESC;
    canvas().notifyListeners(SWT.KeyDown, escape);
    assertEquals(before, accelerators(), "Esc");
    press('e');
    canvas().notifyListeners(SWT.FocusOut, new Event());
    assertEquals(before, accelerators(), "focus out");
    press('e');
    long start = System.nanoTime();
    assertEquals(Map.of(), accelerators());
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !accelerators().isEmpty();
              }
            }),
        "timeout");
    assertTrue((System.nanoTime() - start) / 1_000_000 >= SequenceKeys.TIMEOUT_MILLIS - 100);
    assertEquals(before, accelerators());
  }

  @Test
  void aThemeRebuildWhilePendingKeepsTheAccelerators() {
    Map<Integer, MenuItem> before = accelerators();
    press('e');
    try {
      windows.switchTo(controller, true);
      assertEquals(before.keySet(), accelerators().keySet());
    } finally {
      windows.switchTo(controller, false);
    }
  }

  @Test
  void aKeymapRebuildWhilePendingKeepsTheAccelerators() {
    Map<Integer, MenuItem> before = accelerators();
    final Menu old = window().shell().getMenuBar();
    press('e');
    window().keymapChanged();
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return old.isDisposed();
              }
            }),
        "menu bar rebuilt");
    windows.settle();
    assertEquals(before.keySet(), accelerators().keySet());
  }

  @Test
  void postedKeysReachThePendingSequenceBeforeTheMenu() {
    final Canvas canvas = canvas();
    window().shell().forceActive();
    canvas.setFocus();
    assumeTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return display.getFocusControl() == canvas;
              }
            },
            POSTED_BOUND),
        "the log view takes the focus");
    final List<Integer> reached = new ArrayList<Integer>();
    Listener strokes =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget == canvas && (event.stateMask & SWT.MOD1) != 0) {
              reached.add(event.keyCode);
            }
          }
        };
    final List<Character> selected = new ArrayList<Character>();
    Map<Integer, MenuItem> items = accelerators();
    for (final char key : collisions()) {
      items
          .get(SwtKeys.accelerator(KeyStroke.of(KeyStroke.PRIMARY, key)))
          .addListener(
              SWT.Selection,
              new Listener() {
                @Override
                public void handleEvent(Event event) {
                  selected.add(key);
                  // A second Display in one JVM wraps cocoa's application menu items again, so
                  // their native action is the selection itself and would recurse.
                  event.doit = false;
                }
              });
    }
    display.addFilter(SWT.KeyDown, strokes);
    try {
      mark(20);
      selectLine(100);
      boolean first = true;
      for (final char key : collisions()) {
        reached.clear();
        postPrimary('e');
        BooleanSupplier pending =
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return reached.contains((int) 'e');
              }
            };
        if (first) {
          assumeTrue(
              EventLoop.runUntil(display, pending, POSTED_BOUND), "posted keys reach the log view");
          first = false;
        } else {
          assertTrue(windows.await(pending), "Ctrl+E before " + key);
        }
        postPrimary(key);
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return reached.contains((int) key) || !selected.isEmpty();
              }
            });
        windows.settle();
        assertEquals(List.of(), selected, "menu command after Ctrl+E " + key);
      }
      assertEquals(20, caretLine(), "Previous Mark");
      postPrimary(',');
      assertTrue(
          windows.await(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return !selected.isEmpty();
                }
              }),
          "Preferences with none pending");
    } finally {
      display.removeFilter(SWT.KeyDown, strokes);
      for (Shell shell : display.getShells()) {
        if (shell != window().shell()) {
          shell.dispose();
        }
      }
    }
  }

  /**
   * Posts a key with the primary modifier held. The modifier goes down before each key: a posted
   * key's release drops the posted modifier from the next key's state under Xvfb.
   */
  private void postPrimary(char key) {
    postKey(SWT.KeyDown, SWT.MOD1, '\0');
    try {
      postKey(SWT.KeyDown, 0, key);
      postKey(SWT.KeyUp, 0, key);
    } finally {
      postKey(SWT.KeyUp, SWT.MOD1, '\0');
    }
  }

  private void postKey(int type, int keyCode, char character) {
    Event event = new Event();
    event.type = type;
    event.keyCode = keyCode;
    event.character = character;
    display.post(event);
  }
}
