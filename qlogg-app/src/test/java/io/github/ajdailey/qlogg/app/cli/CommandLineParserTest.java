package io.github.ajdailey.qlogg.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CommandLineParserTest {
  private static final Path CWD = Path.of("work").toAbsolutePath();
  private final CommandLineParser parser = new CommandLineParser(CWD);

  private CommandLine parse(String... args) throws CommandLineException {
    return parser.parse(args);
  }

  @Test
  void dataSyncMonitorLogCommandLine() throws Exception {
    Path file = CWD.getRoot().resolve("var").resolve("log").resolve("app.log");
    CommandLine line = parse("-m", "-f", file.toString());
    assertEquals(CommandLine.Action.RUN, line.action());
    assertTrue(line.request().multi());
    assertTrue(line.request().follow());
    assertEquals(List.of(new FileRequest(file, 0)), line.request().files());
  }

  @Test
  void compactedFlags() throws Exception {
    CommandLine line = parse("-mf", "a.log");
    assertTrue(line.request().multi());
    assertTrue(line.request().follow());
    assertEquals(CWD.resolve("a.log"), line.request().files().get(0).path());
  }

  @ParameterizedTest
  @CsvSource({
    "--window-width=900, --window-height=600",
    "--window-width, 900",
  })
  void longOptionValueForms(String first, String second) throws Exception {
    CommandLine line =
        first.contains("=") ? parse(first, second) : parse(first, second, "--window-height", "600");
    assertEquals(900, line.request().width());
    assertEquals(600, line.request().height());
    assertNull(line.request().x());
  }

  @Test
  void windowPosition() throws Exception {
    CommandLine line = parse("--window-x", "-50", "--window-y=40");
    assertEquals(-50, line.request().x());
    assertEquals(40, line.request().y());
  }

  @ParameterizedTest
  @CsvSource({
    "-d, 1, INFO",
    "-d, 2, DEBUG",
    "-d, 3, TRACE",
    "-d, 9, TRACE",
    "-d, 0, WARN",
    "--debug, error, ERROR",
    "--debug, trace, TRACE"
  })
  void debugLevels(String option, String value, LogLevel level) throws Exception {
    assertEquals(level, parse(option, value).logLevel());
  }

  @Test
  void debugValueInGroup() throws Exception {
    CommandLine line = parse("-md2");
    assertTrue(line.request().multi());
    assertEquals(LogLevel.DEBUG, line.logLevel());
    assertEquals(LogLevel.WARN, parse().logLevel());
  }

  @Test
  void lineBindsToTheNextFileOnly() throws Exception {
    List<FileRequest> files = parse("+line:5", "a.log", "b.log").request().files();
    assertEquals(new FileRequest(CWD.resolve("a.log"), 5), files.get(0));
    assertEquals(new FileRequest(CWD.resolve("b.log"), 0), files.get(1));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void lineAfterDoubleDashIsAFileName() throws Exception {
    List<FileRequest> files = parse("--", "+line:5").request().files();
    assertEquals(List.of(new FileRequest(CWD.resolve("+line:5"), 0)), files);
    assertEquals(CWD.resolve("-m"), parse("--", "-m").request().files().get(0).path());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void lineAfterDoubleDashIsAnInvalidWindowsFileName() throws Exception {
    CommandLineException e =
        assertThrows(
            CommandLineException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                parse("--", "+line:5");
              }
            });
    assertEquals("+line:5 is not a valid path", e.getMessage());
    assertEquals(CWD.resolve("-m"), parse("--", "-m").request().files().get(0).path());
  }

  @Test
  void otherPlusArgumentsAreFiles() throws Exception {
    assertEquals(CWD.resolve("+x.log"), parse("+x.log").request().files().get(0).path());
  }

  @Test
  void settingsDirectory() throws Exception {
    assertEquals(CWD.resolve("cfg"), parse("--settings", "cfg").request().settingsDirectory());
    assertNull(parse().request().settingsDirectory());
  }

  @Test
  void sessionModes() throws Exception {
    assertEquals(SessionMode.LOAD, parse("-s").request().session());
    assertEquals(SessionMode.NEW, parse("--new-session").request().session());
    assertEquals(SessionMode.DEFAULT, parse().request().session());
  }

  @Test
  void helpAndVersion() throws Exception {
    assertEquals(CommandLine.Action.HELP, parse("-h").action());
    assertEquals(CommandLine.Action.HELP, parse("--help-all").action());
    assertEquals(CommandLine.Action.VERSION, parse("-v").action());
    assertTrue(parse("-l").logToFile());
    assertFalse(parse().logToFile());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "--bogus",
        "-x",
        "+line:",
        "+line:0",
        "+line:abc",
        "+line:5",
        "--window-width",
        "--window-width=0",
        "--window-width=wide",
        "--window-height=-3",
        "--multi=yes",
        "-d",
        "-d=-1",
        "-sn"
      })
  void rejects(final String arg) {
    assertThrows(
        CommandLineException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            parse(arg);
          }
        });
  }

  @Test
  void rejectsLineWithoutFileBeforeAnotherLine() {
    assertThrows(
        CommandLineException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            parse("+line:2", "+line:3", "a.log");
          }
        });
  }

  @Test
  void usageListsTheKloggOptions() {
    String usage = CommandLineParser.usage();
    for (String option :
        List.of("--help", "--version", "--debug", "--multi", "--load-session", "--new-session")) {
      assertTrue(usage.contains(option), option);
    }
    for (String option : List.of("--log", "--follow", "--window-width", "--window-height")) {
      assertTrue(usage.contains(option), option);
    }
  }
}
