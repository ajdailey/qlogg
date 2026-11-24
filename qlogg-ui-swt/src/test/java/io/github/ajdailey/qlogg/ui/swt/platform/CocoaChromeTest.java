package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
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
  void cocoaChromeLeavesAPaletteColoredFolderAlone() {
    Shell shell = new Shell(display);
    CTabFolder tabs = new CTabFolder(shell, SWT.NONE);
    tabs.setData(ChromeColors.PALETTE_COLORED, Boolean.TRUE);
    RGB mock = new RGB(0x12, 0x34, 0x56);
    tabs.setForeground(new Color(mock));
    tabs.setSelectionForeground(new Color(mock));
    tabs.setSelectionBackground(new Color(mock));
    CocoaChrome chrome = new CocoaChrome(display, false);
    try {
      chrome.apply(true);
      assertEquals(mock, tabs.getForeground().getRGB());
      assertEquals(mock, tabs.getSelectionForeground().getRGB());
      assertEquals(mock, tabs.getSelectionBackground().getRGB());
      chrome.apply(false);
      assertEquals(mock, tabs.getForeground().getRGB());
      assertEquals(mock, tabs.getSelectionBackground().getRGB());
    } finally {
      shell.dispose();
    }
  }

  @Test
  void cocoaChromeFollowsAnAppearanceThatDiffersFromTheStartupOne() {
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
    CocoaChrome chrome = new CocoaChrome(display, false);
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
  void cocoaChromeGivesHeadersExplicitColorsInBothDirections() {
    assertNotEquals(
        CocoaChrome.headerBackground(true).getRGB(), CocoaChrome.headerBackground(false).getRGB());
    assertNotEquals(
        CocoaChrome.headerForeground(true).getRGB(), CocoaChrome.headerBackground(true).getRGB());
    assertNotEquals(
        CocoaChrome.headerForeground(false).getRGB(), CocoaChrome.headerBackground(false).getRGB());
    Shell shell = new Shell(display);
    shell.setLayout(new FillLayout());
    Table table = new Table(shell, SWT.BORDER);
    table.setHeaderVisible(true);
    new TableColumn(table, SWT.LEFT).setText("mock");
    Tree tree = new Tree(shell, SWT.BORDER);
    tree.setHeaderVisible(true);
    new TreeColumn(tree, SWT.LEFT).setText("mock");
    shell.open();
    CocoaChrome chrome = new CocoaChrome(display, false);
    for (boolean dark : new boolean[] {true, false}) {
      chrome.apply(dark);
      String name = dark ? "dark" : "light";
      assertEquals(
          CocoaChrome.headerBackground(dark).getRGB(), table.getHeaderBackground().getRGB(), name);
      assertEquals(
          CocoaChrome.headerForeground(dark).getRGB(), table.getHeaderForeground().getRGB(), name);
      assertEquals(
          CocoaChrome.headerBackground(dark).getRGB(), tree.getHeaderBackground().getRGB(), name);
      assertEquals(
          CocoaChrome.headerForeground(dark).getRGB(), tree.getHeaderForeground().getRGB(), name);
      assertTrue(table.getHeaderVisible(), name);
    }
    shell.dispose();
  }
}
