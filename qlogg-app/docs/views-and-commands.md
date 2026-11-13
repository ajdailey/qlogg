# Views and commands

The command line (`app.cli`), the view contracts (`app.view`), key bindings
(`app.command`) and UI text (`app.i18n`) of [qlogg-app](../README.md).

## Command line

`CommandLineParser` is hand-written. It accepts `--opt value`, `--opt=value`,
`-o value`, compacted flags (`-mf`, `-md2`) and `--` ending the options.
Options added beyond the base set are long-only, so no short flag changes
meaning. The option table is in the root `README.md`.

- `+line:N` (N >= 1) applies to the next positional file only. `+line:` with a
  bad number, with no file after it, or followed by another `+line:` is an
  error.
- After `--`, an argument starting `+line:` is a file name. Any other argument
  starting `+` is a file name anywhere.
- `-s` with `-n` is an error.
- Paths are resolved against the working directory without touching the file
  system. The caller validates them with `SafePaths`.
- A parse error is a `CommandLineException` with a one-line message.

The result is a `CommandLine`: an `Action` (`RUN`, `HELP`, `VERSION`), a
`StartupRequest`, the `-d` level and the `-l` flag. In `StartupRequest`, a null
width, height, x or y means not given.

`Version.get()` reads `Implementation-Version` from this module's jar manifest,
which Maven writes from the project version. Outside a jar it returns
`unknown`.

## View contracts

`UiDispatcher` is the only route onto the UI thread. An implementation drops a
submission once the UI is disposed. There is no synchronous variant, so a
worker never waits on the UI thread.

`MainView` is the main window as controllers see it. Every method is called on
the UI thread and returns without waiting for the user, except
`chooseFilesToOpen` and `chooseSaveFile`, which run the platform chooser. It
extends
`FileDialogService`, `ClipboardService`, `NotificationService` (a one-line
status-bar notice through `showNotice`) and `ActivityIndicator`.

### Activity indicator

`ActivityIndicator.setActive` and `setIdle` are callable from any thread. Every
operation that calls `setActive` must call `setIdle` once when it ends,
including on cancel and failure. `BusyCounter` posts each call through
`UiDispatcher` and counts on the UI thread: the indicator turns on with the
first active operation and off with the last. An idle call with nothing active
is ignored.

### Listeners and state

| Listener | Events | State carried |
|---|---|---|
| `DocumentViewListener` | `stateChanged(DocumentViewState)`, `contentChanged()` (repaint from the controller's snapshot), `timeChanged` | Name, path, encoding and whether it was forced, line count, index percent, sparse index, following, file size and modification time, missing file, stopped loading, error. `hasFile()` is false for pasted text |
| `SearchViewListener` | `stateChanged(SearchViewState)`, `resultsChanged()`, `optionsChanged(text, options)` (no-op by default) | Text, `SearchOptions`, phase (`IDLE`, `RUNNING`, `DONE`, `CANCELED`, `FAILED`), percent, match count, limit, skipped lines, error |
| `QuickFindListener` | `QuickFindState` | Open, text, ignore case, regex, incremental, running, last notice |

`optionsChanged` fires when a key or an edit with selection changes the toggles
or the field. `SearchOptions` adds `autoRefresh` and `keepResults` to the
matching toggles; its `with...` methods copy it with one toggle changed.
`SearchViewState.statusText()` is the search bar's line and names the lines too
long to search. `timeChanged` reports a change of the `TimeStatus` (the view's
caret, first and last line times as read, before the offset), the offset or the
format.

### Text formatters

| Class | Formats |
|---|---|
| `StatusText` | The top row's size and encoding, the size's tooltip (`Last modified` and the time), the status bar's notes |
| `PositionText` | `Ln 12/200  Col 5`, plus `Sel 6 chars` within one line or `Sel 4 lines`. The four-argument form takes the size from another selection and appends its time difference |
| `TimeText` | Caret time (`yyyy-MM-dd HH:mm:ss`, at least 3 fraction digits, more when the line has them); a selection's difference (`+00:01:23.456`, `+2d 03:00:00.000`, negative when the last line is earlier, with the larger fraction-digit count of the two lines); the offset (`[+-][d.]HH:mm:ss[.fff]`). Parses an offset and a Go to Time target (`yyyy-MM-dd HH:mm:ss[.f]`, or `HH:mm:ss[.f]` on a given date); a refused text parses as `INVALID` |

### Update coalescing

`UiUpdateQueue` coalesces notices from worker threads into flushes on the UI
thread. `post()` from any thread schedules at most one flush per interval (50
ms in both controllers), and the flush reads the latest published state.
`close()` stops later flushes.

### Window geometry

`WindowGeometry.place` sizes and positions the window. Per dimension the
command line wins over persisted geometry, which wins over 1024 x 768. The size
is the client area plus the trim.

- The monitor is the one containing the requested top-left corner, else the
  primary.
- Size is clamped to the monitor's work area, then the location is clamped so
  the window lies inside it.
- Without a position, or where position is unsupported (Wayland), the window
  system places the window.

### Test dispatcher

`ManualUiDispatcher` (test sources, published in the test jar) queues
submissions until a test runs them on its own thread. `runUntil` drives the
queue to a condition with a bound, rechecking it at least every 10 ms, so
controller tests need no sleeps. Draining from another thread throws.
`confined(type, listener)` wraps a listener so a call off the UI thread is
recorded, and the next drain throws it.

## Key bindings

`Keymap` is the one table of bindings. The menu bar, the log view's key handler
and Help > Keyboard Shortcuts all read it. `PRIMARY` is Ctrl, or Command on
macOS. `CONTROL` is the macOS Control key: outside macOS the `Keymap`
constructor folds it into `PRIMARY` (`KeyStroke.forPlatform`) and drops a
binding the fold repeats, so a macOS binding read from `qlogg.conf` becomes the
`Ctrl` binding.

`Keymap.defaults()` takes `OsTraits.current().macKeymap()`; `defaults(mac)`
differs only for Go Back and Go Forward: `Alt+Left` and `Alt+Right`, or
`Control+Command+Left` and `Right` on macOS, where `Option`+arrow moves by word
in a text field.

### Commands and menus

`CommandId` lists every command in menu order with its menu (`CommandGroup`),
label, section break and cascade (`submenu()`, Search > Search Options). A
`CommandId` with `check()` shows as a check item, or as a radio item when it is
in `MenuTable.RADIO`. The menus are File, Edit,
Search, Go, View, Highlighters, Encoding, Tools, Window and Help. `LOG_VIEW`
commands have no menu-bar item; some of them are in the log view's context
menu.

`MenuTable.commands(group, appMenu)` lists a menu's commands. With the macOS
application menu present it leaves out About, Preferences and Exit, which that
menu holds as About, Preferences and Quit. `MenuTable` also names the cascades
filled from state (`cascadeBefore`, `cascadeAfter`), the Time Spread cascade
(`nestedCascade`) and the radio items (`RADIO`: Theme, the filtered view
modes, Encoding > Auto, Time Spread).

Every item of a menu, cascade or context menu has a mnemonic no other item
there shares (`MenuTableTest` for the menu bar and the context menu tables,
`LogViewTest` for every built menu); listed entries have none. `MenuTable` holds
the context menus' layout: `LOG_VIEW_MENU` with `logViewCascade` and
`LOG_VIEW_CASCADES`, `TAB_MENU`, `PATH_MENU` and `SEARCH_FIELD_MENU`; null is a
separator. The log view's
context menu shows `CommandId.label()`, so a command in both takes a letter
free in both.

File > Close Multiple Tabs runs `CLOSE_OTHERS`, `CLOSE_LEFT` and `CLOSE_RIGHT`
on the active tab, enabled from `CommandStates.tabItems`; the tab menu runs
the same controller calls on the clicked tab.

### Keystrokes and scopes

`KeyStroke` is toolkit-free. A printable key is the char it types: a letter in
lower case plus `SHIFT`, and `$` and `^` without `SHIFT`. Other keys are
constants; `Space` is `SPACE`, not a printable char. `KeyStroke.parse` reads
`KeyStroke.text(false)` back. `text(true)` writes `CONTROL` as `Ctrl+` and
`PRIMARY` as `Cmd+`; `text(false)` writes them as `Control+` and `Ctrl+`.
`SwtKeys` maps `CONTROL` to `SWT.MOD4`, which is Control on macOS and 0
elsewhere.

| Scope | Applies | Dispatched by |
|---|---|---|
| `WINDOW` | Anywhere in the main window; single strokes only | The menu item's accelerator; a command's other window strokes (`Ctrl`+keypad `+`) through `windowCommand`, which the window runs from a key filter |
| `LOG_VIEW` | While the log view has focus | `KeySequencer`, called by the view |

Single keys (`M`, `1` to `9`, `V`, `'`, `*`, `+`, `Space`) are `LOG_VIEW`
bindings, so typing in a text field never runs them, and a text field never
sees `J` or `Ctrl+C`.

`KeySequencer` holds the pending prefix of a two-stroke sequence (`Ctrl+E
Ctrl+T`) on the UI thread. The next stroke completes it; `Esc` or any unbound
stroke cancels it.

`KeymapTest` rejects a stroke bound twice, a single stroke that is also a
sequence prefix, an accelerator on a command with no menu, and a log view
command with no key, and checks both platforms' Go Back, Go Forward and Reopen
Closed Tab keys.

### User overrides

`ShortcutOverrides` holds the bindings the user set in place of a command's
defaults: `shortcut.<COMMAND>.count` and `shortcut.<COMMAND>.<n>` = `window` or
`view`, then the keys as `Binding.text` writes them. Count 0 unbinds.

- `Keymap.withOverrides` puts a command's new bindings where its first default
  stood.
- `Keymap.conflicts` lists the bindings with the same keys in any scope (a
  window stroke also reaches the log view) and a single stroke that starts a
  sequence. The shortcut editor refuses a binding with any conflict.
- `scopeFor` gives `LOG_VIEW` to a sequence, a log view command or a plain key;
  the default binding's scope to a stroke the command has by default; and
  `WINDOW` to other `Ctrl`, `Command`, `Control`, `Alt` or function keys.

## UI text

`UiText` names every string the UI shows. Its constant name is the key in
`messages.properties` beside it, the only bundle shipped. `text()` returns the
value and `format(args)` applies it as a `String.format` pattern in
`Locale.ROOT`. Command and menu labels come from it through `CommandId` and
`CommandGroup`. The usage text and key names (`Ctrl+`, `PgUp`) stay in code.

`UiTextTest` checks that constants and bundle keys match, and
`ArchitectureTest` that every constant is read outside `UiText`.
