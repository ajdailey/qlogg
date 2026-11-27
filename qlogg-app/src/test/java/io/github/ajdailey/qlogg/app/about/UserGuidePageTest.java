package io.github.ajdailey.qlogg.app.about;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UserGuidePageTest {
  private static final Pattern ID = Pattern.compile("<a id=\"([^\"]+)\"></a>");
  private static final Pattern TARGET = Pattern.compile("href=\"#([^\"]*)\"");
  private static final Pattern SOURCE = Pattern.compile("src=\"([^\"]*)\"");

  @Test
  void everyPageLinkNamesAHeadingAnchor() throws IOException {
    String page = page();
    Set<String> ids = new HashSet<String>();
    Matcher id = ID.matcher(page);
    while (id.find()) {
      ids.add(id.group(1));
    }
    assertTrue(ids.contains("opening-files"), "heading anchors are rendered");
    Matcher target = TARGET.matcher(page);
    int links = 0;
    while (target.find()) {
      links++;
      assertTrue(ids.contains(target.group(1)), "#" + target.group(1));
    }
    assertTrue(links > 0, "the page has in-page links");
  }

  @Test
  void thePageCarriesThePolicyAndEmbedsEveryImage() throws IOException {
    String page = page();
    int policy =
        page.indexOf(
            "<meta charset=\"UTF-8\">\n<meta http-equiv=\"Content-Security-Policy\" content=\""
                + UserGuide.POLICY
                + "\">");
    assertTrue(policy > 0, "the policy meta follows the charset meta");
    assertTrue(policy < page.indexOf("<style>"), "the policy precedes the styles");
    Matcher source = SOURCE.matcher(page);
    int images = 0;
    while (source.find()) {
      images++;
      assertTrue(source.group(1).startsWith("data:image/png;base64,"), source.group(1));
    }
    assertTrue(images > 0, "images are embedded");
  }

  @Test
  void linuxShowsTheGuideEmbeddedOnlyInPerUserMode() {
    assertTrue(UserGuide.embedded(SettingsMode.PER_USER, OsTraits.of("Linux")));
    assertFalse(UserGuide.embedded(SettingsMode.PORTABLE, OsTraits.of("Linux")));
    assertFalse(UserGuide.embedded(SettingsMode.EXPLICIT, OsTraits.of("Linux")));
    assertTrue(UserGuide.embedded(SettingsMode.PORTABLE, OsTraits.of("Windows 11")));
    assertTrue(UserGuide.embedded(SettingsMode.EXPLICIT, OsTraits.of("Mac OS X")));
  }

  private static String page() throws IOException {
    try (InputStream in = UserGuide.class.getResourceAsStream(UserGuide.RESOURCE)) {
      assertNotNull(in, UserGuide.RESOURCE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
