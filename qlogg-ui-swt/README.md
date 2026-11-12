# qlogg-ui-swt

The SWT views, in package `io.github.ajdailey.qlogg.ui.swt`. They draw and
forward input; behavior lives in `qlogg-app`. The module depends on
`qlogg-app`, `qlogg-core`, slf4j-api and SWT, and is compiled and tested
against the build host's SWT fragment.

---

## Map

| Classes | Contents | Detail |
|---|---|---|
| `MainWindow`, `SearchBar`, `ResultsPane`, `QuickFindBar`, `TabDots`, `WindowStacking` | The main window: top row, tab row, split body, search row, filtered view, status bar, Always on Top | [docs/main-window.md](docs/main-window.md) |
| `MainMenuBar`, `TimeSyncMenu`, `AppMenu` | Menu bar, listed entries, Time Sync cascades, macOS application menu | [docs/menus.md](docs/menus.md) |
| `LogCanvas`, `TextGrid`, `RowPainter`, `PaletteColors`, `SwtKeys`, `OverviewBar` | The log view and the overview bar | [docs/log-view.md](docs/log-view.md) |
| `Dialogs` and the dialog classes, `GuideWindow`, `ScratchpadWindow`, `MultiSearchWindow`, `FolderSearchWindow`, `MarksWindow` | Dialogs and tool windows | [docs/dialogs.md](docs/dialogs.md) |
| `SwtThemeService`, `CocoaChrome`, `DialogColors`, `Icons`, `ToolIcons` | Native widget theme per platform, dialog colors, tab strip colors, icons | [docs/theme.md](docs/theme.md) |
| `SwtUiDispatcher` | `UiDispatcher` over the Display | [UI thread](#ui-thread) |
| `PlatformTraits`, `Win32Traits`, `CocoaTraits`, `GtkTraits`, `Gtk3Traits`, `Gtk4Traits` | Every view-layer platform difference | [docs/main-window.md](docs/main-window.md#platform-traits) |

## UI thread

`SwtUiDispatcher` implements `UiDispatcher` over the Display. It is the only
class that calls `Display.asyncExec` or `timerExec`, and nothing calls
`syncExec`. It drops a submission once the Display is disposed, including when
disposal races the call. `cancelTimer` cancels a `timerExec` task that has not
run.

View classes must not block the UI thread. `ArchitectureTest` rejects in this
module `Future.get`, `Thread.join`, `Thread.sleep`, `Object.wait`, `Lock.lock`,
`Condition.await`, `Semaphore.acquire`, `BlockingQueue.put` and `take`,
`CountDownLatch.await`, `OperationHandle.await` and `java.nio.channels`.

Views read controller state only. They do not use `LogDocument`,
`MutableLineSet`, `LineReader` or `FoldMap`, do not call the `Viewport`
mutators, and use only the enums of `core.search` and `core.watch`.

## Public SWT API

The views use only SWT's public API, so they run unchanged on GTK 3 and GTK 4;
`ArchitectureTest` rejects `org.eclipse.swt.internal`. Platform differences go
through `PlatformTraits` ([main window](docs/main-window.md#platform-traits)).
`GtkTraits` and `Gtk3Traits` read the GDK backend system property, an internal
name that fails safe when absent. View > Always on Top and GTK 4 tool item
sizing are the exceptions: `Win32Stacking`, `CocoaStacking`, `GtkX11Stacking`
and `Gtk4ToolItemSizing` reach SWT internals by reflection, and
`ArchitectureTest` allows reflection nowhere else
([main window](docs/main-window.md#always-on-top),
[theme](docs/theme.md#gtk-4-tool-item-size)).

### GTK 4

GTK 3 is the Linux default. GTK 4 is an experimental opt-in: set `SWT_GTK4=1`
before starting QLogg (linux-x86_64 only; linux-aarch64 has no SWT GTK 4
natives and stays on GTK 3). Open SWT GTK 4 defects affect the log view: slow
scrolling, custom painting and missing mouse events.

Under GTK 4, Help > Documentation shows a notice naming the guide's copy and
creates no `Browser`, because WebKitGTK 6.0 aborts the JVM when the browser
starts (measured, Ubuntu 24.04).

## UI text

Every label, title and message comes from `UiText` (`qlogg-app`).

## Testing

Tests are tagged `swt` and open real windows. How they run and what each
class covers is in [TESTING.md](TESTING.md).
