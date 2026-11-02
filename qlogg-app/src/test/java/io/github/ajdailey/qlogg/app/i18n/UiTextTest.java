package io.github.ajdailey.qlogg.app.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every {@link UiText} constant has a bundle entry and every entry has a constant. */
class UiTextTest {
  @Test
  void constantsAndBundleKeysMatch() {
    List<String> constants = new ArrayList<String>();
    for (UiText text : UiText.values()) {
      constants.add(text.name());
      assertFalse(text.text().isEmpty(), text.name());
    }
    List<String> keys = new ArrayList<String>();
    Enumeration<String> names = UiText.bundle().getKeys();
    while (names.hasMoreElements()) {
      keys.add(names.nextElement());
    }
    Collections.sort(constants);
    Collections.sort(keys);
    assertEquals(constants, keys);
  }

  @Test
  void patternsFormat() {
    assertEquals("app.log - QLogg", UiText.WINDOW_TITLE_FILE.format("app.log"));
    assertEquals("Indexing 42%", UiText.STATUS_INDEXING.format(42));
    assertEquals("==== NOTICE ====", UiText.LICENSES_HEADING.format("NOTICE"));
    assertTrue(UiText.COPY_TOO_LARGE.format(1.5, 0.25).contains("1.5 MiB"));
  }
}
