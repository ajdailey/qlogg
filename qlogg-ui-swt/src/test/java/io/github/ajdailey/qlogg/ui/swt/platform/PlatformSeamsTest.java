package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.command.CommandGroup;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.ui.swt.EventLoop;
import io.github.ajdailey.qlogg.ui.swt.Gtk4;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
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
  void onlyWin32RebuildsTheWindowsForAChangedSide() {
    final int[] rebuilds = {0};
    WindowRebuild windows =
        new WindowRebuild() {
          @Override
          public void rebuild() {
            rebuilds[0]++;
          }
        };
    new GtkThemeFollow(display).showOnWindows(true, windows);
    new CocoaThemeFollow(display).showOnWindows(true, windows);
    new Win32ThemeFollow(display).start(ThemeMode.LIGHT);
    assertEquals(0, rebuilds[0]);
    assertEquals(Boolean.FALSE, display.getData(DialogColors.NATIVE_DARK));
    Win32ThemeFollow win32 = new Win32ThemeFollow(display);
    win32.showOnWindows(false, windows);
    assertEquals(0, rebuilds[0]);
    win32.showOnWindows(true, windows);
    assertEquals(1, rebuilds[0]);
    assertEquals(Boolean.TRUE, display.getData(DialogColors.NATIVE_DARK));
    win32.showOnWindows(true, windows);
    assertEquals(1, rebuilds[0]);
    win32.showOnWindows(false, windows);
    assertEquals(2, rebuilds[0]);
    assertEquals(Boolean.FALSE, display.getData(DialogColors.NATIVE_DARK));
  }

  @Test
  void gtkSystemModeMakesNoStartupSetting() {
    assertFalse(new GtkThemeFollow(display).start(ThemeMode.SYSTEM));
    assertNull(display.getData(DialogColors.NATIVE_DARK));
  }

  @Test
  void cocoaPinsTheResolvedSideInEveryMode() {
    CocoaThemeFollow cocoa = new CocoaThemeFollow(display);
    assertTrue(cocoa.start(ThemeMode.DARK));
    assertEquals(Boolean.TRUE, display.getData(DialogColors.NATIVE_DARK));
    assertFalse(cocoa.start(ThemeMode.LIGHT));
    assertEquals(Boolean.FALSE, display.getData(DialogColors.NATIVE_DARK));
    boolean system = Display.isSystemDarkTheme();
    assertEquals(system, cocoa.start(ThemeMode.SYSTEM));
    assertEquals(system, display.getData(DialogColors.NATIVE_DARK));
  }

  @Test
  void cocoaRecolorsShellsFromTheCachedSideToThePinnedSide() {
    RGB window = new RGB(0x32, 0x32, 0x32);
    Composite row = new Composite(shell, SWT.NONE);
    RGB cached = row.getBackground().getRGB();
    CocoaThemeFollow cocoa = new CocoaThemeFollow(display);
    cocoa.start(ThemeMode.DARK);
    cocoa.watch(
        false,
        new UiDispatcher() {
          @Override
          public void asyncExec(Runnable task) {}

          @Override
          public void timerExec(int delayMillis, Runnable task) {}

          @Override
          public boolean isUiThread() {
            return true;
          }
        },
        new Runnable() {
          @Override
          public void run() {}
        });
    Shell shown = new Shell(display);
    Composite shownRow = new Composite(shown, SWT.NONE);
    shown.open();
    assertEquals(window, shownRow.getBackground().getRGB());
    shown.dispose();
    cocoa.showOnWindows(false, WindowRebuild.NONE);
    assertEquals(Boolean.FALSE, display.getData(DialogColors.NATIVE_DARK));
    assertEquals(cached, row.getBackground().getRGB());
    cocoa.showOnWindows(true, WindowRebuild.NONE);
    assertEquals(Boolean.TRUE, display.getData(DialogColors.NATIVE_DARK));
    assertEquals(window, row.getBackground().getRGB());
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
  void onlyGtk3PadsMenus() {
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
      assertEquals(2, menu.getItemCount(), name);
    }
  }

  @Test
  void onlyCocoaGivesAToolWindowItsOwnersMenus() {
    final List<Shell> built = new ArrayList<Shell>();
    MenuBehavior.ToolMenus menus =
        new MenuBehavior.ToolMenus() {
          @Override
          public void build(Shell tool) {
            built.add(tool);
          }
        };
    new Win32MenuBehavior().toolWindowMenus(shell, menus);
    new Gtk3MenuBehavior().toolWindowMenus(shell, menus);
    new Gtk4MenuBehavior().toolWindowMenus(shell, menus);
    assertEquals(List.of(), built);
    new CocoaMenuBehavior().toolWindowMenus(shell, menus);
    assertEquals(List.of(shell), built);
  }

  /**
   * A cocoa tool window minimized while still a child window takes its owner off screen, so
   * Minimize does nothing while the stacking is unsupported.
   */
  @Test
  void cocoaMinimizeDoesNothingWhileUnsupported() {
    CocoaStacking stacking = new CocoaStacking();
    assumeTrue(!stacking.supported(), "the cocoa members resolve");
    Shell tool = new Shell(shell, SWT.SHELL_TRIM);
    tool.open();
    stacking.minimize(tool);
    assertFalse(tool.getMinimized());
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

  /**
   * Asserts a platform class leaves out the given commands, every menu otherwise as complete as
   * before, and hooks nothing.
   */
  private void assertNoSystemMenu(MenuBehavior menus, Set<CommandId> left) {
    assertEquals(left, menus.systemCommands(display));
    for (CommandGroup group : CommandGroup.values()) {
      if (group.menu()) {
        List<CommandId> expected = new ArrayList<CommandId>(MenuTable.commands(group, Set.of()));
        expected.removeAll(left);
        assertEquals(
            expected, MenuTable.commands(group, menus.systemCommands(display)), group.name());
      }
    }
    List<String> calls = new ArrayList<String>();
    menus.hookAppMenu(display, recorder(calls));
    assertEquals(List.of(), calls);
  }

  @Test
  void win32MenusKeepAboutPreferencesAndExitAndLeaveOutMinimize() {
    assertNoSystemMenu(new Win32MenuBehavior(), MenuTable.TITLE_BAR_COMMANDS);
  }

  @Test
  void gtk3MenusKeepAboutPreferencesAndExitAndLeaveOutMinimize() {
    assertNoSystemMenu(new Gtk3MenuBehavior(), MenuTable.TITLE_BAR_COMMANDS);
  }

  @Test
  void gtk4MenusKeepAboutPreferencesAndExitAndLeaveOutMinimize() {
    assertNoSystemMenu(new Gtk4MenuBehavior(), MenuTable.TITLE_BAR_COMMANDS);
  }

  @Test
  void cocoaMovesAboutPreferencesAndQuitToTheSystemMenu() {
    CocoaMenuBehavior cocoa = new CocoaMenuBehavior();
    Set<CommandId> system = cocoa.systemCommands(display);
    if (display.getSystemMenu() == null) {
      assertNoSystemMenu(cocoa, Set.of());
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
  void win32FitsAWindowBelowTheWorkAreaInsideIt() {
    shell.setBounds(114, 114, 600, 500);
    Win32WindowPlacement.fit(shell, new Rectangle(0, 0, 800, 400));
    Rectangle bounds = shell.getBounds();
    assertEquals(600, bounds.width);
    assertEquals(400, bounds.height);
    if (!Gtk4.active()) {
      assertEquals(new Rectangle(114, 0, 600, 400), bounds);
    }
    Win32WindowPlacement.fit(shell, new Rectangle(0, 0, 1600, 900));
    assertEquals(bounds, shell.getBounds());
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
  void onlyGtk4MaximizesAgainAfterOpen() {
    Shell probe = new Shell(display);
    probe.setSize(300, 200);
    probe.open();
    probe.setMaximized(true);
    assumeTrue(maximized(probe, true), "the platform did not maximize a shell");
    probe.dispose();
    shell.setSize(300, 200);
    shell.open();
    assertTrue(maximized(shell, false));
    WindowPlacement[] none = {
      new Win32WindowPlacement(), new NativeWindowPlacement(), new GtkWindowPlacement()
    };
    for (WindowPlacement placement : none) {
      placement.afterOpen(shell, true);
      assertFalse(shell.getMaximized(), placement.getClass().getSimpleName());
    }
    Gtk4WindowPlacement gtk4 = new Gtk4WindowPlacement();
    gtk4.afterOpen(shell, false);
    assertFalse(shell.getMaximized());
    gtk4.afterOpen(shell, true);
    assertTrue(maximized(shell, true), "Gtk4WindowPlacement maximizes a restored shell");
  }

  private boolean maximized(final Shell target, final boolean state) {
    return EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return target.getMaximized() == state;
          }
        },
        Duration.ofSeconds(5));
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
