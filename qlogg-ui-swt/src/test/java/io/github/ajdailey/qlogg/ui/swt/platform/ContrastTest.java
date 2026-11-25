package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;

/** {@link Contrast#readable} on measured theme colors. */
class ContrastTest {
  private static final RGB ADWAITA_DARK_GROUND = new RGB(53, 53, 53);
  private static final RGB ADWAITA_DARK_LINK = new RGB(53, 132, 228);

  @Test
  void anUnreadableLinkLightensOnADarkGround() {
    RGB moved = Contrast.readable(ADWAITA_DARK_LINK, ADWAITA_DARK_GROUND);
    assertTrue(Contrast.ratio(moved, ADWAITA_DARK_GROUND) >= Contrast.LINK);
    assertTrue(moved.red > ADWAITA_DARK_LINK.red);
  }

  @Test
  void anUnreadableLinkDarkensOnALightGround() {
    RGB ground = new RGB(246, 245, 244);
    RGB link = new RGB(120, 170, 240);
    RGB moved = Contrast.readable(link, ground);
    assertTrue(Contrast.ratio(moved, ground) >= Contrast.LINK);
    assertTrue(moved.red < link.red);
  }

  @Test
  void aReadableLinkKeepsItsColor() {
    RGB link = new RGB(26, 95, 180);
    RGB ground = new RGB(246, 245, 244);
    assertEquals(link, Contrast.readable(link, ground));
  }
}
