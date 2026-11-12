package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.Options;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The charsets the Encoding menu lists: the common ones first, then every other charset the runtime
 * supports ({@link Options#supported}: a decode-only charset is left out). The runtime's charset
 * set is fixed, so {@link #available()} computes the lists once. Listing every charset loads each
 * provider (130 to 230 ms cold, measured), so {@link #available()} runs on a worker; {@link
 * #commonCharsets()} looks up the common names only.
 *
 * @param common the supported entries of {@link #COMMON_NAMES}, in that order
 * @param other every other supported charset, in {@link Charset#availableCharsets()} order
 */
public record EncodingChoices(List<Charset> common, List<Charset> other) {
  /** Charsets listed first, when the runtime supports them. */
  public static final List<String> COMMON_NAMES =
      List.of(
          "UTF-8",
          "UTF-16LE",
          "UTF-16BE",
          "ISO-8859-1",
          "ISO-8859-15",
          "windows-1252",
          "US-ASCII",
          "windows-1251",
          "KOI8-R",
          "GB18030",
          "Big5",
          "Shift_JIS",
          "EUC-JP",
          "EUC-KR");

  /**
   * Copies the lists.
   *
   * @param common the common charsets
   * @param other the other charsets
   */
  public EncodingChoices {
    common = List.copyOf(common);
    other = List.copyOf(other);
  }

  /** Receives the choices on the UI thread. */
  public interface Listener {
    /**
     * Called once the choices are computed.
     *
     * @param choices the runtime's choices
     */
    void loaded(EncodingChoices choices);
  }

  /** The runtime's choices once a call to {@link #available()} has computed them. */
  private static volatile EncodingChoices loaded;

  /**
   * Returns the runtime's choices, computing them on the first call. Call on a worker.
   *
   * @return the choices
   */
  @WorkerThread
  public static EncodingChoices available() {
    EncodingChoices choices = loaded;
    if (choices == null) {
      choices = of(new ArrayList<Charset>(Charset.availableCharsets().values()));
      loaded = choices;
    }
    return choices;
  }

  /**
   * Returns the runtime's choices if computed.
   *
   * @return the choices, or null before {@link #available()} returned once
   */
  public static EncodingChoices loaded() {
    return loaded;
  }

  /**
   * Returns the supported entries of {@link #COMMON_NAMES}, looked up by name.
   *
   * @return the common charsets, in that order
   */
  public static List<Charset> commonCharsets() {
    List<Charset> common = new ArrayList<Charset>();
    for (String name : COMMON_NAMES) {
      Charset charset = Options.charset(name);
      if (charset != null) {
        common.add(charset);
      }
    }
    return common;
  }

  /**
   * Splits charsets into the common ones and the rest.
   *
   * @param all the charsets to list; those not {@link Options#supported} are left out
   * @return the choices
   */
  static EncodingChoices of(List<Charset> all) {
    List<Charset> common = new ArrayList<Charset>();
    for (String name : COMMON_NAMES) {
      Charset charset = Options.charset(name);
      if (charset != null && all.contains(charset)) {
        common.add(charset);
      }
    }
    List<Charset> other = new ArrayList<Charset>();
    for (Charset charset : all) {
      if (!common.contains(charset) && Options.supported(charset)) {
        other.add(charset);
      }
    }
    return new EncodingChoices(common, other);
  }

  /**
   * Returns the charset name the Encoding menu checks for a document: the forced one of a document
   * with a file, else empty, which checks Auto.
   *
   * @param document the shown document, or null
   * @return the forced charset's name, or empty
   */
  public static String forced(DocumentController document) {
    if (document == null || !document.state().hasFile() || !document.state().encodingForced()) {
      return "";
    }
    return document.state().encodingName();
  }
}
