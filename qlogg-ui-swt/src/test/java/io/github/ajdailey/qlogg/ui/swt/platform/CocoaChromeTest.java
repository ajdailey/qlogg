package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qlogg.ui.swt.EventLoop;
import io.github.ajdailey.qlogg.ui.swt.SwtUiDispatcher;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link CocoaChrome} on any platform: the colors it sets per appearance and what it leaves. */
@Tag("swt")
class CocoaChromeTest {
  private static final RGB DARK_LIST = new RGB(0x1e, 0x1e, 0x1e);
  private static final Duration BOUND = Duration.ofSeconds(10);

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
  void cocoaChromeLeavesPaletteColoredControlsAlone() {
    Shell shell = new Shell(display);
    CTabFolder tabs = new CTabFolder(shell, SWT.NONE);
    tabs.setData(ChromeColors.PALETTE_COLORED, Boolean.TRUE);
    Text field = new Text(shell, SWT.SINGLE);
    field.setData(ChromeColors.PALETTE_COLORED, Boolean.TRUE);
    RGB mock = new RGB(0x12, 0x34, 0x56);
    tabs.setForeground(new Color(mock));
    tabs.setSelectionForeground(new Color(mock));
    tabs.setSelectionBackground(new Color(mock));
    field.setBackground(new Color(mock));
    CocoaChrome chrome = new CocoaChrome(display, false, new SwtUiDispatcher(display));
    try {
      chrome.apply(true);
      assertEquals(mock, tabs.getForeground().getRGB());
      assertEquals(mock, tabs.getSelectionForeground().getRGB());
      assertEquals(mock, tabs.getSelectionBackground().getRGB());
      assertEquals(mock, field.getBackground().getRGB());
      chrome.apply(false);
      assertEquals(mock, tabs.getForeground().getRGB());
      assertEquals(mock, tabs.getSelectionBackground().getRGB());
      assertEquals(mock, field.getBackground().getRGB());
    } finally {
      shell.dispose();
    }
  }

  @Test
  void cocoaChromeFollowsAPinnedSideThatDiffersFromTheCachedOne() {
    RGB window = new RGB(0x32, 0x32, 0x32);
    Shell shell = new Shell(display);
    Composite row = new Composite(shell, SWT.NONE);
    CTabFolder tabs = new CTabFolder(shell, SWT.NONE);
    Text field = new Text(shell, SWT.SINGLE);
    RGB before = row.getBackground().getRGB();
    RGB fieldBefore = field.getBackground().getRGB();
    final int[] settings = {0};
    row.addListener(
        SWT.Settings,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            settings[0]++;
          }
        });
    CocoaChrome chrome = new CocoaChrome(display, false, new SwtUiDispatcher(display));
    try {
      chrome.apply(true);
      assertEquals(window, row.getBackground().getRGB());
      assertEquals(new RGB(0xff, 0xff, 0xff), tabs.getForeground().getRGB());
      assertEquals(new RGB(0x1e, 0x1e, 0x1e), field.getBackground().getRGB());
      assertEquals(1, settings[0]);
      Shell shown = new Shell(display);
      Composite shownRow = new Composite(shown, SWT.NONE);
      shown.open();
      assertEquals(window, shownRow.getBackground().getRGB());
      shown.dispose();
    } finally {
      chrome.apply(false);
    }
    assertEquals(before, row.getBackground().getRGB());
    assertEquals(fieldBefore, field.getBackground().getRGB());
    assertEquals(2, settings[0]);
    shell.dispose();
  }

  @Test
  void cocoaChromeColorsAControlCreatedWhileTheSidesDiffer() {
    Shell shell = new Shell(display);
    shell.open();
    Text before = new Text(shell, SWT.SINGLE);
    RGB cached = before.getBackground().getRGB();
    CocoaChrome chrome = new CocoaChrome(display, false, new SwtUiDispatcher(display));
    chrome.apply(true);
    Text field = new Text(shell, SWT.SINGLE);
    RGB list = new RGB(0x1e, 0x1e, 0x1e);
    pump();
    assertEquals(list, field.getBackground().getRGB());
    chrome.apply(false);
    Text after = new Text(shell, SWT.SINGLE);
    pump();
    assertEquals(cached, after.getBackground().getRGB());
    shell.dispose();
  }

  /** Runs pending events, the {@link SWT#Skin} events of new controls among them. */
  private void pump() {
    for (int i = 0; i < 1000 && display.readAndDispatch(); i++) {
      // Each turn runs one batch.
    }
  }

  /**
   * Drops every {@link SWT#Skin} event, as cocoa holds Skin while a menu tracks, and opens a shell
   * whose fields are not dark on this host.
   */
  private Shell skinlessShell() {
    display.addFilter(
        SWT.Skin,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.type = SWT.None;
          }
        });
    Shell shell = new Shell(display);
    shell.open();
    Text probe = new Text(shell, SWT.SINGLE);
    assumeFalse(DARK_LIST.equals(probe.getBackground().getRGB()), "the host's fields are dark");
    probe.dispose();
    return shell;
  }

  /** Runs the event loop past three sweep intervals. */
  private void runPastSweeps() {
    final boolean[] fired = new boolean[1];
    display.timerExec(
        3 * CocoaChrome.SWEEP_MILLIS,
        new Runnable() {
          @Override
          public void run() {
            fired[0] = true;
          }
        });
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return fired[0];
              }
            },
            BOUND));
  }

  /**
   * With Skin held back, a field created in a pop-up menu's {@link SWT#Show} takes the pinned side
   * at the next sweep; one created after the menu's {@link SWT#Hide}, or after a menu bar's Show,
   * keeps the cached side past three sweep intervals.
   */
  @Test
  void aControlCreatedWhileAPopupMenuShowsTakesThePinnedSideAtTheNextSweep() {
    final Shell shell = skinlessShell();
    CocoaChrome chrome = new CocoaChrome(display, false, new SwtUiDispatcher(display));
    chrome.apply(true);
    Menu menu = new Menu(shell, SWT.POP_UP);
    final Text[] created = new Text[1];
    menu.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            created[0] = new Text(shell, SWT.SINGLE);
          }
        });
    menu.notifyListeners(SWT.Show, new Event());
    assertNotEquals(DARK_LIST, created[0].getBackground().getRGB());
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return DARK_LIST.equals(created[0].getBackground().getRGB());
              }
            },
            BOUND));
    menu.notifyListeners(SWT.Hide, new Event());
    Text after = new Text(shell, SWT.SINGLE);
    runPastSweeps();
    assertNotEquals(DARK_LIST, after.getBackground().getRGB());
    Menu bar = new Menu(shell, SWT.BAR);
    bar.notifyListeners(SWT.Show, new Event());
    Text underBar = new Text(shell, SWT.SINGLE);
    runPastSweeps();
    assertNotEquals(DARK_LIST, underBar.getBackground().getRGB());
  }

  /** With Skin held back, a stale field takes the pinned side at its {@link SWT#Show}. */
  @Test
  void aStaleControlTakesThePinnedSideWhenShown() {
    Shell shell = skinlessShell();
    CocoaChrome chrome = new CocoaChrome(display, false, new SwtUiDispatcher(display));
    chrome.apply(true);
    Text field = new Text(shell, SWT.SINGLE);
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
    assertNotEquals(DARK_LIST, field.getBackground().getRGB());
    field.notifyListeners(SWT.Show, new Event());
    assertEquals(DARK_LIST, field.getBackground().getRGB());
    chrome.apply(false);
    assertNotEquals(DARK_LIST, field.getBackground().getRGB());
  }

  /**
   * A tree and its header take the list and text colors while the sides differ, and the header the
   * Display's list colors back on the cached side, where a check box loses its clear background.
   */
  @Test
  void cocoaChromeColorsTreesAndRestoresCheckBoxes() {
    Shell shell = new Shell(display);
    Tree tree = new Tree(shell, SWT.NONE);
    tree.setHeaderVisible(true);
    new TreeColumn(tree, SWT.LEFT).setText("mock");
    Button check = new Button(shell, SWT.CHECK);
    RGB checkBefore = check.getBackground().getRGB();
    CocoaChrome chrome = new CocoaChrome(display, false, new SwtUiDispatcher(display));
    try {
      chrome.apply(true);
      assertEquals(new RGB(0x1e, 0x1e, 0x1e), tree.getBackground().getRGB());
      assertEquals(new RGB(0xff, 0xff, 0xff), tree.getHeaderForeground().getRGB());
      assertEquals(new RGB(0x1e, 0x1e, 0x1e), tree.getHeaderBackground().getRGB());
    } finally {
      chrome.apply(false);
    }
    assertEquals(
        display.getSystemColor(SWT.COLOR_LIST_FOREGROUND).getRGB(),
        tree.getHeaderForeground().getRGB());
    assertEquals(checkBefore, check.getBackground().getRGB());
    shell.dispose();
  }

  /**
   * A table header gets explicit colors from creation, on the cached side too, and stays visible
   * through each change of side; {@link ThemeFollow#HEADER_DARK} records the side its colors are
   * for.
   */
  @Test
  void cocoaChromeGivesTableHeadersExplicitColorsOnEitherSide() {
    CocoaChrome chrome = new CocoaChrome(display, false, new SwtUiDispatcher(display));
    Shell shell = new Shell(display);
    shell.setLayout(new FillLayout());
    try {
      Table table = new Table(shell, SWT.BORDER);
      table.setHeaderVisible(true);
      new TableColumn(table, SWT.LEFT).setText("mock");
      shell.open();
      pump();
      assertEquals(Boolean.FALSE, table.getData(ThemeFollow.HEADER_DARK));
      chrome.apply(true);
      assertEquals(Boolean.TRUE, table.getData(ThemeFollow.HEADER_DARK));
      assertEquals(new RGB(0x1e, 0x1e, 0x1e), table.getHeaderBackground().getRGB());
      assertEquals(new RGB(0xff, 0xff, 0xff), table.getHeaderForeground().getRGB());
      assertTrue(table.getHeaderVisible());
      chrome.apply(false);
      assertEquals(Boolean.FALSE, table.getData(ThemeFollow.HEADER_DARK));
      assertEquals(
          display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB(),
          table.getHeaderBackground().getRGB());
      assertTrue(table.getHeaderVisible());
    } finally {
      chrome.apply(false);
      shell.dispose();
    }
  }
}
