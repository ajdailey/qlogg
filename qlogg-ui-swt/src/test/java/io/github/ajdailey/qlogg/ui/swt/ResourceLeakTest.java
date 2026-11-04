package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.graphics.DeviceData;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tab open and close, theme switches and font zoom return the Display's tracked graphics resources
 * ({@code DeviceData.tracking}) to their count before the cycles. A failure lists each leaked
 * object's allocation stack.
 */
@Tag("swt")
class ResourceLeakTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int CYCLES = 4;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() throws Exception {
    DeviceData data = new DeviceData();
    data.tracking = true;
    display = new Display(data);
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    MemoryBudget budget = MemoryBudget.of(1L << 28);
    DocumentServices services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    window = new MainWindow(display);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
            Preferences.DEFAULTS);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
    window.open();
    openTab("first.log");
  }

  @AfterEach
  void close() {
    controller.shutdown();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  /** Dispatches every pending event. */
  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private void openTab(final String name) throws IOException {
    Path file = temp.resolve(name);
    if (!Files.exists(file)) {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < 200; i++) {
        text.append("line ").append(i).append(i % 5 == 0 ? " ERROR" : "").append('\n');
      }
      Files.writeString(file, text.toString(), StandardCharsets.US_ASCII);
    }
    controller.openFile(file, 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != null
                    && shown.state().title().equals(name)
                    && !shown.state().indexing()
                    && window.canvas() != null
                    && window.canvas().document().owner() == shown
                    && shown.lines().contains(shown.viewport().topLine());
              }
            }),
        name);
    drain();
  }

  private void closeTab() {
    final int before = controller.documents().size();
    controller.execute(CommandId.CLOSE);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.documents().size() == before - 1
                    && window.canvas() != null
                    && window.canvas().document().owner() == controller.activeDocument();
              }
            }));
    drain();
  }

  private void theme(CommandId command, final Palette expected) {
    controller.execute(command);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.canvas().palette().equals(expected);
              }
            }));
    drain();
  }

  private void zoom(CommandId command) {
    final int before = window.canvas().lineHeight();
    controller.execute(command);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.canvas() != null && window.canvas().lineHeight() != before;
              }
            }));
    drain();
  }

  /** One round of every operation; the first round creates lazily cached resources. */
  private void cycle() throws IOException {
    openTab("second.log");
    closeTab();
    theme(CommandId.THEME_DARK, Palette.DARK);
    theme(CommandId.THEME_LIGHT, Palette.LIGHT);
    zoom(CommandId.ZOOM_IN);
    zoom(CommandId.ZOOM_OUT);
    window.syncMenu();
    drain();
  }

  @Test
  void tabsThemesAndZoomReleaseTheirResources() throws IOException {
    cycle();
    Map<Object, Error> baseline = tracked();
    Map<String, Integer> types = counts(baseline.keySet());
    assertTrue(types.containsKey("Font") && types.containsKey("Image"), "tracked " + types);
    for (int i = 0; i < CYCLES; i++) {
      cycle();
    }
    Map<Object, Error> after = tracked();
    assertEquals(counts(baseline.keySet()), counts(after.keySet()), leaks(baseline, after));
  }

  /** The live tracked resources with their allocation stacks. */
  private Map<Object, Error> tracked() {
    DeviceData data = display.getDeviceData();
    Map<Object, Error> live = new IdentityHashMap<Object, Error>();
    for (int i = 0; i < data.objects.length; i++) {
      live.put(data.objects[i], data.errors[i]);
    }
    return live;
  }

  private static Map<String, Integer> counts(Iterable<Object> objects) {
    Map<String, Integer> counts = new TreeMap<String, Integer>();
    for (Object object : objects) {
      counts.merge(object.getClass().getSimpleName(), 1, Integer::sum);
    }
    return counts;
  }

  /** The type and allocation stack of each resource live after the cycles and not before. */
  private static String leaks(Map<Object, Error> baseline, Map<Object, Error> after) {
    List<String> found = new ArrayList<String>();
    for (Map.Entry<Object, Error> entry : after.entrySet()) {
      if (!baseline.containsKey(entry.getKey())) {
        StringWriter stack = new StringWriter();
        entry.getValue().printStackTrace(new PrintWriter(stack));
        found.add(entry.getKey().getClass().getSimpleName() + " allocated at\n" + stack);
      }
    }
    return found.size() + " resources not in the baseline:\n" + String.join("\n", found);
  }
}
