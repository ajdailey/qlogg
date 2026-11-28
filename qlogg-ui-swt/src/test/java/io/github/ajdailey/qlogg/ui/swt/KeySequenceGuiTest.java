package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

  /** The events the history records. */
  private static final Map<Integer, String> EVENT_NAMES =
      Map.of(
          SWT.KeyDown, "KeyDown",
          SWT.FocusIn, "FocusIn",
          SWT.FocusOut, "FocusOut",
          SWT.Activate, "Activate",
          SWT.Deactivate, "Deactivate");

  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;
  private ApplicationController controller;
  private DocumentController document;

  /** Key, focus and activation events since the test started, for failure messages. */
  private final List<String> history = new ArrayList<String>();

  /**
   * Key strokes (not modifiers alone) and focus losses on the main log view; each ends a pending
   * sequence.
   */
  private int viewEnds;

  private long started;

  /** When the {@code Ctrl+E} that started the pending sequence was posted. */
  private long sequenceStart;

  private final Listener recorder =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          boolean view =
              !windows.slots.isEmpty()
                  && window() != null
                  && window().canvas() != null
                  && event.widget == window().canvas().control();
          boolean stroke =
              event.type == SWT.KeyDown
                  && SwtKeys.stroke(event.stateMask, event.keyCode, event.character) != null;
          if (view && (stroke || event.type == SWT.FocusOut)) {
            viewEnds++;
          }
          history.add(
              (System.nanoTime() - started) / 1_000_000
                  + "ms "
                  + EVENT_NAMES.get(event.type)
                  + (view ? " view" : " " + event.widget.getClass().getSimpleName())
                  + (event.type == SWT.KeyDown
                      ? " " + event.keyCode + "/" + Integer.toHexString(event.stateMask)
                      : ""));
        }
      };

  @BeforeEach
  void open() throws IOException {
    started = System.nanoTime();
    display = new Display();
    windows = new SlotWindows(display, temp.resolve("config"), ThemeMode.LIGHT, null);
    for (int type : EVENT_NAMES.keySet()) {
      display.addFilter(type, recorder);
    }
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
    for (int type : EVENT_NAMES.keySet()) {
      display.removeFilter(type, recorder);
    }
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
  void theCollisionsAreThePreferencesKeyGoToLineAndOnMacOsMinimize() {
    Set<Character> expected = new TreeSet<Character>();
    expected.add(',');
    // Ctrl+E Ctrl+L locates a filter tab's line; Ctrl+L stays Go to Line.
    expected.add('l');
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
    // A key or a focus loss on the log view during the wait also ends the sequence, so an attempt
    // with one starts over.
    for (int attempt = 1; ; attempt++) {
      press('e');
      int ends = viewEnds;
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
      long millis = (System.nanoTime() - start) / 1_000_000;
      if (viewEnds == ends || attempt == 3) {
        assertTrue(
            millis >= SequenceKeys.TIMEOUT_MILLIS - 100,
            "restored after " + millis + " ms; " + history);
        break;
      }
    }
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
    Menu bar = window().shell().getMenuBar();
    final LogCanvas old = window().canvas();
    press('e');
    window().keymapChanged();
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window().canvas() != old;
              }
            }),
        "views rebuilt");
    windows.settle();
    assertSame(bar, window().shell().getMenuBar());
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
        boolean pending = startSequence();
        if (first) {
          assumeTrue(pending, "posted keys reach the log view");
          first = false;
        } else {
          assertTrue(pending, "Ctrl+E before " + key + "; " + history);
        }
        secondStroke(key, reached, selected);
      }
      assertEquals(20, caretLine(), "Previous Mark");
      preferencesWithNonePending(reached, selected);
    } finally {
      display.removeFilter(SWT.KeyDown, strokes);
      for (Shell shell : display.getShells()) {
        if (shell != window().shell()) {
          shell.dispose();
        }
      }
    }
  }

  /** Returns whether a sequence is pending: the hold clears every accelerator only then. */
  private boolean pending() {
    return accelerators().isEmpty();
  }

  /** Activates the main window, focuses the log view and waits until both hold. */
  private void focusView() {
    final Canvas canvas = canvas();
    window().shell().setMinimized(false);
    window().shell().forceActive();
    canvas.setFocus();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return display.getActiveShell() == window().shell()
                && display.getFocusControl() == canvas;
          }
        },
        POSTED_BOUND);
  }

  /** Cancels a pending sequence with {@code Esc}. */
  private void cancelPending() {
    if (pending()) {
      Event escape = new Event();
      escape.keyCode = SWT.ESC;
      canvas().notifyListeners(SWT.KeyDown, escape);
    }
  }

  /**
   * Posts {@code Ctrl+E} until a sequence is pending and stays pending once the queue is empty, at
   * most three times. A posted key occasionally never arrives on the macOS runners, and one that
   * arrives after the next post completes the pair {@code Ctrl+E Ctrl+E}, which ends the sequence;
   * both lead to another post.
   *
   * @return true once a sequence is pending
   */
  private boolean startSequence() {
    BooleanSupplier pending =
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pending();
          }
        };
    for (int attempt = 0; attempt < 3; attempt++) {
      cancelPending();
      focusView();
      sequenceStart = System.nanoTime();
      postPrimary('e');
      EventLoop.runUntil(display, pending, POSTED_BOUND);
      windows.settle();
      if (pending()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Posts a colliding second stroke and fails if a menu item took it while the sequence was still
   * pending. The sequence counts as pending when no key or focus loss reached the log view since it
   * started and the timeout has not passed. A menu command after the sequence ended, or a key that
   * never arrived, starts the attempt over, at most three times.
   */
  private void secondStroke(final char key, final List<Integer> reached, List<Character> selected) {
    BooleanSupplier arrived =
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return reached.contains((int) key) || !selected.isEmpty();
          }
        };
    for (int attempt = 1; ; attempt++) {
      if (attempt > 1) {
        assertTrue(startSequence(), "Ctrl+E before " + key + "; " + history);
      }
      reached.clear();
      int ends = viewEnds;
      postPrimary(key);
      windows.await(arrived);
      long millis = (System.nanoTime() - sequenceStart) / 1_000_000;
      windows.settle();
      if (!selected.isEmpty()) {
        boolean ended = viewEnds != ends || millis >= SequenceKeys.TIMEOUT_MILLIS;
        assertTrue(ended, "menu command after Ctrl+E " + key + "; " + history);
        assertTrue(attempt < 3, "sequence ended before " + key + " three times; " + history);
        selected.clear();
      } else if (reached.contains((int) key)) {
        return;
      } else {
        assertTrue(attempt < 3, "Ctrl+E " + key + " never arrived; " + history);
      }
    }
  }

  /**
   * Posts {@code Ctrl+,} with none pending until Preferences runs, at most three times; the log
   * view receiving it fails the test.
   */
  private void preferencesWithNonePending(
      final List<Integer> reached, final List<Character> selected) {
    for (int attempt = 1; ; attempt++) {
      cancelPending();
      focusView();
      reached.clear();
      postPrimary(',');
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return reached.contains((int) ',') || !selected.isEmpty();
            }
          },
          POSTED_BOUND);
      windows.settle();
      if (!selected.isEmpty()) {
        return;
      }
      assertFalse(reached.contains((int) ','), "Ctrl+, reached the log view; " + history);
      assertTrue(attempt < 3, "Preferences with none pending; " + history);
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
