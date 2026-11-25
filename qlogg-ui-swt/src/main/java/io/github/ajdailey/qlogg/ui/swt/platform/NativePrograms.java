package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.program.Program;

/** The system's program registry. Only this package calls {@link Program#findProgram}. */
public final class NativePrograms {
  private NativePrograms() {}

  /**
   * Returns the program the system associates with {@code .txt} files.
   *
   * @return the program, or null when {@code .txt} has no association
   */
  public static Program text() {
    return Program.findProgram("txt");
  }
}
