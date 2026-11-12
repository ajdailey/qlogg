# qlogg-app

The command line, key bindings, controllers, view contracts and settings. It
depends on `qlogg-core` and slf4j-api, never on SWT, so every class is tested
without a display. `qlogg-ui-swt` implements the view contracts and
`qlogg-launcher` composes the two.

---

## Map

| Package | Contents | Detail |
|---|---|---|
| `app.cli` | `CommandLineParser`, `CommandLine`, `StartupRequest`, `Version` | [Views and commands](docs/views-and-commands.md) |
| `app.view` | `UiDispatcher`, `MainView` and the services it extends, listener and state types, text formatters, `UiUpdateQueue`, `WindowGeometry` | [Views and commands](docs/views-and-commands.md) |
| `app.command` | `CommandId`, `CommandGroup`, `Binding`, `Keymap`, `KeySequencer`, `KeyStroke`, `MenuTable`, `ShortcutOverrides` | [Views and commands](docs/views-and-commands.md) |
| `app.i18n` | `UiText` and `messages.properties` | [Views and commands](docs/views-and-commands.md) |
| `app.controller` | Windows, tabs, open, reload, close and quit | [Windows and tabs](docs/windows-and-tabs.md) |
| `app.controller` | The main and filtered views of one document: rows, copy, save, marks, follow, folds | [Document views](docs/document-views.md) |
| `app.controller`, `app.search` | Search, quick find, search across files, search in a folder, history and filters | [Search](docs/search.md) |
| `app.controller`, `app.highlight`, `app.theme` | Highlighters, color labels, keyword triggers, palette | [Highlighting](docs/highlighting.md) |
| `app.controller` | Timestamp formats, Go to Time, time sync, time spread | [Timestamps](docs/timestamps.md) |
| `app.config` | Settings directory, `qlogg.conf`, color scheme files, positions and session stores | [Settings](docs/settings.md) |
| `app.ipc` | Single-instance lock, endpoint and handoff protocol | [Single instance](docs/single-instance.md) |
| `app.about`, `app.log`, `app.scratch`, `app.controller` | About, the user guide, recent log lines, the watchdog, Help commands, scratchpad transforms | [Help, logs and scratchpad](docs/help-and-logs.md) |

## Controller threading

Every controller is confined to the UI thread and never blocks it.
`ArchitectureTest` rejects `Future.get`, `Thread.join`, `Thread.sleep`,
`OperationHandle.await` and `CountDownLatch.await` in `app.controller`.
Controllers read no system property or environment variable; the platform comes
from the view. Public controller methods return `LineSet`, never
`MutableLineSet`.

A blocking call in `app.controller` (file I/O, `LogDocument` open and close,
settings load and save, line reads; the list is `BLOCKING` in
`ArchitectureTest`) is allowed only:

- in a method annotated `@WorkerThread`;
- in `run` of an anonymous `Runnable` that its creating method passes to
  `Workers`, directly or through a helper of the same class that takes a
  `Runnable`.

A creating method that also creates another anonymous `Runnable` or takes a
method reference does not qualify, because the bytecode does not show which
task it submits; annotate that `run` instead. A `@WorkerThread` method is
called only from such code, so annotating a UI-thread method fails at its
caller. Method references to blocking methods are not checked.

Controllers run work on these `Workers` pools:

| Thread prefix | Work |
|---|---|
| `qlogg-index-` | Open, paste, reload, reopen, session restore; indexing |
| `qlogg-read-` | Row reads, caret times, fold classification, first-line selector match, marks list |
| `qlogg-search-` | Search, folder search, quick find, mark toggle, range selection, overview bins, time search, trigger scans, filtered-view snapshots |
| `qlogg-lifecycle-` | Settings writes, document close, quit, imports and exports, path checks (save target, Open in Editor, Clean Up, Open All), About facts, Help folder and guide actions |

Every worker that reads a document runs inside `LogDocument.enter` and `exit`.
After close begins, `enter` fails and the worker posts its empty result.

## Testing

Tests run without a display, over `FakeView` and `ManualUiDispatcher` with real
workers, files and file monitor, and log through `slf4j-jdk14`. The harness and
coverage by class are in [TESTING.md](TESTING.md).
