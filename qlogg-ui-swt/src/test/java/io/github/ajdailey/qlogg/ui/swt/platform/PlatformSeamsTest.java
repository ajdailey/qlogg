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
import io.github.ajdailey.qlogg.app.i18n.UiText;
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
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
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
    assertFalse(stacking.minimizeSupported());
    stacking.minimize(tool);
    assertFalse(tool.getMinimized());
  }

  /**
   * Minimize works outside cocoa whether or not Always on Top does: the window manager's stacking
   * on GTK 3 under Wayland and on GTK 4 leaves it enabled and minimizes the shell.
   */
  @Test
  void minimizeIsSupportedOutsideCocoaWhateverTheStacking() {
    WindowStacking[] stackings = {
      new Win32Stacking(), new GtkX11Stacking(), new WindowManagerStacking()
    };
    for (WindowStacking stacking : stackings) {
      assertTrue(stacking.minimizeSupported(), stacking.getClass().getSimpleName());
    }
    WindowStacking manager = new WindowManagerStacking();
    assertFalse(manager.supported());
    shell.open();
    manager.minimize(shell);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return shell.getMinimized();
              }
            },
            Duration.ofSeconds(5)));
  }

  /**
   * GTK raises a link's color to the link contrast on its background, at creation and at each
   * {@code SWT.Settings}; win32 and cocoa leave it.
   */
  @Test
  void onlyGtkRaisesALinkToTheLinkContrast() {
    RGB ground = new RGB(53, 53, 53);
    ThemeFollow[] follows = {
      new Win32ThemeFollow(display), new CocoaThemeFollow(display), new GtkThemeFollow(display)
    };
    for (ThemeFollow follow : follows) {
      Composite page = new Composite(shell, SWT.NONE);
      page.setBackground(new Color(ground));
      Link link = new Link(page, SWT.NONE);
      link.setBackground(new Color(ground));
      link.setLinkForeground(new Color(53, 132, 228));
      follow.colorLink(link);
      boolean gtk = follow instanceof GtkThemeFollow;
      assertEquals(
          gtk,
          Contrast.ratio(link.getLinkForeground().getRGB(), ground) >= Contrast.LINK,
          follow.getClass().getSimpleName());
      link.setLinkForeground(new Color(53, 132, 228));
      follow.settingsChanged();
      assertEquals(
          gtk,
          Contrast.ratio(link.getLinkForeground().getRGB(), ground) >= Contrast.LINK,
          "settings " + follow.getClass().getSimpleName());
    }
  }

  /** Only GTK explains a disabled Always on Top item; win32 and cocoa leave its tool tip. */
  @Test
  void onlyGtkExplainsAnUnsupportedStacking() {
    Menu bar = new Menu(shell, SWT.BAR);
    WindowStacking[] stackings = {
      new Win32Stacking(), new CocoaStacking(), new GtkX11Stacking(), new WindowManagerStacking()
    };
    for (WindowStacking stacking : stackings) {
      MenuItem item = new MenuItem(bar, SWT.CHECK);
      stacking.explainUnsupported(item);
      // SWT keeps no menu item tool tip on GTK 4.
      boolean gtk =
          (stacking instanceof GtkX11Stacking || stacking instanceof WindowManagerStacking)
              && !Gtk4.active();
      assertEquals(
          gtk ? UiText.ALWAYS_ON_TOP_UNSUPPORTED.text() : null,
          item.getToolTipText(),
          stacking.getClass().getSimpleName());
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
  void onlyWin32DrawsCellImagesInsteadOfSettingThem() {
    Table table = new Table(shell, SWT.FULL_SELECTION);
    for (int i = 0; i < 3; i++) {
      new TableColumn(table, SWT.LEFT).setWidth(60);
    }
    assertSame(
        Win32CellImages.class, PlatformTraits.of("win32", false).cellImages(table, 2).getClass());
    for (PlatformTraits traits :
        List.of(
            PlatformTraits.of("cocoa", false),
            PlatformTraits.of("gtk", false),
            PlatformTraits.of("gtk", true))) {
      assertSame(NativeCellImages.class, traits.cellImages(table, 2).getClass(), traits.name());
    }
    Image red = new Image(display, 16, 16);
    try {
      TableItem drawn = new TableItem(table, SWT.NONE);
      Win32CellImages win32 = new Win32CellImages(table, 2);
      win32.show(drawn, red);
      assertSame(red, win32.image(drawn));
      assertNull(drawn.getImage(2), "no item image, so no column 0 slot");
      TableItem set = new TableItem(table, SWT.NONE);
      CellImages plain = new NativeCellImages(2);
      plain.show(set, red);
      assertSame(red, set.getImage(2));
      assertSame(red, plain.image(set));
    } finally {
      red.dispose();
    }
  }
}
