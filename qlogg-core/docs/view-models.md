# View models

`core.model` classes behind the log views: folding, highlighting, keyword
triggers, marks, the overview bar and the filtered view. `LogDocument` and line
reads are in [Documents](documents.md). Module overview:
[qlogg-core](../README.md).

## Folding

`FoldMap` holds the collapsed entries of the main view: sorted `long` start and
end arrays with a prefix sum of hidden lines, so `rowOf(line)` and
`lineAt(row)` are binary searches and a hidden line maps to its entry's row.

- `collapse(start, end)` refuses an entry of one line, one that overlaps a
  collapsed entry, and one `FOLDS` cannot hold (24 B per entry).
- A change replaces the arrays, so a `cursor(lineCount)` keeps the entries it
  was created with and a worker can walk it. It is `contiguous()` only while
  nothing is collapsed.
- Otherwise the map is confined to one thread.

`FoldClassifier.entries(reader, index, rules, from, to, token)` returns the
entries of two or more lines that overlap `[from, to)`, reading only that
region plus the entry around its edges. It blocks and checks the token per
line and every 4,096 chars a rule reads. An entry longer than 10,000 lines is
not reported, so it stays unfolded.

`FoldRules` decides continuation lines. With `entryStart` set, every line it
finds no match in continues the entry; otherwise a line a `continuation`
pattern finds a match in does (defaults: `^\s`, `^at `, `^Caused by:`,
`^\.\.\. \d+ more`). Line 0 always starts an entry. A rule runs through
`BoundedChars.find`. A run that reads more than `8 * length + 65,536` chars of
a line, or overflows the regex stack, counts as a match for `entryStart` (the
line starts an entry and stays unfolded) and as no match for a `continuation`
pattern.

## Highlighting

`HighlightRule` is one highlighter: pattern, regex or literal, case, whole line
or matched text, text and background colors (either null for none), background
opacity 0-255, color variance 0-50, enabled, and a `RuleTrigger` (`NONE` by
default). `HighlightSet` is a named, ordered list of at most 1,000 rules with
an id.

`Highlighter.of(rules)` takes rules in apply order: each paints over the ones
before it. Where `HighlightSettings` combines sets, the top rule applies last,
so it wins.

| Rule kind | Effect on a matching line |
|---|---|
| Whole line | Sets the line's colors (a null color keeps the earlier one) and drops the spans earlier rules added |
| Matched text | Adds a span per match, or per captured group when the pattern has groups |

- With variance, each matched string gets its own color from the CRC32 of its
  UTF-8 bytes, up to that many percent darker or lighter, the same every time.
- Spans are display columns clipped to the read's column window, sorted and
  without overlap (`HighlightSpans`, colors packed `0xAARRGGBB`, alpha 0 for
  none). A line keeps at most 256 spans. Cost: 16 bytes per span plus 32.
- Patterns compile on first use, on the reading thread; one that does not
  compile is skipped.
- A rule that reads more than `8 * length + 65,536` chars of a line
  (`BoundedChars`), or overflows the regex stack, is abandoned for that line.
  A `LineMatcher` stops coloring after 2^26 chars. A backtracking pattern
  therefore bounds a read instead of stalling it.

## Keyword triggers

`RuleTrigger` holds a rule's actions on appended lines: stop follow, set a mark
with a comment template (at most 1,000 chars), and quiet. `Triggers.of(rules)`
takes the rules highest priority first and keeps the enabled ones with a
pattern and an action; a pattern that does not compile is left out. Every kept
rule that matches a line acts, whatever its color precedence. A rule matches
with `find` under the highlighter's bound; reaching it or a stack overflow
counts as no match.

`Triggers.scan` reads at most `BATCH_LINES` (4,096) lines and returns a
`Batch`: lines scanned, lines no quiet rule matched, the first stop-follow rule
and its line, marks added, refusals and template failures. It applies the
batch itself:

- Marks go in with one `addSorted`, all or none against `RESULTS`. A refusal
  adds no mark and no comment.
- A marked line keeps an existing comment. Otherwise it takes the expansion
  of the first marking rule with a template, through `MarkComments.put`; a
  comment past `MAX_COMMENTS` or the share is a refusal and the mark stays.
- Quiet lines go into the caller's `MutableLineSet`.
- A template that fails expands to its own text and is reported once per
  `Triggers` instance.

### Comment templates

`CommentTemplate.parse` compiles a template once.

| Element | Expands to |
|---|---|
| `%L` | The 1-based line |
| `%F`, `%P`, `%N` | The path, the directory, the file name (empty without a file) |
| `%M`, `%E` | The file name without the extension, the extension without the dot (empty without a file) |
| `{regex}{replacement}` | The line with every match replaced (`Matcher.appendReplacement` syntax) |

- Braces nest and `\` escapes, so `{a{2}}` is one regex; a brace that does not
  open a complete pair is text.
- `expand` throws `TemplateException` for a regex that does not compile,
  reaches `8 * length + 65,536` reads, overflows the stack, or names a
  missing group.
- Output stops at 1,024 chars; `MarkComments.clean` cuts it to 200.

## Marks

### MarkToggle

`MarkToggle.run(marks, rows, firstLine, count, token, progress)` marks the
rows' lines unless all are marked, then unmarks them, in batches of 4,096 under
one write lock each (`MutableLineSet.addSorted`, `removeSorted`).

- A contiguous range checks "all marked" with two `rank` calls.
- Marking reserves from `RESULTS` and stops at the first batch the share
  refuses; cancel stops between batches. Lines changed before either stay
  changed.
- The overload with a `MarkComments` removes the comments of the lines it
  unmarks.

### MarkComments

`MarkComments` maps a marked line to one single-line comment. Every method
holds its monitor, so the gutter reads it on the UI thread while a toggle
removes comments on a worker.

- `clean` replaces control chars (C0, DEL, C1, U+2028, U+2029) with spaces,
  strips the ends and cuts to `MAX_LENGTH` (200) chars.
- `put` with text that cleans to empty removes the comment.
- Each comment reserves 96 bytes plus 2 per char from `RESULTS`. `put`
  refuses a comment the share cannot hold or one past `MAX_COMMENTS` (1,000
  per document) and changes nothing.
- The class does not check marks: the owner removes a comment with its mark,
  and `addAll` skips comments on unmarked lines.

## Overview bins

`OverviewBins.compute(matches, marks, lines, token)` counts the lines of each
set per pixel row as differences of `rank` calls: a few lookups per row,
whatever the set sizes. `lines` holds each pixel row's first line, then the
line after the last row, so a row covers any span. Cost: 8 bytes per pixel row
plus 64.

`lineAt(y)` is `floor(y * lineCount / height)`, exact for every `long` line
count; `rowOf(line)` is its inverse.

## Filtered view

`FilteredView` maps the filtered view's rows to lines over a result set and the
marks, per `Visibility`, without copying anything per row. `next()` cycles the
modes in table order:

| Visibility | Rows |
|---|---|
| `MARKS_AND_MATCHES` | The matches plus an `Extras` snapshot of the marks that were not matches when it was built; a row's line is a binary search over line values on the sum of the two ranks |
| `MARKS` | `select` and `rank` on the marks; no context lines |
| `MATCHES` | `select` and `rank` on the matches |

### Extras snapshot

The `Extras` snapshot is a `MutableLineSet` that reserves from `RESULTS`; it is
partial when the share refuses.

- `buildExtras` walks the marks in batches (`MutableLineSet.fill`) on a
  worker; `installExtras` puts it in place and releases the previous one.
- `extrasStale` reports a change of the marks' `version()` or the matches.
- Until the next snapshot is installed, marks toggled meanwhile are not listed
  and a mark the matches gained meanwhile counts twice.

### Rows, context and threads

- `setContext(before, after, lineCount)` lists each match with the lines
  around it through `ContextRows`; `rows()` is the set the `MATCHES` and
  `MARKS_AND_MATCHES` modes list in place of the matches.
- `isContext(line)` and `startsGroup(line)` tell the painter which rows to
  shade and where a gap lies above a row.
- `rowOf(line)` returns the row of a line or of the next listed line.
- The class is confined to one thread. `cursor()` returns a thread-safe
  `RowCursor` (`next`, `previous` over the sets the mode lists) for a worker;
  copy and quick find walk rows through it. `RowCursor.all(lineCount)` walks
  every line.

### ContextRows

`ContextRows` is a `LineSet` over the matches widened by `before` and `after`
lines, windows merged and cut to the live line count, so the last match's
context extends as the file grows. It stores no row: match `j` adds
`min(before + after + 1, m(j) - m(j - 1))` lines, and a checkpoint of that sum
every 64 matches (8 B, charged to `RESULTS`) lets `rank` and `select` walk at
most 64 matches. `contains`, `ceiling` and `floor` take one match lookup.

- An instance covers the matches its build saw. `extend` builds the next one
  on a worker from where the last stopped, sharing and growing the checkpoint
  array, and starts over when the set changed below the last covered match
  (`stale()` compares the count and the last covered line). The view shows the
  installed instance until `installContext` replaces it.
- A refused checkpoint stops the build at the matches covered so far
  (`complete()` false); the view lists those and does not retry.
- `FilteredView` releases the reservation when it replaces or drops an
  instance; `reservedBeyond` is what a dropped build charged itself.
