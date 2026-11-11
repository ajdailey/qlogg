package io.github.ajdailey.qlogg.app.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.highlight.ColorLabel;
import io.github.ajdailey.qlogg.app.highlight.HighlightSetsEditor;
import io.github.ajdailey.qlogg.core.model.Rgb;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Contrast floors for every listed scheme, Light and Dark included: text 4.5:1 on every row ground,
 * selected text 4.5:1 on the selection, the default color labels and a new highlighter rule 4.5:1,
 * and line numbers, markers, dots and time-spread colors 3:1 on what they are drawn over. With
 * {@code -Dqlogg.qc.contrast=true} each palette's lowest ratio is printed.
 */
class ColorSchemesTest {
  private static final double TEXT = 4.5;
  private static final double GRAPHIC = 3.0;

  @Test
  void everyPaletteMeetsTheContrastFloors() {
    List<String> faults = new ArrayList<String>();
    for (ColorScheme scheme : ColorSchemes.NONE.all()) {
      Palette p = scheme.palette();
      Floor floor = new Floor(scheme.id(), faults);
      Rgb[] grounds = {
        p.background(),
        p.currentLine(),
        p.gutter(),
        p.match(),
        p.mark(),
        p.resultRow(),
        p.contextRow(),
        p.labelGray()
      };
      for (Rgb ground : grounds) {
        floor.check("text", p.text(), ground, TEXT);
      }
      floor.check("selectionText", p.selectionText(), p.selection(), TEXT);
      floor.check("lineNumbers", p.lineNumbers(), p.gutter(), GRAPHIC);
      for (Rgb marker : new Rgb[] {p.matchMarker(), p.markMarker()}) {
        floor.check("marker", marker, p.gutter(), GRAPHIC);
        floor.check("marker", marker, p.overview(), GRAPHIC);
        floor.check("marker", marker, p.currentLine(), GRAPHIC);
      }
      floor.check("overviewFrame", p.overviewFrame(), p.overview(), GRAPHIC);
      Rgb[] dots = {
        p.newLinesDot(),
        p.newMatchesDot(),
        p.followStoppedDot(),
        p.spreadPause(),
        p.spreadActivity()
      };
      for (Rgb dot : dots) {
        floor.check("dot or spread", dot, p.overview(), GRAPHIC);
        floor.check("dot or spread", dot, p.gutter(), GRAPHIC);
        floor.check("dot or spread", dot, p.background(), GRAPHIC);
      }
      for (ColorLabel label : ColorLabel.forPalette(ColorLabel.defaults(), p)) {
        Rgb text = label.foreground() == null ? p.text() : label.foreground();
        floor.check("label " + label.name(), text, label.background(), TEXT);
      }
      floor.check(
          "new rule",
          HighlightSetsEditor.NEW_RULE_FOREGROUND,
          HighlightSetsEditor.NEW_RULE_BACKGROUND,
          TEXT);
      if (Boolean.getBoolean("qlogg.qc.contrast")) {
        System.out.printf("QC %s lowest %.2f (%s)%n", scheme.id(), floor.lowest, floor.where);
      }
    }
    assertTrue(faults.isEmpty(), String.join("\n", faults));
  }

  @Test
  void bundledIdsAreDistinctAndReserved() {
    Set<String> ids = new HashSet<String>();
    for (ColorScheme scheme : ColorSchemes.BUNDLED) {
      assertTrue(ids.add(scheme.id()), scheme.id());
      assertTrue(ColorSchemes.reserved(scheme.id()));
      assertFalse(scheme.name().isBlank());
    }
    assertTrue(ColorSchemes.reserved(ColorSchemes.FOLLOW_THEME));
    assertFalse(ColorSchemes.reserved("mock-scheme"));
    assertEquals(9, ColorSchemes.BUNDLED.size());
  }

  // Light and Dark view colors.

  @Test
  void lightAndDarkLeadTheListWithTheThemePalettes() {
    List<String> ids = new ArrayList<String>();
    for (ColorScheme scheme : ColorSchemes.NONE.all()) {
      ids.add(scheme.id());
    }
    assertEquals(
        List.of(
            "light",
            "dark",
            "sandstone",
            "nightwater",
            "garden-light",
            "garden-dark",
            "legal-pad",
            "paper",
            "midnight",
            "phosphor",
            "graphite"),
        ids);
    assertEquals("follow", ColorSchemes.FOLLOW_THEME);
    assertEquals("Light", ColorSchemes.NONE.find("light").name());
    assertEquals("Dark", ColorSchemes.NONE.find("dark").name());
    assertSame(Palette.LIGHT, ColorSchemes.NONE.find("light").palette());
    assertSame(Palette.DARK, ColorSchemes.NONE.find("dark").palette());
    for (String id : new String[] {"follow", "light", "dark"}) {
      assertTrue(ColorSchemes.reserved(id), id);
    }
    assertFalse(ColorSchemes.reserved("theme"));
    assertNull(ColorSchemes.NONE.find("theme"));
  }

  @Test
  void userSchemesFollowTheBundledOnesAndCannotTakeAReservedId() {
    final ColorScheme user = new ColorScheme("mock-scheme", "Mock", Palette.DARK);
    ColorSchemes schemes = new ColorSchemes(List.of(user));
    assertEquals(ColorSchemes.NONE.all().size() + 1, schemes.all().size());
    assertSame(user, schemes.all().get(schemes.all().size() - 1));
    assertSame(user, schemes.find("mock-scheme"));
    assertNull(schemes.find(ColorSchemes.FOLLOW_THEME));
    assertNull(schemes.find("absent"));
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new ColorSchemes(List.of(new ColorScheme("paper", "Mock", Palette.DARK)));
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new ColorSchemes(List.of(new ColorScheme("dark", "Mock", Palette.LIGHT)));
          }
        });
  }

  @Test
  void colorNamesFollowThePaletteComponents() {
    RecordComponent[] components = Palette.class.getRecordComponents();
    List<String> names = new ArrayList<String>();
    for (int i = 1; i < components.length; i++) {
      names.add(components[i].getName());
    }
    assertEquals(names, Palette.COLOR_NAMES);
    assertEquals("dark", components[0].getName());
  }

  /** Records each ratio below its floor and the lowest ratio seen. */
  private static final class Floor {
    private final String scheme;
    private final List<String> faults;
    double lowest = Double.MAX_VALUE;
    String where = "";

    Floor(String scheme, List<String> faults) {
      this.scheme = scheme;
      this.faults = faults;
    }

    void check(String what, Rgb fore, Rgb back, double floor) {
      double ratio = contrast(fore, back);
      if (ratio < lowest) {
        lowest = ratio;
        where = what + " " + fore + " on " + back;
      }
      if (ratio < floor) {
        faults.add(
            String.format(
                "%s: %s %s on %s is %.2f, below %.1f", scheme, what, fore, back, ratio, floor));
      }
    }
  }

  /** WCAG 2 contrast ratio. */
  static double contrast(Rgb a, Rgb b) {
    double high = Math.max(luminance(a), luminance(b));
    double low = Math.min(luminance(a), luminance(b));
    return (high + 0.05) / (low + 0.05);
  }

  private static double luminance(Rgb rgb) {
    return 0.2126 * linear(rgb.red()) + 0.7152 * linear(rgb.green()) + 0.0722 * linear(rgb.blue());
  }

  private static double linear(int channel) {
    double value = channel / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }
}
