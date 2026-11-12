package io.github.ajdailey.qlogg.ui.swt.archfixture;

import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Viewport;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import org.eclipse.swt.SWT;
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

  /** Creates an owned shell without the dialog colors. */
  static final class OwnsUncoloredShell {
    org.eclipse.swt.widgets.Shell open(org.eclipse.swt.widgets.Shell parent) {
      return new org.eclipse.swt.widgets.Shell(parent, org.eclipse.swt.SWT.DIALOG_TRIM);
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

  /** Reads the SWT platform and the GTK 4 environment outside {@code PlatformTraits}. */
  static final class ReadsPlatform {
    boolean gtk4() {
      return "gtk".equals(SWT.getPlatform()) && System.getenv("SWT_GTK4") != null;
    }
  }
}
