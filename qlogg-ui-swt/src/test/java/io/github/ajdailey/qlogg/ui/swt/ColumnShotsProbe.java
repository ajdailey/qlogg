package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes the main window in column view over a generated CSV file and a generated CLEF file, in
 * Light and Dark, as {@code columns-csv-light.png} and so on into the directory {@code
 * -Dqlogg.column.shots} names. Not part of the gate.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qlogg.column.shots", matches = ".+")
class ColumnShotsProbe {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;

  @Test
  void shoot() throws Exception {
    Path out = Path.of(System.getProperty("qlogg.column.shots"));
    Files.createDirectories(out);
    Path csv = temp.resolve("orders.csv");
    Path clef = temp.resolve("service.clef");
    StringBuilder rows = new StringBuilder("time,level,user,order,amount,note\n");
    StringBuilder events = new StringBuilder();
    String[] levels = {"INFO", "INFO", "WARN", "INFO", "ERROR"};
    String[] clefLevels = {"Information", "Debug", "Warning", "Information", "Error"};
    for (int i = 0; i < 60; i++) {
      rows.append(
          String.format(
              Locale.ROOT,
              "2026-11-27 10:%02d:%02d.%03d,%s,mock-user-%d,ORD-%05d,%d.%02d,\"mock note %d, ok\"\n",
              i / 60,
              i % 60,
              i * 7 % 1000,
              levels[i % 5],
              i % 7,
              1000 + i,
              i * 13 % 500,
              i % 100,
              i));
      events.append(
          String.format(
              Locale.ROOT,
              "{\"@t\":\"2026-11-27T10:00:%02d.%03dZ\",\"@mt\":\"Request {Path} took {Elapsed} ms\","
                  + "\"@l\":\"%s\",\"Path\":\"/mock/api/%d\",\"Elapsed\":%d}\n",
              i % 60,
              i * 11 % 1000,
              clefLevels[i % 5],
              i % 9,
              i * 17 % 300));
    }
    Files.writeString(csv, rows.toString(), StandardCharsets.UTF_8);
    Files.writeString(clef, events.toString(), StandardCharsets.UTF_8);
    display = new Display();
    Workers workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                error.printStackTrace();
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
    final MainWindow window = new MainWindow(display);
    final ApplicationController controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            services,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
            Preferences.DEFAULTS);
    try {
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 1100, 600), null);
      window.open();
      String[] names = {"csv", "clef"};
      Path[] files = {csv, clef};
      for (int i = 0; i < files.length; i++) {
        open(controller, files[i]);
        controller.execute(CommandId.THEME_LIGHT);
        save(window, out.resolve("columns-" + names[i] + "-light.png"));
        controller.execute(CommandId.THEME_DARK);
        save(window, out.resolve("columns-" + names[i] + "-dark.png"));
      }
    } finally {
      controller.shutdown();
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.shell().isDisposed();
            }
          },
          BOUND);
      display.dispose();
      workers.close();
    }
  }

  private void open(final ApplicationController controller, final Path file) {
    controller.openFile(file, 0);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return shown != null
                    && shown.state().title().equals(file.getFileName().toString())
                    && shown.columns().shown()
                    && !shown.columns().layout().unfitted()
                    && shown.lines().cellView();
              }
            },
            BOUND),
        file.toString());
  }

  private void save(MainWindow window, Path file) {
    final long until = System.nanoTime() + 700_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
    Rectangle area = window.shell().getClientArea();
    Image image = new Image(display, area.width, area.height);
    GC gc = new GC(image);
    try {
      window.shell().print(gc);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(file.toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }
}
