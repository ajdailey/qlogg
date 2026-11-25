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
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opens every dialog and tool window over a main window and checks each shell title: no mnemonic
 * marker and no trailing ellipsis, which a title built from a menu label would carry. Each table's
 * first column is created left-aligned, as win32 draws it under any style, so a dark custom-drawn
 * header sits over its cells; the Predefined Filters Regex column is left-aligned over its check
 * images. No column opens narrower than its header text plus {@link TableFit#HEADER_PADDING}, since
 * that header clips without an ellipsis.
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

  /** "name column: fault" for each column created misaligned or narrower than its header. */
  private final List<String> wrongColumns = new ArrayList<String>();

  /** Names of the shells that held a table with columns. */
  private final List<String> tabled = new ArrayList<String>();

  @Test
  void shellTitlesAndTableColumns() throws IOException {
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
    assertEquals(List.of(), wrongColumns, "columns");
    assertTrue(
        tabled.containsAll(
            List.of("MARKS_LIST", "PREDEFINED_FILTERS", "AUTO_HIGHLIGHTERS", "search in folder")),
        "tables checked " + tabled);
    assertEquals("Rename Tab", UiText.TAB_RENAME.title());
  }

  /**
   * Opens a shell through the action, records "name: title" and checks its tables' alignment; no
   * shell records nothing.
   */
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
    // GTK reads a shown column's width as 0 until the tree view is allocated.
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !anyZeroWidth(found[0]);
          }
        },
        Duration.ofSeconds(3));
    checkTables(found[0], name);
    found[0].dispose();
    windows.settle();
  }

  private static boolean anyZeroWidth(Composite parent) {
    for (Control child : parent.getChildren()) {
      if (child instanceof Table table && table.isVisible()) {
        for (TableColumn column : table.getColumns()) {
          if (column.getWidth() == 0) {
            return true;
          }
        }
      } else if (child instanceof Composite composite && anyZeroWidth(composite)) {
        return true;
      }
    }
    return false;
  }

  private void checkTables(Composite parent, String name) {
    for (Control child : parent.getChildren()) {
      if (child instanceof Table table && table.getColumnCount() > 0) {
        tabled.add(name);
        if ((table.getColumn(0).getStyle() & SWT.LEFT) == 0) {
          wrongColumns.add(name + " " + table.getColumn(0).getText() + ": not left");
        }
        if (name.equals(CommandId.PREDEFINED_FILTERS.name())
            && (table.getColumn(FiltersDialog.REGEX).getStyle() & SWT.LEFT) == 0) {
          wrongColumns.add(
              name + " " + table.getColumn(FiltersDialog.REGEX).getText() + ": not left");
        }
        GC gc = new GC(table);
        try {
          for (TableColumn column : table.getColumns()) {
            int minimum = gc.textExtent(column.getText()).x + TableFit.HEADER_PADDING;
            if (column.getWidth() < minimum - ScaleSlack.of(table)) {
              wrongColumns.add(
                  name + " " + column.getText() + ": " + column.getWidth() + " < " + minimum);
            }
          }
        } finally {
          gc.dispose();
        }
      } else if (child instanceof Composite composite) {
        checkTables(composite, name);
      }
    }
  }
}
