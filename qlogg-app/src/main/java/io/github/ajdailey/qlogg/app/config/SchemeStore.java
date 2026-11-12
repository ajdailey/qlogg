package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.app.highlight.HighlightFile;
import io.github.ajdailey.qlogg.app.theme.ColorScheme;
import io.github.ajdailey.qlogg.app.theme.ColorSchemes;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.io.PropertiesFiles;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The user's color schemes, read from {@code <id>.properties} files in the {@link #FOLDER} folder
 * of the settings directory. A file holds {@code dark} ({@code true} or {@code false}), an optional
 * {@code name}, and every {@link Palette#COLOR_NAMES} key as {@code #rrggbb}; other keys are
 * ignored. A file that is not a regular file, is over {@link #MAX_BYTES}, does not parse, lacks a
 * value or has a {@link ColorSchemes#reserved} or malformed id is skipped with one WARN naming it.
 * At most {@link #MAX_FILES} files are read, in name order. Values are never logged. Thread-safe:
 * {@link #load} blocks and runs before the Display or on a worker; {@link #schemes} touches memory
 * only.
 */
public final class SchemeStore {
  /** The folder in the settings directory. */
  public static final String FOLDER = "colors";

  /** The scheme file suffix. */
  public static final String SUFFIX = ".properties";

  /** Most scheme files read. */
  public static final int MAX_FILES = 64;

  /** Largest scheme file read. */
  public static final int MAX_BYTES = 1 << 16;

  /** Longest scheme name kept. */
  public static final int MAX_NAME_CHARS = 64;

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Logger LOG = LoggerFactory.getLogger(SchemeStore.class);

  private final SettingsLocation location;
  private volatile ColorSchemes schemes = ColorSchemes.NONE;

  /**
   * Creates the store; it holds the bundled schemes alone until {@link #load}.
   *
   * @param location the settings directory
   */
  public SchemeStore(SettingsLocation location) {
    this.location = location;
  }

  /**
   * Reports whether a {@code view.colors} value can name a scheme.
   *
   * @param id the value
   * @return true for 1 to 64 letters, digits, {@code _} and {@code -}
   */
  public static boolean validId(String id) {
    return ID.matcher(id).matches();
  }

  /**
   * Returns the bundled schemes and those read by {@link #load}.
   *
   * @return the schemes
   */
  public ColorSchemes schemes() {
    return schemes;
  }

  /** Reads the scheme files, replacing the user schemes held. Blocking. */
  public void load() {
    List<ColorScheme> read = new ArrayList<ColorScheme>();
    Path dir;
    try {
      dir = SafePaths.child(location.directory(), FOLDER);
    } catch (IOException e) {
      return;
    }
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      schemes = ColorSchemes.NONE;
      return;
    }
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
      for (Path entry : entries) {
        Path name = entry.getFileName();
        if (name != null) {
          names.add(name.toString());
        }
      }
    } catch (IOException e) {
      LOG.warn(
          "Cannot list color schemes in {}: {}",
          LogText.oneLine(dir),
          LogText.oneLine(e.getMessage()));
    }
    Collections.sort(names);
    if (names.size() > MAX_FILES) {
      LOG.warn(
          "Reading the first {} of {} color schemes in {}",
          MAX_FILES,
          names.size(),
          LogText.oneLine(dir));
      names = names.subList(0, MAX_FILES);
    }
    for (String name : names) {
      ColorScheme scheme = read(dir, name);
      if (scheme != null) {
        read.add(scheme);
      }
    }
    schemes = new ColorSchemes(read);
  }

  /** Returns the scheme in a file, or null with one WARN. */
  private static ColorScheme read(Path dir, String fileName) {
    String id = fileName.substring(0, fileName.length() - SUFFIX.length());
    if (!validId(id) || ColorSchemes.reserved(id)) {
      skip(fileName, "its name is not a free scheme id");
      return null;
    }
    try {
      Path file = SafePaths.child(dir, fileName);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        skip(fileName, "not a regular file");
        return null;
      }
      Properties properties = PropertiesFiles.read(file, MAX_BYTES);
      String problem = problem(properties);
      if (problem != null) {
        skip(fileName, problem);
        return null;
      }
      return parse(id, properties);
    } catch (IOException | IllegalArgumentException e) {
      skip(fileName, e.getMessage());
      return null;
    }
  }

  /**
   * Returns why properties do not make a scheme.
   *
   * @param properties a scheme file's properties
   * @return the missing or invalid key, or null when {@link #parse} accepts them
   */
  static String problem(Properties properties) {
    String dark = properties.getProperty("dark");
    if (dark == null || !(dark.strip().equals("true") || dark.strip().equals("false"))) {
      return "dark is missing or invalid";
    }
    for (String key : Palette.COLOR_NAMES) {
      String value = properties.getProperty(key);
      if (value == null || HighlightFile.parseColor(value) == null) {
        return key + " is missing or invalid";
      }
    }
    return null;
  }

  /**
   * Builds a scheme from properties {@link #problem} accepts.
   *
   * @param id the scheme id
   * @param properties the file's properties
   * @return the scheme; its name is {@code name} cut to {@link #MAX_NAME_CHARS}, or the id when
   *     {@code name} is blank or holds a control character
   */
  static ColorScheme parse(String id, Properties properties) {
    Rgb[] colors = new Rgb[Palette.COLOR_NAMES.size()];
    for (int i = 0; i < colors.length; i++) {
      colors[i] = HighlightFile.parseColor(properties.getProperty(Palette.COLOR_NAMES.get(i)));
    }
    boolean dark = properties.getProperty("dark").strip().equals("true");
    String name = properties.getProperty("name", "").strip();
    if (name.length() > MAX_NAME_CHARS) {
      name = name.substring(0, MAX_NAME_CHARS).strip();
    }
    if (name.isEmpty() || !LogText.oneLine(name).equals(name)) {
      name = id;
    }
    return new ColorScheme(id, name, Palette.of(dark, colors));
  }

  private static void skip(String fileName, String reason) {
    LOG.warn(
        "Skipping the color scheme {}: {}", LogText.oneLine(fileName), LogText.oneLine(reason));
  }
}
