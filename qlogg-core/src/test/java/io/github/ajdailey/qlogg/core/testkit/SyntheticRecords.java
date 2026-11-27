package io.github.ajdailey.qlogg.core.testkit;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

/**
 * Deterministic records in a column format, for tests and benchmarks: CSV and TSV with a header
 * line, Apache access log, Serilog CLEF, log4j XML events over several lines, and timestamped lines
 * with stack traces. {@link #RARE} is in 0.1% and {@link #COMMON} in 10% of the records. With edges
 * on, values carry the format's escapes, delimiters, quotes, CRLF and line feeds inside values,
 * blank lines and stray quotes; off, CSV quotes only the share of values given. The same arguments
 * give the same text.
 */
public final class SyntheticRecords {
  /** Token in 0.1% of the records. */
  public static final String RARE = "flagword";

  /** Token in 10% of the records. */
  public static final String COMMON = "blockterm";

  private static final String[] WORDS = {
    "mock", "event", "queue", "worker", "record", "accepted", "batch", "sample", "café", "Info"
  };
  private static final String[] LEVELS = {"INFO", "DEBUG", "WARN", "ERROR", "Information"};
  private static final String[] SPECIAL = {
    ",", ";", "\t", "\"", "\"\"", "\\", "&", "<", "\n", "\r\n"
  };

  private final ColumnFormat format;
  private final Random random;
  private final boolean edges;
  private final double quoted;
  private long number;

  /**
   * Creates a generator.
   *
   * @param format the format; CSV and TSV start with a header line
   * @param seed the seed
   * @param edges true writes the format's hard cases
   * @param quoted the share of CSV and TSV values quoted without edges
   */
  public SyntheticRecords(ColumnFormat format, long seed, boolean edges, double quoted) {
    this.format = format;
    this.random = new Random(seed);
    this.edges = edges;
    this.quoted = quoted;
  }

  /**
   * Builds records in memory.
   *
   * @param records the record count, the CSV header included
   * @return the text, each record ending with LF or CRLF
   */
  public String text(int records) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < records; i++) {
      append(out);
    }
    return out.toString();
  }

  /**
   * Writes a new UTF-8 file of at least {@code targetBytes}.
   *
   * @param file created; must not exist
   * @param targetBytes least size
   * @return bytes written
   * @throws IOException on a write error, or when the file exists
   */
  public long write(Path file, long targetBytes) throws IOException {
    long written = 0;
    StringBuilder out = new StringBuilder(1 << 16);
    try (OutputStream output =
        new BufferedOutputStream(
            Files.newOutputStream(file, StandardOpenOption.CREATE_NEW), 1 << 20)) {
      while (written < targetBytes) {
        out.setLength(0);
        while (out.length() < 60_000) {
          append(out);
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        output.write(bytes);
        written += bytes.length;
      }
    }
    return written;
  }

  /**
   * Appends the next record with its terminator.
   *
   * @param out receives the record
   */
  public void append(StringBuilder out) {
    long n = number++;
    switch (format) {
      case CSV, TSV -> delimited(out, n, format == ColumnFormat.CSV ? ',' : '\t');
      case CLF -> clf(out, n);
      case CLEF -> clef(out, n);
      case LOG4J_XML -> log4j(out, n);
      case TIME -> stamped(out, n);
    }
  }

  private String message(long n) {
    StringBuilder text = new StringBuilder();
    int count = 1 + random.nextInt(8);
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        text.append(' ');
      }
      if (edges && random.nextInt(6) == 0) {
        text.append(SPECIAL[random.nextInt(SPECIAL.length)]);
      }
      text.append(WORDS[random.nextInt(WORDS.length)]);
    }
    double draw = random.nextDouble();
    if (draw < 0.001) {
      text.append(' ').append(RARE);
    } else if (draw < 0.101) {
      text.append(' ').append(COMMON);
    }
    text.append(" #").append(n);
    return text.toString();
  }

  private String newline() {
    return edges && random.nextInt(4) == 0 ? "\r\n" : "\n";
  }

  private String level() {
    return LEVELS[random.nextInt(LEVELS.length)];
  }

  private void delimited(StringBuilder out, long n, char delimiter) {
    if (n == 0) {
      String d = String.valueOf(delimiter);
      out.append("time").append(d).append("level").append(d).append("logger").append(d);
      out.append("message").append(d).append("count").append('\n');
      return;
    }
    String[] fields = {
      "2026-11-01 12:00:" + (10 + random.nextInt(50)),
      level(),
      "mock.Logger" + random.nextInt(9),
      message(n),
      String.valueOf(random.nextInt(1000))
    };
    int count = fields.length;
    if (edges && random.nextInt(10) == 0) {
      count = 1 + random.nextInt(fields.length + 2);
    }
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        out.append(delimiter);
      }
      String value = i < fields.length ? fields[i] : "extra" + i;
      if (edges && random.nextInt(20) == 0) {
        value = "";
      }
      boolean special = needsQuotes(value, delimiter);
      if (special || random.nextDouble() < quoted) {
        out.append('"').append(value.replace("\"", "\"\"")).append('"');
        if (edges && random.nextInt(30) == 0) {
          out.append("tail");
        }
      } else if (edges && random.nextInt(25) == 0 && !value.isEmpty()) {
        out.append(value.charAt(0)).append('"').append(value, 1, value.length());
      } else {
        out.append(value);
      }
    }
    out.append(newline());
  }

  private static boolean needsQuotes(String value, char delimiter) {
    return value.indexOf(delimiter) >= 0
        || value.indexOf('"') >= 0
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0;
  }

  private void clf(StringBuilder out, long n) {
    out.append("10.0.").append(random.nextInt(9)).append('.').append(n % 250).append(" - ");
    out.append(random.nextInt(5) == 0 ? "-" : "user" + random.nextInt(9));
    out.append(" [01/Nov/2026:12:").append(10 + random.nextInt(50)).append(":00 +0000] \"GET /");
    out.append(
        message(n).replace(' ', '/').replace("\"", "\\\"").replace("\n", "").replace("\r", ""));
    out.append(" HTTP/1.1\" ").append(200 + random.nextInt(300)).append(' ');
    out.append(random.nextInt(4) == 0 ? "-" : String.valueOf(random.nextInt(99999)));
    if (random.nextBoolean()) {
      out.append(" \"http://mock.example/\" \"Mock/1.0 (").append(level()).append(")\"");
    }
    if (edges && random.nextInt(40) == 0) {
      out.append(" trailing");
    }
    out.append(newline());
  }

  private void clef(StringBuilder out, long n) {
    out.append("{\"@t\":\"2026-11-01T12:").append(10 + random.nextInt(50)).append(":00.123Z\"");
    String message = json(message(n));
    if (random.nextBoolean()) {
      out.append(",\"@m\":\"").append(message).append('"');
    } else {
      out.append(",\"@mt\":\"Run {User} ").append(message).append(" {{x}} {Count}\"");
    }
    out.append(",\"User\":\"").append(json(WORDS[random.nextInt(WORDS.length)])).append('"');
    out.append(",\"Count\":").append(random.nextInt(1000));
    if (random.nextInt(3) > 0) {
      out.append(",\"@l\":\"").append(level()).append('"');
    }
    if (random.nextInt(10) == 0) {
      out.append(",\"@x\":\"mock.Failure: ").append(json(message(n))).append('"');
    }
    if (edges && random.nextInt(10) == 0) {
      out.append(",\"@@at\":{\"nested\":[1,\"").append(json(message(n))).append("\"]}");
    }
    out.append('}');
    if (edges && random.nextInt(50) == 0) {
      out.setLength(out.length() - 1);
    }
    out.append(newline());
  }

  private static String json(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  private void log4j(StringBuilder out, long n) {
    String nl = newline();
    if (edges && random.nextInt(10) == 0) {
      out.append(nl);
    }
    if (edges && random.nextInt(5) == 0) {
      out.append("  ");
    }
    out.append("<log4j:event logger=\"mock.Logger").append(random.nextInt(9));
    out.append("\" timestamp=\"17935344").append(10000 + random.nextInt(89999));
    out.append("\" level=\"").append(level()).append("\" thread=\"worker-");
    out.append(edges && random.nextInt(8) == 0 ? "a&amp;b" : String.valueOf(random.nextInt(9)));
    out.append("\">").append(nl);
    String message = message(n).replace("\r", "");
    if (random.nextBoolean()) {
      out.append("<log4j:message><![CDATA[").append(message).append("]]></log4j:message>");
    } else {
      out.append("<log4j:message>").append(xml(message)).append("</log4j:message>");
    }
    out.append(nl);
    if (random.nextInt(5) == 0) {
      out.append("<log4j:throwable><![CDATA[mock.Failure: ").append(level()).append(nl);
      int frames = 1 + random.nextInt(4);
      for (int i = 0; i < frames; i++) {
        out.append("\tat mock.Frame.run(Frame.java:").append(i).append(')').append(nl);
      }
      out.append("]]></log4j:throwable>").append(nl);
    }
    if (random.nextInt(4) == 0) {
      out.append("<log4j:locationInfo class=\"mock.C\" method=\"run\" file=\"C.java\" line=\"");
      out.append(random.nextInt(99)).append("\"/>").append(nl);
    }
    if (random.nextInt(4) == 0) {
      out.append("<log4j:properties><log4j:data name=\"user\" value=\"");
      out.append(WORDS[random.nextInt(WORDS.length)]).append("\"/></log4j:properties>").append(nl);
    }
    out.append("</log4j:event>").append(nl);
  }

  private static String xml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;");
  }

  private void stamped(StringBuilder out, long n) {
    out.append("2026-11-01 12:").append(10 + random.nextInt(50)).append(":00.123 ");
    out.append(level()).append(' ').append(message(n).replace("\r", "").replace('\n', ' '));
    out.append(newline());
    if (random.nextInt(20) == 0) {
      for (int i = 0; i < 3; i++) {
        out.append("\tat mock.Frame.run(Frame.java:").append(i).append(')').append(newline());
      }
    }
  }
}
