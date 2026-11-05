package io.github.ajdailey.qlogg.app.highlight;

import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.HighlightRule;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Composes the rules one document's views color their rows with, lowest priority first: the active
 * highlighter sets, the match highlight of the last search, then the color labels. Stateless.
 */
public final class HighlightComposer {
  private HighlightComposer() {}

  /**
   * The search whose matches the match rule colors.
   *
   * @param text the search text; empty for none
   * @param mode literal or regex
   * @param caseSensitive false folds case
   * @param wholeWord terms match only between non-word characters
   * @param combining the text is a combined expression
   * @param inverse the search lists the lines the text does not match
   */
  public record LastSearch(
      String text,
      MatchMode mode,
      boolean caseSensitive,
      boolean wholeWord,
      boolean combining,
      boolean inverse) {
    /** No search. */
    public static final LastSearch NONE =
        new LastSearch("", MatchMode.LITERAL, true, false, false, false);

    /** Checks the components. */
    public LastSearch {
      if (text == null || mode == null) {
        throw new IllegalArgumentException("text and mode are required");
      }
    }
  }

  /**
   * Returns the rules in apply order.
   *
   * @param settings the highlight settings
   * @param autoSet id of the set a selector chose for the document, which replaces the active sets
   *     while a set has that id; null for none
   * @param search the last search
   * @param labels the document's color labels
   * @param palette the current palette
   * @return the rules, the last one winning where rules overlap
   */
  public static List<HighlightRule> compose(
      HighlightSettings settings,
      String autoSet,
      LastSearch search,
      LabelState labels,
      Palette palette) {
    HighlightSettings applied = settings;
    if (autoSet != null && settings.ids().contains(autoSet)) {
      applied = settings.withActive(List.of(autoSet));
    }
    List<HighlightRule> rules = new ArrayList<HighlightRule>(applied.rulesInApplyOrder());
    HighlightRule match = matchRule(settings.match(), search, palette.match());
    if (match != null) {
      rules.add(match);
    }
    rules.addAll(labels.rules(ColorLabel.forPalette(settings.labels(), palette.dark())));
    return rules;
  }

  /**
   * Returns the rule coloring a search's matches. Whole word wraps the quoted or regex pattern in
   * word-boundary lookarounds and matches as a regex.
   *
   * @param match the match highlight settings
   * @param search the search
   * @param themeColor the background used when {@code match} has no color
   * @return the rule; null when the match highlight is off, the text is empty, the search is
   *     combined or inverse, or the pattern exceeds {@link HighlightRule#MAX_PATTERN_CHARS}
   */
  public static HighlightRule matchRule(MatchHighlight match, LastSearch search, Rgb themeColor) {
    String text = search.text();
    if (!match.enabled()
        || text.isEmpty()
        || search.combining()
        || search.inverse()
        || text.length() > HighlightRule.MAX_PATTERN_CHARS) {
      return null;
    }
    String pattern = text;
    MatchMode mode = search.mode();
    if (search.wholeWord()) {
      String inner = mode == MatchMode.REGEX ? pattern : Pattern.quote(pattern);
      try {
        pattern = StandardMatchers.wholeWord(inner);
      } catch (PatternSyntaxException e) {
        // The highlighter skips a rule that does not compile.
        pattern = inner;
      }
      mode = MatchMode.REGEX;
      if (pattern.length() > HighlightRule.MAX_PATTERN_CHARS) {
        return null;
      }
    }
    return new HighlightRule(
        pattern,
        mode,
        search.caseSensitive(),
        false,
        null,
        match.color() == null ? themeColor : match.color(),
        255,
        match.variance(),
        true);
  }
}
