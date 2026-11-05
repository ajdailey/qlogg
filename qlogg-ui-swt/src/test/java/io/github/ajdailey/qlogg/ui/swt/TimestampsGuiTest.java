package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.config.TimeSettings;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.StatusText;
import io.github.ajdailey.qlogg.app.view.TimeText;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The top row's time field, the selection difference and the timestamp dialogs under Xvfb. */
@Tag("swt")
class TimestampsGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final long SECOND = 1_000_000_000L;

  /** 2026-03-01 05:00:00 UTC. */
  private static final long BASE = 1_772_341_200L * SECOND;

  private static final LocalDate DAY = LocalDate.of(2026, 3, 1);

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
            Preferences.DEFAULTS.withTime(
                new TimeSettings(ZoneOffset.UTC, ZoneOffset.UTC, List.of())));
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      text.append(String.format("2026-03-01 05:%02d:%02d.250 INFO entry %d%n", i / 60, i % 60, i));
    }
    Path file = temp.resolve("timed.log");
    Files.writeString(file, text.toString(), StandardCharsets.US_ASCII);
    controller.openFile(file, 0);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                DocumentController document = controller.activeDocument();
                return window.canvas() != null
                    && document != null
                    && !document.state().indexing()
                    && document.time().format() != null;
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
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private void until(final String expected, final boolean position) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                String shown = position ? window.positionText() : window.timeText();
                return shown.endsWith(expected);
              }
            }),
        position ? window.positionText() : window.timeText());
  }

  private static void press(org.eclipse.swt.widgets.Button button) {
    button.notifyListeners(SWT.Selection, new Event());
  }

  @Test
  void theTimeFieldShowsTheCaretTimeAndThePositionTheDifference() {
    DocumentController document = controller.activeDocument();
    document.select(Selection.caretAt(new TextPosition(5, 0)));
    until("2026-03-01 05:00:05.250", false);
    assertTrue(window.timeTip().contains(UiText.TIME_FORMAT_ISO.text()), window.timeTip());
    document.select(new Selection(new TextPosition(4, 0), new TextPosition(70, 2)));
    until("Sel 67 lines  +00:01:06.000", true);
    document.time().setOffset(-60 * SECOND);
    until("2026-03-01 05:00:10.250", false);
    assertTrue(window.timeTip().contains("-00:01:00"), window.timeTip());
  }

  @Test
  void thePositionFieldShowsTheFilteredViewsSelectionWhileItHasFocus() {
    final DocumentController document = controller.activeDocument();
    document.search().run("entry 1", document.search().options());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.filtered().viewport().lineCount() >= 3;
              }
            }));
    controller.setFocusedView(document.filtered());
    document.filtered().select(new Selection(new TextPosition(0, 0), new TextPosition(2, 2)));
    until("Sel 3 lines  +00:00:10.000", true);
  }

  @Test
  void theSizeTooltipFollowsTheModificationTime() throws Exception {
    long millis = System.currentTimeMillis() / 1000 * 1000 + 3_600_000;
    Files.setLastModifiedTime(temp.resolve("timed.log"), FileTime.fromMillis(millis));
    final String expected =
        UiText.STATUS_MODIFIED_TIP.format(StatusText.modified(millis, ZoneId.systemDefault()));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return expected.equals(window.sizeTip());
              }
            }),
        window.sizeTip());
  }

  @Test
  void clickingTheTimeFieldOpensGoToTime() {
    DocumentController document = controller.activeDocument();
    document.select(Selection.caretAt(new TextPosition(5, 0)));
    until("2026-03-01 05:00:05.250", false);
    final int shells = display.getShells().length;
    Event click = new Event();
    click.button = 1;
    window.timeLabel().notifyListeners(SWT.MouseUp, click);
    assertEquals(shells + 1, display.getShells().length);
    for (Shell shell : display.getShells()) {
      if (shell != window.shell()) {
        shell.dispose();
      }
    }
  }

  @Test
  void goToTimeAcceptsADateTimeOrATimeOnTheCaretDate() {
    final List<Long> chosen = new ArrayList<Long>();
    GoToTimeDialog dialog =
        new GoToTimeDialog(
            window.shell(),
            "2026-03-01 05:00:05.250",
            ZoneOffset.UTC,
            DAY,
            new MainView.TimeListener() {
              @Override
              public void timeChosen(long nanos) {
                chosen.add(nanos);
              }
            });
    dialog.open();
    assertTrue(dialog.ok().getEnabled());
    dialog.field().setText("05:61:00");
    assertFalse(dialog.ok().getEnabled());
    dialog.field().setText("yesterday");
    assertFalse(dialog.ok().getEnabled());
    dialog.field().setText(" 05:01:09.5 ");
    assertTrue(dialog.ok().getEnabled());
    press(dialog.ok());
    assertEquals(List.of(BASE + 69 * SECOND + SECOND / 2), chosen);
    assertTrue(dialog.shell().isDisposed());
  }

  @Test
  void timeOffsetFieldsFollowEachOther() {
    final List<Long> chosen = new ArrayList<Long>();
    TimeOffsetDialog dialog =
        new TimeOffsetDialog(
            window.shell(),
            0,
            BASE,
            3,
            ZoneOffset.UTC,
            new MainView.TimeListener() {
              @Override
              public void timeChosen(long nanos) {
                chosen.add(nanos);
              }
            });
    dialog.open();
    assertEquals("+00:00:00", dialog.offsetField().getText());
    assertEquals("2026-03-01 05:00:00.000", dialog.caretField().getText());
    dialog.caretField().setText("05:01:30");
    assertEquals("+00:01:30", dialog.offsetField().getText());
    dialog.offsetField().setText("-1.00:00:00");
    assertEquals("2026-02-28 05:00:00.000", dialog.caretField().getText());
    dialog.offsetField().setText("later");
    assertFalse(dialog.ok().getEnabled());
    dialog.offsetField().setText("+00:00:02.5");
    press(dialog.ok());
    assertEquals(List.of(2 * SECOND + SECOND / 2), chosen);
  }

  @Test
  void theFormatEditorPreviewsTheCaretLineAndRefusesABadPattern() {
    final List<List<UserFormat>> saved = new ArrayList<List<UserFormat>>();
    TimestampFormatsDialog dialog =
        new TimestampFormatsDialog(
            window.shell(),
            List.of(),
            "2026-03-01 05:00:07.250 INFO entry 7",
            TimeContext.of(ZoneOffset.UTC, 0),
            ZoneOffset.UTC,
            new MainView.FormatsListener() {
              @Override
              public void formatsChosen(List<UserFormat> formats) {
                saved.add(formats);
              }
            });
    dialog.open();
    press(dialog.add());
    assertEquals(
        UiText.TIME_FORMAT_PREVIEW.format(TimeText.instant(BASE + 7 * SECOND, 0, ZoneOffset.UTC)),
        dialog.previewShown());
    dialog.pattern().setText("yyyy-MM-dd HH:mm:ss qq{");
    assertFalse(dialog.ok().getEnabled());
    assertFalse(dialog.statusShown().isEmpty());
    dialog.locate().setText("^(?<ts>\\S+ \\S+)");
    dialog.pattern().setText("yyyy-MM-dd HH:mm:ss.SSS");
    assertTrue(dialog.ok().getEnabled());
    assertEquals(
        UiText.TIME_FORMAT_PREVIEW.format(
            TimeText.instant(BASE + 7 * SECOND + SECOND / 4, 3, ZoneOffset.UTC)),
        dialog.previewShown());
    press(dialog.ok());
    assertEquals(1, saved.size());
    assertEquals("yyyy-MM-dd HH:mm:ss.SSS", saved.get(0).get(0).pattern());
  }
}
