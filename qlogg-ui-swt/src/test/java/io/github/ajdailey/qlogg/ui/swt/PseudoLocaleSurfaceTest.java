package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.about.AboutInfo;
import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.Binding;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.PseudoLocale;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The main window with a rotated set's tab, a filter tab over it, its quick find bar, a status note
 * and a notice, its menu bar, log view and tab menus, About and Licenses, and every dialog {@link
 * DialogTitlesGuiTest} opens, in the {@code en-XA} pseudo locale: every shown text is one pseudo
 * value and fits its control. Runs in the module's {@code pseudo-locale} surefire execution and
 * skips in any other. With {@code -Dqlogg.pseudo.shots=<dir>} it also saves each window as a PNG
 * there.
 */
@Tag("swt")
class PseudoLocaleSurfaceTest {
  /** The log's name. */
  private static final String LOG = "mock.log";

  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;
  private PseudoTexts texts;

  @BeforeEach
  void open() {
    assumeTrue(PseudoLocale.LOCALE.equals(Locale.getDefault()), "another surefire execution");
    display = new Display();
  }

  @AfterEach
  void close() {
    if (display == null) {
      return;
    }
    try {
      if (windows != null) {
        windows.close();
        assertTrue(windows.failures.isEmpty(), windows.failures.toString());
      }
    } finally {
      display.dispose();
    }
  }

  /** Shown text that is data, each with its reason. */
  private List<PseudoTexts.Exemption> exemptions() {
    Set<String> keys = new HashSet<String>();
    for (Binding binding : windows.set.keymap().bindings()) {
      keys.add(binding.text(false));
      keys.add(binding.text(true));
    }
    for (CommandId command : CommandId.values()) {
      keys.add(windows.set.keymap().keysText(command, SwtKeys.mac()));
    }
    keys.remove("");
    StringJoiner keyNames = new StringJoiner("|");
    for (String key : keys) {
      keyNames.add(Pattern.quote(key));
    }
    StringJoiner charsets = new StringJoiner("|");
    for (String charset : Charset.availableCharsets().keySet()) {
      charsets.add(Pattern.quote(charset));
    }
    StringJoiner zones = new StringJoiner("|");
    for (String zone : ZoneId.getAvailableZoneIds()) {
      zones.add(Pattern.quote(zone));
    }
    StringJoiner fonts = new StringJoiner("|");
    for (boolean scalable : new boolean[] {true, false}) {
      for (FontData font : display.getFontList(null, scalable)) {
        fonts.add(Pattern.quote(font.getName()));
      }
    }
    return List.of(
        new PseudoTexts.Exemption(
            "file names",
            "the test's log and its rotated file",
            Pattern.compile(Pattern.quote(LOG) + "(\\.1)?")),
        new PseudoTexts.Exemption(
            "paths", "a file's location", Pattern.compile("(/|[A-Za-z]:\\\\).*")),
        new PseudoTexts.Exemption(
            "web addresses",
            "the project's address",
            Pattern.compile(Pattern.quote(AboutInfo.PROJECT_URL))),
        new PseudoTexts.Exemption(
            "app name", "the product name", Pattern.compile(Pattern.quote("QLogg"))),
        new PseudoTexts.Exemption(
            "key names", "keys as the platform writes them", Pattern.compile(keyNames.toString())),
        new PseudoTexts.Exemption(
            "encoding names", "Java charset names", Pattern.compile(charsets.toString())),
        new PseudoTexts.Exemption(
            "time zones", "Java time zone IDs in Time Zone", Pattern.compile(zones.toString())),
        new PseudoTexts.Exemption(
            "font names", "the system's font families", Pattern.compile(fonts.toString())),
        new PseudoTexts.Exemption(
            "SWT text",
            "SWT translates its own tool tips",
            Pattern.compile(Pattern.quote(SWT.getMessage("SWT_ShowList")))),
        new PseudoTexts.Exemption(
            "colors", "hex color values in Highlighters", Pattern.compile("#\\p{XDigit}{6}")),
        new PseudoTexts.Exemption(
            "numbers", "values, counts and line numbers", Pattern.compile("[^\\p{L}]+")));
  }

  @Test
  void everyShownTextIsOnePseudoValueThatFits() throws IOException {
    assertEquals(PseudoLocale.translate("QLogg"), UiText.WINDOW_TITLE.text());
    windows = new SlotWindows(display, temp.resolve("config"), ThemeMode.LIGHT, null);
    final ApplicationController controller = windows.open(40, 40, 1000, 640);
    final MainWindowSlot slot = windows.slots.get(0);
    Path rotated =
        Files.writeString(temp.resolve(LOG + ".1"), "2026-11-01 11:00:00 INFO mock old\n");
    Files.setLastModifiedTime(rotated, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
    Path file =
        Files.writeString(temp.resolve(LOG), "2026-11-01 12:00:00 INFO mock line\n".repeat(50));
    controller.open(List.of(new FileRequest(file, 0)));
    shown(controller, slot, LOG);
    // A rotated set's tab, then a filter tab of a search over it.
    controller.execute(CommandId.INCLUDE_ROTATED);
    shown(controller, slot, UiText.TAB_SET_TITLE.format(LOG, 1));
    final DocumentController set = controller.activeDocument();
    set.search().run("mock", set.search().options());
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return set.search().state().phase() == SearchViewState.Phase.DONE;
              }
            }),
        "search done");
    controller.execute(CommandId.FILTER_TO_TAB);
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.activeDocument() != set
                    && controller.activeDocument() != null
                    && !controller.activeDocument().state().indexing()
                    && slot.window().canvas() != null;
              }
            }),
        "filter tab");
    texts =
        new PseudoTexts(
            exemptions(),
            List.of(
                new PseudoTexts.Exemption(
                    "texts holding a path",
                    "a path of any length is cut at the control's edge in every locale",
                    Pattern.compile(".*(/[^/ ]+/|[A-Za-z]:\\\\).*"))));
    controller.execute(CommandId.QUICK_FIND_FORWARD);
    controller.execute(CommandId.FOLLOW);
    controller.execute(CommandId.NEXT_MARK);
    windows.settle();
    assertTrue(!slot.window().statusText().isEmpty(), "status bar note");
    texts.shell("main window", slot.window().shell());
    shot("main window", slot.window().shell());
    Control canvas = slot.window().canvas().control();
    Event detect = new Event();
    Point at = canvas.toDisplay(10, 1);
    detect.x = at.x;
    detect.y = at.y;
    canvas.notifyListeners(SWT.MenuDetect, detect);
    texts.menu("log view menu", canvas.getMenu());
    canvas.getMenu().setVisible(false);
    Menu tabMenu = slot.window().tabStrip().showTabMenu(controller.activeDocument(), 0, 0);
    texts.menu("tab menu", tabMenu);
    tabMenu.setVisible(false);
    windows.settle();

    Shell about =
        opened(
            new Runnable() {
              @Override
              public void run() {
                controller.execute(CommandId.ABOUT);
              }
            });
    assertTrue(about != null, "about");
    check("ABOUT", about);
    final Button licenses = button(about, UiText.BUTTON_LICENSES.text());
    dialog(
        "licenses",
        new Runnable() {
          @Override
          public void run() {
            licenses.notifyListeners(SWT.Selection, new Event());
          }
        });
    about.dispose();
    windows.settle();
    for (final CommandId command : Win32DarkDialogsTest.DIALOGS) {
      dialog(
          command.name(),
          new Runnable() {
            @Override
            public void run() {
              controller.execute(command);
            }
          });
    }
    final MainView.NameListener ignore =
        new MainView.NameListener() {
          @Override
          public void nameChosen(String name) {}
        };
    dialog(
        "rename tab",
        new Runnable() {
          @Override
          public void run() {
            slot.askTabName("mock", ignore);
          }
        });
    dialog(
        "mark comment",
        new Runnable() {
          @Override
          public void run() {
            slot.askMarkComment(0, "", ignore);
          }
        });
    dialog(
        "save as filter",
        new Runnable() {
          @Override
          public void run() {
            slot.askFilterName("mock", ignore);
          }
        });
    dialog(
        "search in open files",
        new Runnable() {
          @Override
          public void run() {
            slot.showMultiSearch();
          }
        });
    dialog(
        "search in folder",
        new Runnable() {
          @Override
          public void run() {
            slot.showFolderSearch();
          }
        });
    dialog(
        "log",
        new Runnable() {
          @Override
          public void run() {
            slot.showLog("mock log");
          }
        });

    StringJoiner report = new StringJoiner("\n");
    for (String finding : texts.findings()) {
      report.add(finding);
    }
    assertEquals(0, texts.findings().size(), report.toString());
  }

  @Test
  void theWalkerReportsEachFault() {
    Shell shell = new Shell(display);
    shell.setLayout(new GridLayout(1, false));
    new Label(shell, SWT.NONE).setText("Literal text");
    new Label(shell, SWT.NONE).setText("[Öñé ļ] [Ţŵö ļ]");
    Label narrow = new Label(shell, SWT.NONE);
    narrow.setText("[Å ļàƀéļ ţöö ŵîðé ƒöŕ îţš þļàçé]");
    GridData data = new GridData();
    data.widthHint = 20;
    narrow.setLayoutData(data);
    new Label(shell, SWT.NONE).setText("[Ŵĥöļé ṽàļûé ļö]");
    new Label(shell, SWT.NONE).setText("[F??? ??]");
    shell.pack();
    shell.open();
    PseudoTexts walker = new PseudoTexts(List.of(), List.of());
    walker.shell("mock", shell);
    List<String> found = new ArrayList<String>(walker.findings());
    assertEquals(4, found.size(), found.toString());
    assertTrue(found.get(0).contains("unmarked: 'Literal text'"), found.toString());
    assertTrue(found.get(1).contains("concatenated"), found.toString());
    assertTrue(found.get(2).contains("clipped"), found.toString());
    assertTrue(found.get(3).contains("accents lost"), found.toString());
  }

  /** Waits until the shown tab has the title and is indexed. */
  private void shown(
      final ApplicationController controller, final MainWindowSlot slot, final String title) {
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != null
                    && shown.state().title().equals(title)
                    && !shown.state().indexing()
                    && slot.window().canvas() != null;
              }
            }),
        title);
  }

  /** Runs the action, checks the window it opens, and closes it. */
  private void dialog(String name, Runnable action) {
    Shell shell = opened(action);
    assertTrue(shell != null, name + " window");
    check(name, shell);
    shot(name, shell);
    shell.dispose();
    windows.settle();
  }

  /** Runs the action and returns the shell it opened, or null after 3 s. */
  private Shell opened(Runnable action) {
    final List<Shell> before = List.of(display.getShells());
    action.run();
    final Shell[] found = new Shell[1];
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (Shell shell : display.getShells()) {
              if (!before.contains(shell) && shell.isVisible()) {
                found[0] = shell;
                return true;
              }
            }
            return false;
          }
        },
        Duration.ofSeconds(3));
    windows.settle();
    return found[0];
  }

  /** Checks a window, then each page of its tab folders. */
  private void check(String where, Shell shell) {
    texts.shell(where, shell);
    for (TabFolder folder : folders(shell, new ArrayList<TabFolder>())) {
      for (int page = 0; page < folder.getItemCount(); page++) {
        folder.setSelection(page);
        folder.notifyListeners(SWT.Selection, new Event());
        windows.settle();
        texts.control(where + " page " + page, folder.getItem(page).getControl());
      }
    }
  }

  private static List<TabFolder> folders(Control control, List<TabFolder> found) {
    if (control instanceof TabFolder folder) {
      found.add(folder);
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        folders(child, found);
      }
    }
    return found;
  }

  private static Button button(Composite parent, String text) {
    for (Control child : parent.getChildren()) {
      if (child instanceof Button button && button.getText().equals(text)) {
        return button;
      }
      if (child instanceof Composite composite) {
        Button found = button(composite, text);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /**
   * Saves a window's pixels as {@code <name>.png} in the folder the {@code qlogg.pseudo.shots}
   * property names; without it, does nothing.
   */
  private void shot(String name, Shell shell) {
    String folder = System.getProperty("qlogg.pseudo.shots");
    if (folder == null) {
      return;
    }
    Point size = shell.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      shell.print(gc);
      ImageLoader loader = new ImageLoader();
      loader.data = new org.eclipse.swt.graphics.ImageData[] {image.getImageData()};
      loader.save(Path.of(folder, name + ".png").toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }
}
