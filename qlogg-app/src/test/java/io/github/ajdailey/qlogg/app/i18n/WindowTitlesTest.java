package io.github.ajdailey.qlogg.app.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.ajdailey.qlogg.app.command.CommandId;
import org.junit.jupiter.api.Test;

/** Titles from menu and button labels: markers and a trailing ellipsis removed. */
class WindowTitlesTest {
  @Test
  void removesMarkersAndATrailingEllipsis() {
    assertEquals("Rename Tab", WindowTitles.plain("Rena&me Tab..."));
    assertEquals("Import", WindowTitles.plain("&Import..."));
    assertEquals("Find & Replace", WindowTitles.plain("&Find && Replace"));
    assertEquals("Wait... then go", WindowTitles.plain("Wait... then go"));
    assertEquals("", WindowTitles.plain("..."));
    assertEquals("Trailing", WindowTitles.plain("Trailing&"));
  }

  @Test
  void everyCommandTitleIsPlain() {
    for (CommandId command : CommandId.values()) {
      String title = command.title();
      assertFalse(title.contains("&") || title.endsWith("..."), command.name() + ": " + title);
    }
  }
}
