package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.FontSettings;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.CommandStates;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The composed main window over a real document, driven through SWT events under Xvfb. */
@Tag("swt")
class LogViewTest {
  /** Label of the primary modifier: Cmd on macOS, Ctrl elsewhere. */
  private static final String MOD1 = "cocoa".equals(SWT.getPlatform()) ? "Cmd+" : "Ctrl+";

  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int LINES = 200;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    MemoryBudget budget = MemoryBudget.of(1L << 28);
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    window = new MainWindow(display);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
    window.open();
    controller.openFile(write(), 0);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return window.canvas() != null
                    && document != null
                    && !document.state().indexing()
                    && document.lines().contains(document.viewport().topLine())
                    && document.viewport().rows() > 5;
              }
            },
            BOUND));
  }

  @AfterEach
  void close() {
    controller.shutdown();
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        },
        BOUND);
    display.dispose();
    assertTrue(window.shell().isDisposed());
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private Path write() throws IOException {
    Path file = temp.resolve("view.log");
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (int i = 1; i <= LINES; i++) {
        out.write(i == 3 ? "tab\there café 日本" : "line " + i);
        out.write("\r\n");
      }
    }
    return file;
  }

  private DocumentController document() {
    return controller.activeDocument();
  }

  private Canvas canvas() {
    return window.canvas().control();
  }

  private void key(int stateMask, int keyCode, char character) {
    Event event = new Event();
    event.stateMask = stateMask;
    event.keyCode = keyCode;
    event.character = character;
    canvas().notifyListeners(SWT.KeyDown, event);
  }

  private void mouse(int type, int x, int y, int stateMask) {
    Event event = new Event();
    event.button = 1;
    event.x = x;
    event.y = y;
    event.stateMask = stateMask;
    canvas().notifyListeners(type, event);
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private Shell shellTitled(final String title) {
    final Shell[] found = new Shell[1];
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (Shell shell : display.getShells()) {
              if (!shell.isDisposed() && title.equals(shell.getText())) {
                found[0] = shell;
                return true;
              }
            }
            return false;
          }
        });
    return found[0];
  }

  private static <T extends Widget> T find(Control root, Class<T> type, String text) {
    if (type.isInstance(root)) {
      T widget = type.cast(root);
      String label =
          widget instanceof Button button
              ? button.getText()
              : widget instanceof Link link ? link.getText() : null;
      if (text == null || text.equals(label)) {
        return widget;
      }
    }
    if (root instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        T found = find(child, type, text);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  @Test
  void paintsTextAndLineNumbers() {
    int width = canvas().getClientArea().width;
    int height = window.canvas().lineHeight();
    Image image = new Image(display, canvas().getSize().x, canvas().getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(canvas().print(gc));
      ImageData data = image.getImageData();
      int gutter = window.canvas().gutterWidth();
      assertTrue(gutter > 0, "gutter width " + gutter);
      int gutterBackground = data.getPixel(1, 1);
      int dark = 0;
      int numberInk = 0;
      for (int x = 0; x < width; x++) {
        for (int y = 0; y < height; y++) {
          int pixel = data.getPixel(x, y);
          if (x < gutter) {
            if (pixel != gutterBackground) {
              numberInk++;
            }
          } else {
            RGB rgb = data.palette.getRGB(pixel);
            if (rgb.red + rgb.green + rgb.blue < 200) {
              dark++;
            }
          }
        }
      }
      assertTrue(dark > 20, "text pixels " + dark);
      assertTrue(numberInk > 20, "line number pixels " + numberInk);
    } finally {
      gc.dispose();
      image.dispose();
    }
    assertTrue(window.infoText().contains("UTF-8"), window.infoText());
    assertTrue(window.positionText().contains("/200"), window.positionText());
    // The setup waits for the top line only; the rest of the block can arrive later.
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().lines().contains(2);
              }
            }),
        "line 2 never fetched");
    assertEquals("tab     here café 日本", document().lines().text(2));
  }

  @Test
  void keysRunCommandsIncludingASequence() {
    key(0, SWT.ARROW_DOWN, '\0');
    assertEquals(1, document().selection().caret().line());
    key(0, 'j', 'j');
    assertEquals(2, document().selection().caret().line());
    key(SWT.MOD2, SWT.ARROW_DOWN, '\0');
    assertEquals(new TextPosition(2, 0), document().selection().anchor());
    assertEquals(3, document().selection().caret().line());
    assertTrue(document().lineNumbers());
    key(SWT.MOD1, 'e', (char) 5);
    key(SWT.MOD1, 't', (char) 20);
    assertFalse(document().lineNumbers());
    key(SWT.MOD1, SWT.END, '\0');
    assertEquals(LINES - 1, document().selection().caret().line());
    key(SWT.MOD2, 'g', 'G');
    assertTrue(document().viewport().atBottom());
  }

  @Test
  void searchBarSearchesAndFindKeysWalkTheMatches() {
    final SearchBar bar = window.searchBar();
    assertTrue(bar.pattern().isEnabled());
    bar.pattern().setText("line 1");
    bar.searchButton().notifyListeners(SWT.Selection, new Event());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return bar.statusText().equals("111 matches") && window.busy().active() == 0;
              }
            }));
    key(0, 'n', 'n');
    assertEquals(9, document().selection().caret().line());
    key(0, 'n', 'n');
    assertEquals(10, document().selection().caret().line());
    key(SWT.MOD2, 'n', 'N');
    assertEquals(9, document().selection().caret().line());
    key(SWT.MOD2, 'n', 'N');
    assertEquals(0, document().selection().caret().line());
    bar.pattern().setText("(unclosed");
    bar.pattern().notifyListeners(SWT.DefaultSelection, new Event());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return bar.statusText().startsWith("Invalid regular expression");
              }
            }));
    controller.execute(CommandId.CLOSE);
    assertFalse(bar.pattern().isEnabled());
    assertEquals("", bar.statusText());
  }

  @Test
  void mouseSelectsAndCtrlCCopies() {
    int row = window.canvas().lineHeight();
    TextPosition start = window.canvas().hit(10_000, row + 1);
    mouse(SWT.MouseDown, 10_000, row + 1, 0);
    assertEquals(start, document().selection().caret());
    TextPosition end = window.canvas().hit(0, 3 * row + 1);
    mouse(SWT.MouseMove, 0, 3 * row + 1, 0);
    mouse(SWT.MouseUp, 0, 3 * row + 1, 0);
    assertEquals(start, document().selection().anchor());
    assertEquals(new TextPosition(3, 0), end);
    assertEquals(end, document().selection().caret());
    key(SWT.MOD1, 'c', (char) 3);
    final Clipboard clipboard = new Clipboard(display);
    try {
      final String nl = System.lineSeparator();
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  Object text = clipboard.getContents(TextTransfer.getInstance());
                  return text != null && text.toString().endsWith(nl);
                }
              }),
          "clipboard " + clipboard.getContents(TextTransfer.getInstance()));
      String expected = nl + "tab\there café 日本" + nl;
      assertEquals(expected, clipboard.getContents(TextTransfer.getInstance()));
    } finally {
      clipboard.dispose();
    }
  }

  @Test
  void scrollBarAndWheelScroll() {
    window.canvas().verticalScrollBar().setSelection(100);
    Event drag = new Event();
    drag.detail = SWT.DRAG;
    window.canvas().verticalScrollBar().notifyListeners(SWT.Selection, drag);
    assertEquals(100, document().viewport().topLine());
    Event wheel = new Event();
    wheel.count = -3;
    canvas().notifyListeners(SWT.MouseVerticalWheel, wheel);
    assertEquals(103, document().viewport().topLine());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document().lines().contains(103);
              }
            }));
    assertEquals("line 104", document().lines().text(103));
    assertEquals(103, window.canvas().verticalScrollBar().getSelection());
  }

  @Test
  void menuBarComesFromTheKeymap() {
    Menu bar = window.shell().getMenuBar();
    String[] titles = new String[bar.getItemCount()];
    for (int i = 0; i < titles.length; i++) {
      titles[i] = bar.getItem(i).getText();
    }
    assertEquals(
        List.of(
            "&File",
            "&Edit",
            "&Search",
            "G&o",
            "&View",
            "Hi&ghlighters",
            "E&ncoding",
            "&Tools",
            "&Window",
            "&Help"),
        List.of(titles));
    MenuItem open = bar.getItem(0).getMenu().getItem(0);
    assertEquals("&Open...\t" + MOD1 + "O", open.getText());
    assertEquals(SWT.MOD1 | 'O', open.getAccelerator());
    MenuItem copy = bar.getItem(1).getMenu().getItem(0);
    assertEquals("&Copy\t" + MOD1 + "C", copy.getText());
    assertEquals(0, copy.getAccelerator());
    // GTK parses the text after the tab as one accelerator: one key, never a list or a sequence.
    for (MenuItem top : bar.getItems()) {
      for (MenuItem item : top.getMenu().getItems()) {
        String text = item.getText();
        int tab = text.indexOf('\t');
        String keys = tab < 0 ? "" : text.substring(tab + 1);
        assertFalse(keys.contains(", ") || keys.contains(" "), text);
      }
    }
    MenuItem findNext = item(bar, CommandId.FIND_NEXT);
    assertTrue(findNext.getText().endsWith("\tF3"), findNext.getText());
  }

  private static MenuItem item(Menu bar, CommandId command) {
    for (MenuItem top : bar.getItems()) {
      for (MenuItem item : top.getMenu().getItems()) {
        if (item.getData() == command) {
          return item;
        }
      }
    }
    throw new AssertionError("no menu item for " + command);
  }

  @Test
  void menusFollowTheLayoutOrder() {
    Menu bar = window.shell().getMenuBar();
    assertEquals(
        onThisPlatform(
            List.of(
                "Open",
                "Open from Clipboard",
                "-",
                "Recent Files",
                "Reopen Closed Tab",
                "Recently Closed",
                "Favorites",
                "-",
                "Open Containing Folder",
                "Open in Editor",
                "-",
                "Save Selection to File",
                "Save Filtered Results to File",
                "-",
                "Close",
                "Close All",
                "Close Multiple Tabs",
                "-",
                "Exit")),
        labels(bar.getItem(0).getMenu()));
    assertEquals(
        List.of(
            "Copy",
            "Copy This Line",
            "Copy with Line Numbers",
            "Select All",
            "-",
            "Set Selection Start",
            "Set Selection End",
            "Block Selection",
            "-",
            "Copy Full Path",
            "Copy File Name"),
        labels(bar.getItem(1).getMenu()));
    Menu search = bar.getItem(2).getMenu();
    assertEquals(
        List.of(
            "Find",
            "Find Next",
            "Find Previous",
            "-",
            "Search Field",
            "Clear Results",
            "Search All Open Files",
            "Search in Folder",
            "Next Folder Match",
            "Previous Folder Match",
            "-",
            "Add Selection to Search",
            "Exclude Selection from Search",
            "Replace Search with Selection",
            "Select Range Around Line",
            "-",
            "Search Options",
            "Search Limits",
            "-",
            "Edit Search History",
            "Clear Search History"),
        labels(search));
    assertEquals(
        List.of(
            "Match Case",
            "Regular Expression",
            "Inverse Match",
            "Combine Expressions",
            "Range Search",
            "-",
            "Auto-Refresh",
            "Keep Results",
            "Search After Add, Exclude or Replace"),
        labels(cascade(search, "Search Options")));
    assertEquals(
        List.of("Set Search Start", "Set Search End", "Clear Search Limits"),
        labels(cascade(search, "Search Limits")));
    assertEquals(
        List.of(
            "Go to Line",
            "Go to Time",
            "Go to Top",
            "Go to Bottom",
            "-",
            "Go Back",
            "Go Forward",
            "-",
            "Mark or Unmark",
            "Next Mark",
            "Previous Mark",
            "Edit Mark Comment",
            "Marks List",
            "-",
            "Scroll All Tabs to This Time"),
        labels(bar.getItem(3).getMenu()));
    Menu view = bar.getItem(4).getMenu();
    assertEquals(
        List.of(
            "Follow File",
            "Follow New Files",
            "Follow on Overscroll",
            "-",
            "Reload",
            "Stop Loading",
            "-",
            "Overview Bar",
            "Line Numbers",
            "Line Numbers in Filtered View",
            "-",
            "Text Wrap",
            "Wrap New Files",
            "Show Whitespace",
            "Hide ANSI Colors",
            "-",
            "Folding",
            "Timestamps",
            "Filtered View",
            "-",
            "Zoom In",
            "Zoom Out",
            "Reset Zoom",
            "-",
            "Theme",
            "Always on Top",
            "-",
            "Command Palette"),
        labels(view));
    assertEquals(
        List.of("Toggle Fold", "Collapse All Folds", "Expand All Folds"),
        labels(view.getItem(16).getMenu()));
    assertEquals(
        List.of("Format", "-", "Edit Formats", "-", "Time Zone", "Time Offset", "-", "Time Spread"),
        labels(view.getItem(17).getMenu()));
    assertEquals(
        List.of(
            "Marks and Matches",
            "Marks",
            "Matches",
            "-",
            "Next Filtered View Mode",
            "Previous Filtered View Mode",
            "-",
            "Grow Main View",
            "Shrink Main View",
            "Save Splitter Position"),
        labels(view.getItem(18).getMenu()));
    assertEquals(List.of("Light", "Dark", "System"), labels(view.getItem(24).getMenu()));
    assertEquals(
        List.of(
            "Configure Highlighters",
            "Automatic Highlighters",
            "-",
            "None",
            "-",
            "Highlight Search Matches",
            "-",
            "Next Color Label",
            "Clear Color Labels",
            "Color Label"),
        labels(bar.getItem(5).getMenu()));
    assertEquals(
        onThisPlatform(
            List.of("Predefined Filters", "Save as Filter", "-", "Scratchpad", "-", "Preferences")),
        labels(bar.getItem(7).getMenu()));
    assertEquals(
        List.of(
            "New Window",
            "-",
            "Move to New Window",
            "-",
            "Next Tab",
            "Previous Tab",
            "Switch to File",
            "Opened Files"),
        labels(bar.getItem(8).getMenu()));
    assertEquals(
        onThisPlatform(
            List.of(
                "User Guide",
                "Keyboard Shortcuts",
                "Command-Line Options",
                "-",
                "Show Log",
                "Debug Log File",
                "Open Log Folder",
                "Open Settings Folder",
                "-",
                "Report an Issue",
                "-",
                "About QLogg")),
        labels(bar.getItem(9).getMenu()));
  }

  @Test
  void theLogViewContextMenuFollowsTheLayoutOrder() {
    window.canvas().menuAt(10, 1);
    Menu menu = window.canvas().control().getMenu();
    menu.setVisible(false);
    assertEquals(
        List.of(
            "Copy",
            "Copy This Line",
            "Copy with Line Numbers",
            "-",
            "Mark or Unmark",
            "Edit Mark Comment",
            "-",
            "Find Selection Forward",
            "Find Selection Backward",
            "-",
            "Add Selection to Search",
            "Exclude Selection from Search",
            "Replace Search with Selection",
            "Search Limits",
            "-",
            "Set Selection Start",
            "Set Selection End",
            "-",
            "Save Selection to File",
            "Send Selection to Scratchpad",
            "Replace Scratchpad with Selection",
            "-",
            "Toggle Fold",
            "Scroll All Tabs to This Time",
            "Time Sync",
            "-",
            "Highlighters",
            "Color Label"),
        labels(menu));
    assertEquals(
        List.of("Set Search Start", "Set Search End", "Clear Search Limits"),
        labels(menu.getItem(13).getMenu()));
  }

  /**
   * Every menu, cascade and context menu gives each item a mnemonic letter of its own. Listed
   * entries (paths, open files, sets, charsets, timestamp formats, color labels, tabs) and the
   * placeholders of empty lists carry none.
   */
  @Test
  void mnemonicsAreUniqueInEveryMenu() {
    List<String> repeated = new ArrayList<String>();
    mnemonics("menu bar", window.shell().getMenuBar(), repeated);
    window.canvas().menuAt(10, 1);
    Menu context = window.canvas().control().getMenu();
    context.setVisible(false);
    mnemonics("log view", context, repeated);
    Menu tab = window.tabStrip().showTabMenu(controller.activeDocument(), 0, 0);
    tab.setVisible(false);
    mnemonics("tab", tab, repeated);
    mnemonics("path field", window.pathField().getMenu(), repeated);
    mnemonics("search field", window.searchBar().pattern().getMenu(), repeated);
    assertEquals(List.of(), repeated);
  }

  /**
   * Theme, the filtered view modes and Encoding > Auto are radio items; a choice runs the chosen
   * item's command only, though SWT also fires for the item it clears.
   */
  @Test
  void choicesAreRadioItemsThatRunTheChosenCommand() {
    Menu bar = window.shell().getMenuBar();
    for (CommandId command : MenuTable.RADIO) {
      assertTrue((nestedItem(bar, command).getStyle() & SWT.RADIO) != 0, command.name());
    }
    MenuItem both = nestedItem(bar, CommandId.SHOW_MARKS_AND_MATCHES);
    MenuItem marks = nestedItem(bar, CommandId.SHOW_MARKS);
    CommandStates states = new CommandStates(controller);
    assertTrue(states.checked(CommandId.SHOW_MARKS_AND_MATCHES));
    both.setSelection(false);
    marks.setSelection(true);
    both.notifyListeners(SWT.Selection, new Event());
    marks.notifyListeners(SWT.Selection, new Event());
    assertTrue(states.checked(CommandId.SHOW_MARKS));
    marks.setSelection(false);
    both.setSelection(true);
    marks.notifyListeners(SWT.Selection, new Event());
    assertTrue(states.checked(CommandId.SHOW_MARKS));
    both.notifyListeners(SWT.Selection, new Event());
    assertTrue(states.checked(CommandId.SHOW_MARKS_AND_MATCHES));
  }

  /** Returns a command's item in a menu or its cascades. */
  private static MenuItem nestedItem(Menu menu, CommandId command) {
    MenuItem found = findNested(menu, command);
    assertNotNull(found, command.name());
    return found;
  }

  private static MenuItem findNested(Menu menu, CommandId command) {
    for (MenuItem item : menu.getItems()) {
      if (item.getData() == command) {
        return item;
      }
      MenuItem found = item.getMenu() == null ? null : findNested(item.getMenu(), command);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /**
   * Records each letter a menu repeats and each fixed label without one, then walks its cascades,
   * each filled as when shown.
   */
  private static void mnemonics(String name, Menu menu, List<String> repeated) {
    menu.notifyListeners(SWT.Show, new Event());
    List<Character> seen = new ArrayList<Character>();
    for (MenuItem item : menu.getItems()) {
      if ((item.getStyle() & SWT.SEPARATOR) != 0) {
        continue;
      }
      String text = item.getText();
      int tab = text.indexOf('\t');
      text = tab < 0 ? text : text.substring(0, tab);
      char key = mnemonic(text);
      if (key != 0 && seen.contains(key)) {
        repeated.add(name + ": '" + key + "' again in " + text);
      }
      boolean listed = (item.getStyle() & SWT.RADIO) != 0 && !(item.getData() instanceof CommandId);
      if (key == 0 && !listed && fixedLabel(text)) {
        repeated.add(name + ": no mnemonic in " + text);
      }
      seen.add(key);
      if (item.getMenu() != null) {
        mnemonics(name + " > " + text, item.getMenu(), repeated);
      }
    }
  }

  /** Reports whether a menu text is a bundle label rather than a listed entry or a placeholder. */
  private static boolean fixedLabel(String text) {
    Set<UiText> placeholders =
        EnumSet.of(
            UiText.RECENT_NONE,
            UiText.CLOSED_NONE,
            UiText.FAVORITES_NONE,
            UiText.TIME_SYNC_NO_TABS);
    for (UiText label : UiText.values()) {
      if (!placeholders.contains(label) && label.text().equals(text)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the lower-case letter after the first single {@code &}, or 0. */
  private static char mnemonic(String text) {
    for (int i = 0; i < text.length() - 1; i++) {
      if (text.charAt(i) == '&') {
        if (text.charAt(i + 1) != '&') {
          return Character.toLowerCase(text.charAt(i + 1));
        }
        i++;
      }
    }
    return 0;
  }

  /**
   * Drops the commands the macOS application menu carries, and the separators they leave at an end
   * or doubled, when the application menu is present.
   */
  private List<String> onThisPlatform(List<String> expected) {
    if (!AppMenu.present(display)) {
      return expected;
    }
    List<String> kept = new ArrayList<String>();
    for (String label : expected) {
      if (label.equals("Exit") || label.equals("Preferences") || label.equals("About QLogg")) {
        continue;
      }
      if (label.equals("-") && (kept.isEmpty() || kept.get(kept.size() - 1).equals("-"))) {
        continue;
      }
      kept.add(label);
    }
    while (!kept.isEmpty() && kept.get(kept.size() - 1).equals("-")) {
      kept.remove(kept.size() - 1);
    }
    return kept;
  }

  /** The cascade of a menu whose label, as {@link #labels} writes it, is {@code label}. */
  private static Menu cascade(Menu menu, String label) {
    int at = labels(menu).indexOf(label);
    assertTrue(at >= 0, label);
    return menu.getItem(at).getMenu();
  }

  /** Menu labels without mnemonics, accelerator text or ellipses; "-" for a separator. */
  private static List<String> labels(Menu menu) {
    List<String> labels = new ArrayList<String>();
    for (MenuItem item : menu.getItems()) {
      if ((item.getStyle() & SWT.SEPARATOR) != 0) {
        labels.add("-");
        continue;
      }
      String text = item.getText().replace("&", "");
      int tab = text.indexOf('\t');
      text = tab < 0 ? text : text.substring(0, tab);
      labels.add(text.endsWith("...") ? text.substring(0, text.length() - 3) : text);
    }
    return labels;
  }

  @Test
  void goToLineDialogMovesTheCaret() {
    controller.execute(CommandId.GO_TO_LINE);
    Shell dialog = shellTitled("Go to Line");
    assertNotNull(dialog);
    find(dialog, Text.class, null).setText("150");
    find(dialog, Button.class, "OK").notifyListeners(SWT.Selection, new Event());
    assertTrue(dialog.isDisposed());
    assertEquals(149, document().selection().caret().line());
    assertEquals(150, LineNumberDialog.parse(" 150 "));
    assertEquals(0, LineNumberDialog.parse("-3"));
    assertEquals(0, LineNumberDialog.parse("12a"));
  }

  @Test
  void helpWindowsOpen() {
    controller.execute(CommandId.KEYBOARD_SHORTCUTS);
    Shell shortcuts = shellTitled("Keyboard Shortcuts");
    Table table = find(shortcuts, Table.class, null);
    assertTrue(table.getItemCount() > 20);
    find(shortcuts, Text.class, null).setText("line numbers");
    assertEquals(1, table.getItemCount());
    assertEquals(MOD1 + "E " + MOD1 + "T", table.getItem(0).getText(2));
    find(shortcuts, Text.class, null).setText("right edge");
    assertEquals(1, table.getItemCount());
    assertEquals("End", table.getItem(0).getText(2));
    find(shortcuts, Text.class, null).setText("line start");
    assertEquals("Home, ^, 0", table.getItem(0).getText(2));

    controller.execute(CommandId.COMMAND_LINE_OPTIONS);
    Shell usage = shellTitled("Command-Line Options");
    assertTrue(find(usage, Text.class, null).getText().contains("+line:N"));

    controller.execute(CommandId.ABOUT);
    Shell about = shellTitled("About QLogg");
    assertNotNull(about);
    assertNotNull(find(about, Link.class, "<a>https://github.com/ajdailey/qlogg</a>"));
    Label logo = (Label) about.getChildren()[0];
    Label heading = (Label) ((Composite) about.getChildren()[1]).getChildren()[0];
    assertTrue(heading.getText().startsWith("QLogg "), heading.getText());
    assertNotNull(logo.getImage());
    assertTrue(heading.getFont().getFontData()[0].getStyle() == SWT.BOLD);
    Rectangle logoBounds = logo.getBounds();
    assertTrue(
        heading.toDisplay(0, 0).x >= logo.toDisplay(0, 0).x + logoBounds.width,
        "heading right of the logo");
    Button ok = find(about, Button.class, "OK");
    Button licensesButton = find(about, Button.class, "&Licenses");
    assertTrue(ok.toDisplay(0, 0).x > licensesButton.toDisplay(0, 0).x, "OK right of Licenses");
    find(about, Button.class, "&Licenses").notifyListeners(SWT.Selection, new Event());
    Shell licenses = shellTitled("Licenses");
    assertTrue(find(licenses, Text.class, null).getText().contains("Apache License"));
  }

  @Test
  void busyCursorShowsWhileAnyOperationIsActive() {
    final Shell shell = window.shell();
    final Cursor appStarting = display.getSystemCursor(SWT.CURSOR_APPSTARTING);
    window.setActive();
    window.setActive();
    window.setIdle();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.busy().active() == 1;
              }
            }));
    assertEquals(appStarting, shell.getCursor());
    window.setIdle();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.busy().active() == 0;
              }
            }));
    assertNull(shell.getCursor());
  }

  @Test
  void closingTheDocumentRemovesTheView() {
    controller.execute(CommandId.CLOSE);
    assertNull(window.canvas());
    assertEquals("QLogg", window.shell().getText());
  }

  @Test
  void swtKeysTranslate() {
    assertEquals(KeyStroke.of(KeyStroke.PRIMARY, 'e'), SwtKeys.stroke(SWT.MOD1, 'e', (char) 5));
    assertEquals(KeyStroke.of(0, 'G'), SwtKeys.stroke(SWT.MOD2, 'g', 'G'));
    assertEquals(new KeyStroke(0, '$'), SwtKeys.stroke(SWT.MOD2, '4', '$'));
    assertEquals(new KeyStroke(0, KeyStroke.F1), SwtKeys.stroke(0, SWT.F1, '\0'));
    assertNull(SwtKeys.stroke(SWT.MOD1, SWT.CTRL, '\0'));
    assertEquals(SWT.MOD1 | SWT.HOME, SwtKeys.accelerator(new KeyStroke(1, KeyStroke.HOME)));
  }

  /**
   * The grid places column 150 where the font draws it: with an advance rounded to whole pixels, a
   * fractional font advance moves the selection off its glyphs by a pixel fraction per column.
   */
  @Test
  void aSelectionAtColumn150SitsOnItsGlyphs() throws IOException {
    final LogCanvas old = window.canvas();
    controller.setOptions(
        controller.preferences().options().withFont(new FontSettings("", 6, 0, false, false)));
    window.shell().setSize(1000, 400);
    Path grid = temp.resolve("grid.log");
    Files.writeString(
        grid, " ".repeat(150) + "M".repeat(10) + " ".repeat(40) + "\n", StandardCharsets.US_ASCII);
    controller.openFile(grid, 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return window.canvas() != old
                    && window.canvas().document().owner() == shown
                    && shown.state().title().equals("grid.log")
                    && !shown.state().indexing()
                    && shown.lines().contains(0);
              }
            }));
    LogCanvas view = window.canvas();
    int gutter = view.gutterWidth();
    final int height = view.lineHeight();
    final int inkFrom = gutter + view.columnOffset(140);
    final int inkTo = gutter + view.columnOffset(170);
    final int backgroundX = gutter + 2;
    // The canvas width a shell size gives depends on SWT's cached window trim.
    Rectangle bounds = window.shell().getBounds();
    window.shell().setSize(bounds.width + Math.max(0, inkTo - canvasWidth()), bounds.height);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return canvasWidth() >= inkTo;
          }
        });
    int width = canvasWidth();
    assertTrue(inkTo <= width, "column 170 ends at " + inkTo + " px of a " + width + " px canvas");
    // The row's text can reach the canvas after the line is indexed.
    final ImageData[] painted = new ImageData[1];
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                painted[0] = capture();
                return hasInk(painted[0], inkFrom, inkTo, height, backgroundX);
              }
            }),
        "no text painted");
    ImageData plain = painted[0];
    document().select(new Selection(new TextPosition(0, 150), new TextPosition(0, 160)));
    ImageData selected = capture();
    assertTrue(
        plain.width >= width && selected.width >= width,
        "captured " + plain.width + " and " + selected.width + " px of a " + width + " px canvas");
    RGB background = rgbAt(plain, backgroundX, height / 2);
    int inkLeft = -1;
    int inkRight = -1;
    for (int x = inkFrom; x < inkTo; x++) {
      for (int y = 0; y < height; y++) {
        if (!rgbAt(plain, x, y).equals(background)) {
          inkLeft = inkLeft < 0 ? x : inkLeft;
          inkRight = x;
          break;
        }
      }
    }
    int band = height - 1;
    int selectedLeft = -1;
    int selectedRight = -1;
    for (int x = gutter; x < width; x++) {
      if (!rgbAt(selected, x, band).equals(rgbAt(plain, x, band))) {
        selectedLeft = selectedLeft < 0 ? x : selectedLeft;
        selectedRight = x + 1;
      }
    }
    String found =
        "advance "
            + view.advance()
            + ", ink "
            + inkLeft
            + "-"
            + inkRight
            + ", selection "
            + selectedLeft
            + "-"
            + selectedRight;
    int slack = (int) Math.ceil(view.advance() / 2);
    assertTrue(inkLeft >= 0 && selectedLeft >= 0, found);
    assertTrue(inkLeft >= selectedLeft - 1 && inkLeft - selectedLeft <= slack, found);
    assertTrue(inkRight < selectedRight + 1 && selectedRight - 1 - inkRight <= slack, found);
  }

  /** True when a pixel of the first row in {@code [from, to)} differs from the row background. */
  private static boolean hasInk(ImageData data, int from, int to, int height, int backgroundX) {
    RGB background = rgbAt(data, backgroundX, height / 2);
    for (int x = from; x < to; x++) {
      for (int y = 0; y < height; y++) {
        if (!rgbAt(data, x, y).equals(background)) {
          return true;
        }
      }
    }
    return false;
  }

  private int canvasWidth() {
    return canvas().getClientArea().width;
  }

  private ImageData capture() {
    Image image = new Image(display, canvas().getSize().x, canvas().getSize().y);
    GC gc = new GC(image);
    try {
      Gtk4.assertPrinted(canvas().print(gc));
      return image.getImageData();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static RGB rgbAt(ImageData data, int x, int y) {
    return data.palette.getRGB(data.getPixel(x, y));
  }
}
