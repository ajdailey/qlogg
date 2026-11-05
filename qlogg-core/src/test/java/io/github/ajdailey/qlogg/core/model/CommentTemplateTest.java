package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Mark comment template expansion. */
class CommentTemplateTest {
  private static final Path PATH = Path.of("var", "log", "app.service.log").toAbsolutePath();

  private static String expand(String template, String line) throws Exception {
    return CommentTemplate.parse(template).expand(41, PATH, line);
  }

  @Test
  void placeholdersExpandFromTheLineAndThePath() throws Exception {
    assertEquals(
        "42 " + PATH + " " + PATH.getParent() + " app.service.log app.service log",
        expand("%L %F %P %N %M %E", "text"));
    assertEquals("100% %x %", expand("100% %x %", "text"), "other percent signs stay");
  }

  @Test
  void pathPlaceholdersAreEmptyWithoutAFile() throws Exception {
    assertEquals("1:::", CommentTemplate.parse("%L:%F:%N:%E").expand(0, null, "x"));
  }

  @Test
  void aNameWithoutADotHasNoExtension() throws Exception {
    assertEquals(
        "README|",
        CommentTemplate.parse("%M|%E").expand(0, Path.of("README").toAbsolutePath(), "x"));
  }

  @Test
  void aRegexReplacesWithinTheLineText() throws Exception {
    assertEquals(
        "user flagword at 42",
        expand("user {.*user=(\\w+).*}{$1} at %L", "time=1 user=flagword op=read"));
    assertEquals("a-b-c", expand("{\\s}{-}", "a b c"));
    assertEquals("x", expand("{a{2}}{x}", "aa"), "a quantifier's braces nest");
    assertEquals("}", expand("{\\}}{$0}", "}"), "an escaped brace does not close");
  }

  @Test
  void anIncompletePairIsText() throws Exception {
    assertEquals("{a} b", expand("{a} b", "line"));
    assertEquals("{a}{b", expand("{a}{b", "line"));
  }

  @Test
  void aRegexThatDoesNotCompileFailsEveryExpansion() {
    final CommentTemplate template = CommentTemplate.parse("at %L {(}{x}");
    assertNotNull(template.invalid());
    assertThrows(
        CommentTemplate.TemplateException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            template.expand(0, PATH, "line");
          }
        });
    assertNull(CommentTemplate.parse("plain").invalid());
  }

  @Test
  void aMissingGroupOrABacktrackingRegexFailsTheExpansion() {
    assertThrows(
        CommentTemplate.TemplateException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            expand("{a}{$3}", "a");
          }
        });
    final String line = "a".repeat(1000);
    assertThrows(
        CommentTemplate.TemplateException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            expand("{.*.*.*z}{x}", line);
          }
        });
  }

  @Test
  void anExpansionIsCut() throws Exception {
    String line = "y".repeat(5000);
    assertEquals(
        CommentTemplate.MAX_EXPANDED_CHARS, expand("{y}{yy}", line).length(), "regex output");
    assertEquals(CommentTemplate.MAX_EXPANDED_CHARS, expand("{^}{} %F", line).length());
  }
}
