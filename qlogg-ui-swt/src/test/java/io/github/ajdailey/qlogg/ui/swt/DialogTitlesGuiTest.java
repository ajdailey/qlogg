package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.MainView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opens every dialog and tool window over a main window and checks each shell title: no mnemonic
 * marker and no trailing ellipsis, which a title built from a menu label would carry.
 */
@Tag("swt")
class DialogTitlesGuiTest {
  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;

  @AfterEach
  void close() {
    try {
      if (windows != null) {
        windows.close();
        assertTrue(windows.failures.isEmpty(), windows.failures.toString());
      }
    } finally {
      if (display != null && !display.isDisposed()) {
        display.dispose();
      }
    }
  }

  @Test
  void noShellTitleCarriesAMnemonicOrAnEllipsis() throws IOException {
    display = new Display();
    windows = new SlotWindows(display, temp.resolve("config"), ThemeMode.LIGHT, null);
    final ApplicationController controller = windows.open(40, 40, 1000, 640);
    final MainWindowSlot slot = windows.slots.get(0);
    Path file =
        Files.writeString(
            temp.resolve("titles.log"), "2026-11-01 12:00:00 INFO mock line\n".repeat(50));
    controller.open(List.of(new FileRequest(file, 0)));
    assertTrue(
        windows.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.activeDocument() != null
                    && !controller.activeDocument().state().indexing()
                    && slot.window().canvas() != null;
              }
            }),
        "document open");
    final MainView.NameListener ignore =
        new MainView.NameListener() {
          @Override
          public void nameChosen(String name) {}
        };
    List<String> titles = new ArrayList<String>();
    for (final CommandId command : Win32DarkDialogsTest.DIALOGS) {
      title(
          command.name(),
          new Runnable() {
            @Override
            public void run() {
              controller.execute(command);
            }
          },
          titles);
    }
    title(
        "rename tab",
        new Runnable() {
          @Override
          public void run() {
            slot.askTabName("mock", ignore);
          }
        },
        titles);
    title(
        "mark comment",
        new Runnable() {
          @Override
          public void run() {
            slot.askMarkComment(0, "", ignore);
          }
        },
        titles);
    title(
        "save as filter",
        new Runnable() {
          @Override
          public void run() {
            slot.askFilterName("mock", ignore);
          }
        },
        titles);
    title(
        "search in open files",
        new Runnable() {
          @Override
          public void run() {
            slot.showMultiSearch();
          }
        },
        titles);
    title(
        "search in folder",
        new Runnable() {
          @Override
          public void run() {
            slot.showFolderSearch();
          }
        },
        titles);
    title(
        "log",
        new Runnable() {
          @Override
          public void run() {
            slot.showLog("mock log");
          }
        },
        titles);
    List<String> wrong = new ArrayList<String>();
    for (String title : titles) {
      if (title.contains("&") || title.endsWith("...")) {
        wrong.add(title);
      }
    }
    assertEquals(List.of(), wrong, "titles " + titles);
    assertEquals("Rename Tab", UiText.TAB_RENAME.title());
  }

  /** Opens a shell through the action and records "name: title"; no shell records nothing. */
  private void title(String name, Runnable action, List<String> titles) {
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
    if (found[0] == null) {
      titles.add(name.toLowerCase(Locale.ROOT) + ": no shell");
      return;
    }
    titles.add(found[0].getText());
    found[0].dispose();
    windows.settle();
  }
}
