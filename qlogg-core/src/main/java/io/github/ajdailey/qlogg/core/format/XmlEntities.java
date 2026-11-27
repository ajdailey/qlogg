package io.github.ajdailey.qlogg.core.format;

import java.util.Arrays;

/**
 * XML entity unescape. It replaces the five predefined XML entities, the 252 named entities of HTML
 * 4.01 and decimal and hex character references; any other {@code &}, an unknown name, a reference
 * without its {@code ;} and a reference to no Unicode scalar value (or to U+0000) stay as they are.
 * Names are case-sensitive. No DTD or entity declaration is read. {@link XmlReferences} holds the
 * range overload.
 */
public final class XmlEntities {
  /** Latin-1 entities, U+00A0 to U+00FF in order (HTML 4.01 {@code HTMLlat1}). */
  private static final String[] LATIN1 = {
    "nbsp", "iexcl", "cent", "pound", "curren", "yen", "brvbar", "sect", "uml", "copy", "ordf",
    "laquo", "not", "shy", "reg", "macr", "deg", "plusmn", "sup2", "sup3", "acute", "micro", "para",
    "middot", "cedil", "sup1", "ordm", "raquo", "frac14", "frac12", "frac34", "iquest", "Agrave",
    "Aacute", "Acirc", "Atilde", "Auml", "Aring", "AElig", "Ccedil", "Egrave", "Eacute", "Ecirc",
    "Euml", "Igrave", "Iacute", "Icirc", "Iuml", "ETH", "Ntilde", "Ograve", "Oacute", "Ocirc",
    "Otilde", "Ouml", "times", "Oslash", "Ugrave", "Uacute", "Ucirc", "Uuml", "Yacute", "THORN",
    "szlig", "agrave", "aacute", "acirc", "atilde", "auml", "aring", "aelig", "ccedil", "egrave",
    "eacute", "ecirc", "euml", "igrave", "iacute", "icirc", "iuml", "eth", "ntilde", "ograve",
    "oacute", "ocirc", "otilde", "ouml", "divide", "oslash", "ugrave", "uacute", "ucirc", "uuml",
    "yacute", "thorn", "yuml"
  };

  /**
   * The other HTML 4.01 entities ({@code HTMLsymbol}, {@code HTMLspecial}) as name and code point
   * pairs, and XML's {@code apos}.
   */
  private static final Object[] OTHERS = {
    "quot",
    34,
    "amp",
    38,
    "apos",
    39,
    "lt",
    60,
    "gt",
    62,
    "OElig",
    338,
    "oelig",
    339,
    "Scaron",
    352,
    "scaron",
    353,
    "Yuml",
    376,
    "fnof",
    402,
    "circ",
    710,
    "tilde",
    732,
    "Alpha",
    913,
    "Beta",
    914,
    "Gamma",
    915,
    "Delta",
    916,
    "Epsilon",
    917,
    "Zeta",
    918,
    "Eta",
    919,
    "Theta",
    920,
    "Iota",
    921,
    "Kappa",
    922,
    "Lambda",
    923,
    "Mu",
    924,
    "Nu",
    925,
    "Xi",
    926,
    "Omicron",
    927,
    "Pi",
    928,
    "Rho",
    929,
    "Sigma",
    931,
    "Tau",
    932,
    "Upsilon",
    933,
    "Phi",
    934,
    "Chi",
    935,
    "Psi",
    936,
    "Omega",
    937,
    "alpha",
    945,
    "beta",
    946,
    "gamma",
    947,
    "delta",
    948,
    "epsilon",
    949,
    "zeta",
    950,
    "eta",
    951,
    "theta",
    952,
    "iota",
    953,
    "kappa",
    954,
    "lambda",
    955,
    "mu",
    956,
    "nu",
    957,
    "xi",
    958,
    "omicron",
    959,
    "pi",
    960,
    "rho",
    961,
    "sigmaf",
    962,
    "sigma",
    963,
    "tau",
    964,
    "upsilon",
    965,
    "phi",
    966,
    "chi",
    967,
    "psi",
    968,
    "omega",
    969,
    "thetasym",
    977,
    "upsih",
    978,
    "piv",
    982,
    "ensp",
    8194,
    "emsp",
    8195,
    "thinsp",
    8201,
    "zwnj",
    8204,
    "zwj",
    8205,
    "lrm",
    8206,
    "rlm",
    8207,
    "ndash",
    8211,
    "mdash",
    8212,
    "lsquo",
    8216,
    "rsquo",
    8217,
    "sbquo",
    8218,
    "ldquo",
    8220,
    "rdquo",
    8221,
    "bdquo",
    8222,
    "dagger",
    8224,
    "Dagger",
    8225,
    "bull",
    8226,
    "hellip",
    8230,
    "permil",
    8240,
    "prime",
    8242,
    "Prime",
    8243,
    "lsaquo",
    8249,
    "rsaquo",
    8250,
    "oline",
    8254,
    "frasl",
    8260,
    "euro",
    8364,
    "image",
    8465,
    "weierp",
    8472,
    "real",
    8476,
    "trade",
    8482,
    "alefsym",
    8501,
    "larr",
    8592,
    "uarr",
    8593,
    "rarr",
    8594,
    "darr",
    8595,
    "harr",
    8596,
    "crarr",
    8629,
    "lArr",
    8656,
    "uArr",
    8657,
    "rArr",
    8658,
    "dArr",
    8659,
    "hArr",
    8660,
    "forall",
    8704,
    "part",
    8706,
    "exist",
    8707,
    "empty",
    8709,
    "nabla",
    8711,
    "isin",
    8712,
    "notin",
    8713,
    "ni",
    8715,
    "prod",
    8719,
    "sum",
    8721,
    "minus",
    8722,
    "lowast",
    8727,
    "radic",
    8730,
    "prop",
    8733,
    "infin",
    8734,
    "ang",
    8736,
    "and",
    8743,
    "or",
    8744,
    "cap",
    8745,
    "cup",
    8746,
    "int",
    8747,
    "there4",
    8756,
    "sim",
    8764,
    "cong",
    8773,
    "asymp",
    8776,
    "ne",
    8800,
    "equiv",
    8801,
    "le",
    8804,
    "ge",
    8805,
    "sub",
    8834,
    "sup",
    8835,
    "nsub",
    8836,
    "sube",
    8838,
    "supe",
    8839,
    "oplus",
    8853,
    "otimes",
    8855,
    "perp",
    8869,
    "sdot",
    8901,
    "lceil",
    8968,
    "rceil",
    8969,
    "lfloor",
    8970,
    "rfloor",
    8971,
    "lang",
    9001,
    "rang",
    9002,
    "loz",
    9674,
    "spades",
    9824,
    "clubs",
    9827,
    "hearts",
    9829,
    "diams",
    9830
  };

  /** The names of {@link #LATIN1} and {@link #OTHERS}, sorted. */
  private static final String[] NAMES = sortedNames();

  /** The code point of each of {@link #NAMES}. */
  private static final int[] POINTS = points();

  private XmlEntities() {}

  /**
   * Returns the code point a name stands for.
   *
   * @param name the name between {@code &} and {@code ;}
   * @return the code point, or -1 for an unknown name
   */
  static int named(String name) {
    return named(name, 0, name.length());
  }

  /** Returns the number of names known: the HTML 4.01 entities and {@code apos}. */
  static int nameCount() {
    return NAMES.length;
  }

  /**
   * Replaces each known reference with its char.
   *
   * @param text the text
   * @return the unescaped text, never longer than {@code text}
   */
  public static String unescape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    XmlReferences.unescape(text, 0, text.length(), out);
    return out.toString();
  }

  /** Binary search of {@link #NAMES} for the chars {@code [from, end)}; -1 when none. */
  static int named(CharSequence text, int from, int end) {
    int low = 0;
    int high = NAMES.length - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      int order = compare(NAMES[mid], text, from, end);
      if (order == 0) {
        return POINTS[mid];
      }
      if (order < 0) {
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return -1;
  }

  /** Compares a name with the chars {@code [from, end)} in {@link String#compareTo} order. */
  private static int compare(String name, CharSequence text, int from, int end) {
    int length = end - from;
    int common = Math.min(name.length(), length);
    for (int i = 0; i < common; i++) {
      int diff = name.charAt(i) - text.charAt(from + i);
      if (diff != 0) {
        return diff;
      }
    }
    return name.length() - length;
  }

  private static String[] sortedNames() {
    String[] names = new String[LATIN1.length + OTHERS.length / 2];
    for (int i = 0; i < LATIN1.length; i++) {
      names[i] = LATIN1[i];
    }
    for (int i = 0; i < OTHERS.length; i += 2) {
      names[LATIN1.length + i / 2] = (String) OTHERS[i];
    }
    Arrays.sort(names);
    return names;
  }

  private static int[] points() {
    int[] points = new int[NAMES.length];
    for (int n = 0; n < NAMES.length; n++) {
      points[n] = -1;
      for (int i = 0; i < LATIN1.length && points[n] < 0; i++) {
        if (LATIN1[i].equals(NAMES[n])) {
          points[n] = 0xa0 + i;
        }
      }
      for (int i = 0; i < OTHERS.length && points[n] < 0; i += 2) {
        if (OTHERS[i].equals(NAMES[n])) {
          points[n] = (Integer) OTHERS[i + 1];
        }
      }
    }
    return points;
  }
}
