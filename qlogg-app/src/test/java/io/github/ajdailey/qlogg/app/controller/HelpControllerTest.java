package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link HelpController} over a fake view; the menu paths run in {@code WindowSetTest}. */
class HelpControllerTest {
  @TempDir Path temp;

  private final AtomicBoolean closing = new AtomicBoolean();
  private ControllerFixture fixture;
  private FakeView view;
  private HelpController help;

  @BeforeEach
  void setUp() {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    view = new FakeView();
    help = new HelpController(fixture.application.windowSet(), view, closing::get);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    fixture.close();
    view.assertConfined();
  }

  @Test
  void aboutAndLicensesShowOnceTheirWorkersRead() throws Exception {
    help.showAbout();
    help.showLicenses();
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.about != null && view.texts.size() == 1;
          }
        });
    assertTrue(view.texts.get(0).startsWith(UiText.LICENSES_TITLE.text()), view.texts.get(0));
  }

  @Test
  void resultsArrivingWhileTheWindowClosesAreDropped() throws Exception {
    help.showAbout();
    help.showLicenses();
    help.reportIssue();
    help.showGuide();
    help.openFolder(temp.resolve("logs"));
    closing.set(true);
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !fixture.workers.liveThreadNames().contains(Workers.Kind.LIFECYCLE.prefix());
              }
            },
            ControllerFixture.BOUND));
    fixture.ui.runPending();
    assertNull(view.about);
    assertTrue(view.texts.isEmpty());
    assertTrue(view.notices.isEmpty());
    assertTrue(view.urls.isEmpty());
    assertTrue(view.guides.isEmpty());
    assertTrue(view.launched.isEmpty());
    help.showGuideElsewhere(temp.resolve("guide.html"));
    assertTrue(view.launched.isEmpty());
  }

  @Test
  void aFolderThatCannotBeCreatedShowsANotice() throws Exception {
    Path file = Files.writeString(temp.resolve("plain"), "x");
    help.openFolder(file.resolve("logs"));
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !view.notices.isEmpty();
          }
        });
    assertTrue(view.launched.isEmpty());
  }

  @Test
  void aLaunchNoApplicationTakesShowsANotice() {
    Path target = temp.resolve("app.log");
    help.launch(target);
    assertEquals(List.of(target), view.launched);
    view.launchResult = false;
    help.launch(target);
    assertEquals(UiText.NOTICE_LAUNCH_FAILED.text(), view.lastNotice());
  }
}
