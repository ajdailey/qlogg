package io.github.ajdailey.qlogg.dist;

import java.nio.charset.Charset;

/**
 * Run by {@link SmokeTest} on a bundle runtime. Prints {@code module jdk.charsets} when the boot
 * layer has that module, {@code unsupported <name>} for each argument the runtime does not support,
 * then {@code done}.
 */
public final class CharsetProbe {
  private CharsetProbe() {}

  public static void main(String[] args) {
    if (ModuleLayer.boot().findModule("jdk.charsets").isPresent()) {
      System.out.println("module jdk.charsets");
    }
    for (String name : args) {
      if (!Charset.isSupported(name)) {
        System.out.println("unsupported " + name);
      }
    }
    System.out.println("done");
  }
}
