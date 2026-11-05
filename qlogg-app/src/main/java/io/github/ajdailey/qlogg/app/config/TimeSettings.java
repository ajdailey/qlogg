package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.time.ZoneId;
import java.util.List;

/**
 * Timestamp settings in {@code qlogg.conf}. Immutable.
 *
 * @param zone {@code time.zone}: the zone of local times, or null for the system zone
 * @param displayZone {@code time.displayZone}: the zone times show in, or null for the system zone
 * @param formats {@code time.format.N.*}, at most {@link UserFormat#MAX_FORMATS}, names distinct
 */
public record TimeSettings(ZoneId zone, ZoneId displayZone, List<UserFormat> formats) {
  /** System zones and no user formats. */
  public static final TimeSettings DEFAULTS = new TimeSettings(null, null, List.of());

  /** The id {@link PositionStore} records for a file the user set to no format. */
  public static final String NO_FORMAT = "none";

  /** Checks and copies the formats. */
  public TimeSettings {
    if (formats == null || formats.size() > UserFormat.MAX_FORMATS) {
      throw new IllegalArgumentException("formats missing or over the limit");
    }
    for (int i = 0; i < formats.size(); i++) {
      for (int j = 0; j < i; j++) {
        if (formats.get(i).name().equals(formats.get(j).name())) {
          throw new IllegalArgumentException("duplicate format name");
        }
      }
    }
    formats = List.copyOf(formats);
  }

  /**
   * Returns the zone of local times.
   *
   * @return {@link #zone}, else the system zone
   */
  public ZoneId zoneOrSystem() {
    return zone == null ? ZoneId.systemDefault() : zone;
  }

  /**
   * Returns the zone times show in.
   *
   * @return {@link #displayZone}, else the system zone
   */
  public ZoneId displayZoneOrSystem() {
    return displayZone == null ? ZoneId.systemDefault() : displayZone;
  }

  /**
   * Returns a copy with other user formats.
   *
   * @param list the formats
   * @return the copy
   */
  public TimeSettings withFormats(List<UserFormat> list) {
    return new TimeSettings(zone, displayZone, list);
  }

  /**
   * Finds a format by its {@link TimestampFormat#id()}.
   *
   * @param id a built-in id or {@code user:} plus a name
   * @return the format, or null for an unknown id
   */
  public TimestampFormat format(String id) {
    if (id.startsWith(UserFormat.ID_PREFIX)) {
      String name = id.substring(UserFormat.ID_PREFIX.length());
      for (UserFormat format : formats) {
        if (format.name().equals(name)) {
          return format;
        }
      }
      return null;
    }
    return BuiltInFormat.byId(id);
  }
}
