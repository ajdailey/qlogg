# qlogg-launcher

Composes the application: parses the command line, configures logging and the
platform, then runs the SWT event loop. It is the only module that configures
java.util.logging (through `slf4j-jdk14`), and its test class path sees every
module except the bootstrap, so it holds the architecture rules.

---

## Map

| Class | Role |
|---|---|
| `Main` | Entry point; installs logging, calls `Launcher.run`, exits on a nonzero status |
| `Launcher` | Settings directory, platform setup, single-instance handoff, debug log file |
| `PlatformSetup` | `user.home` and the WebView2 data folder |
| `Gui` | Composition and the event loop on the UI thread |
| `ShutdownHook` | Quit on SIGTERM, logoff or a parent ending the process |
| `UiFailures` | The Display's runtime-exception and error handlers |
| `LogConfigurator`, `StderrHandler`, `RecentHandler`, `LogFileHandler`, `LineFormatter`, `RetainingLogManager` | Logging |
| `FileLog` | The debug log file (`LogFiles`) and pruning |

## Exit status

`Launcher.run` returns the exit status:

| Status | Cause |
|---|---|
| 0 | Help, version, the files went to the running instance, or the last window closed |
| 1 | Bad command line, or a `--settings` path that cannot be a directory |
| 3 | SWT failed to load (`UnsatisfiedLinkError` or `SWTError` from `new Display()`), typically missing GTK 3 libraries |
| 4 | UI-thread failures ran the quit (`UiFailures`) |

`Main` calls `System.exit` only for a nonzero status. After a normal close
`main` returns and the JVM exits on its own.

## Before the Display

`Launcher` resolves the settings directory (`SettingsLocation.fromSystem`) and
loads `qlogg.conf`, `positions.conf`, and `qlogg_session.conf` when the session
is restored: with `-s`, or with neither `-s` nor `-n`, no file given and
`session.loadLast` on.

`PlatformSetup` then sets, from `OsTraits.current()`:

| Property | When | Value | Why |
|---|---|---|---|
| `user.home` | Explicit and portable mode, Linux | The settings directory | GTK SWT writes `.swt/trims.prefs` there |
| `org.eclipse.swt.browser.EdgeDataDir` | Windows, every mode | `<settings>/cache/webview2` | The user guide's WebView2 browser keeps its data in the settings directory |

### Single-instance handoff

Without `-m`, `Launcher` runs the handoff (`app.ipc`). It creates the settings
directory owner-only if missing. `InstanceServer.claim` either makes this
launch the primary, or `InstanceClient.handOff` sends the files, `-f` and
`+line:N` to the primary and `run` returns 0. The primary may still be
starting; the client then waits for its first window, up to 35 s.

A failure at any step, or a settings directory another user owns or others can
write (`InstanceClient.refusal`), logs one WARN, and the launch opens its own
window as with `-m`. A launch that stays opens the debug log file when `-l` or
`log.file` asks for it.

## Gui

`Gui` runs on the calling thread, which becomes the UI thread. In order, it:

1. Creates `Workers` and starts the primary's `InstanceServer` on them, so
   handoffs are accepted before the Display exists.
2. Resolves `ui.theme` before the first Shell. For light and dark,
   `SwtThemeService.open` also sets the native widgets' theme (system leaves
   them to the OS); on Windows a theme change reaches them at the next start,
   and on macOS they always follow the OS.
3. Loads the default time zone's rules on a `qlogg-lifecycle-` worker, so the
   first open does not read the JDK time-zone data on the UI thread.
4. Creates the `MemoryBudget` from `Runtime.maxMemory()` and the document
   services: an `Indexer` with default settings, a `BlockCache` of 64 KiB
   blocks holding the `BLOCK_CACHE` share up to 64 MiB, and
   `encoding.default` as the fallback charset, and the primary server's held
   lock (null without one).
5. Creates the dispatcher and the `WindowSet` with `LogConfigurator.RECENT` and
   the `FileLog`, and connects the macOS application menu (`AppMenu.hook`, a
   no-op elsewhere).
6. Creates the first window with the default `Keymap`, the user's overrides and
   its `ApplicationController`. It places the window (the `--window-*`
   options, else the remembered bounds), maximizes it when it was maximized and
   no `--window-*` option is given, opens it, and logs `Main window open:
   client area <w>x<h>, location <x>,<y>` at INFO.
7. Restores the session: the first window takes `window.0`'s tabs and each
   further session window opens a window with its own. Then the command-line
   files open in the first window on workers, a tab each, following with `-f`.
   When `qlogg_error.flag` exists, the session's tabs and windows are skipped
   (see [UI-thread failures](#ui-thread-failures)).
8. Runs the event loop (`Gui.loop`) until every window is disposed, or until
   a quit `UiFailures` ran throws, then disposes the Display and returns.

The instance saves the tab list only when it restored the session without
`-m`. New Window goes through the set's `Factory` in `Gui`: a window at the
remembered size, placed by the window system. A window's close button closes
that window (`closeWindow`); the last one quits.

### Handed files

A primary's `InstanceServer` holds handed files until the first window is open
and the command-line files are on their way; then `deliverTo` releases them in
arrival order. Its receiver answers `ERR` once quit has begun and otherwise
posts `WindowSet.openHandoff`.

Quit closes the server first on its lifecycle worker. `Gui` closes it on exit
before `Workers.close`, including an exit before the first window (SWT failed
to load), so held launches get `ERR` and open their own windows.

### Shutdown hook

`Gui` registers a JVM shutdown hook (`ShutdownHook`, thread `qlogg-shutdown`
from `Workers.shutdownHook`) and removes it when the event loop ends. On
SIGTERM, logoff or a parent ending the process, the hook posts `WindowSet.quit`
through the dispatcher and waits up to 3 s for the quit's finish listener. On
timeout it logs the surviving `qlogg-` threads at WARN. It then closes the
handoff listener, which deletes the endpoint file.

### UI-thread failures

`Gui` sets `UiFailures` as the Display's runtime-exception and error handlers,
which SWT calls for a failure of a listener, `asyncExec` or timer task. Each
failure is logged at ERROR with its stack, so the recent-log warning marker
shows, and the event loop continues. More than 10 runtime exceptions within
1 s, or any `Error`, writes `qlogg_error.flag` in the settings directory
(`ErrorExitMarker`), runs `WindowSet.quit` once, which saves the session, and
`Gui.run` returns status 4. No dialog is shown. The marker write runs on the UI
thread without SWT, because a dialog or a worker may not run after an `Error`.

`Gui.loop` also catches a runtime exception or `Error` that leaves
`readAndDispatch` and passes it to `UiFailures`, which counts it the same way.
When the quit `UiFailures` runs throws, `UiFailures` logs it at ERROR and the
loop ends at once with status 4: the marker is written and the session is not
saved. `Gui.run` then closes the workers within their bound and disposes the
Display, which disposes the windows left open, and `Main` exits with
`System.exit`.

`Gui.run` reads the marker before the Display opens:

| Launch | Session tabs | Notice, when the marker is empty | Marker afterwards |
|---|---|---|---|
| Restores the session, without `-m` | Skipped | `NOTICE_ERROR_EXIT` | Deleted |
| Restores the session, with `-m` | Skipped | `NOTICE_ERROR_EXIT` | `noticeShown` |
| Does not restore (files given, or `-n`) | Not loaded | `NOTICE_ERROR_EXIT_LOG`, which claims no skipped tabs | `noticeShown` |

A marker holding `noticeShown` shows no notice. A launch that skips the tabs
loads no `qlogg_session.conf`: no tabs, further windows, Always on Top or
dropped-comment notice. It opens the command-line files and owns the session
as a restoring launch does, so its quit replaces the saved tabs. Settings,
window geometry and per-file positions in `qlogg.conf` and `positions.conf`
load as usual, so a file the user opens again returns to its position. The
runtime-exception burst writes the marker too: a restored tab that throws on
each paint repeats the quit as an `Error` does.

### Event loop watchdog

When QLogg's loggers take DEBUG at start (`-d 2` and above, or the debug log
file attached by `-l` or `log.file`), `Gui` starts an `EventLoopWatchdog`
(`qlogg-app`) on a `qlogg-watchdog-` thread. Turning on Help > Debug Log File
later does not start it. It posts a probe through the dispatcher every 250 ms
and logs a WARN with the UI thread's stack when one waits over 100 ms, then an
INFO with the whole delay. It never interrupts the UI thread.

## Logging

SLF4J logs through `slf4j-jdk14` to java.util.logging (JUL). `Main` calls
`LogConfigurator.install` before any class logs. It sets
`java.util.logging.manager` to `RetainingLogManager` unless the property is
set, then calls `LogManager.reset()`, which drops what the JDK read from
`$JAVA_HOME/conf/logging.properties` or a `java.util.logging.config.*`
property, and attaches the root handlers:

| Handler | Level | Destination |
|---|---|---|
| `StderrHandler` | `-d`, WARN when absent | Standard error |
| `RecentHandler` | INFO, or `-d` when finer | `LogConfigurator.RECENT` (`RecentLog`); WARN and ERROR lines count as warnings |
| `LogFileHandler` | DEBUG, or `-d` when finer | `<settings>/logs/qlogg.log` through `FileLog`, while attached; `qlogg-<pid>.log` in a process without the handoff lock |

`-d` maps to JUL as slf4j-jdk14 maps the SLF4J levels: `error` SEVERE, `warn`
WARNING, `info` INFO, `debug` FINE, `trace` FINEST. The
`io.github.ajdailey.qlogg` logger takes the finest handler level. Every other
logger, including the JDK's own, stays at INFO, so `-d` and the file do not
turn on JDK debug output.

`RetainingLogManager` ignores `reset()` once configured. The JDK's logging
shutdown hook calls `reset()`, which would remove the handlers while
`ShutdownHook` still logs its forced-exit WARN.

The uncaught exception handler logs the throwable with its stack trace.

### Line format

Every handler formats with `LineFormatter` and publishes on the logging
thread: `2026-11-04 12:00:00,123 WARN  [thread] i.g.a.q.l.Launcher -
message`, then the stack trace when there is one. The logger name is cut to 20
characters by shortening packages from the left. CR, LF, NEL, U+2028 and
U+2029 in the message become `_`, a backstop behind `LogText.oneLine` at the
call sites.

### Log file

`LogFileHandler` writes each line with one unbuffered write, so a crash loses
no line and a slow disk holds the thread that logs. It writes no lock file. The
`logs` folder is created owner-only, and on POSIX the file `rw-------`.

| Event | Action |
|---|---|
| A write would take `qlogg.log` past 10 MiB | Moves the file to `qlogg.log.1` and starts a new one |
| The move fails | Reopens `qlogg.log` and keeps appending; the move is tried again once the file has grown another 10 MiB, or at the next start. The first failure after a successful move logs a WARN |
| A write or open fails | Detaches the file and logs an ERROR |

### Pruning

A primary instance started with the file attached runs `FileLog.prune` on a
`qlogg-lifecycle-` worker. It deletes each regular file in `logs` named
`qlogg-<pid>.log` (the name `FileLog.fileName` gives a process without the
lock), or its rolled `qlogg-<pid>.log.1`, that was modified more than 7 days
ago and whose process is not running (`ProcessHandle.of`). Both checks protect
a live instance's file: its process runs, and its writes keep it young.
`qlogg.log` and other names are never deleted.

## Architecture rules

`ArchitectureTest` checks every class except the bootstrap; test classes and
test jars are not imported. Each rule with a fixture test
(`...FailsOnItsFixture`, `...FailOnTheirFixtures`) runs against the violating
classes in `ui.swt.archfixture` and `app.controller.archfixture` (test
sources), so a rule that stops matching fails:

| Rule | Why |
|---|---|
| SWT only in `ui.swt` and `launcher` | Model and controllers test without a display |
| No dependency on `org.eclipse.swt.internal` | Public SWT API only, so GTK3 and GTK4 behave the same |
| No `Class.forName`, `getDeclared*`, `getMethod`, `getField` or `setAccessible` in any class except `Win32Stacking`, `CocoaStacking`, `GtkX11Stacking` and `Gtk4ToolItemSizing` (`reflectionOnlyInExceptionClasses`) | Reflection into SWT stays in listed Public SWT API exceptions |
| `SWT.getPlatform()` only in `ui.swt.platform`; no `System.getenv` elsewhere in `ui.swt` or in `launcher` (`platformOnlyInTraits`) | Each view-layer platform difference is a `PlatformTraits` method |
| `ui.swt.TextGrid` does not depend on SWT | Column geometry and hit testing test without a display |
| A code unit that creates a `Shell` with an owning `Shell` calls `DialogColors.prepare` (`ownedShellsTakeDialogColors`) | A dialog built without `Dialogs.shell` still takes the dark win32 dialog colors |
| `core` does not depend on `app`, `ui` or `launcher`; `app` not on `ui` or `launcher` | Layering |
| `core.{io, text, index, search, watch, concurrent}` not on `core.model`; `app.view` not on `app.{controller, config, cli, ipc}`; `ui.swt` not on `core.{io, index, concurrent}` | Layering |
| No package cycles | Layering |
| `core.util` depends on no other QLogg package | Utilities stay leaf classes |
| `ui.swt` uses `core.{search, watch}` only for their enums | Views read controller state, not the search and file-watch model |
| Views do not depend on `LogDocument`, `MutableLineSet`, `LineReader` or `FoldMap`, and do not call `Viewport` mutators | The controller owns the document, the sets and the viewport |
| Views do not call `Highlighter.of` or `compile` | `HighlightComposer` and `HighlightSetsEditor` compose and validate rules |
| `ui.swt` does not call `ApplicationController.isFavorite`, `debugLogFile`, `runSearchOnAdd` or `ThemeController.mode` | `CommandStates` computes check item state |
| `app.controller` classes other than `WindowSet` and `ApplicationController` do not call `ApplicationController.windowSet()` (`helpersDoNotReachTheWindowSet`) | Each helper takes a narrow host interface |
| Main code does not call the `DocumentServices` constructor without the held lock (`mainCodeCarriesTheHeldLock`) | Every path check rejects the instance lock file |
| `app.controller.TextExporter` does not depend on `app.view` | The exporter runs on a worker; its caller posts the result to the UI thread |
| Public `app.controller` methods do not return `MutableLineSet` | Views cannot change marks or results |
| `app.controller` reads no system property or environment variable | Tests choose the platform through the view |
| Every `UiText` constant is read outside `UiText` | No unused bundle entry |
| `java.util.logging` and `org.slf4j.jul` only in `launcher` | Everything else logs through slf4j; only the launcher sets handlers, levels or the `LogManager` |
| No `java.net.http`, `URLConnection`, `Socket` or `URL.openStream`; `SocketChannel` and `ServerSocketChannel` only in `app.ipc` | No outbound network |
| No `ProcessBuilder`, `Runtime.exec`, object streams, `XMLDecoder`, or `java.util.Random` and its subtypes other than `SecureRandom` | Security |
| `System.exit` and `Runtime.halt` only in `launcher.Main` | `main` exits only for a nonzero status, after `Gui.run` has disposed the Display |
| No `Display.syncExec`; `Display.asyncExec` and `timerExec` only in `SwtUiDispatcher` | A worker never waits on the UI thread; submissions after disposal are dropped |
| No `Future.get`, `Thread.join`, `Thread.sleep`, `Object.wait`, `Lock.lock`, `Condition.await`, `Semaphore.acquire`, `BlockingQueue.put` or `take`, `CountDownLatch.await`, `OperationHandle.await` or `java.nio.channels` in `ui.swt` and `app.view` | The UI thread never blocks |
| No `Future.get`, `Thread.join`, `Thread.sleep`, `OperationHandle.await` or `CountDownLatch.await` in `app.controller` and `app.command` | Controllers run on the UI thread |
| In `app.controller`, blocking file, document and settings calls only in `@WorkerThread` methods or tasks submitted to `Workers`; `@WorkerThread` methods called only from those | Controllers run on the UI thread |
| No `new Thread`, `Thread.ofVirtual`, `ofPlatform`, `startVirtualThread`, `Executors` or pool constructors outside `Workers` | One owner for every thread |
| `LogDocument.open` and `ofText` only in `app.controller.DocumentOpener` within `app` | A document whose open ends after its window closed is closed, not leaked |
| The string `os.name` only in `core.util.os` (`osNameOnlyInTheOsSeam`, read from each class file's constant pool); no `OsTraits.current()` in `app.controller` | Operating system checks go through `OsTraits`; tests choose the system |

## Testing

Classes tagged `swt` open windows and run under a display (Xvfb under
`xvfb-run`). Surefire plants `java.util.logging.config.file`
(`planted-logging.properties`) so `LogConfiguratorTest` can check that
`install` removes it.

| Class | Covers |
|---|---|
| `GuiTest` (`swt`) | The composed application: waits for the file to name the window at 900 x 600, location 50,40, shuts down, and checks that no `qlogg-` thread survives; a failing UI task leaving the window open with status 0; 11 failing tasks, or one `Error`, quitting with status 4 with the session saved and the marker written; `Gui.loop` on a Display without handlers routing a runtime exception and an `Error` out of `readAndDispatch` to `UiFailures`, the `Error` quitting with status 4; a quit that throws ending the loop with status 4 and the window still open; with the marker, a `-s` launch with a file opens only that file, shows the notice and deletes the marker, and the next launch restores that file without the notice; a launch with a file and no restore shows the other notice once and keeps the marker, a second shows none, and a later `-s` launch skips the saved tab without a notice and deletes the marker |
| `UiFailuresTest` | 10 failures within 1 s keep running, the 11th quits once, failures more than 1 s apart keep running, an `Error` quits; each quit writes the marker; a quit that throws sets `quitFailed` and runs once |
| `LargeFileGuiTest` (`swt`) | A 3 GiB file of 64-byte numbered lines (50,331,648 lines) in the temporary directory: the first line shows while indexing runs, go to line shows the last line (past 2^31 bytes) with the scroll bar at its end, a thumb drag shows the mapped line. Needs 4 GiB free and is skipped otherwise |
| `ScreenshotsTest` (`swt`) | Runs only with `-Dqlogg.screenshots=<dir>` (`tools/screenshots.sh`): a generated mock log at 1100 x 700 (`-Dqlogg.screenshots.width` and `.height` override it; `-Dqlogg.screenshots.tabs=2` opens a second log so the tab row holds two tabs) with a highlighter set, a search and two marks; writes `qlogg-light.png` and `qlogg-dark.png` through `Shell.print` |
| `PortableRunTest` (`swt`) | `Gui` twice over a portable bundle with `user.home` set as `PlatformSetup` sets it, once with the marker in the bundle and once in its `bin/`: the first run opens a log, zooms and adds a favorite, the second restores the session; every new or changed file lies in the marker's folder and the home directory is empty |
| `PortableGuideTest` (`swt`, Linux, not GTK 4) | `PortableGuideMain` as a child process in portable mode with `HOME` an empty folder and no application for HTML: Help > User Guide writes the copy and opens no guide window, and no `QLogg` folder under `.cache` or `.local/share` and no `.cache/mesa_shader_cache` appears. GTK 4's renderer writes the Mesa cache in every run |
| `WindowsGuiTest` (`swt`) | `Gui` as a primary: New Window opens a second window, a handoff from a worker opens its file in the window activated last, quit closes both windows, the workers and the endpoint file; a handoff sent before `Gui` runs opens as a tab |
| `StoreFaultsTest` (`swt`) | `Gui` over a malformed, an oversized and an unknown-version session file: each opens an empty window, exits 0 and logs a WARN. A remembered encoding the runtime does not support opens the file with detection |
| `SigtermTest` (`swt`, Linux) | `Main` as a child process restoring a two-file session: after both files' `Indexing ... done` lines in its `-l` log, SIGTERM (`Process.destroy`) ends it within the hook's bound, the endpoint file is gone, the session and positions files were written, and the hook's INFO line reached the log. Windows has no SIGTERM; `destroy` there terminates without hooks |
| `ShutdownHookTest` | The hook's task run directly: it requests the quit and releases once it finishes, only releases after an ordered quit, and returns after its bound when the quit never finishes, naming the surviving threads |
| `LauncherProcessTest` | Launches as real processes through `LaunchMain`: a primary asked to open, import from or export over `instance.lock` keeps the lock, and the next launch hands its files over |
| `LauncherTest` | The WebView2 data folder for per-user, portable and explicit settings on Windows; other systems leave the property unset |
| `LogConfiguratorTest` | The planted handlers, levels and properties are gone after `install` |
| `RetainingLogManagerTest` | `reset()` keeps the handlers once retained |
| `FileLogTest` | The file's location; the level with the file attached and detached; only `qlogg.log` in the folder after a detach; `-d trace` staying finer; a process without the lock writing `qlogg-<pid>.log`; the roll to `qlogg.log.1`; a failed roll (a non-empty directory named `qlogg.log.1`) writing on with one WARN, not tried again before the file grows by the size, and rolling then; a failed reopen (a read-only file) detaching the file with its ERROR in the recent lines; with set modification times, prune deleting only an old `qlogg-<pid>.log` and `.1` of an ended process and keeping a young one, a running process's, `qlogg.log`, a `.2` name, other names and a directory |
| `ArchitectureTest` | The rules above, and the fixture tests |
| `BusyCoverageTest` | Every `Workers.tryStart`, `start` or `executeCpu` call and anonymous `BusyWork` in `qlogg-app`, keyed by the named method after leaving anonymous classes, matches a row marked `BUSY`, `QUICK` or `UPKEEP`: a new or removed site fails. A `BUSY` row creates a `BusyWork` or calls its row's method. Indexing and the search start their tasks in `qlogg-core` and are not listed |
