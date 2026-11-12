package io.github.ajdailey.qlogg.app.highlight;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.FilterFile;
import io.github.ajdailey.qlogg.core.io.AtomicFiles;
import io.github.ajdailey.qlogg.core.io.PropertiesFiles;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.RuleTrigger;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Highlight settings as Java properties, under indexed keys with no gap from 0:
 *
 * <table>
 *   <caption>Keys</caption>
 *   <tr><th>Key</th><th>Value</th></tr>
 *   <tr><td>{@code highlight.set.N.id}, {@code .name}</td><td>The set</td></tr>
 *   <tr><td>{@code highlight.set.N.rule.M.pattern}, {@code .regex}, {@code .ignoreCase},
 *       {@code .wholeLine}, {@code .enabled}</td><td>A rule; flags are {@code true} or
 *       {@code false}</td></tr>
 *   <tr><td>{@code highlight.set.N.rule.M.fore}, {@code .back}</td><td>{@code #rrggbb}, or empty
 *       for none</td></tr>
 *   <tr><td>{@code highlight.set.N.rule.M.alpha}, {@code .variance}</td><td>0-255; 0 to
 *       {@link HighlightRule#MAX_VARIANCE}</td></tr>
 *   <tr><td>{@code highlight.set.N.rule.M.stopFollow}, {@code .setMark}, {@code .quiet},
 *       {@code .comment}</td><td>The rule's trigger: flags, and the comment template of at most
 *       {@link RuleTrigger#MAX_TEMPLATE_CHARS} chars; absent keys mean no action</td></tr>
 *   <tr><td>{@code highlight.active.N}</td><td>An active set's id</td></tr>
 *   <tr><td>{@code label.N.name}, {@code .fore}, {@code .back}, {@code .cycle}</td><td>Color
 *       label N, 0 to 8</td></tr>
 *   <tr><td>{@code matchHighlight.enabled}, {@code .color}, {@code .variance}</td><td>The match
 *       highlight; an empty color is the palette's</td></tr>
 * </table>
 *
 * <p>{@code qlogg.conf} holds all of them; {@link #load} skips a malformed set or rule and keeps
 * the default for a malformed label. An export file holds {@code version=1} and the sets only;
 * {@link #read} accepts at most {@link FilterFile#MAX_BYTES} bytes, {@link
 * HighlightSettings#MAX_SETS} sets and {@link HighlightSet#MAX_RULES} rules a set, and rejects the
 * whole file on any malformed entry. A malformed trigger key is not a malformed entry: in either
 * method the rule loads without that action, and one warning per load names the count. Both file
 * methods block, so they run on a worker, and take the user's path through {@link
 * SafePaths#logFile(Path)}.
 */
public final class HighlightFile {
  static final String SET = "highlight.set.";
  static final String ACTIVE = "highlight.active.";
  static final String LABEL = "label.";
  static final String MATCH = "matchHighlight.";
  static final String VERSION_KEY = "version";
  static final String VERSION = "1";

  /** Marks an empty color value; compared by identity. */
  private static final Rgb NO_COLOR = new Rgb(0, 0, 0);

  /** Marks a color value that does not parse; compared by identity. */
  private static final Rgb BAD_COLOR = new Rgb(0, 0, 0);

  private static final Logger LOG = LoggerFactory.getLogger(HighlightFile.class);

  private HighlightFile() {}

  /**
   * Reads the sets a file exports.
   *
   * @param path the user's file
   * @return the sets, unmodifiable
   * @throws IOException when the path is rejected or unreadable; {@link FilterFile.FormatException}
   *     when the file is too large, does not parse, names another version or holds a malformed set
   */
  public static List<HighlightSet> read(Path path) throws IOException {
    Path file = SafePaths.logFile(path);
    if (Files.size(file) > FilterFile.MAX_BYTES) {
      throw new FilterFile.FormatException(UiText.FILTER_FILE_TOO_LARGE.text());
    }
    Properties properties;
    try {
      properties = PropertiesFiles.read(file, FilterFile.MAX_BYTES);
    } catch (IllegalArgumentException e) {
      throw new FilterFile.FormatException(UiText.FILTER_FILE_NOT_PROPERTIES.text());
    }
    String version = properties.getProperty(VERSION_KEY);
    if (version == null || !VERSION.equals(version.strip())) {
      throw new FilterFile.FormatException(UiText.HIGHLIGHT_FILE_NOT_EXPORT.text());
    }
    List<HighlightSet> sets = new ArrayList<HighlightSet>();
    AtomicInteger malformed = new AtomicInteger();
    for (int i = 0; properties.getProperty(SET + i + ".id") != null; i++) {
      if (sets.size() == HighlightSettings.MAX_SETS) {
        throw new FilterFile.FormatException(
            UiText.HIGHLIGHT_FILE_TOO_MANY.format(HighlightSettings.MAX_SETS));
      }
      HighlightSet set = set(properties, i, true, malformed);
      if (set == null) {
        throw new FilterFile.FormatException(UiText.HIGHLIGHT_FILE_INCOMPLETE.format(i + 1));
      }
      sets.add(set);
    }
    warnMalformed(malformed);
    return Collections.unmodifiableList(sets);
  }

  /**
   * Writes sets to a file through {@link AtomicFiles#writeUserFile}, replacing it.
   *
   * @param path the user's file
   * @param sets the sets
   * @throws IOException when the path is rejected or the write fails
   */
  public static void write(Path path, List<HighlightSet> sets) throws IOException {
    Path file = SafePaths.logFile(path);
    Properties properties = new Properties();
    properties.setProperty(VERSION_KEY, VERSION);
    storeSets(sets, properties);
    AtomicFiles.writeUserFile(file, format(properties));
  }

  /**
   * Returns properties as file bytes: sorted keys, ISO-8859-1 with escapes, no date comment.
   *
   * @param properties the properties
   * @return the bytes
   * @throws IOException never for an in-memory stream
   */
  static byte[] format(Properties properties) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    properties.store(out, null);
    StringBuilder kept = new StringBuilder();
    for (String line : out.toString(StandardCharsets.ISO_8859_1).split("\\R")) {
      if (!line.startsWith("#")) {
        kept.append(line).append('\n');
      }
    }
    return kept.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  /**
   * Puts every highlight setting into properties.
   *
   * @param settings the settings
   * @param properties receives the keys
   */
  public static void store(HighlightSettings settings, Properties properties) {
    storeSets(settings.sets(), properties);
    for (int i = 0; i < settings.active().size(); i++) {
      properties.setProperty(ACTIVE + i, settings.active().get(i));
    }
    for (int i = 0; i < settings.labels().size(); i++) {
      ColorLabel label = settings.labels().get(i);
      properties.setProperty(LABEL + i + ".name", label.name());
      properties.setProperty(LABEL + i + ".fore", hex(label.foreground()));
      properties.setProperty(LABEL + i + ".back", hex(label.background()));
      properties.setProperty(LABEL + i + ".cycle", Boolean.toString(label.cycle()));
    }
    MatchHighlight match = settings.match();
    properties.setProperty(MATCH + "enabled", Boolean.toString(match.enabled()));
    properties.setProperty(MATCH + "color", hex(match.color()));
    properties.setProperty(MATCH + "variance", Integer.toString(match.variance()));
  }

  private static void storeSets(List<HighlightSet> sets, Properties properties) {
    for (int i = 0; i < sets.size(); i++) {
      HighlightSet set = sets.get(i);
      String prefix = SET + i;
      properties.setProperty(prefix + ".id", set.id());
      properties.setProperty(prefix + ".name", set.name());
      for (int r = 0; r < set.rules().size(); r++) {
        HighlightRule rule = set.rules().get(r);
        String key = prefix + ".rule." + r;
        properties.setProperty(key + ".pattern", rule.pattern());
        properties.setProperty(key + ".regex", Boolean.toString(rule.mode() == MatchMode.REGEX));
        properties.setProperty(key + ".ignoreCase", Boolean.toString(!rule.caseSensitive()));
        properties.setProperty(key + ".wholeLine", Boolean.toString(rule.wholeLine()));
        properties.setProperty(key + ".fore", hex(rule.foreground()));
        properties.setProperty(key + ".back", hex(rule.background()));
        properties.setProperty(key + ".alpha", Integer.toString(rule.alpha()));
        properties.setProperty(key + ".variance", Integer.toString(rule.variance()));
        properties.setProperty(key + ".enabled", Boolean.toString(rule.enabled()));
        storeTrigger(rule.trigger(), key, properties);
      }
    }
  }

  /**
   * Reads highlight settings from properties, skipping what is malformed.
   *
   * @param properties the properties
   * @return the settings
   */
  public static HighlightSettings load(Properties properties) {
    List<HighlightSet> sets = new ArrayList<HighlightSet>();
    List<String> ids = new ArrayList<String>();
    AtomicInteger malformed = new AtomicInteger();
    for (int i = 0; properties.getProperty(SET + i + ".id") != null; i++) {
      HighlightSet set = set(properties, i, false, malformed);
      if (set != null && !ids.contains(set.id()) && sets.size() < HighlightSettings.MAX_SETS) {
        sets.add(set);
        ids.add(set.id());
      }
    }
    warnMalformed(malformed);
    List<String> active = new ArrayList<String>();
    for (int i = 0; i < HighlightSettings.MAX_SETS; i++) {
      String id = properties.getProperty(ACTIVE + i);
      if (id == null) {
        break;
      }
      active.add(id.strip());
    }
    List<ColorLabel> labels = new ArrayList<ColorLabel>(ColorLabel.defaults());
    for (int i = 0; i < ColorLabel.COUNT; i++) {
      ColorLabel label = label(properties, i);
      if (label != null) {
        labels.set(i, label);
      }
    }
    MatchHighlight match = MatchHighlight.DEFAULTS;
    int enabled = flag(properties.getProperty(MATCH + "enabled"));
    int variance = number(properties.getProperty(MATCH + "variance"), HighlightRule.MAX_VARIANCE);
    Rgb color = color(properties.getProperty(MATCH + "color", ""));
    match =
        new MatchHighlight(
            enabled < 0 ? match.enabled() : enabled == 1,
            color == NO_COLOR ? null : color,
            variance < 0 ? match.variance() : variance);
    return new HighlightSettings(sets, active, labels, match);
  }

  /** Returns a set, or null when malformed; {@code strict} also rejects a malformed rule. */
  private static HighlightSet set(
      Properties properties, int i, boolean strict, AtomicInteger malformed) {
    String prefix = SET + i;
    String id = properties.getProperty(prefix + ".id", "").strip();
    String name = properties.getProperty(prefix + ".name");
    if (id.isEmpty()
        || id.length() > HighlightSet.MAX_NAME_CHARS
        || name == null
        || name.length() > HighlightSet.MAX_NAME_CHARS) {
      return null;
    }
    List<HighlightRule> rules = new ArrayList<HighlightRule>();
    for (int r = 0; properties.getProperty(prefix + ".rule." + r + ".pattern") != null; r++) {
      if (rules.size() == HighlightSet.MAX_RULES) {
        return null;
      }
      HighlightRule rule = rule(properties, prefix + ".rule." + r, malformed);
      if (rule != null) {
        rules.add(rule);
      } else if (strict) {
        return null;
      }
    }
    return new HighlightSet(id, name.strip(), rules);
  }

  private static HighlightRule rule(Properties properties, String key, AtomicInteger malformed) {
    String pattern = properties.getProperty(key + ".pattern");
    int regex = flag(properties.getProperty(key + ".regex", "true"));
    int ignoreCase = flag(properties.getProperty(key + ".ignoreCase", "false"));
    int wholeLine = flag(properties.getProperty(key + ".wholeLine", "false"));
    int enabled = flag(properties.getProperty(key + ".enabled", "true"));
    Rgb fore = color(properties.getProperty(key + ".fore", ""));
    Rgb back = color(properties.getProperty(key + ".back", ""));
    int alpha = number(properties.getProperty(key + ".alpha", "255"), 255);
    int variance =
        number(properties.getProperty(key + ".variance", "0"), HighlightRule.MAX_VARIANCE);
    if (pattern == null
        || pattern.length() > HighlightRule.MAX_PATTERN_CHARS
        || (regex | ignoreCase | wholeLine | enabled) < 0
        || fore == BAD_COLOR
        || back == BAD_COLOR
        || alpha < 0
        || variance < 0) {
      return null;
    }
    return new HighlightRule(
        pattern,
        regex == 1 ? MatchMode.REGEX : MatchMode.LITERAL,
        ignoreCase == 0,
        wholeLine == 1,
        fore == NO_COLOR ? null : fore,
        back == NO_COLOR ? null : back,
        alpha,
        variance,
        enabled == 1,
        trigger(properties, key, malformed));
  }

  // Keyword triggers.

  private static void storeTrigger(RuleTrigger trigger, String key, Properties properties) {
    if (!trigger.any() && trigger.commentTemplate().isEmpty()) {
      return;
    }
    properties.setProperty(key + ".stopFollow", Boolean.toString(trigger.stopFollow()));
    properties.setProperty(key + ".setMark", Boolean.toString(trigger.setMark()));
    properties.setProperty(key + ".comment", trigger.commentTemplate());
    properties.setProperty(key + ".quiet", Boolean.toString(trigger.quiet()));
  }

  /** Reads a rule's trigger; a malformed value turns its action off and counts once. */
  private static RuleTrigger trigger(Properties properties, String key, AtomicInteger malformed) {
    int stopFollow = flag(properties.getProperty(key + ".stopFollow", "false"));
    int setMark = flag(properties.getProperty(key + ".setMark", "false"));
    int quiet = flag(properties.getProperty(key + ".quiet", "false"));
    String comment = properties.getProperty(key + ".comment", "");
    int bad = 0;
    if (stopFollow < 0) {
      bad++;
    }
    if (setMark < 0) {
      bad++;
    }
    if (quiet < 0) {
      bad++;
    }
    if (comment.length() > RuleTrigger.MAX_TEMPLATE_CHARS) {
      bad++;
      comment = "";
    }
    malformed.addAndGet(bad);
    if (stopFollow != 1 && setMark != 1 && quiet != 1 && comment.isEmpty()) {
      return RuleTrigger.NONE;
    }
    return new RuleTrigger(stopFollow == 1, setMark == 1, comment, quiet == 1);
  }

  private static void warnMalformed(AtomicInteger malformed) {
    if (malformed.get() > 0) {
      LOG.warn("Ignored {} malformed highlighter trigger values", malformed.get());
    }
  }

  private static ColorLabel label(Properties properties, int i) {
    String name = properties.getProperty(LABEL + i + ".name");
    if (name == null) {
      return null;
    }
    Rgb fore = color(properties.getProperty(LABEL + i + ".fore", ""));
    Rgb back = color(properties.getProperty(LABEL + i + ".back", ""));
    int cycle = flag(properties.getProperty(LABEL + i + ".cycle", "true"));
    if (name.isBlank()
        || name.length() > ColorLabel.MAX_NAME_CHARS
        || fore == BAD_COLOR
        || back == BAD_COLOR
        || back == NO_COLOR
        || cycle < 0) {
      return null;
    }
    return new ColorLabel(name.strip(), fore == NO_COLOR ? null : fore, back, cycle == 1);
  }

  /**
   * Formats a color.
   *
   * @param rgb the color, or null
   * @return {@code #rrggbb}, or empty for null
   */
  public static String hex(Rgb rgb) {
    if (rgb == null) {
      return "";
    }
    return String.format(Locale.ROOT, "#%02x%02x%02x", rgb.red(), rgb.green(), rgb.blue());
  }

  /**
   * Parses a color.
   *
   * @param text {@code #rrggbb}, blanks around it ignored
   * @return the color, or null when the text is not one
   */
  public static Rgb parseColor(String text) {
    Rgb parsed = color(text);
    return parsed == NO_COLOR || parsed == BAD_COLOR ? null : parsed;
  }

  /** Returns the color, {@link #NO_COLOR} for empty, {@link #BAD_COLOR} when it does not parse. */
  private static Rgb color(String text) {
    String value = text.strip();
    if (value.isEmpty()) {
      return NO_COLOR;
    }
    if (value.length() != 7 || value.charAt(0) != '#') {
      return BAD_COLOR;
    }
    for (int i = 1; i < 7; i++) {
      if (Character.digit(value.charAt(i), 16) < 0) {
        return BAD_COLOR;
      }
    }
    return Rgb.of(Integer.parseInt(value.substring(1), 16));
  }

  /** Returns 1 for {@code true}, 0 for {@code false}, -1 when absent or anything else. */
  private static int flag(String text) {
    if (text == null) {
      return -1;
    }
    String value = text.strip();
    if (value.equals("true")) {
      return 1;
    }
    return value.equals("false") ? 0 : -1;
  }

  /** Returns the number in 0 to max, or -1 when absent, out of range or not a number. */
  private static int number(String text, int max) {
    if (text == null) {
      return -1;
    }
    String value = text.strip();
    if (value.isEmpty() || value.length() > 4) {
      return -1;
    }
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) < '0' || value.charAt(i) > '9') {
        return -1;
      }
    }
    int parsed = Integer.parseInt(value);
    return parsed <= max ? parsed : -1;
  }
}
