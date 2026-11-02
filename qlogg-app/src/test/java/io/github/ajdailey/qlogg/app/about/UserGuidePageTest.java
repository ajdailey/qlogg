package io.github.ajdailey.qlogg.app.about;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Test
  void everyPageLinkNamesAHeadingAnchor() throws IOException {
    String page;
    try (InputStream in = UserGuide.class.getResourceAsStream(UserGuide.RESOURCE)) {
      assertNotNull(in, UserGuide.RESOURCE);
      page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
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
}
