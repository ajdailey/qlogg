package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.ui.swt.platform.DialogColors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WindowViews} turn by turn, each turn run from a queue: one window rebuilt per turn, none
 * inside the request, a window waiting for its modal dialog or native chooser and rebuilt after it
 * closes, a window opened after the request left alone, and the windows counted open across a
 * rebuild.
 */
@Tag("swt")
class WindowViewsTest {
  /** Holds the turns {@link WindowViews} schedules until the test runs them. */
  private static final class Turns implements UiDispatcher {
    final List<Runnable> queued = new ArrayList<Runnable>();
    final List<Integer> delays = new ArrayList<Integer>();

    @Override
    public void asyncExec(Runnable task) {
      queued.add(task);
      delays.add(0);
    }

    @Override
    public void timerExec(int delayMillis, Runnable task) {
      queued.add(task);
      delays.add(delayMillis);
    }

    @Override
    public boolean isUiThread() {
      return true;
    }

    /** Runs the queued turns, at most 20, until none is left. */
    void drain() {
      for (int i = 0; i < 20 && !queued.isEmpty(); i++) {
        runOne();
      }
      assertTrue(queued.isEmpty(), "turns left: " + delays);
    }

    /** Runs the oldest turn. */
    void runOne() {
      delays.remove(0);
      queued.remove(0).run();
    }
  }

  @TempDir Path temp;

  private Display display;
  private Turns turns;
  private SlotWindows windows;

  @BeforeEach
  void open() throws IOException {
    display = new Display();
    turns = new Turns();
    windows =
        new SlotWindows(
            display, temp.resolve("config"), ThemeMode.LIGHT, new WindowViews(display, turns));
    windows.open(40, 40, 700, 450);
    windows.open(80, 80, 700, 450);
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

  /** Records a native side as {@code Win32ThemeFollow} does and asks for the rebuild. */
  private void toSide(boolean dark) {
    display.setData(DialogColors.NATIVE_DARK, dark);
    windows.views.rebuild();
  }

  @Test
  void aWaitingWindowSwitchedBackBeforeItsTurnIsNotRebuilt() {
    MainWindow first = windows.slots.get(0).window();
    MainWindow second = windows.slots.get(1).window();
    Shell dialog = Dialogs.shell(first.shell(), "Mock", true);
    dialog.setSize(200, 120);
    dialog.open();
    windows.settle();
    toSide(true);
    turns.runOne();
    turns.runOne();
    assertSame(first, windows.slots.get(0).window());
    assertNotSame(second, windows.slots.get(1).window());
    assertEquals(1, windows.views.rebuilds());
    assertTrue(windows.views.pending());
    toSide(false);
    dialog.dispose();
    turns.drain();
    assertSame(first, windows.slots.get(0).window());
    assertEquals(2, windows.views.rebuilds(), "the rebuilt window goes back to light");
    assertFalse(windows.views.pending());
  }

  @Test
  void aSwitchAndBackBeforeTheFirstTurnRebuildsNothing() {
    MainWindow first = windows.slots.get(0).window();
    toSide(true);
    toSide(false);
    turns.drain();
    assertSame(first, windows.slots.get(0).window());
    assertEquals(0, windows.views.rebuilds());
  }

  @Test
  void oneWindowPerTurnAndNoneInsideTheRequest() {
    MainWindow first = windows.slots.get(0).window();
    MainWindow second = windows.slots.get(1).window();
    toSide(true);
    assertSame(first, windows.slots.get(0).window());
    assertEquals(1, turns.queued.size());
    turns.runOne();
    assertNotSame(first, windows.slots.get(0).window());
    assertTrue(first.shell().isDisposed());
    assertSame(second, windows.slots.get(1).window());
    assertEquals(List.of(0), turns.delays);
    turns.runOne();
    assertNotSame(second, windows.slots.get(1).window());
    assertTrue(second.shell().isDisposed());
    turns.runOne();
    assertTrue(turns.queued.isEmpty());
    assertTrue(windows.views.anyOpen());
  }

  @Test
  void aWindowWithAModalDialogWaitsUntilItCloses() {
    MainWindow first = windows.slots.get(0).window();
    MainWindow second = windows.slots.get(1).window();
    Shell dialog = Dialogs.shell(first.shell(), "Mock", true);
    dialog.setSize(200, 120);
    dialog.open();
    windows.settle();
    toSide(true);
    turns.runOne();
    assertSame(first, windows.slots.get(0).window());
    assertNotSame(second, windows.slots.get(1).window());
    turns.runOne();
    assertEquals(List.of(WindowViews.RECHECK_MILLIS), turns.delays);
    turns.runOne();
    turns.runOne();
    assertSame(first, windows.slots.get(0).window());
    assertFalse(dialog.isDisposed());
    assertEquals(List.of(WindowViews.RECHECK_MILLIS), turns.delays);
    dialog.dispose();
    turns.drain();
    assertNotSame(first, windows.slots.get(0).window());
    assertTrue(first.shell().isDisposed());
  }

  @Test
  void aModelessWindowHoldingInputHoldsItsWindowWhileItHolds() {
    MainWindow first = windows.slots.get(0).window();
    Shell tool = Dialogs.shell(first.shell(), "Mock", false);
    tool.setSize(200, 120);
    tool.open();
    windows.settle();
    final boolean[] holding = {true};
    WindowViews.holdWhile(
        tool,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return holding[0];
          }
        });
    assertTrue(WindowViews.busy(first.shell()));
    toSide(true);
    turns.runOne();
    turns.runOne();
    assertSame(first, windows.slots.get(0).window());
    holding[0] = false;
    assertFalse(WindowViews.busy(first.shell()));
    turns.drain();
    assertNotSame(first, windows.slots.get(0).window());
    assertTrue(tool.isDisposed());
  }

  @Test
  void aWindowWithANativeChooserWaitsUntilItReturns() {
    MainWindow first = windows.slots.get(0).window();
    Shell top = NativeDialogs.enter(first.shell());
    assertSame(first.shell(), top);
    assertTrue(WindowViews.busy(first.shell()));
    toSide(true);
    turns.runOne();
    turns.runOne();
    turns.runOne();
    assertSame(first, windows.slots.get(0).window());
    NativeDialogs.leave(top);
    assertFalse(WindowViews.busy(first.shell()));
    turns.drain();
    assertNotSame(first, windows.slots.get(0).window());
  }

  @Test
  void aChooserOverAToolWindowCountsOnItsMainWindow() {
    MainWindow first = windows.slots.get(0).window();
    first.showMarks();
    Shell top = NativeDialogs.enter(first.marks().shell());
    try {
      assertSame(first.shell(), top);
      assertTrue(WindowViews.busy(first.shell()));
    } finally {
      NativeDialogs.leave(top);
    }
  }

  @Test
  void aWindowOpenedAfterTheRequestIsLeftAlone() {
    toSide(true);
    windows.open(120, 120, 700, 450);
    MainWindow late = windows.slots.get(2).window();
    turns.drain();
    assertSame(late, windows.slots.get(2).window());
    assertFalse(late.shell().isDisposed());
  }

  @Test
  void aClosedWindowIsSkippedAndTheRestCountOpen() {
    MainWindow second = windows.slots.get(1).window();
    windows.controllers.get(0).closeWindow();
    windows.settle();
    assertTrue(windows.views.anyOpen());
    toSide(true);
    turns.drain();
    assertNotSame(second, windows.slots.get(1).window());
    assertTrue(windows.views.anyOpen());
  }
}
