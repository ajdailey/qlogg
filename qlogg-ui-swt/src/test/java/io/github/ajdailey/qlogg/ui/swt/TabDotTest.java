package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.TabDataState;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.RuleTrigger;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tab data dot on a background tab that grows, under Xvfb. */
@Tag("swt")
class TabDotTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
    window.open();
    Path first = Files.writeString(temp.resolve("first.log"), "a\nb\nc\n");
    Path second = Files.writeString(temp.resolve("second.log"), "x\ny\n");
    controller.open(List.of(new FileRequest(first, 0), new FileRequest(second, 0)));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController shown = controller.activeDocument();
                return window.tabStrip().tabs().getItemCount() == 2
                    && shown != null
                    && shown.state().title().equals("second.log")
                    && !shown.state().indexing()
                    && !controller.documents().get(0).state().indexing();
              }
            }));
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
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  @Test
  void aGrownBackgroundTabShowsTheDotUntilShown() throws Exception {
    final CTabFolder tabs = window.tabStrip().tabs();
    final CTabItem first = tabs.getItem(0);
    assertNull(first.getImage(), "initial indexing is not growth");
    assertNull(tabs.getItem(1).getImage());
    Files.writeString(
        temp.resolve("first.log"), "d\n", StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return first.getImage() != null;
              }
            }));
    Image lit = first.getImage();
    assertSame(window.tabStrip().tabDots().image(TabDataState.NEW_LINES), lit);
    assertTrue(first.getToolTipText().endsWith("\nNew lines"), first.getToolTipText());
    controller.setTheme(ThemeMode.DARK);
    assertNotSame(lit, first.getImage(), "a palette change recolors the dot");
    assertTrue(lit.isDisposed(), "the old palette's dots are disposed");
    assertSame(window.tabStrip().tabDots().image(TabDataState.NEW_LINES), first.getImage());
    Event event = new Event();
    event.item = first;
    tabs.setSelection(0);
    tabs.notifyListeners(SWT.Selection, event);
    assertNull(first.getImage(), "showing the tab clears the dot");
    assertEquals(controller.documents().get(0).state().path(), first.getToolTipText());
  }

  @Test
  void theDotIsAnOpaqueCircleOnATransparentSquare() {
    Rgb color = Palette.LIGHT.newMatchesDot();
    ImageData data = TabDots.render(color, 2 * TabDots.SIZE);
    int center = TabDots.SIZE;
    assertEquals(255, data.getAlpha(center, center));
    assertEquals(
        color.red() << 16 | color.green() << 8 | color.blue(), data.getPixel(center, center));
    assertEquals(0, data.getAlpha(0, 0));
    assertTrue(data.getAlpha(center, 2) > 0, "the rim reaches the top");
  }

  // Keyword triggers.

  @Test
  void aTriggerThatStopsFollowGivesABackgroundTabItsOwnDot() throws Exception {
    HighlightRule stop =
        HighlightRule.matchOnly("FATAL", MatchMode.LITERAL, null, null)
            .withTrigger(new RuleTrigger(true, false, "", false));
    HighlightSet set = new HighlightSet("mock-stop", "Mock stop", List.of(stop));
    controller.setHighlightSettings(
        controller.highlightSettings().withSets(List.of(set)).withActive(List.of("mock-stop")));
    final DocumentController background = controller.documents().get(0);
    background.setFollowing(true);
    final CTabItem first = window.tabStrip().tabs().getItem(0);
    Files.writeString(
        temp.resolve("first.log"),
        "FATAL d\n",
        StandardCharsets.US_ASCII,
        StandardOpenOption.APPEND);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return first.getImage()
                    == window.tabStrip().tabDots().image(TabDataState.FOLLOW_STOPPED);
              }
            }));
    assertTrue(!background.following());
    assertTrue(
        first.getToolTipText().endsWith("\n" + UiText.TAB_DATA_FOLLOW_STOPPED.text()),
        first.getToolTipText());
    Rgb color = Palette.LIGHT.followStoppedDot();
    ImageData data = TabDots.render(color, 2 * TabDots.SIZE);
    assertEquals(
        color.red() << 16 | color.green() << 8 | color.blue(),
        data.getPixel(TabDots.SIZE, TabDots.SIZE));
  }
}
