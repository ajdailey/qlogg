package io.github.ajdailey.qlogg.core.io;

import io.github.ajdailey.qlogg.core.util.Streams;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads a Java properties file that QLogg wrote or a text editor saved: one leading UTF-8 BOM is
 * dropped, and the rest decodes as UTF-8, or as ISO-8859-1 when it holds a malformed UTF-8
 * sequence. QLogg writes ASCII with Unicode escapes, which reads the same either way.
 */
public final class PropertiesFiles {
  private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private PropertiesFiles() {}

  /**
   * Reads a properties file. Blocking.
   *
   * @param file the file
   * @param maxBytes the largest file accepted
   * @return the properties
   * @throws IOException when the file cannot be read or holds more than {@code maxBytes} bytes
   * @throws IllegalArgumentException when the text holds a malformed Unicode escape
   */
  public static Properties read(Path file, int maxBytes) throws IOException {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(file)) {
      bytes = Streams.readLimited(in, maxBytes);
    }
    int start = bomLength(bytes);
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes, start, bytes.length - start))
              .toString();
    } catch (CharacterCodingException e) {
      text = new String(bytes, start, bytes.length - start, StandardCharsets.ISO_8859_1);
    }
    Properties properties = new Properties();
    properties.load(new StringReader(text));
    return properties;
  }

  private static int bomLength(byte[] bytes) {
    if (bytes.length < BOM.length) {
      return 0;
    }
    for (int i = 0; i < BOM.length; i++) {
      if (bytes[i] != BOM[i]) {
        return 0;
      }
    }
    return BOM.length;
  }
}
