# qlogg-launcher

Composes the application: parses the command line, configures logging and the
platform, then runs the SWT event loop. It is the only module that depends on
logback, and its test class path sees every module except the bootstrap, so
it holds the architecture rules.

## Startup and exit

`Launcher.run` returns the exit status:

| Status | Cause |
|---|---|
| 0 | Help, version, the files went to the running instance, or the last window closed |
| 1 | Bad command line, or a `--settings` path that cannot be a directory |
| 3 | SWT failed to load (`UnsatisfiedLinkError` or `SWTError` from `new Display()`), typically missing GTK 3 libraries |

`Main` calls `System.exit` only for a nonzero status, which arises only before
the Display exists. After a normal close `main` returns and the JVM exits on
its own.

Before the Display exists, `Launcher` resolves the settings directory
(`SettingsLocation.fromSystem`) and loads `qlogg.conf`. In explicit and
portable mode on Linux, `PlatformSetup` then sets `user.home` to the settings
directory, so GTK SWT writes its `.swt/trims.prefs` there. On Windows, in
every mode, it sets `org.eclipse.swt.browser.EdgeDataDir` to
`<settings>/cache/webview2`, so the user guide's WebView2 browser keeps its
data in the settings directory.

Without `-m`, `Launcher` then runs the single-instance handoff (`app.ipc`):
it creates the settings directory owner-only if missing, and
`InstanceServer.claim` either makes this launch the primary or
`InstanceClient.handOff` sends the files, `-f` and `+line:N` to the primary
and `run` returns 0. A failure at any step, or a settings directory another
user owns or others can write (`InstanceClient.refusal`), logs one WARN and the
launch opens its own window, as with `-m`. A launch that stays then opens the
debug log file when `-l` or `log.file` asks for it.

`Gui` runs on the calling thread, which becomes the UI thread. It resolves
`ui.theme` before the first Shell; for light and dark,
`SwtThemeService.open` also sets the native widgets' theme (system leaves them
to the OS), so on Windows and macOS a theme change reaches them at the next
start. It creates
`Workers` and loads the default time zone's rules on a `qlogg-lifecycle-`
worker, so the first open does not read the JDK time-zone data on the UI
thread. It creates the `MemoryBudget` from `Runtime.maxMemory()`, the document
services (an `Indexer` with default settings, a `BlockCache` of 64 KiB blocks
holding the whole `BLOCK_CACHE` share, `encoding.default` as the fallback
charset), the dispatcher, the `WindowSet` with `LogbackConfigurator.RECENT`
and the `FileLog`, connects the macOS application menu (`AppMenu.hook`, a
no-op elsewhere), and the first window with the default `Keymap` and the
user's overrides and its `ApplicationController`, places the window (the
`--window-*` options, else the remembered bounds), maximizes it when it was
maximized and no `--window-*` option is given, opens it, and logs
`Main window open: client area <w>x<h>, location <x>,<y>` at INFO. Before the
Display exists it loads `positions.conf`, and `qlogg_session.conf` when the
session is restored: with `-s`, or with neither `-s` nor `-n`, no file given
and `session.loadLast` on. The first window restores `window.0`'s tabs and
each further session window opens a window with its own; then the
command-line files open in the first window on workers, a tab each, following
with `-f`. The instance saves the tab list only when it restored the session
without `-m`. New Window goes through the set's `Factory` in `Gui`: a window
at the remembered size, placed by the window system. A window's close button
closes that window (`closeWindow`); the last one quits.
A primary's `InstanceServer` starts accepting once the first window is open;
its receiver answers `ERR` once quit has begun and otherwise posts
`WindowSet.openHandoff`. Quit closes it first on its lifecycle worker, and
`Gui` closes it again on exit.
`Gui` registers a JVM shutdown hook (`ShutdownHook`, thread `qlogg-shutdown`
from `Workers.shutdownHook`) and removes it when the event loop ends. On
SIGTERM, logoff or a parent ending the process, the hook posts
`WindowSet.quit` through the dispatcher, waits up to 3 s for the quit's
finish listener, logs the surviving `qlogg-` threads at WARN if it times out,
and closes the handoff listener, which deletes the endpoint file.
At `-d 2` and above it starts an `EventLoopWatchdog` (`qlogg-app`) on a
`qlogg-watchdog-` thread: a probe through the dispatcher every 250 ms, and a
WARN with the UI thread's stack when one waits over 100 ms, then an INFO with
the whole delay; it never interrupts the UI thread. The loop ends when
every window is disposed; then the Display is disposed and `run` returns.

## Logging

`LogbackConfigurator` configures logback in code. It is registered in
`META-INF/services` at `CUSTOM_TOP_PRIORITY` and returns
`DO_NOT_INVOKE_NEXT_IF_ANY`, so logback never reads a `logback.xml`. There is
no XML configuration, so no logback condition is evaluated, and the enforcer
bans janino.

| Appender | Level | Destination |
|---|---|---|
| `stderr` | `-d`, WARN when absent | Standard error |
| `recent` | INFO, or `-d` when finer | `LogbackConfigurator.RECENT` (`RecentLog`); WARN and ERROR lines count as warnings |
| `qlogg-logfile` | DEBUG, or `-d` when finer | `<settings>/logs/qlogg.log` through `FileLog`, while attached; `qlogg-<pid>.log` in a process without the handoff lock |

The root level is the finest of them. `FileLog` wraps a `RollingFileAppender`
(`qlogg.log`, rolled to `qlogg.log.1` at 10 MiB) in an `AsyncAppender` with a
4,096-line queue that drops a line when full and never blocks the logging
thread; logback's own daemon thread `AsyncAppender-Worker-qlogg-logfile`
writes the file. The `logs` folder is created owner-only, and on POSIX the
file `rw-------`. An ERROR status from the file appender, its rolling policy or
its output stream detaches the file and logs an ERROR.

A primary instance started with the file attached runs `FileLog.prune` on a
`qlogg-lifecycle-` worker: it deletes each regular file in `logs` named
`qlogg-<pid>.log` (the name `FileLog.fileName` gives a process without the
lock) or its rolled `qlogg-<pid>.log.1` that was modified more than 7 days
ago and whose process is not running (`ProcessHandle.of`). Both checks
protect a live instance's file: its process runs, and its writes keep it
young. `qlogg.log` and other names are never
deleted.

The pattern ends with
`%replace(%msg){'[\r\n\u0085  ]', '_'}`, a backstop behind
`LogText.oneLine` at the call sites. The uncaught exception handler logs the
throwable with its stack trace.

## Architecture rules

`ArchitectureTest` checks every class except the bootstrap; test classes and
test jars are not imported:

| Rule | Why |
|---|---|
| SWT only in `ui.swt` and `launcher` | Model and controllers test without a display |
| No dependency on `org.eclipse.swt.internal` | Public SWT API only, so GTK3 and GTK4 behave the same |
| `core` does not depend on `app`, `ui` or `launcher`; `app` not on `ui` or `launcher` | Layering |
| `core.{io, text, index, search, watch, concurrent}` not on `core.model`; `app.view` not on `app.{controller, config, cli, ipc}`; `ui.swt` not on `core.{io, index, concurrent}` | Layering |
| No package cycles | Layering |
| `core.util` depends on no other QLogg package | Utilities stay leaf classes |
| Every `UiText` constant is read outside `UiText` | No unused bundle entry |
| logback only in `launcher` | Everything else logs through slf4j |
| No `java.net.http`, `URLConnection`, `Socket` or `URL.openStream`; `SocketChannel` and `ServerSocketChannel` only in `app.ipc` | No outbound network |
| No `ProcessBuilder`, `Runtime.exec`, object streams, `XMLDecoder`, or `java.util.Random` and its subtypes other than `SecureRandom` | Security |
| `System.exit` and `Runtime.halt` only in `launcher.Main` | Exit only before the Display exists |
| No `Display.syncExec`; `Display.asyncExec` and `timerExec` only in `SwtUiDispatcher` | A worker never waits on the UI thread; submissions after disposal are dropped |
| No `Future.get`, `Thread.join` or `java.nio.channels` in `ui.swt` and `app.view` | The UI thread never blocks |
| No `Future.get`, `Thread.join`, `Thread.sleep`, `OperationHandle.await` or `CountDownLatch.await` in `app.controller` and `app.command` | Controllers run on the UI thread |
| No `new Thread`, `Thread.ofVirtual`, `ofPlatform`, `startVirtualThread`, `Executors` or pool constructors outside `Workers` | One owner for every thread |

## Tests

`GuiTest` (tag `swt`) runs the composed application under Xvfb: it waits for
the file to name the window at 900 x 600, location 50,40, shuts down, and
checks that no `qlogg-` thread survives. `LargeFileGuiTest` (tag `swt`) writes a 3 GiB
file of 64-byte numbered lines (50,331,648 lines) into the temporary directory,
opens it through `Gui`, and checks that the first line shows while indexing
runs, that go to line shows the last line (past 2^31 bytes) with the scroll
bar at its end, and that a thumb drag shows the mapped line. It needs 4 GiB
free and is skipped otherwise. `ScreenshotsTest` runs only with
`-Dqlogg.screenshots=<dir>` (`tools/screenshots.sh`): it opens a generated
mock log through `Gui` at 1100 x 700 (`-Dqlogg.screenshots.width` and
`.height` override it; `-Dqlogg.screenshots.tabs=2` opens a second log so the tab
row shows) with a highlighter set, runs a search,
marks two lines, and writes `qlogg-light.png` and `qlogg-dark.png` through
`Shell.print`. `LogbackConfiguratorTest` puts a
`logback.xml` on the test class path and checks that logback ignores it.
`PortableRunTest` (tag `swt`) runs `Gui` twice over a portable bundle with
`user.home` set as `PlatformSetup` sets it: the first run opens a log, zooms
and adds a favorite, the second restores the session; afterwards every new or
changed file lies inside the bundle and the home directory is empty.
`WindowsGuiTest` (tag `swt`) runs `Gui` as a primary instance: New Window
opens a second window, a handoff from a worker opens its file in the window
activated last, and quit closes both windows, the workers and the endpoint
file. `ShutdownHookTest` runs the hook's task directly: it requests the quit
and releases once it finishes, only releases after an ordered quit, and
returns after its bound when the quit never finishes, naming the surviving
threads. `SigtermTest` (tag `swt`, Linux) starts `Main` as a
child process that restores a two-file session, waits for both files'
`Indexing ... done` lines in its `-l` log, sends SIGTERM (`Process.destroy`)
and checks that the process ends within the hook's bound, the endpoint file is
gone, and the session and positions files were written. Windows has no
SIGTERM; `destroy` there terminates without hooks. `FileLogTest` checks the file's location, the root level with the file
attached and detached, `-d trace` staying finer, a process without the lock
writing `qlogg-<pid>.log`, and that an ERROR status
from the file appender detaches it and reaches the recent lines; with set
modification times, prune deletes only an old `qlogg-<pid>.log` and `.1` of an
ended process and keeps a young one, a running process's, `qlogg.log`, a `.2`
name, other names and a directory. `LauncherTest` checks the WebView2 data folder for
per-user, portable and explicit settings on Windows, and that other systems
leave the property unset.
