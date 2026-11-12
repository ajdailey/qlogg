package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.io.AtomicFiles;
import io.github.ajdailey.qlogg.core.io.PropertiesFiles;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One properties file in the settings directory with {@code version=1}, read and written as {@link
 * ConfigStore} treats {@code qlogg.conf}: a file that cannot be read, is over its size cap, does
 * not parse or names another version is malformed, logged once and copied to {@code <name>.invalid}
 * before the first save replaces it; writes are atomic and owner-only; a save over the size cap is
 * refused with one WARN and leaves the file as it was; a read-only directory disables saving with
 * one WARN. Blocking; a lock serializes the I/O, so only workers call it.
 */
final class SettingsFile {
  private static final Logger LOG = LoggerFactory.getLogger(SettingsFile.class);

  private final SettingsLocation location;
  private final String name;
  private final int maxBytes;
  private final ReentrantLock lock = new ReentrantLock();
  private boolean keepInvalid;
  private boolean saveDisabled;

  SettingsFile(SettingsLocation location, String name, int maxBytes) {
    this.location = location;
    this.name = name;
    this.maxBytes = maxBytes;
  }

  /** Returns the file's properties; empty when it is missing or malformed. */
  Properties load() {
    lock.lock();
    try {
      Path file = SafePaths.child(location.directory(), name);
      Properties properties;
      try {
        properties = PropertiesFiles.read(file, maxBytes);
      } catch (NoSuchFileException e) {
        return new Properties();
      } catch (IllegalArgumentException e) {
        return malformed(file);
      }
      String version = properties.getProperty("version");
      if (version != null && !ConfigStore.VERSION.equals(version.strip())) {
        return malformed(file);
      }
      return properties;
    } catch (IOException e) {
      LOG.warn(
          "Cannot read {} in {}: {}",
          LogText.oneLine(name),
          LogText.oneLine(location.directory()),
          LogText.oneLine(e.getMessage()));
      keepInvalid = true;
      return new Properties();
    } finally {
      lock.unlock();
    }
  }

  private Properties malformed(Path file) {
    keepInvalid = true;
    LOG.warn(
        "Malformed file {}; it is ignored and kept as {} before the next save",
        LogText.oneLine(file),
        LogText.oneLine(name + ConfigStore.INVALID_SUFFIX));
    return new Properties();
  }

  /**
   * Writes the properties with {@code version=1}; false when saving is disabled, the content is
   * over the size cap, or the write failed.
   */
  boolean save(Properties properties) {
    lock.lock();
    try {
      if (saveDisabled) {
        return false;
      }
      Path dir = location.directory();
      if (location.mode() == SettingsMode.PER_USER && !Files.isDirectory(dir)) {
        SafePaths.settingsDirectory(dir);
      }
      properties.setProperty("version", ConfigStore.VERSION);
      byte[] content = format(properties);
      if (content.length > maxBytes) {
        LOG.warn(
            "{} not saved: {} bytes is over the {} bytes a load reads; the last save stays",
            LogText.oneLine(name),
            content.length,
            maxBytes);
        return false;
      }
      Path file = SafePaths.child(dir, name);
      if (keepInvalid && Files.exists(file)) {
        Files.copy(
            file,
            SafePaths.child(dir, name + ConfigStore.INVALID_SUFFIX),
            StandardCopyOption.REPLACE_EXISTING);
      }
      keepInvalid = false;
      AtomicFiles.write(file, content);
      return true;
    } catch (IOException e) {
      // A refused move says nothing about the directory: on Windows a file open elsewhere is
      // refused too, and the next save can succeed.
      if (Files.isDirectory(location.directory()) && !Files.isWritable(location.directory())) {
        disable();
      } else {
        LOG.warn(
            "Cannot save {} in {}: {}",
            LogText.oneLine(name),
            LogText.oneLine(location.directory()),
            LogText.oneLine(e.getMessage()));
      }
      return false;
    } finally {
      lock.unlock();
    }
  }

  private void disable() {
    if (!saveDisabled) {
      saveDisabled = true;
      LOG.warn(
          "Settings directory {} is read-only; {} is not saved",
          LogText.oneLine(location.directory()),
          LogText.oneLine(name));
    }
  }

  /** Returns the content: sorted keys, ISO-8859-1 with escapes, no date comment. */
  private static byte[] format(Properties properties) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    properties.store(out, null);
    String text = out.toString(StandardCharsets.ISO_8859_1);
    StringBuilder kept = new StringBuilder();
    for (String line : text.split("\\R")) {
      if (!line.startsWith("#")) {
        kept.append(line).append('\n');
      }
    }
    return kept.toString().getBytes(StandardCharsets.ISO_8859_1);
  }
}
