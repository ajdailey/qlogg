package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandGroup;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Each platform's class of every view seam, constructed directly so a Linux run checks the win32
 * and cocoa behavior that has no native dependency.
 */
@Tag("swt")
class PlatformSeamsTest {
  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
  }

  @AfterEach
  void close() {
    display.setDarkThemePreferred(false);
    display.dispose();
  }

  @Test
  void onlyWin32ShowsASideOnlyWhenItIsTheStartupSetting() {
    for (boolean dark : new boolean[] {false, true}) {
      for (boolean nativeDark : new boolean[] {false, true}) {
        assertTrue(new GtkThemeFollow(display).showsNow(dark, nativeDark));
        assertTrue(new CocoaThemeFollow(display).showsNow(dark, nativeDark));
        assertEquals(dark == nativeDark, new Win32ThemeFollow(display).showsNow(dark, nativeDark));
      }
    }
  }

  @Test
  void cocoaAndGtkSystemModeMakeNoStartupSetting() {
    assertFalse(new CocoaThemeFollow(display).start(ThemeMode.DARK));
    assertFalse(new GtkThemeFollow(display).start(ThemeMode.SYSTEM));
    assertNull(display.getData(DialogColors.NATIVE_DARK));
  }

  @Test
  void win32AndGtkRecordAnExplicitStartupSetting() {
    assertFalse(new Win32ThemeFollow(display).start(ThemeMode.LIGHT));
    assertEquals(Boolean.FALSE, display.getData(DialogColors.NATIVE_DARK));
    assertFalse(new GtkThemeFollow(display).start(ThemeMode.LIGHT));
    assertEquals(Boolean.FALSE, display.getData(DialogColors.NATIVE_DARK));
  }

  @Test
  void onlyWin32MenusTakeTheFixedStartupInk() {
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    MenuBehavior.Ink<String> ink =
        new MenuBehavior.Ink<String>() {
          @Override
          public String live() {
            return "live";
          }

          @Override
          public String fixed(boolean dark) {
            return "fixed " + dark;
          }
        };
    assertEquals("fixed true", new Win32MenuBehavior().ink(display, ink));
    assertEquals("live", new CocoaMenuBehavior().ink(display, ink));
    assertEquals("live", new Gtk3MenuBehavior().ink(display, ink));
    assertEquals("live", new Gtk4MenuBehavior().ink(display, ink));
  }

  @Test
  void onlyGtkFadesADisabledMenuIconOnDark() {
    final int[] faded = {0};
    MenuBehavior.FadedImage copy =
        new MenuBehavior.FadedImage() {
          @Override
          public Image get() {
            faded[0]++;
            return null;
          }
        };
    new Win32MenuBehavior().disabledOnDark(null, copy);
    new CocoaMenuBehavior().disabledOnDark(null, copy);
    assertEquals(0, faded[0]);
    new Gtk3MenuBehavior().disabledOnDark(null, copy);
    new Gtk4MenuBehavior().disabledOnDark(null, copy);
    assertEquals(2, faded[0]);
  }

  @Test
  void onlyGtk3PadsAndOnlyCocoaAddsWindowControls() {
    for (MenuBehavior menus :
        List.of(
            new Win32MenuBehavior(),
            new CocoaMenuBehavior(),
            new Gtk3MenuBehavior(),
            new Gtk4MenuBehavior())) {
      Menu menu = new Menu(shell, SWT.DROP_DOWN);
      new MenuItem(menu, SWT.PUSH).setData("icon");
      new MenuItem(menu, SWT.PUSH);
      final List<String> calls = new ArrayList<String>();
      menus.decorate(
          menu,
          new MenuBehavior.ItemImages() {
            @Override
            public boolean apply(MenuItem item) {
              calls.add("apply");
              return item.getData() != null;
            }

            @Override
            public void applyBlank(MenuItem item) {
              calls.add("blank");
            }
          });
      menus.addWindowControls(menu, shell);
      String name = menus.getClass().getSimpleName();
      boolean images = menus instanceof ImageMenuBehavior;
      boolean pads = menus instanceof Gtk3MenuBehavior;
      List<String> expected = new ArrayList<String>();
      if (images) {
        expected.add("apply");
        expected.add("apply");
      }
      if (pads) {
        expected.add("blank");
      }
      assertEquals(expected, calls, name);
      assertEquals(menus instanceof CocoaMenuBehavior ? 5 : 2, menu.getItemCount(), name);
    }
  }

  /** Records what an application menu runs. */
  private static MenuBehavior.AppMenuActions recorder(final List<String> calls) {
    return new MenuBehavior.AppMenuActions() {
      @Override
      public void select(int id) {
        calls.add("select " + id);
      }

      @Override
      public void quit() {
        calls.add("quit");
      }

      @Override
      public void open(String file) {
        calls.add("open " + file);
      }
    };
  }

  /** Asserts a platform class leaves every menu as complete as before and hooks nothing. */
  private void assertNoSystemMenu(MenuBehavior menus) {
    assertEquals(Set.of(), menus.systemCommands(display));
    for (CommandGroup group : CommandGroup.values()) {
      if (group.menu()) {
        assertEquals(
            MenuTable.commands(group, Set.of()),
            MenuTable.commands(group, menus.systemCommands(display)),
            group.name());
      }
    }
    List<String> calls = new ArrayList<String>();
    menus.hookAppMenu(display, recorder(calls));
    assertEquals(List.of(), calls);
  }

  @Test
  void win32MenusKeepAboutPreferencesAndExit() {
    assertNoSystemMenu(new Win32MenuBehavior());
  }

  @Test
  void gtk3MenusKeepAboutPreferencesAndExit() {
    assertNoSystemMenu(new Gtk3MenuBehavior());
  }

  @Test
  void gtk4MenusKeepAboutPreferencesAndExit() {
    assertNoSystemMenu(new Gtk4MenuBehavior());
  }

  @Test
  void cocoaMovesAboutPreferencesAndQuitToTheSystemMenu() {
    CocoaMenuBehavior cocoa = new CocoaMenuBehavior();
    Set<CommandId> system = cocoa.systemCommands(display);
    if (display.getSystemMenu() == null) {
      assertNoSystemMenu(cocoa);
      return;
    }
    assertEquals(MenuTable.APP_MENU_COMMANDS, system);
    assertFalse(MenuTable.commands(CommandGroup.HELP, system).contains(CommandId.ABOUT));
    assertFalse(MenuTable.commands(CommandGroup.TOOLS, system).contains(CommandId.PREFERENCES));
    assertFalse(MenuTable.commands(CommandGroup.FILE, system).contains(CommandId.QUIT));
  }

  @Test
  void cocoaReportsAGroupRole() {
    AccessibleControlEvent role = new AccessibleControlEvent(shell.getAccessible());
    role.childID = ACC.CHILDID_SELF;
    role.detail = -1;
    CocoaAccessibleLabels.GROUP_ROLE.getRole(role);
    assertEquals(ACC.ROLE_GROUP, role.detail);
  }

  @Test
  void onlyCocoaWaitsForLaunchDocuments() {
    LaunchDocuments.Wait wait =
        new LaunchDocuments.Wait() {
          @Override
          public List<String> run() {
            return List.of("mock.log");
          }
        };
    assertEquals(List.of("mock.log"), new CocoaLaunchDocuments().await(wait));
    assertEquals(List.of(), new NativeLaunchDocuments().await(wait));
  }

  @Test
  void gtk4NamesTheGuideInANotice() {
    final List<String> shown = new ArrayList<String>();
    GuideDisplay.Shows shows =
        new GuideDisplay.Shows() {
          @Override
          public boolean inBrowser(Path file) {
            shown.add("browser");
            return false;
          }

          @Override
          public void notice(Path file) {
            shown.add("notice");
          }
        };
    Path guide = Path.of("guide.html");
    assertFalse(new BrowserGuideDisplay().show(guide, shows));
    assertTrue(new NoticeGuideDisplay().show(guide, shows));
    assertEquals(List.of("browser", "notice"), shown);
  }

  @Test
  void win32PlacesAgainWithTheMeasuredTrim() {
    shell.setMenuBar(new Menu(shell, SWT.BAR));
    final List<int[]> placed = new ArrayList<int[]>();
    new Win32WindowPlacement()
        .place(
            shell,
            new WindowPlacement.Placer() {
              @Override
              public void place(int trimWidth, int trimHeight, boolean positions) {
                assertTrue(positions);
                placed.add(new int[] {trimWidth, trimHeight});
                shell.setSize(400 + trimWidth, 300 + trimHeight);
              }
            });
    Point size = shell.getSize();
    int[] last = placed.get(placed.size() - 1);
    assertTrue(placed.size() <= 2, String.valueOf(placed.size()));
    assertEquals(size.x - shell.getClientArea().width, last[0]);
    assertEquals(size.y - shell.getClientArea().height, last[1]);
  }

  @Test
  void gtkPositionsWindowsExceptUnderWayland() {
    final boolean[] positions = new boolean[1];
    new GtkWindowPlacement()
        .place(
            shell,
            new WindowPlacement.Placer() {
              @Override
              public void place(int trimWidth, int trimHeight, boolean value) {
                positions[0] = value;
              }
            });
    String backend = System.getProperty(PlatformTraits.GDK_BACKEND);
    assertEquals(!"wayland".equals(backend), positions[0], backend);
  }

  @Test
  void everyDialogColorsCheckIsACheckBoxOnItsParent() {
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    Win32DialogColors colors = new Win32DialogColors(display);
    Button plain = colors.check(shell);
    assertTrue((plain.getStyle() & SWT.CHECK) != 0);
    assertSame(shell, plain.getParent());
    Shell dialog = new Shell(shell, SWT.DIALOG_TRIM);
    colors.prepare(dialog);
    Button colored = colors.check(dialog);
    assertTrue((colored.getStyle() & SWT.CHECK) != 0);
    assertSame(dialog, colored.getShell());
    Button nativeCheck = new NativeDialogColors().check(shell);
    assertTrue((nativeCheck.getStyle() & SWT.CHECK) != 0);
  }
}
