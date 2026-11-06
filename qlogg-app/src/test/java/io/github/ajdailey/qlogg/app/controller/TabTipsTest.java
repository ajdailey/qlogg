package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The tab tooltip text; expected values are the English bundle's. */
class TabTipsTest {
  private static final String PATH = "logs/app.log";

  @Test
  void aPathAloneIsTheTip() {
    assertEquals(PATH, TabTips.text(PATH, TabDataState.NONE, 0));
  }

  @Test
  void aPastedDocumentWithNothingToSayHasNoTip() {
    assertNull(TabTips.text("", TabDataState.NONE, 0));
  }

  @Test
  void eachDataStateAddsItsLineAfterThePath() {
    assertEquals(PATH + "\nNew lines", TabTips.text(PATH, TabDataState.NEW_LINES, 0));
    assertEquals(PATH + "\nNew matching lines", TabTips.text(PATH, TabDataState.NEW_MATCHES, 0));
    assertEquals(
        PATH + "\nFollow stopped by a highlighter",
        TabTips.text(PATH, TabDataState.FOLLOW_STOPPED, 0));
  }

  @Test
  void aPastedDocumentStartsWithItsDataStateLine() {
    assertEquals("New lines", TabTips.text("", TabDataState.NEW_LINES, 0));
    assertEquals("New matching lines", TabTips.text("", TabDataState.NEW_MATCHES, 0));
    assertEquals(
        "Follow stopped by a highlighter", TabTips.text("", TabDataState.FOLLOW_STOPPED, 0));
  }

  @Test
  void aSyncGroupComesLast() {
    assertEquals(PATH + "\nTime synced, group 2", TabTips.text(PATH, TabDataState.NONE, 2));
    assertEquals(
        PATH + "\nNew lines\nTime synced, group 1", TabTips.text(PATH, TabDataState.NEW_LINES, 1));
    assertEquals("Time synced, group 3", TabTips.text("", TabDataState.NONE, 3));
    assertEquals(
        "New matching lines\nTime synced, group 3", TabTips.text("", TabDataState.NEW_MATCHES, 3));
  }

  @Test
  void everyAmpersandInTheFinalTextIsDoubled() {
    assertEquals("a&&b && c.log", TabTips.text("a&b & c.log", TabDataState.NONE, 0));
    assertEquals(
        "a&&b.log\nNew lines\nTime synced, group 1",
        TabTips.text("a&b.log", TabDataState.NEW_LINES, 1));
  }
}
