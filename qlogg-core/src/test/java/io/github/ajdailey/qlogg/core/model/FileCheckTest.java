package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.rotate.MemberIdentity;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCheckTest {
  @TempDir Path dir;

  /**
   * Regression: an indexing pass that ended after a copy-truncate recorded digests of the truncated
   * file, so the copy failed to match the member's identity and the set reported ROLLED instead of
   * following the copy.
   */
  @Test
  void recordOnATruncatedFileKeepsTheIdentityTheCopyMatches() throws Exception {
    Path file = dir.resolve("app.log");
    String text = "app.log line 0\napp.log line 1\napp.log line 2\n";
    Files.writeString(file, text, StandardCharsets.US_ASCII);
    long end = Files.size(file);
    try (FileByteSource source = FileByteSource.open(file, null)) {
      FileCheck check = new FileCheck(source);
      check.record(file, end);
      MemberIdentity before = check.identity();
      assertNotNull(before);
      Path copy = dir.resolve("app.log.1");
      Files.copy(file, copy);
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
        channel.truncate(0);
      }
      check.record(file, end);
      assertEquals(before, check.identity());
      assertEquals(
          MemberIdentity.Outcome.FOUND, check.identity().byContent().find(List.of(copy)).outcome());
    }
  }
}
