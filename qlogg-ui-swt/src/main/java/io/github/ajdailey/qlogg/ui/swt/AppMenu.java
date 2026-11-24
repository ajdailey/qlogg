package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.WindowSet;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.ui.swt.platform.MenuBehavior;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The macOS application menu: About, Preferences and Quit run {@link CommandId#ABOUT}, {@link
 * CommandId#PREFERENCES} and {@link CommandId#QUIT} in the window activated last, so the menu bar
 * leaves them out ({@link io.github.ajdailey.qlogg.app.command.MenuTable}). Hide, Hide Others and
 * Show All stay native. A quit request from the system (Cmd-Q, logout) runs {@link WindowSet#quit}
 * in place of SWT's own disposal, and a file opened from the Finder or the Dock goes to {@link
 * WindowSet#openHandoff}. The platform's {@link MenuBehavior} connects the menu on cocoa and does
 * nothing elsewhere.
 */
public final class AppMenu {
  private static final Logger LOG = LoggerFactory.getLogger(AppMenu.class);

  private AppMenu() {}

  /**
   * Connects the application menu through the platform's {@link MenuBehavior#hookAppMenu}; call
   * once, on the UI thread.
   *
   * @param display the Display
   * @param set the main windows
   */
  public static void hook(Display display, final WindowSet set) {
    PlatformTraits.PLATFORM
        .menus()
        .hookAppMenu(
            display,
            new MenuBehavior.AppMenuActions() {
              @Override
              public void select(int id) {
                CommandId command = command(id);
                if (command != null) {
                  run(set, command);
                }
              }

              @Override
              public void quit() {
                set.quit();
              }

              @Override
              public void open(String file) {
                try {
                  set.openHandoff(List.of(new FileRequest(Path.of(file), 0)), false);
                } catch (InvalidPathException e) {
                  LOG.warn(
                      "Cannot open {}: {}", LogText.oneLine(file), LogText.oneLine(e.getMessage()));
                }
              }
            });
  }

  /**
   * Returns the command an application-menu item runs.
   *
   * @param id the item's {@link MenuItem#getID()}
   * @return the command, or null for an item that stays native
   */
  static CommandId command(int id) {
    return switch (id) {
      case SWT.ID_ABOUT -> CommandId.ABOUT;
      case SWT.ID_PREFERENCES -> CommandId.PREFERENCES;
      case SWT.ID_QUIT -> CommandId.QUIT;
      default -> null;
    };
  }

  private static void run(WindowSet set, CommandId command) {
    ApplicationController window = set.target();
    if (window != null) {
      window.execute(command);
    } else if (command == CommandId.QUIT) {
      set.quit();
    }
  }
}
