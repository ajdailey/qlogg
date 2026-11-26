package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.highlight.ColorLabel;
import io.github.ajdailey.qlogg.app.theme.ColorScheme;
import io.github.ajdailey.qlogg.app.theme.ColorSchemes;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.ui.swt.platform.Contrast;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;

/** The control box color and its contrast fallback, without a display. */
class ControlBoxesTest {
  private static RGB rgb(int packed) {
    return new RGB(packed >> 16 & 0xff, packed >> 8 & 0xff, packed & 0xff);
  }

  private static double ratio(int a, int b) {
    return Contrast.ratio(rgb(a), rgb(b));
  }

  private static int packed(Rgb rgb) {
    return PaletteColors.packed(rgb);
  }

  /** Checks a box on a ground: at least 3:1, or the text color where the blend is lower. */
  private static void assertReadable(int text, int ground, String where) {
    int ink = ControlBoxes.ink(text, ground);
    int blend = PaletteColors.blend(ControlBoxes.ALPHA << 24 | text, ground);
    if (ratio(blend, ground) >= ControlBoxes.MIN_CONTRAST) {
      assertEquals(blend, ink, where);
    } else {
      assertEquals(text, ink, where);
    }
    assertTrue(
        ratio(ink, ground) >= Math.min(ControlBoxes.MIN_CONTRAST, ratio(text, ground)), where);
  }

  @Test
  void everyGroundOfEverySchemeReachesThreeToOneOrTheTextColor() {
    List<ColorScheme> schemes = new ArrayList<ColorScheme>();
    schemes.add(ColorSchemes.LIGHT);
    schemes.add(ColorSchemes.DARK);
    schemes.addAll(ColorSchemes.BUNDLED);
    for (ColorScheme scheme : schemes) {
      Palette palette = scheme.palette();
      int text = packed(palette.text());
      Rgb[] grounds = {
        palette.background(),
        palette.currentLine(),
        palette.contextRow(),
        palette.resultRow(),
        palette.match(),
        palette.mark(),
        palette.labelGray()
      };
      for (Rgb ground : grounds) {
        assertReadable(text, packed(ground), scheme.id() + " " + ground);
      }
      assertReadable(
          packed(palette.selectionText()), packed(palette.selection()), scheme.id() + " selection");
      for (ColorLabel label : ColorLabel.forPalette(ColorLabel.defaults(), palette)) {
        int fore = label.foreground() == null ? text : packed(label.foreground());
        assertReadable(fore, packed(label.background()), scheme.id() + " " + label.name());
      }
    }
  }

  @Test
  void aLowContrastHighlighterBackgroundTakesTheTextColor() {
    int text = packed(Palette.LIGHT.text());
    int ground = 0x8090a0;
    int blend = PaletteColors.blend(ControlBoxes.ALPHA << 24 | text, ground);
    assertTrue(ratio(blend, ground) < ControlBoxes.MIN_CONTRAST);
    assertEquals(text, ControlBoxes.ink(text, ground));
  }

  @Test
  void theDefaultPalettesKeepTheBlend() {
    int light = ControlBoxes.ink(packed(Palette.LIGHT.text()), 0xffffff);
    int dark = ControlBoxes.ink(packed(Palette.DARK.text()), packed(Palette.DARK.background()));
    assertEquals(4.6, ratio(light, 0xffffff), 0.05);
    assertEquals(5.6, ratio(dark, packed(Palette.DARK.background())), 0.05);
  }
}
