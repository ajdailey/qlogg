package io.github.ajdailey.qlogg.app.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class FilterFileTest {
  private static final List<PredefinedFilter> FILTERS =
      List.of(
          new PredefinedFilter("Errors", "ERROR|FATAL", MatchMode.REGEX),
          new PredefinedFilter("Mock term", "BLOCKTERM = \"x\"", MatchMode.LITERAL),
          new PredefinedFilter("caf\u00e9", "\\d{3}-\\d{2}", MatchMode.REGEX));

  @TempDir Path temp;

  /** Returns the bytes after a UTF-8 byte order mark, as an editor saves them. */
  private static byte[] withBom(byte[] content) {
    byte[] marked = new byte[content.length + 3];
    marked[0] = (byte) 0xEF;
    marked[1] = (byte) 0xBB;
    marked[2] = (byte) 0xBF;
    System.arraycopy(content, 0, marked, 3, content.length);
    return marked;
  }

  @Test
  void exportThenImportRoundTrips() throws IOException {
    Path file = temp.resolve("filters.conf");
    FilterFile.write(file, FILTERS);
    assertEquals(FILTERS, FilterFile.read(file));
    String text = Files.readString(file, StandardCharsets.ISO_8859_1);
    assertTrue(text.startsWith("filter.0.name=Errors\n"), text);
    assertTrue(text.contains("version=1\n"), text);
    assertFalse(text.contains("#"), text);
    FilterFile.write(file, FILTERS.subList(0, 1));
    assertEquals(FILTERS.subList(0, 1), FilterFile.read(file));
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(temp)) {
      for (Path entry : entries) {
        names.add(entry.getFileName().toString());
      }
    }
    assertEquals(List.of("filters.conf"), names);
  }

  private void assertMalformed(String content) throws IOException {
    final Path file = temp.resolve("bad.conf");
    Files.writeString(file, content, StandardCharsets.ISO_8859_1);
    assertThrows(
        FilterFile.FormatException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            FilterFile.read(file);
          }
        });
  }

  @Test
  void malformedFilesAreRefused() throws IOException {
    assertMalformed("filter.0.name=a\nfilter.0.pattern=b\n");
    assertMalformed("version=2\nfilter.0.name=a\nfilter.0.pattern=b\n");
    assertMalformed("version=1\nfilter.0.name=a\n");
    assertMalformed("version=1\nfilter.0.name=a\nfilter.0.pattern=b\nfilter.0.regex=perhaps\n");
    assertMalformed("version=1\nbroken=\\u12\n");
    assertMalformed("version=1\n#" + "x".repeat(FilterFile.MAX_BYTES));
  }

  @Test
  void aDirectoryIsNotAFilterFile() {
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            FilterFile.read(temp);
          }
        });
  }

  @Test
  void anEmptyExportHasNoFilters() throws IOException {
    Path file = temp.resolve("empty.conf");
    FilterFile.write(file, List.of());
    assertEquals(List.of(), FilterFile.read(file));
  }

  @Test
  void anExportWithAUtf8ByteOrderMarkImports() throws IOException {
    Path file = temp.resolve("filters.conf");
    FilterFile.write(file, FILTERS);
    Files.write(file, withBom(Files.readAllBytes(file)));
    assertEquals(FILTERS, FilterFile.read(file));
  }

  @Test
  void anExportEditedAsUtf8OrLatin1KeepsItsNonAsciiText() throws IOException {
    Path file = temp.resolve("filters.conf");
    FilterFile.write(file, FILTERS);
    String text = Files.readString(file, StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("\\u00E9"), text);
    String typed = text.replace("\\u00E9", "\u00e9");
    Files.writeString(file, typed, StandardCharsets.UTF_8);
    assertEquals(FILTERS, FilterFile.read(file));
    Files.writeString(file, typed, StandardCharsets.ISO_8859_1);
    assertEquals(FILTERS, FilterFile.read(file));
  }
}
