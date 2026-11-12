package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.WindowSet;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The macOS application menu: About, Preferences and Quit run {@link CommandId#ABOUT}, {@link
 * CommandId#PREFERENCES} and {@link CommandId#QUIT} in the window activated last, so the menu bar
 * leaves them out ({@link io.github.ajdailey.qlogg.app.command.MenuTable}). Hide, Hide Others and
 * Show All stay native. A quit request from the system (Cmd-Q, logout) runs {@link WindowSet#quit}
 * in place of SWT's own disposal, and a file opened from the Finder or the Dock goes to {@link
 * WindowSet#openHandoff}. Elsewhere {@link Display#getSystemMenu()} is null and nothing is added.
 */
public final class AppMenu {
  private static final Logger LOG = LoggerFactory.getLogger(AppMenu.class);

  private AppMenu() {}

  /**
   * Reports whether the system supplies the application menu.
   *
   * @param display the Display
   * @return true on cocoa
   */
  public static boolean present(Display display) {
    return PlatformTraits.PLATFORM.systemAppMenu() && display.getSystemMenu() != null;
  }

  /**
   * Adds the listeners when the application menu is present; call once, on the UI thread.
   *
   * @param display the Display
   * @param set the main windows
   * @return true when the listeners were added
   */
  public static boolean hook(Display display, final WindowSet set) {
    if (!present(display)) {
      return false;
    }
    Menu menu = display.getSystemMenu();
    for (MenuItem item : menu.getItems()) {
      final CommandId command = command(item.getID());
      if (command != null) {
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                run(set, command);
              }
            });
      }
    }
    display.addListener(
        SWT.Close,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.doit = false;
            set.quit();
          }
        });
    display.addListener(
        SWT.OpenDocument,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.text == null || event.text.isEmpty()) {
              return;
            }
            try {
              set.openHandoff(List.of(new FileRequest(Path.of(event.text), 0)), false);
            } catch (InvalidPathException e) {
              LOG.warn(
                  "Cannot open {}: {}",
                  LogText.oneLine(event.text),
                  LogText.oneLine(e.getMessage()));
            }
          }
        });
    return true;
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
