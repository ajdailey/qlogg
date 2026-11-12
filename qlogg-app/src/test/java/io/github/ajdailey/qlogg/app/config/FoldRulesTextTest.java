package io.github.ajdailey.qlogg.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing and validation of the fold rules the Preferences dialog edits. */
class FoldRulesTextTest {
  @Test
  void theDefaultsRoundTripWithTheirTrailingSpace() {
    FoldRulesText parsed =
        FoldRulesText.parse("", FoldRulesText.continuationText(FoldRules.DEFAULTS));
    assertNull(parsed.error());
    assertEquals(FoldRules.DEFAULTS, parsed.rules());
  }

  @Test
  void blankLinesAreIgnoredAndCrLfSplits() {
    FoldRulesText parsed = FoldRulesText.parse("  ", "^\\s\r\n\n^at \n");
    assertEquals(new FoldRules("", List.of("^\\s", "^at ")), parsed.rules());
  }

  @Test
  void anEntryStartAloneIsEnough() {
    FoldRulesText parsed = FoldRulesText.parse("^\\d{4}-", "");
    assertEquals(new FoldRules("^\\d{4}-", List.of()), parsed.rules());
  }

  @Test
  void badTextIsRefusedWithTheReason() {
    assertEquals(UiText.PREFERENCES_FOLD_EMPTY.text(), FoldRulesText.parse("", "\n").error());
    FoldRulesText invalid = FoldRulesText.parse("", "^ok\n[unclosed");
    assertNull(invalid.rules());
    assertTrue(invalid.error().startsWith("Pattern [unclosed: "), invalid.error());
    assertTrue(FoldRulesText.parse("(", "^x").error().startsWith("Pattern (: "));
    StringBuilder many = new StringBuilder();
    for (int i = 0; i <= ViewFile.MAX_CONTINUATION; i++) {
      many.append("^p").append(i).append('\n');
    }
    assertEquals(
        UiText.PREFERENCES_FOLD_TOO_MANY.format(ViewFile.MAX_CONTINUATION),
        FoldRulesText.parse("", many.toString()).error());
  }
}
