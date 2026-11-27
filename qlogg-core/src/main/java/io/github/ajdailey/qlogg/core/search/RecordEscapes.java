package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.format.XmlReferences;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.List;

/**
 * The prefilter of a column literal on a {@link ColumnSchema#verbatim} column: {@link #mayHold}
 * decides from a record's bytes whether a column value of the record can hold the literal, and
 * {@link ColumnTerms} parses only a record for which it is true.
 *
 * <p>A CSV, TSV or log4j value is a run of the record's chars with escape markup removed or
 * replaced. An occurrence of the literal in a value is therefore in the bytes, or it spans removed
 * or replaced markup, and the literal's chars before the first such markup are the bytes right
 * before it:
 *
 * <table>
 *   <caption>Prefilter by format</caption>
 *   <tr><th>Format</th><th>{@link #mayHold} is true when the record holds the literal or</th></tr>
 *   <tr><td>CSV, TSV</td><td>A quote right after a proper prefix of the literal</td></tr>
 *   <tr><td>log4j XML</td><td>A {@code <![CDATA[}, {@code ]]>} or reference {@code &...;} right
 *       after a proper prefix of the literal; a reference whose char starts the literal</td></tr>
 *   <tr><td>Any format in another encoding, CLEF</td><td>Any of {@link ColumnSchema#escapes}</td>
 *   </tr>
 * </table>
 *
 * <p>The CSV and log4j tests find the literal and the markup in one pass, 8 one-byte units at a
 * time, and need a byte-aligned, ASCII-compatible encoding. Not thread-safe.
 */
abstract class RecordEscapes {
  /**
   * Returns the prefilter for a schema.
   *
   * @param schema the columns
   * @param encoding the document's encoding
   * @return the prefilter, or null when an escape has no byte form
   */
  static RecordEscapes of(ColumnSchema schema, TextEncoding encoding) {
    boolean bytewise =
        encoding.unitWidth() == 1
            && StandardMatchers.byteAligned(encoding)
            && encoding.asciiCompatible();
    ColumnFormat format = schema.format();
    if (bytewise && (format == ColumnFormat.CSV || format == ColumnFormat.TSV)) {
      return new Quotes();
    }
    if (bytewise && format == ColumnFormat.LOG4J_XML) {
      return new Log4j(encoding.charset());
    }
    List<String> texts = schema.escapes();
    StandardMatchers.ByteLiteral[] escapes = new StandardMatchers.ByteLiteral[texts.size()];
    for (int i = 0; i < escapes.length; i++) {
      escapes[i] = StandardMatchers.contains(texts.get(i), true, encoding, false);
      if (escapes[i] == null) {
        return null;
      }
    }
    return new Any(escapes);
  }

  /** Starts the next record. */
  void next() {
    // Only a prefilter that keeps a result per record resets it.
  }

  /**
   * Reports whether a column value of the record can hold the literal.
   *
   * @param record the record's bytes
   * @param literal the literal
   * @return false when no value holds it
   */
  abstract boolean mayHold(LineText record, StandardMatchers.ByteLiteral literal);

  /** The literal, or any escape byte sequence; the escape result is kept per record. */
  private static final class Any extends RecordEscapes {
    private final StandardMatchers.ByteLiteral[] escapes;
    private int escaped = -1;

    Any(StandardMatchers.ByteLiteral[] escapes) {
      this.escapes = escapes;
    }

    @Override
    void next() {
      escaped = -1;
    }

    @Override
    boolean mayHold(LineText record, StandardMatchers.ByteLiteral literal) {
      if (literal.test(record)) {
        return true;
      }
      if (escaped < 0) {
        escaped = 0;
        for (StandardMatchers.ByteLiteral escape : escapes) {
          if (escape.test(record)) {
            escaped = 1;
            break;
          }
        }
      }
      return escaped == 1;
    }
  }

  /**
   * A format whose markup starts with one byte: each offset holding the literal's first byte and,
   * one byte on, its second byte or a markup byte is a candidate, compared on until the literal
   * ends or markup interrupts it.
   */
  private abstract static class Marked extends RecordEscapes {
    private static final long CASE = 0x2020202020202020L;
    private final long markA;
    private final long markB;
    private final long markC;
    private final long replacement;
    private final boolean replaces;

    /**
     * Creates the scan for markup starting with any of three bytes, repeats allowed.
     *
     * @param replacement the byte starting markup replaced by chars, or 0 for none
     */
    Marked(byte a, byte b, byte c, byte replacement) {
      markA = ByteScan.repeat(a);
      markB = ByteScan.repeat(b);
      markC = ByteScan.repeat(c);
      this.replacement = ByteScan.repeat(replacement);
      replaces = replacement != 0;
    }

    /** Reports whether markup the parser removes or replaces starts at {@code at}. */
    abstract boolean markup(ByteBuffer bytes, int end, int at);

    /** Reports whether markup at {@code at} is replaced by chars the literal starts with. */
    abstract boolean replacedByStart(
        ByteBuffer bytes, int end, int at, StandardMatchers.ByteLiteral literal);

    @Override
    final boolean mayHold(LineText record, StandardMatchers.ByteLiteral literal) {
      ByteBuffer bytes = record.buffer();
      int start = record.start();
      int end = record.end();
      byte[] pattern = literal.pattern();
      boolean fold = literal.folds();
      if (pattern.length == 1 && !replaces) {
        return literal.test(record);
      }
      boolean little = bytes.order() == ByteOrder.LITTLE_ENDIAN;
      long first = ByteScan.repeat(pattern[0]);
      long firstCase = fold && letter(pattern[0]) ? CASE : 0;
      long second = pattern.length > 1 ? ByteScan.repeat(pattern[1]) : 0;
      long secondCase = pattern.length > 1 && fold && letter(pattern[1]) ? CASE : 0;
      for (int at = start; at < end; at += Long.BYTES) {
        boolean whole = end - at > Long.BYTES;
        long word = whole ? bytes.getLong(at) : ByteScan.word(bytes, at, end);
        if (whole && !little) {
          word = Long.reverseBytes(word);
        }
        long candidates = ByteScan.flags(word | firstCase, first);
        long replaced = replaces ? ByteScan.flags(word, replacement) : 0;
        if ((candidates | replaced) == 0) {
          continue;
        }
        if (pattern.length > 1) {
          long ahead;
          if (whole) {
            ahead = bytes.getLong(at + 1);
            ahead = little ? ahead : Long.reverseBytes(ahead);
          } else {
            ahead = at + 1 < end ? ByteScan.word(bytes, at + 1, end) : 0;
          }
          candidates &=
              ByteScan.flags(ahead | secondCase, second)
                  | ByteScan.flags(ahead, markA)
                  | ByteScan.flags(ahead, markB)
                  | ByteScan.flags(ahead, markC);
        }
        long events = candidates | replaced;
        while (events != 0) {
          int bit = Long.numberOfTrailingZeros(events);
          events &= events - 1;
          int offset = at + (bit >>> 3);
          if (offset >= end) {
            break;
          }
          long flag = 1L << bit;
          if ((candidates & flag) != 0 && continues(bytes, end, offset, pattern, fold)) {
            return true;
          }
          if ((replaced & flag) != 0 && replacedByStart(bytes, end, offset, literal)) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * Reports whether the pattern starts at {@code at} and runs to its end or to markup after its
     * first byte.
     */
    private boolean continues(ByteBuffer bytes, int end, int at, byte[] pattern, boolean fold) {
      for (int k = 0; ; k++) {
        if (k == pattern.length) {
          return true;
        }
        if (at + k >= end) {
          return false;
        }
        if (k > 0 && markup(bytes, end, at + k)) {
          return true;
        }
        byte b = bytes.get(at + k);
        if ((fold ? StandardMatchers.ByteLiteral.lower(b) : b) != pattern[k]) {
          return false;
        }
      }
    }

    private static boolean letter(byte b) {
      return b >= 'a' && b <= 'z';
    }

    /** Reports whether {@code text} is at {@code at}. */
    static boolean at(ByteBuffer bytes, int at, int end, byte[] text) {
      if (end - at < text.length) {
        return false;
      }
      for (int i = 0; i < text.length; i++) {
        if (bytes.get(at + i) != text[i]) {
          return false;
        }
      }
      return true;
    }

    /** Reports whether the pattern and {@code encoded} agree over their common length. */
    static boolean sharesStart(StandardMatchers.ByteLiteral literal, byte[] encoded) {
      byte[] pattern = literal.pattern();
      int common = Math.min(pattern.length, encoded.length);
      for (int k = 0; k < common; k++) {
        byte b = encoded[k];
        if ((literal.folds() ? StandardMatchers.ByteLiteral.lower(b) : b) != pattern[k]) {
          return false;
        }
      }
      return common > 0;
    }
  }

  /** CSV and TSV: the parser removes quotes and keeps every other char. */
  private static final class Quotes extends Marked {
    Quotes() {
      super((byte) '"', (byte) '"', (byte) '"', (byte) 0);
    }

    @Override
    boolean markup(ByteBuffer bytes, int end, int at) {
      return bytes.get(at) == '"';
    }

    @Override
    boolean replacedByStart(
        ByteBuffer bytes, int end, int at, StandardMatchers.ByteLiteral literal) {
      return false;
    }
  }

  /**
   * log4j: the parser removes CDATA markers and replaces references. Each {@code <![CDATA[} and
   * {@code ]]>} counts, whether or not the parser reads it as a section.
   */
  private static final class Log4j extends Marked {
    private static final byte[] CDATA = {'<', '!', '[', 'C', 'D', 'A', 'T', 'A', '['};
    private static final byte[] CDATA_END = {']', ']', '>'};
    private static final int MAX_BODY = 32;
    private final Charset charset;
    private final StringBuilder body = new StringBuilder(MAX_BODY);

    Log4j(Charset charset) {
      super((byte) '<', (byte) ']', (byte) '&', (byte) '&');
      this.charset = charset;
    }

    @Override
    boolean markup(ByteBuffer bytes, int end, int at) {
      return switch (bytes.get(at)) {
        case '<' -> at(bytes, at, end, CDATA);
        case ']' -> at(bytes, at, end, CDATA_END);
        case '&' -> reference(bytes, end, at) >= 0;
        default -> false;
      };
    }

    @Override
    boolean replacedByStart(
        ByteBuffer bytes, int end, int at, StandardMatchers.ByteLiteral literal) {
      int cp = reference(bytes, end, at);
      return cp >= 0 && sharesStart(literal, new String(Character.toChars(cp)).getBytes(charset));
    }

    /**
     * Returns the code point of the reference at {@code amp}, read as {@link
     * XmlReferences#unescape} reads one, or -1. A body with a non-ASCII byte names none.
     */
    private int reference(ByteBuffer bytes, int end, int amp) {
      body.setLength(0);
      int limit = Math.min(end, amp + 2 + MAX_BODY);
      for (int i = amp + 1; i < limit; i++) {
        byte b = bytes.get(i);
        if (b == ';') {
          return i > amp + 1 ? XmlReferences.codePoint(body, 0, body.length()) : -1;
        }
        if (b < 0 || b == '&' || b == '<' || Character.isWhitespace(b)) {
          return -1;
        }
        body.append((char) b);
      }
      return -1;
    }
  }
}
