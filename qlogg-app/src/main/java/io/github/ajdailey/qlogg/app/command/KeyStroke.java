package io.github.ajdailey.qlogg.app.command;

/**
 * One key press, free of any UI toolkit. A printable key is the char it types: a letter in lower
 * case with {@link #SHIFT} when shifted, and any other printable char ({@code $}, {@code ^}) as
 * itself without {@link #SHIFT}. Other keys, the space bar included, use the constants below.
 *
 * @param modifiers a combination of {@link #PRIMARY}, {@link #SHIFT}, {@link #ALT} and {@link
 *     #CONTROL}
 * @param key a printable char or one of the key constants
 */
public record KeyStroke(int modifiers, int key) {
  /** Ctrl, or Command on macOS. */
  public static final int PRIMARY = 1;

  /** Shift. */
  public static final int SHIFT = 2;

  /** Alt, or Option on macOS. */
  public static final int ALT = 4;

  /** Control on macOS; elsewhere Ctrl is {@link #PRIMARY} and {@link #forPlatform} folds it in. */
  public static final int CONTROL = 8;

  private static final int SPECIAL = 0x110000;

  /** F1; F2 to F12 follow it. */
  public static final int F1 = SPECIAL + 1;

  /** F3. */
  public static final int F3 = F1 + 2;

  /** F4. */
  public static final int F4 = F1 + 3;

  /** F5. */
  public static final int F5 = F1 + 4;

  /** F10. */
  public static final int F10 = F1 + 9;

  /** Up arrow. */
  public static final int UP = SPECIAL + 0x20;

  /** Down arrow. */
  public static final int DOWN = UP + 1;

  /** Left arrow. */
  public static final int LEFT = UP + 2;

  /** Right arrow. */
  public static final int RIGHT = UP + 3;

  /** Page Up. */
  public static final int PAGE_UP = UP + 4;

  /** Page Down. */
  public static final int PAGE_DOWN = UP + 5;

  /** Home. */
  public static final int HOME = UP + 6;

  /** End. */
  public static final int END = UP + 7;

  /** Escape. */
  public static final int ESCAPE = UP + 8;

  /** Enter or Return. */
  public static final int ENTER = UP + 9;

  /** Backspace. */
  public static final int BACKSPACE = UP + 10;

  /** Tab. */
  public static final int TAB = UP + 11;

  /** Delete. */
  public static final int DELETE = UP + 12;

  /** Insert. */
  public static final int INSERT = UP + 13;

  /** Space bar. */
  public static final int SPACE = UP + 14;

  /** Keypad plus. */
  public static final int KEYPAD_ADD = UP + 15;

  /** Keypad minus. */
  public static final int KEYPAD_SUBTRACT = UP + 16;

  private static final int LAST = KEYPAD_SUBTRACT;

  private static final String[] SPECIAL_NAMES = {
    "Up",
    "Down",
    "Left",
    "Right",
    "PgUp",
    "PgDn",
    "Home",
    "End",
    "Esc",
    "Enter",
    "Backspace",
    "Tab",
    "Delete",
    "Insert",
    "Space",
    "Num+",
    "Num-"
  };

  /** Checks the components. */
  public KeyStroke {
    if ((modifiers & ~(PRIMARY | SHIFT | ALT | CONTROL)) != 0) {
      throw new IllegalArgumentException("unknown modifier");
    }
    boolean printable = key > ' ' && key < SPECIAL && !Character.isUpperCase(key);
    boolean function = key >= F1 && key < F1 + 12;
    if (!printable && !function && (key < UP || key > LAST)) {
      throw new IllegalArgumentException("not a key: " + key);
    }
  }

  /**
   * Creates a stroke for a printable char.
   *
   * @param modifiers the modifiers
   * @param c the char; an upper-case letter means the lower-case letter with {@link #SHIFT}
   * @return the stroke
   */
  public static KeyStroke of(int modifiers, char c) {
    if (Character.isUpperCase(c)) {
      return new KeyStroke(modifiers | SHIFT, Character.toLowerCase(c));
    }
    return new KeyStroke(modifiers, c);
  }

  /**
   * Returns the stroke as a platform's keyboard produces it: outside macOS, {@link #CONTROL}
   * becomes {@link #PRIMARY}.
   *
   * @param mac true for macOS
   * @return this stroke, or the folded one
   */
  public KeyStroke forPlatform(boolean mac) {
    if (mac || (modifiers & CONTROL) == 0) {
      return this;
    }
    return new KeyStroke((modifiers & ~CONTROL) | PRIMARY, key);
  }

  /**
   * Reports whether the key is a printable char.
   *
   * @return false for the key constants
   */
  public boolean printable() {
    return key < SPECIAL;
  }

  /**
   * Returns the stroke as the platform writes it, for example {@code Ctrl+Shift+G}, {@code Cmd+O},
   * {@code Ctrl+Cmd+Left}, {@code $} or {@code F1}. With {@code mac} false, {@link #CONTROL} is
   * {@code Control+}, which {@link #forPlatform} leaves only in a macOS binding stored in {@code
   * qlogg.conf}.
   *
   * @param mac true for macOS names
   * @return the text
   */
  public String text(boolean mac) {
    StringBuilder text = new StringBuilder();
    if ((modifiers & CONTROL) != 0) {
      text.append(mac ? "Ctrl+" : "Control+");
    }
    if ((modifiers & PRIMARY) != 0) {
      text.append(mac ? "Cmd+" : "Ctrl+");
    }
    if ((modifiers & ALT) != 0) {
      text.append(mac ? "Option+" : "Alt+");
    }
    if ((modifiers & SHIFT) != 0) {
      text.append("Shift+");
    }
    if (key >= F1 && key < F1 + 12) {
      text.append('F').append(key - F1 + 1);
    } else if (key >= UP) {
      text.append(SPECIAL_NAMES[key - UP]);
    } else if (Character.isLetter(key)) {
      text.appendCodePoint(Character.toUpperCase(key));
    } else {
      text.appendCodePoint(key);
    }
    return text.toString();
  }

  /**
   * Parses a stroke written as {@link #text} writes it with {@code mac} false, for example {@code
   * Ctrl+Shift+G}, {@code F5}, {@code PgDn} or {@code $}.
   *
   * @param text the stroke
   * @return the stroke
   * @throws IllegalArgumentException when the text names no stroke
   */
  public static KeyStroke parse(String text) {
    int modifiers = 0;
    String rest = text;
    while (true) {
      if (rest.startsWith("Control+") && rest.length() > 8) {
        modifiers |= CONTROL;
        rest = rest.substring(8);
      } else if (rest.startsWith("Ctrl+") && rest.length() > 5) {
        modifiers |= PRIMARY;
        rest = rest.substring(5);
      } else if (rest.startsWith("Alt+") && rest.length() > 4) {
        modifiers |= ALT;
        rest = rest.substring(4);
      } else if (rest.startsWith("Shift+") && rest.length() > 6) {
        modifiers |= SHIFT;
        rest = rest.substring(6);
      } else {
        break;
      }
    }
    for (int i = 0; i < SPECIAL_NAMES.length; i++) {
      if (SPECIAL_NAMES[i].equals(rest)) {
        return new KeyStroke(modifiers, UP + i);
      }
    }
    if (rest.length() > 1 && rest.charAt(0) == 'F') {
      try {
        int number = Integer.parseInt(rest.substring(1));
        if (number >= 1 && number <= 12) {
          return new KeyStroke(modifiers, F1 + number - 1);
        }
      } catch (NumberFormatException e) {
        // Not a function key; reported below.
      }
    }
    if (rest.codePointCount(0, rest.length()) != 1) {
      throw new IllegalArgumentException("not a key stroke: " + text);
    }
    int key = rest.codePointAt(0);
    if (Character.isLetter(key)) {
      key = Character.toLowerCase(key);
    }
    return new KeyStroke(modifiers, key);
  }
}
