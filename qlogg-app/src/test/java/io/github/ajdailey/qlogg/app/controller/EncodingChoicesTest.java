package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.Options;
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
  void aDecodeOnlyCharsetIsNotListed() {
    Charset decodeOnly = Charset.forName("x-JISAutoDetect");
    assertFalse(decodeOnly.canEncode());
    EncodingChoices choices =
        EncodingChoices.of(List.of(decodeOnly, StandardCharsets.UTF_8, Charset.forName("IBM437")));
    assertEquals(List.of(Charset.forName("IBM437")), choices.other());
    for (Charset charset : EncodingChoices.available().other()) {
      assertTrue(charset.canEncode(), charset.name());
    }
    assertNull(Options.charset(decodeOnly.name()));
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
    int encodable = 0;
    for (Charset charset : Charset.availableCharsets().values()) {
      if (charset.canEncode()) {
        encodable++;
      }
    }
    assertEquals(encodable, choices.common().size() + choices.other().size());
    for (Charset charset : choices.common()) {
      assertTrue(!choices.other().contains(charset), charset.name());
    }
  }

  @Test
  void noDocumentChecksAuto() {
    assertEquals("", EncodingChoices.forced(null));
  }
}
