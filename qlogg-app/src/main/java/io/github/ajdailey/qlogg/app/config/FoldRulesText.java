package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.FoldRules;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The fold rules as the Options dialog edits them: an entry start pattern and continuation patterns
 * one per line, blank lines ignored and other lines kept as typed (a trailing space can matter, as
 * in {@code ^at }). {@link #parse} compiles every pattern, so a rule that reaches the settings
 * always compiles.
 *
 * @param rules the rules, or null when the text is refused
 * @param error the reason for a refusal, or null
 */
public record FoldRulesText(FoldRules rules, String error) {
  /**
   * Checks the edited text and builds the rules.
   *
   * @param entryStart the entry start pattern; blank for none
   * @param continuation continuation patterns, one per line
   * @return the rules, or the first problem: a pattern that does not compile, no pattern at all, or
   *     more than {@code ViewFile.MAX_CONTINUATION} continuation patterns
   */
  public static FoldRulesText parse(String entryStart, String continuation) {
    String start = entryStart.isBlank() ? "" : entryStart;
    List<String> patterns = new ArrayList<String>();
    for (String line : continuation.split("\\R")) {
      if (!line.isBlank()) {
        patterns.add(line);
      }
    }
    if (start.isEmpty() && patterns.isEmpty()) {
      return new FoldRulesText(null, UiText.OPTIONS_FOLD_EMPTY.text());
    }
    if (patterns.size() > ViewFile.MAX_CONTINUATION) {
      return new FoldRulesText(
          null, UiText.OPTIONS_FOLD_TOO_MANY.format(ViewFile.MAX_CONTINUATION));
    }
    List<String> all = new ArrayList<String>(patterns);
    if (!start.isEmpty()) {
      all.add(0, start);
    }
    for (String pattern : all) {
      try {
        Pattern.compile(pattern);
      } catch (PatternSyntaxException e) {
        return new FoldRulesText(
            null, UiText.OPTIONS_FOLD_INVALID.format(pattern, e.getDescription()));
      }
    }
    return new FoldRulesText(new FoldRules(start, patterns), null);
  }

  /**
   * Returns continuation patterns as the dialog shows them.
   *
   * @param rules the rules
   * @return one pattern per line
   */
  public static String continuationText(FoldRules rules) {
    return String.join("\n", rules.continuation());
  }
}
