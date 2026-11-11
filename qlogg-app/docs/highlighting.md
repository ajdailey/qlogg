# Highlighting

Highlighter sets, color labels, automatic set selection, keyword triggers and
the palette in [qlogg-app](../README.md): `app.highlight`, `app.theme`, and
`ViewFeatures` and `TriggerController` in `app.controller`. The `Highlighter`
that colors rows is in `qlogg-core`.

## Settings and commands

`ApplicationController` applies highlight settings (`highlightSettings`,
`setHighlightSettings`, `toggleHighlightSet`, None): each change saves at once
and recolors every tab. `importHighlights` and `exportHighlights` run
`HighlightFile` on a `qlogg-lifecycle-` worker and report to a
`SettingsFileListener`. Color label commands read the focused view's selected
text on a worker, then change the document's labels.

| Class | Contract |
|---|---|
| `HighlightSettings` | The sets (at most 256, distinct ids), the active ids (unknown ids dropped), 9 `ColorLabel`s and the `MatchHighlight`. `rulesInApplyOrder` lists the active sets' rules so the top rule of the first active set applies last and wins; `withImported` appends only sets with new ids |
| `HighlightFile` | The keys are in its Javadoc. `load` skips a malformed set or rule and keeps the default for a malformed label. A malformed trigger value (flag not `true` or `false`, template over 1,000 chars) turns only that action off in `load` and `read`, with one WARN per call; a file without trigger keys loads with `RuleTrigger.NONE`. `read` takes the user's path through `SafePaths.logFile` and refuses a file over 1 MiB, without `version=1`, with over 256 sets or a malformed set or rule (`FilterFile.FormatException`); `write` replaces the file through `AtomicFiles` |
| `HighlightSetsEditor` | The Configure Highlighters list model over a copy of the sets: add, remove, move and rename sets; add, remove and move rules; at most `MAX_SETS` sets and `MAX_RULES` rules per set. Each edit returns the index to select next; an index out of range changes nothing; removing a set drops its active id. `setRule` keeps a rule that does not compile and returns `validate`'s message, which also reports a comment template whose regex does not compile; `importSets` appends the sets with new ids |
| `LabelState` | One document's labeled texts. `apply(n, text)` moves a text to label n, or removes it when it has n; `next` steps the cycle (labels with `cycle`) and removes a labeled text's label, still advancing; `EMPTY` clears and restarts the cycle. At most 256 texts; cost 56 bytes per text plus 2 per char |

## Rule composition

`HighlightComposer.compose` returns one document's rules, lowest priority
first:

1. the active sets (`rulesInApplyOrder`), or only the automatic set while a set
   has its id;
2. the match rule of the last search;
3. the color labels in the palette's colors.

`matchRule` returns null when the match highlight is off, for empty text, for a
combined or inverse search, and for a pattern over `MAX_PATTERN_CHARS`. The
length counts the whole-word wrap `(?<!\w)(?:...)(?!\w)` that
`StandardMatchers.wholeWord` puts around the quoted or regex text. `LastSearch`
carries the search toggles the match rule reads, so `app.highlight` does not
depend on `app.view`; that dependency forms a package cycle.

`triggerRules` returns the active or automatic set's rules, highest priority
first, for `TriggerController`.

## Automatic highlighter sets

`ViewFeatures` applies the view settings to each new document and tries the
`HighlightSelector`s in order. The first match colors that document with the
chosen set instead of the active sets
(`DocumentController.setAutoHighlightSet`). Choosing sets in the Highlighters
menu while that document shows drops its automatic set.

| Selector | Evaluated |
|---|---|
| File-name glob | On the UI thread |
| First-line regex | On a `qlogg-read-` worker over the first 4 KiB, decoded. It reads at most `8 * length + 65,536` chars through the core `BoundedChars`, the highlighter's bound, and counts as no match past it |

## Keyword triggers

`TriggerController` (`DocumentController.triggers()`) applies the `Triggers` of
the rules `HighlightComposer.triggerRules` returns. `refreshHighlights`
rebuilds them when the rules change; a running scan keeps the rules it started
with.

### Scope

Triggers act on complete lines appended after the first indexing pass ends.

- A reload or reopen creates a new controller, so a first pass, `F5` and an
  automatic reopen never fire.
- A partial last line is scanned once it ends.
- Growth that arrives while no rule acts is skipped, so a rule added later does
  not act on it.

### Scans

Each flush with unscanned growth and no scan running starts a scan on a
`qlogg-search-` worker under `LogDocument.enter`. The scan loops over
`Triggers.scan` in batches of 4,096 lines to the indexed end, re-reading the
index each batch, and posts each batch. `detach` cancels its token. A scan that
fails to read keeps its position, and the next starts when the complete line
count changes.

On the UI thread, a batch:

- logs a template failure once (WARN);
- repaints after marks;
- calls `followStoppedByTrigger` for a stop-follow match;
- notifies `TriggerListener`s.

`followStoppedByTrigger` does nothing while not following. Otherwise follow
turns off, and `DocumentHost.followStoppedByTrigger` shows a notice naming the
rule (pattern cut to 60 chars) for the shown tab, or sets `FOLLOW_STOPPED` for
another.

### Limits

- A mark or comment refused by `RESULTS` or the 1,000-comment cap shows one
  notice per scan, naming the tab.
- Quiet lines stay in a `MutableLineSet` charged to `RESULTS` until the
  document closes. When the share refuses them, the set is cleared and a notice
  shows; quiet lines then count toward the tab dot again for that document, and
  later scans keep none.

## Palette and theme

`Palette` holds the log view, gutter bullet, overview bar, time-spread and
gray color label colors as `Rgb` values (`LIGHT`, `DARK`). `ThemeController`
resolves `ui.theme` (`ThemeMode`) against `ThemeService.systemDark()`, and
`view.colors` against `ColorSchemes`, and pushes a new palette to
`PaletteListener`s:

| `view.colors` | Palette | Native controls |
|---|---|---|
| `follow` (Follow theme), or an id that names no scheme | `LIGHT` or `DARK` from `ui.theme`; `SYSTEM` follows the service's change events | Follow `ui.theme` |
| `light`, `dark` | `LIGHT`, `DARK`, through every theme and system change | Follow `ui.theme` |
| A scheme id | The scheme's, through every theme and system change | Follow `ui.theme` |

Each mode change, and each system change in `SYSTEM`, calls
`ThemeService.preferDark` with the theme's side, never the scheme's.

`ApplicationController.theme()` is the `ThemeController`. The View menu's theme
items call `setTheme`, which saves `ui.theme`. When `ThemeController.setMode`
reports that native controls show the theme only from the next start (Windows),
`setTheme` shows `THEME_RESTART_MESSAGE` through `showInfo`. Tools > Options
saves `view.colors` through `setViewOptions`, which calls
`ThemeController.setColors`.

### Color schemes

`ColorSchemes.all()` lists `LIGHT` and `DARK` as Light and Dark, then the nine
schemes of `ColorSchemes.BUNDLED`, each a complete `Palette`, then the user's
schemes from `SchemeStore` ([Settings](settings.md#color-scheme-files)).
`ColorSchemesTest` holds every listed scheme to these contrast floors:

| Color | On | Floor |
|---|---|---|
| `text` | `background`, `currentLine`, `gutter`, `match`, `mark`, `resultRow`, `contextRow`, `labelGray` | 4.5 |
| `selectionText` | `selection` | 4.5 |
| Default color label text, or `text` for the gray label | The label background | 4.5 |
| `NEW_RULE_FOREGROUND` | `NEW_RULE_BACKGROUND` | 4.5 |
| `lineNumbers` | `gutter` | 3 |
| `matchMarker`, `markMarker` | `gutter`, `overview`, `currentLine` | 3 |
| `overviewFrame` | `overview` | 3 |
| Tab dots, `spreadPause`, `spreadActivity` | `overview`, `gutter`, `background` | 3 |

`LIGHT` and `DARK` start from a navy (`#1b2e44`) and white pair; each bundled
scheme starts from a published terminal palette or a plain pair.
A color below its floor moves toward black or white only as far as the floor
needs. The text moves against `background`, `currentLine` and `gutter`; the
other row colors move against the text.

A palette change recolors every tab's match highlight, whose default color is
the palette's, and the color labels (`ColorLabel.forPalette`): on a dark
palette a label still at its default colors paints with the dark default, and
the default gray label, which keeps the line text color, takes `labelGray`.
Colors the user set, highlighter colors included, are never changed. A new
highlighter rule is `NEW_RULE_FOREGROUND` on `NEW_RULE_BACKGROUND`, readable on
every palette.
