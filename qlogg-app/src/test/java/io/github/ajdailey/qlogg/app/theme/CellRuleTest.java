package io.github.ajdailey.qlogg.app.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.model.Rgb;
import org.junit.jupiter.api.Test;

/** The column-view cell rule reaches 3:1 on each ground it crosses, in every bundled scheme. */
class CellRuleTest {
  @Test
  void everySchemesRuleReachesTheFloorOnEachGround() {
    for (ColorScheme scheme : ColorSchemes.NONE.all()) {
      Palette palette = scheme.palette();
      Rgb rule = CellRule.of(palette);
      for (Rgb ground : CellRule.grounds(palette)) {
        double ratio = CellRule.contrast(rule, ground);
        assertTrue(
            ratio >= CellRule.MIN_CONTRAST,
            scheme.id() + ": " + rule + " on " + ground + " is " + ratio);
      }
    }
  }

  @Test
  void contrastIsTheWcagRatio() {
    assertEquals(21, CellRule.contrast(new Rgb(255, 255, 255), new Rgb(0, 0, 0)), 0.01);
    assertEquals(1, CellRule.contrast(new Rgb(9, 9, 9), new Rgb(9, 9, 9)), 0.001);
  }
}
