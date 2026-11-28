package io.github.ajdailey.qlogg.ui.swt.archfixture;

import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Viewport;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SwtCallable;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;

/** View classes that each break one architecture rule; never instantiated. */
final class ViewViolations {
  private ViewViolations() {}

  /** Uses a {@code core.search} enum, which is allowed, and a {@code core.watch} record. */
  static final class UsesWatchRecord {
    MatchMode mode() {
      return MatchMode.REGEX;
    }

    int poll() {
      return MonitorSettings.DEFAULTS.pollMillis();
    }
  }

  /** Reaches the document model. */
  static final class ReachesDocument {
    boolean inMemory(LogDocument document) {
      return document.inMemory();
    }
  }

  /** Reads the viewport, which is allowed, and moves it. */
  static final class MovesViewport {
    long scroll(Viewport viewport) {
      viewport.scrollBy(1);
      return viewport.topLine();
    }
  }

  /** Blocks in every banned way. */
  static final class Blocks {
    int charsets() {
      return Charset.availableCharsets().size();
    }

    void block(
        Object monitor,
        Lock lock,
        Condition condition,
        Semaphore semaphore,
        BlockingQueue<String> queue,
        CountDownLatch latch,
        OperationHandle handle)
        throws InterruptedException {
      Thread.sleep(1);
      monitor.wait();
      lock.lock();
      lock.lockInterruptibly();
      condition.await();
      semaphore.acquire();
      queue.put("x");
      queue.take();
      latch.await();
      handle.await(1);
    }
  }

  // Highlight rule composition and validation.

  /** Composes a highlighter and compiles a rule. */
  static final class CompilesHighlightRule {
    boolean compiles(HighlightRule rule) {
      return Highlighter.of(List.of(rule)) != null && Highlighter.compile(rule) != null;
    }
  }

  // Menu command state.

  /** Reads the palette, which is allowed, and check state that {@code CommandStates} maps. */
  static final class ReadsCheckState {
    boolean dark(
        io.github.ajdailey.qlogg.app.controller.ApplicationController controller,
        io.github.ajdailey.qlogg.app.controller.DocumentController document) {
      return controller.theme().palette() != null
          && controller.theme().mode() == io.github.ajdailey.qlogg.app.theme.ThemeMode.DARK
          && controller.isFavorite(document);
    }
  }

  /** Sets a tool item's and a button's image outside {@code ToolIcons}. */
  static final class SetsIcon {
    void set(
        org.eclipse.swt.widgets.ToolItem item,
        org.eclipse.swt.widgets.Button button,
        org.eclipse.swt.graphics.Image image) {
      item.setImage(image);
      button.setImage(image);
    }
  }

  /** Creates an owned shell without the dialog colors or the window icons. */
  static final class OwnsUncoloredShell {
    org.eclipse.swt.widgets.Shell open(org.eclipse.swt.widgets.Shell parent) {
      return new org.eclipse.swt.widgets.Shell(parent, org.eclipse.swt.SWT.DIALOG_TRIM);
    }
  }

  /** Creates a native tab folder outside the platform package. */
  static final class CreatesTabFolder {
    org.eclipse.swt.widgets.TabFolder open(org.eclipse.swt.widgets.Shell parent) {
      return new org.eclipse.swt.widgets.TabFolder(parent, SWT.TOP);
    }
  }

  /** Creates a list outside {@code Dialogs.list}. */
  static final class CreatesList {
    org.eclipse.swt.widgets.List open(org.eclipse.swt.widgets.Shell parent) {
      return new org.eclipse.swt.widgets.List(parent, SWT.SINGLE);
    }
  }

  /** Shows a table's grid lines outside the platform package. */
  static final class ShowsGridLines {
    void open(org.eclipse.swt.widgets.Table table) {
      table.setLinesVisible(true);
    }
  }

  /** Sets SWT's win32 header divider color key outside Win32HeaderLines. */
  static final class SetsHeaderLineColor {
    void open(Display display) {
      display.setData("org.eclipse.swt.internal.win32.Table.headerLineColor", null);
    }
  }

  /** Opens a native chooser outside NativeDialogs. */
  static final class OpensFileDialog {
    String choose(org.eclipse.swt.widgets.Shell parent) {
      return new org.eclipse.swt.widgets.FileDialog(parent).open();
    }
  }

  // Always on Top.

  /** Reaches an SWT internal by reflection outside the Always on Top platform classes. */
  static final class ReflectsIntoSwt {
    Object handle(Display display) throws ReflectiveOperationException {
      Class.forName("org.eclipse.swt.internal.Library");
      Field field = Display.class.getDeclaredField("mock");
      field.setAccessible(true);
      return field.get(display);
    }
  }

  // Platform traits.

  /**
   * Reads the SWT platform, the GTK 4 environment and an SWT internal property outside {@code
   * PlatformTraits}.
   */
  static final class ReadsPlatform {
    boolean gtk4() {
      return "gtk".equals(SWT.getPlatform()) && System.getenv("SWT_GTK4") != null;
    }

    String gtkVersion() {
      return System.getProperty("org.eclipse.swt.internal.gtk.version");
    }
  }

  /** Reads a boolean trait outside the platform package. */
  static final class SwitchesOnATrait {
    boolean mac() {
      return PlatformTraits.PLATFORM.macKeys();
    }
  }

  /** Asks the toolkit platform questions outside the platform package. */
  static final class AsksThePlatform {
    boolean ask(Display display) {
      return display.getSystemMenu() != null
          || display.getSystemTray() != null
          || display.getSystemTaskBar() != null
          || Display.isSystemDarkTheme()
          || Program.findProgram("txt") != null;
    }
  }

  /** Branches on value traits outside their reviewed callers. */
  static final class BranchesOnAValue {
    int pick(PlatformTraits traits, OsTraits os, Path file) {
      return traits.macKeys() || os.runsInsteadOfOpening(file) ? 1 : 0;
    }
  }

  // Display access.

  /** Waits on the UI thread through {@code syncCall} and posts through {@code execute}. */
  static final class WaitsOnDisplay {
    int post(Display display, Runnable task) {
      display.execute(task);
      return display.syncCall(
          new SwtCallable<Integer, RuntimeException>() {
            @Override
            public Integer call() {
              return 1;
            }
          });
    }
  }
}
