# Help, logs and scratchpad

The Help menu's controller and content, the recent log lines, the event loop
watchdog and the scratchpad transforms in [qlogg-app](../README.md):
`HelpController`, `app.about`, `app.log` and `app.scratch`.

## Help commands

Each window's `HelpController` runs the Help commands. A result that arrives
after the window began closing is dropped.

| Item | Behavior |
|---|---|
| Show Log | Shows `RecentLog`'s lines through `MainView.showLog` and clears the warning marker in every window. A WARN or ERROR line after that shows it again (`MainView.showWarningMarker`) |
| Debug Log File | Attaches or detaches `LogFiles` on a `qlogg-lifecycle-` worker and saves `log.file`. Its check mark reads `debugLogFile()`, so it stays clear when the file did not open |
| Open Log Folder, Open Settings Folder | Create the folder owner-only on a worker, then launch it |
| Report an Issue | Collects the About details on a worker, puts them on the clipboard and opens `AboutInfo.ISSUES_URL` through `MainView.launchUrl` |
| Documentation | Writes the guide's copy, then shows it ([User guide](#user-guide)) |
| About | About facts and the license notices are read on a `qlogg-lifecycle-` worker, then shown |

## About

`AboutInfo` is the About dialog's content: version, Java, OS, toolkit line,
settings directory and mode, and `details()` for Copy Details. `licenseText()`
reads `LICENSE`, `NOTICE` and `THIRD-PARTY.txt`, which the build copies into
this module's jar.

## User guide

`UserGuide` is Help > Documentation's page.

### Build

At `generate-resources` the build copies commonmark-java 0.28.0 and its GFM
tables extension (BSD-2-Clause, build only) and runs
`src/build/GuideRenderer.java` with the Java source launcher.
`docs/user-guide.md` becomes one HTML page under
`target/generated-resources/guide`, with each image a `data:` URI and each
heading an `<a id>` with its GitHub anchor.

- A link that leaves the page or names no heading fails the build.
- An image that is not a PNG beside the guide fails the build.
- `UserGuidePageTest` checks the shipped page's links against its anchors.

### Showing the guide

`writeCopy` streams the page to `<settings>/cache/guide.html` (owner-only
folder, `AtomicFiles`) on a `qlogg-lifecycle-` worker. The controller then
calls `MainView.showGuide`, else `showGuideElsewhere`: `launch` on the copy,
else a notice naming it. The launcher's `Gui` passes `UserGuide.embedded` to
`WindowSet.setEmbeddedGuide`, and while it is false the controller skips
`showGuide`. `embedded` is false on Linux in portable and `--settings` mode: WebKitGTK and Mesa write `~/.cache/QLogg`,
`~/.local/share/QLogg` and `~/.cache/mesa_shader_cache` once the embedded
browser opens, outside the settings directory (measured with `strace -f`, an
empty `HOME`, WebKitGTK 4.1). Moving them through `XDG_CACHE_HOME` and
`XDG_DATA_HOME` is not used: set before the Display it hides
`~/.local/share/fonts` and passes to launched programs, and set later it
moves nothing because GLib caches both. The view also calls `showGuideElsewhere` when its
guide window fails after `showGuide` returned true.

## Recent log lines

`RecentLog` holds the last 500 log lines, each cut at 2,000 chars, for Show
Log. Append and snapshot each hold the deque's lock only to copy references.
The launcher's `RecentHandler` feeds it and marks WARN and ERROR lines;
`warnings()` counts them, and the warning listener runs on the logging thread
after each one.

`LogFiles` is the debug log file as the controllers see it; the launcher's
`FileLog` implements it.

## Event loop watchdog

`EventLoopWatchdog` posts a probe through the `UiDispatcher` every
`intervalMillis` from a `qlogg-watchdog-` thread.

- A probe still waiting after `boundMillis` reports a stall with the UI
  thread's stack (`StallListener.stalled`; `LOGGING` logs it at WARN), then the
  whole delay once it runs (`recovered`, INFO).
- It never interrupts the UI thread.
- It ends at `stop` or when the workers close.
- `enabled()` is true when its logger takes DEBUG (`-d 2` and above).

## Scratchpad transforms

`Transforms.apply(kind, text)` runs one built-in transform: Base64 and hex to
and from UTF-8, URL decode, JSON and XML indentation. Nothing runs code from
the text.

- JSON and XML are reformatted by a scan of quotes, brackets and tags, not
  parsed, so no entity, DTD or external reference is read.
- Input that does not fit throws `IllegalArgumentException` with the reason. An
  input or output over `MAX_CHARS` (1 Mi) throws its subclass
  `TooLongException`.
- JSON and XML indent at most `MAX_DEPTH` (32) levels, so the work is linear in
  `MAX_CHARS` whatever the nesting.

`values(text, zone)` lists the selection's CRC32, its value in the other number
base and, for 10 or 13 digits, the epoch time in UTC and in the zone.
