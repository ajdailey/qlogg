# Log view

`LogCanvas` paints one `LineViewController` snapshot, the main view's or the
filtered view's; one implementation serves both. `TextGrid` holds the
geometry, `RowPainter` paints rows, `PaletteColors` caches colors, `SwtKeys`
translates keys and `OverviewBar` paints the bar beside the main view. Module
overview: [../README.md](../README.md).

## Painting

`LogCanvas` (`SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND`) paints the visible rows
of the snapshot's `LineBlock` with their highlight spans, a bullet column, each
row's source line number in a gutter sized to the document's line count, the
current line and the selection.

- Rows the block does not hold yet stay blank, numbers included, until the
  next read arrives, so painting never touches the file.
- The bullet column shows a mark bullet for a marked line (square when the mark
  has a comment), else a match bullet for a line in the shown results. Context
  rows carry no match bullet.
- With context lines on, a context row (`LineViewController.contextRow`) is
  shaded, and a rule in the line-number color is drawn across the text area on
  top of each row that starts a group (`startsGroup`).
- A collapsed entry's row has a triangle at the gutter's right edge and a rule
  under it.
- The first paint in the process that draws a loaded line logs `First paint of
  file text` at DEBUG; the benchmark harness's `startup` command waits for that
  line.

### Text grid

Text sits on a grid of the `FontSettings` font: an empty family is Monospace,
Consolas or Menlo; bold and forced text anti-aliasing apply when set.
`TextGrid` has no SWT types. Its column advance is the `GC.textExtent` width of
1000 digits divided by 1000, kept fractional, and column `c` sits at
`round(c * advance)`. It also sizes the gutter and maps points to columns
(`hit`, `wrappedHit`, `wrapFit`). Hit testing always uses the grid.

A printable-ASCII row is drawn as one string with the font's own advances, so
an advance rounded to whole pixels would drift off the glyphs by the fraction
per column (macOS Menlo, GTK 4, fractional Windows scaling).

### Row painting

`RowPainter` paints each screen row, in the plain and the wrapped paint alike,
in this order: the background (base, or the palette's `contextRow` for a
context line, then the current line, then the whole-line highlight), the span
backgrounds, the selection, the text in the line's color, the text again
clipped to each span with a text color and to the selection, then the
whitespace markers. A translucent color is blended over the color under it.

### Rows outside printable ASCII

A row outside printable ASCII is laid out in a `TextLayout`, which draws it. A
column table read from the layout (`getLocation`) places the spans, the
selection and the markers on its glyphs. An entry is read when its column is
first placed, since each `getLocation` call on GTK walks the line. The painter
keeps a row's layout and table, keyed by the row's text, while each paint draws
it; `LogCanvas` calls `endPaint` after a paint to release the rest, so a
repaint of an unchanged screen lays out nothing.
Do not measure a prefix alone: GTK draws spaces that lead CJK text in the CJK
font of the run they join, so the prefix measures wider than in the row.

- Line and paragraph separators are laid out as spaces so the row stays one
  line.
- The layout takes its line ascent and leading from the tallest font in the
  line. The painter draws it shifted to put its baseline on the GC font's
  (ascent plus leading); drawn at the row's top, a CJK row sits 1-3 px low.
- `GC.setTextAntialias` does not reach a `TextLayout` on GTK, so forced
  anti-aliasing applies to printable-ASCII rows only.

### Whitespace markers

With whitespace markers on, a tab's first cell shows an arrow, a trailing space
a dot and a lone CR a left arrow, each inside its cell. After the line's last
column an LF shows as a corner and a CRLF as a left arrow and a corner. Only
lines whose end lies in the read window get the end marker.

### Resources

The font, `PaletteColors` and the painter's `TextLayout` are disposed with the
canvas, the colors also on a palette change. Painting allocates only a
highlight color seen for the first time; `PaletteColors` keeps at most 512 and
empties the cache before a paint past that.

## Scrolling

The vertical scroll bar is a `Slider` beside the canvas in `LogCanvas.frame()`,
so `addSide` can place a control between the text and the bar; the canvas
keeps its native horizontal bar. The slider takes `Viewport`'s maximum, thumb
and selection and hands focus back to the canvas.

| Input | Effect |
|---|---|
| Slider arrow or page click | Scrolls by lines |
| Slider drag | `setScrollbarSelection`, which maps past `int` range |
| Wheel | Scrolls by the event count |
| `Alt`+wheel | That many columns |
| `Shift`+wheel | One page per notch |
| `Ctrl`+wheel (`Cmd` on macOS) | `zoomFont` |

## Mouse and keys

- Focus, a click, a key and the context menu make the canvas the view commands
  go to (`ApplicationController.setFocusedView`).
- Keys pass through `SwtKeys` to `KeySequencer`. Arrow, page, Escape and Return
  traversal is turned off so those keys reach it.
- A double click selects the word (letters, digits, `_`) under the pointer
  within the decoded column window of a printable-ASCII row
  (`LineViewController.selectWordAt`).
- A left click without `Shift` outside the bullet column calls
  `LineViewController.rowChosen`, so a click in the filtered view is a jump of
  the navigation history.
- A left click in the bullet column toggles the mark of the clicked row's line.
  Over a commented mark's bullet the canvas tooltip shows the comment;
  elsewhere it shows none.
- An `Alt`-drag, or any drag with Edit > Block Selection on, selects a block of
  columns, painted per row between the two corners' columns.
- A drag that leaves the view above or below scrolls one line at once, then one
  line every 50 ms from a `SwtUiDispatcher.timerExec` timer while the button
  stays down outside, extending the selection to the edge row. Release, focus
  loss and dispose cancel the timer.

### Context menu

The right-click menu lists Mark or Unmark, Edit Mark Comment, Copy, Copy This
Line, find the selection forward and backward, add, exclude and replace
search, search start, end and clear, selection start and end, Save Splitter
Position, Copy with Line Numbers, Save Selection to File, Send Selection to
Scratchpad, Toggle Fold, Scroll All Tabs to This Time, and the Highlighters
(None and the sets), Color Label (None and the label names) and Time Sync
submenus. A right click outside the selection first moves the caret to that
row.

The menu is built on the canvas's first `MenuDetect`, not with the canvas: it
costs 35-60 ms per view under GTK 4, which every file open would otherwise pay
twice on the UI thread.

## Text wrap

With text wrap on, each line paints as rows of the view's width in columns,
the number and bullet on its first row, and hit testing walks the wrapped rows.

- A click at or past a row's right edge maps to the boundary that row shares
  with the next. A double click there maps to the row's last column instead
  (`TextGrid.wrappedWordHit`), so it selects the word at the row's end.
- A segment's glyphs wider than the grid run past the right edge, clipped.
- After each new snapshot the canvas counts the lines from the top that fit
  whole, a line not read yet counting one row, and reports it (`wrapFitted`).
- The horizontal scroll bar has nothing to scroll.

## Overview bar

`OverviewBar` (14 px wide) paints the `OverviewController`'s last bins: a mark
line where a pixel row holds marks, a match line over it where it holds
matches, a line down its left edge, and a line at the first and at the last
visible line. Pixel rows split the main view's rows, not the file's lines.

Under the lines lies the time-spread layer: each row of the controller's last
`TimeSpread` is filled from its second column on, in `spreadPause` or
`spreadActivity` at one of four alphas, or in `followStoppedDot` where the
row's time runs backward.

A click or drag shows the line under the pointer. A resize reports the height.
The controller computes nothing while the bar is hidden or after it is
disposed.
