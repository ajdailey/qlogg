package io.github.ajdailey.qlogg.app.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.search.FilterFile;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.RuleTrigger;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class HighlightFileTest {
  @TempDir Path temp;

  private static HighlightSet sample(String id) {
    return new HighlightSet(
        id,
        "Mock set " + id,
        List.of(
            new HighlightRule(
                "BLOCKTERM", MatchMode.LITERAL, false, true, Rgb.of(0x112233), null, 255, 0, true),
            new HighlightRule(
                "ID-(\\d{3})",
                MatchMode.REGEX, true, false, null, Rgb.of(0xffeedd), 128, 20, false)));
  }

  @Test
  void settingsRoundTripThroughProperties() {
    List<ColorLabel> labels = new ArrayList<ColorLabel>(ColorLabel.defaults());
    labels.set(2, new ColorLabel("flagword", null, Rgb.of(0x010203), false));
    HighlightSettings settings =
        new HighlightSettings(
            List.of(sample("a"), sample("b")),
            List.of("b", "missing"),
            labels,
            new MatchHighlight(false, Rgb.of(0xabcdef), 7));
    assertEquals(List.of("b"), settings.active());
    Properties properties = new Properties();
    HighlightFile.store(settings, properties);
    assertEquals(settings, HighlightFile.load(properties));
    assertEquals(HighlightSettings.DEFAULTS, HighlightFile.load(new Properties()));
  }

  @Test
  void loadSkipsWhatIsMalformed() {
    Properties properties = new Properties();
    HighlightFile.store(
        new HighlightSettings(
            List.of(sample("a")), List.of("a"), ColorLabel.defaults(), MatchHighlight.DEFAULTS),
        properties);
    properties.setProperty("highlight.set.0.rule.0.alpha", "999");
    properties.setProperty("highlight.set.0.rule.1.fore", "#12345");
    properties.setProperty("label.0.back", "red");
    properties.setProperty("matchHighlight.variance", "x");
    HighlightSettings loaded = HighlightFile.load(properties);
    assertEquals(0, loaded.sets().get(0).rules().size());
    assertEquals(ColorLabel.defaults().get(0), loaded.labels().get(0));
    assertEquals(0, loaded.match().variance());
  }

  @Test
  void exportAndImportAddOnlyNewIds() throws Exception {
    Path file = temp.resolve("sets.conf");
    HighlightFile.write(file, List.of(sample("a"), sample("b")));
    List<HighlightSet> read = HighlightFile.read(file);
    assertEquals(List.of(sample("a"), sample("b")), read);
    assertTrue(Files.readString(file, StandardCharsets.ISO_8859_1).startsWith("highlight.set."));
    HighlightSettings current =
        HighlightSettings.DEFAULTS.withSets(List.of(sample("a").withName("kept")));
    HighlightSettings merged = current.withImported(read);
    assertEquals(2, merged.sets().size());
    assertEquals("kept", merged.sets().get(0).name());
    assertEquals("b", merged.sets().get(1).id());
  }

  @Test
  void malformedFilesAreRejected() throws Exception {
    final Path notExport = temp.resolve("plain.conf");
    Files.writeString(notExport, "highlight.set.0.id=a\n", StandardCharsets.ISO_8859_1);
    assertRejected(notExport);
    final Path badRule = temp.resolve("bad.conf");
    Files.writeString(
        badRule,
        "version=1\nhighlight.set.0.id=a\nhighlight.set.0.name=n\n"
            + "highlight.set.0.rule.0.pattern=p\nhighlight.set.0.rule.0.regex=maybe\n",
        StandardCharsets.ISO_8859_1);
    assertRejected(badRule);
    final Path big = temp.resolve("big.conf");
    byte[] bytes = new byte[FilterFile.MAX_BYTES + 1];
    Files.write(big, bytes);
    assertRejected(big);
    final Path escape = temp.resolve("escape.conf");
    Files.writeString(escape, "version=1\nkey=\\u12\n", StandardCharsets.ISO_8859_1);
    assertRejected(escape);
  }

  @Test
  void anExportWithAUtf8ByteOrderMarkImports() throws Exception {
    Path file = temp.resolve("sets.conf");
    HighlightFile.write(file, List.of(sample("a")));
    byte[] written = Files.readAllBytes(file);
    byte[] marked = new byte[written.length + 3];
    marked[0] = (byte) 0xEF;
    marked[1] = (byte) 0xBB;
    marked[2] = (byte) 0xBF;
    System.arraycopy(written, 0, marked, 3, written.length);
    Files.write(file, marked);
    assertEquals(List.of(sample("a")), HighlightFile.read(file));
  }

  private static void assertRejected(final Path file) {
    assertThrows(
        FilterFile.FormatException.class,
        new Executable() {
          @Override
          public void execute() throws Exception {
            HighlightFile.read(file);
          }
        });
  }

  @Test
  void colorsFormatAndParse() {
    assertEquals("#0a0b0c", HighlightFile.hex(Rgb.of(0x0a0b0c)));
    assertEquals("", HighlightFile.hex(null));
    assertEquals(Rgb.of(0xa0b0c0), HighlightFile.parseColor(" #A0b0C0 "));
    assertNull(HighlightFile.parseColor("#xyz000"));
    assertNull(HighlightFile.parseColor(""));
  }

  @Test
  void rulesApplyTopOfTheFirstActiveSetLast() {
    HighlightSet first = sample("a");
    HighlightSet second = sample("b");
    HighlightSettings settings =
        HighlightSettings.DEFAULTS.withSets(List.of(first, second)).withActive(List.of("b", "a"));
    List<HighlightRule> rules = settings.rulesInApplyOrder();
    assertEquals(4, rules.size());
    assertEquals(second.rules().get(1), rules.get(0));
    assertEquals(first.rules().get(0), rules.get(3));
    assertEquals(List.of("b"), settings.toggle("a").active());
  }

  // Keyword triggers.

  private static HighlightSet triggered(String id) {
    HighlightSet set = sample(id);
    return set.withRules(
        List.of(
            set.rules().get(0).withTrigger(new RuleTrigger(true, true, "at %L {x(\\d)}{$1}", true)),
            set.rules().get(1).withTrigger(new RuleTrigger(false, false, "kept", false))));
  }

  @Test
  void triggersRoundTripThroughExportAndProperties() throws Exception {
    Path file = temp.resolve("triggers.conf");
    HighlightFile.write(file, List.of(triggered("a")));
    assertEquals(List.of(triggered("a")), HighlightFile.read(file));
    Properties properties = new Properties();
    HighlightSettings settings =
        new HighlightSettings(
            List.of(triggered("a")), List.of("a"), ColorLabel.defaults(), MatchHighlight.DEFAULTS);
    HighlightFile.store(settings, properties);
    assertEquals(settings, HighlightFile.load(properties));
  }

  @Test
  void aFileWithoutTriggersLoadsWithoutActions() throws Exception {
    Path file = temp.resolve("old.conf");
    HighlightFile.write(file, List.of(sample("a")));
    String text = Files.readString(file, StandardCharsets.ISO_8859_1);
    assertTrue(!text.contains("stopFollow") && !text.contains(".quiet"), text);
    HighlightSet read = HighlightFile.read(file).get(0);
    assertEquals(sample("a"), read);
    assertEquals(RuleTrigger.NONE, read.rules().get(0).trigger());
  }

  @Test
  void aMalformedTriggerValueTurnsOnlyThatActionOff() throws Exception {
    Path file = temp.resolve("bad.conf");
    HighlightFile.write(file, List.of(triggered("a")));
    String text =
        Files.readString(file, StandardCharsets.ISO_8859_1)
            .replace("rule.0.stopFollow=true", "rule.0.stopFollow=yes")
            .replace("rule.1.comment=kept", "rule.1.comment=" + "c".repeat(1001));
    Files.writeString(file, text, StandardCharsets.ISO_8859_1);
    HighlightSet read = HighlightFile.read(file).get(0);
    assertEquals(
        new RuleTrigger(false, true, "at %L {x(\\d)}{$1}", true), read.rules().get(0).trigger());
    assertEquals(RuleTrigger.NONE, read.rules().get(1).trigger());
    Properties properties = new Properties();
    properties.load(new StringReader(text));
    assertEquals(read, HighlightFile.load(properties).sets().get(0));
  }
}
