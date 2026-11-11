package io.github.ajdailey.qlogg.app.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ajdailey.qlogg.app.theme.ColorSchemes;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ColorLabelTest {
  @Test
  void defaultsTakeTheDarkVariantAndUserColorsStay() {
    List<ColorLabel> labels = new ArrayList<ColorLabel>(ColorLabel.defaults());
    ColorLabel custom = new ColorLabel("mine", Rgb.of(0x000000), Rgb.of(0xffff00), true);
    labels.set(1, custom);
    assertSame(labels, ColorLabel.forPalette(labels, false));
    List<ColorLabel> dark = ColorLabel.forPalette(labels, true);
    assertEquals(ColorLabel.COUNT, dark.size());
    assertNotEquals(labels.get(0).background(), dark.get(0).background());
    assertEquals(labels.get(0).name(), dark.get(0).name());
    assertEquals(custom, dark.get(1));
    assertEquals(Rgb.of(0x4a4a4a), dark.get(8).background());
    assertEquals(null, dark.get(8).foreground());
  }

  // View colors.

  @Test
  void aDefaultLabelKeepingTheTextColorTakesTheSchemeGray() {
    List<ColorLabel> labels = new ArrayList<ColorLabel>(ColorLabel.defaults());
    ColorLabel custom = new ColorLabel("mine", null, Rgb.of(0xffff00), true);
    labels.set(7, custom);
    Palette graphite = ColorSchemes.NONE.find("graphite").palette();
    List<ColorLabel> painted = ColorLabel.forPalette(labels, graphite);
    assertEquals(graphite.labelGray(), painted.get(8).background());
    assertEquals(null, painted.get(8).foreground());
    assertEquals(custom, painted.get(7));
    assertEquals(ColorLabel.forPalette(labels, true).get(0), painted.get(0));
    assertEquals(
        Palette.LIGHT.labelGray(),
        ColorLabel.forPalette(labels, Palette.LIGHT).get(8).background());
  }
}
