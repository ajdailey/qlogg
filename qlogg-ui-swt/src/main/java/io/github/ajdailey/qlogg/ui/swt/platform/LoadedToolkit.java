package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the running platform's traits from the toolkit SWT loaded. {@code SWT_GTK4=1} asks SWT
 * for GTK 4, but SWT loads GTK 3 when GTK 4 does not load, and GTK 4 when GTK 3 does not, so the
 * GTK major version comes from {@link PlatformTraits#GTK_VERSION}, not from the environment.
 */
final class LoadedToolkit {
  /** The environment variable that asks SWT for GTK 4 with {@code 1}. */
  static final String SWT_GTK4 = "SWT_GTK4";

  private static final Logger LOG = LoggerFactory.getLogger(LoadedToolkit.class);

  private LoadedToolkit() {}

  /**
   * Returns the running platform's traits. On GTK this loads the toolkit first: the {@link Display}
   * class initializer loads SWT's GTK library, which sets {@link PlatformTraits#GTK_VERSION}.
   *
   * @return the traits
   */
  static PlatformTraits traits() {
    String platform = SWT.getPlatform();
    String version = null;
    if ("gtk".equals(platform)) {
      try {
        Display.getCurrent();
      } catch (LinkageError | SWTError e) {
        LOG.debug("SWT's GTK library failed to load", e);
      }
      version = System.getProperty(PlatformTraits.GTK_VERSION);
    }
    return choose(platform, version, "1".equals(System.getenv(SWT_GTK4)));
  }

  /**
   * Returns a platform's traits for the GTK version SWT loaded, logging one WARN when GTK 4 was
   * requested and another version loaded.
   *
   * @param platform an {@link SWT#getPlatform} value
   * @param gtkVersion the {@link PlatformTraits#GTK_VERSION} value, or null off GTK
   * @param gtk4Requested whether {@code SWT_GTK4=1} is set
   * @return the traits
   * @throws IllegalArgumentException for a platform SWT does not build
   */
  static PlatformTraits choose(String platform, String gtkVersion, boolean gtk4Requested) {
    boolean gtk = "gtk".equals(platform);
    boolean gtk4 = gtk && isGtk4(gtkVersion);
    if (gtk && gtk4Requested && !gtk4) {
      LOG.warn("SWT_GTK4=1 is set but GTK 4 did not load; SWT runs on GTK 3");
    }
    return PlatformTraits.of(platform, gtk4);
  }

  /**
   * Reports whether a GTK version is GTK 4.
   *
   * @param gtkVersion a {@link PlatformTraits#GTK_VERSION} value such as {@code 4.14.5}, or null
   * @return true for major version 4
   */
  static boolean isGtk4(String gtkVersion) {
    return gtkVersion != null && gtkVersion.startsWith("4.");
  }
}
