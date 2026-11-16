package io.github.ajdailey.qlogg.launcher;

import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;

/**
 * A child process for {@code LoadedToolkitTest}: configures logging as {@link Main} does, then
 * prints {@link #TRAITS} with the simple name of the traits class chosen before any Display exists,
 * and {@link #TOOLKIT} with the toolkit those traits report.
 */
public final class LoadedToolkitMain {
  static final String TRAITS = "traits ";
  static final String TOOLKIT = "toolkit ";

  private LoadedToolkitMain() {}

  /**
   * Runs once.
   *
   * @param args none
   */
  public static void main(String[] args) {
    LogConfigurator.install();
    PlatformTraits traits = PlatformTraits.PLATFORM;
    System.out.println(TRAITS + traits.getClass().getSimpleName());
    System.out.println(TOOLKIT + traits.toolkitVersion());
  }
}
