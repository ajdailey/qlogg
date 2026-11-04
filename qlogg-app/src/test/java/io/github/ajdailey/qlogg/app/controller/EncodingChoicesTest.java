package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Encoding menu's lists and the forced-encoding rule for no document. */
class EncodingChoicesTest {
  @Test
  void commonCharsetsComeFirstInTheirOrderAndOnlyOnce() {
    Charset koi8 = Charset.forName("KOI8-R");
    Charset ibm = Charset.forName("IBM437");
    EncodingChoices choices =
        EncodingChoices.of(List.of(ibm, StandardCharsets.UTF_16BE, koi8, StandardCharsets.UTF_8));
    assertEquals(
        List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16BE, koi8), choices.common());
    assertEquals(List.of(ibm), choices.other());
  }

  @Test
  void theRuntimeListsEverySupportedCharsetOnce() {
    EncodingChoices choices = EncodingChoices.available();
    assertSame(choices, EncodingChoices.available());
    assertSame(choices, EncodingChoices.loaded());
    assertEquals(EncodingChoices.commonCharsets(), choices.common());
    List<String> common = new ArrayList<String>();
    for (Charset charset : choices.common()) {
      common.add(charset.name());
    }
    List<String> supported = new ArrayList<String>();
    for (String name : EncodingChoices.COMMON_NAMES) {
      if (Charset.isSupported(name)) {
        supported.add(Charset.forName(name).name());
      }
    }
    assertEquals(supported, common);
    assertEquals(
        Charset.availableCharsets().size(), choices.common().size() + choices.other().size());
    for (Charset charset : choices.common()) {
      assertTrue(!choices.other().contains(charset), charset.name());
    }
  }

  @Test
  void noDocumentChecksAuto() {
    assertEquals("", EncodingChoices.forced(null));
  }
}
