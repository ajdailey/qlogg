package io.github.ajdailey.qlogg.launcher;

import io.github.ajdailey.qlogg.app.about.UserGuide;
import io.github.ajdailey.qlogg.app.cli.SessionMode;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.ui.swt.MainWindow;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * A child process for {@code PortableGuideTest}: a run over the settings directory given, in
 * portable ({@code portable}) or {@code --settings} mode ({@code settings}), which opens Help >
 * Documentation unless the third argument is {@code false}, then quits. Prints {@link #GUIDE} and
 * whether a guide window opened, then {@link #EXIT} and the status.
 */
public final class PortableGuideMain {
  static final String GUIDE = "guide ";
  static final String EXIT = "exit ";

  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(30);

  /** How long the run stays open after the guide's copy appears, for any writer to start. */
  private static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(3);

  private PortableGuideMain() {}

  /**
   * Runs once.
   *
   * @param args the settings directory, {@code portable} or {@code settings}, and whether to open
   *     the guide
   */
  public static void main(String[] args) {
    Path dir = Path.of(args[0]);
    SettingsMode mode = "portable".equals(args[1]) ? SettingsMode.PORTABLE : SettingsMode.EXPLICIT;
    final boolean open = args.length < 3 || Boolean.parseBoolean(args[2]);
    SettingsLocation location = new SettingsLocation(mode, dir);
    PlatformSetup.apply("Linux", location);
    ConfigStore config = new ConfigStore(location);
    StartupRequest request =
        new StartupRequest(
            List.of(), false, SessionMode.DEFAULT, 800, 500, null, null, null, false);
    final Path copy = dir.resolve(UserGuide.FOLDER).resolve(UserGuide.FILE);
    final boolean[] shown = new boolean[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              MainWindow window, final ApplicationController controller, Workers workers) {
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            final long[] settled = {0};
            if (open) {
              controller.execute(CommandId.DOCUMENTATION);
            }
            display.timerExec(
                50,
                new Runnable() {
                  @Override
                  public void run() {
                    shown[0] |= guideShell(display);
                    long now = System.nanoTime();
                    if (settled[0] == 0 && (!open || Files.isRegularFile(copy))) {
                      settled[0] = now + SETTLE_NANOS;
                    }
                    if ((settled[0] != 0 && now > settled[0]) || now > deadline) {
                      controller.shutdown();
                    } else {
                      display.timerExec(50, this);
                    }
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    int status = new Gui(request, config, config.load(), err, observer).run();
    System.out.println(GUIDE + shown[0]);
    System.out.println(EXIT + status);
  }

  private static boolean guideShell(Display display) {
    for (Shell shell : display.getShells()) {
      if (UiText.GUIDE_TITLE.text().equals(shell.getText())) {
        return true;
      }
    }
    return false;
  }
}
