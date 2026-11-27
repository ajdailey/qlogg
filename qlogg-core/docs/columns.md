# Columns

`core.columns` splits a record into column values for the built-in formats.
It depends on `core.text`, `core.format` and `core.time`, not on `core.model`
or `core.search`; the record scan and column search are in `core.search`,
the record row map in `core.model`. Parsers are hand-written scanners: no
regex, so no input backtracks, and no XML parser, so nothing reads a DTD, an
entity declaration or an external reference. Module overview:
[qlogg-core](../README.md).

## Formats

| Format | Id | Columns (hidden by default in italics) | Record |
|---|---|---|---|
| CSV | `csv` | Header fields, or `1` to `N` | One line, or several when a quoted field holds a line feed |
| TSV | `tsv` | As CSV | As CSV |
| Apache access log | `clf` | Host, *Ident*, User, Time, Request, Status, Size, Referer, User agent | One line |
| Serilog compact JSON | `clef` | Time, Level, Message, Exception, Properties, *Template*, *Event id* | One line |
| log4j XML | `log4j-xml` | Time, Level, Logger, Thread, Message, Throwable, *NDC*, *Location*, *Properties* | One `<log4j:event>` element over several lines |
| Timestamp | `time` | Time, Text | One line |

`ColumnFormat` holds the id, the fixed columns and `multiLine()`.
`ColumnSchema` adds what a file decides: CSV and TSV columns, delimiter and
header flag, or the timestamp format for `time`. At most 1,024 columns, and a
name is cut at 256 chars. `columnIndex(name)` finds a column by name, ignoring
ASCII case, or by 1-based number, as a search term names it.

## Field parsers

`ColumnSchema.parser(TimeContext)` creates a `FieldParser` for one thread.
`parse(record)` returns false for a record that does not parse, which shows
its raw text across all columns. After a parse, `values()` gives each
column's span in one reused buffer, and `nanos()` the Time column's instant
(`TimestampParser.NONE` without one). The next parse overwrites the buffer and
every view `FieldValues.value` returned.

- A parser reads each record char a bounded number of times through `charAt`,
  so a cancelable `CharSequence` cancels it, and grows only its reused buffers.
  The CSV and TSV parser reads an array-backed `CharBuffer` from its array,
  and copies any other record into a reused array first; a search hands every
  parser the decoded record as such a buffer (`LineText.charBuffer`).
- Values stop at `FieldValues.MAX_CHARS` (1 Mi chars); `truncated()` reports
  the cut.
- A newline inside a value stays in the value.

| Parser | Rules |
|---|---|
| CSV, TSV | RFC 4180 quoting. A quote opens a quoted field only at a field start; elsewhere it is literal, so a stray quote does not join lines. `""` inside quotes is one quote. Fields past the last column join it with the delimiter; missing fields are empty. A leading BOM is skipped. Every record parses |
| `clf` | `host ident user [time] "request" status size`, optionally `"referer" "user agent"`. Quoted values stay as written, backslash escapes included; `-` alone is empty. Trailing text or any other shape does not parse |
| `clef` | One JSON object; its top-level members. Message is `@m`, else `@mt` with `{Name}`, `{@Name}`, `{$Name}`, `{Name:format}` and `{Name,width}` replaced by the property's value and `{{` `}}` unescaped; the first 1,024 tokens are replaced, an unknown name stays as written. Level defaults to `Information`. Properties lists the members not named with a single leading `@` as `name=value`, a `@@` name with one `@`. Nested values stay raw text, at most 64 deep; deeper, a malformed object or trailing text does not parse. Strings unescape through `JsonStrings`, a malformed escape kept as written |
| `log4j-xml` | The record must start with the `<log4j:event` start tag. Attributes `timestamp` (1 to 13 digits of epoch milliseconds), `level`, `logger`, `thread`; text of `log4j:message`, `log4j:throwable` and `log4j:NDC`, CDATA as written and other text through `XmlReferences`; `log4j:locationInfo` as `class.method(file:line)`; `log4j:data` pairs as `name=value`. Other elements, comments and text between elements are skipped; a record cut before `</log4j:event>` keeps what it holds |
| `time` | Time is the line up to `TimestampParser.end()`, Text the rest after a `]` closing a leading `[` and the blanks after it. A line without a stamp does not parse |

## Detection

`ColumnDetector.detect(head, encoding, fileName, context)` reads the first 200
non-empty lines (CSV and TSV: records) of the head `LogDocument.open` reads:
the file's first 64 KiB, or a rotated set's newest member's with bytes, with
the set's base name. `LogDocument.columns()` returns the result and `head()`
the head, which the owner passes to `ColumnDetector.schema` for a format the
user chose. The first rule that matches wins; text logs match none.

| Format | File name | Content |
|---|---|---|
| `log4j-xml` | any | A line whose first non-blank chars are `<log4j:event` |
| `clef` | any | At least 3 lines, 80% of them objects with a top-level `@t` |
| `clf` | any | At least 3 lines, 80% of them parse |
| `csv` | `.csv` | At least 2 fields, the same count in 90% of the records |
| `tsv` | `.tsv`, `.tab` | As `csv` |

- The CSV delimiter is `,`, `;` or `|`, whichever gives the most records with
  the most common field count.
- The first record is the header unless a field in it is empty, repeated
  (ignoring case), a number or a built-in timestamp.
- A head of 64 KiB is cut from a longer file, so its unterminated last line
  is left out.
- `time` is never detected. `ColumnDetector.schema` builds the schema of a
  format the user chose, with the header guess or its override.

## Records

`RecordScan` (`core.search`) finds the records of a multi-line format in one
pass over the complete lines, on the caller's thread, and `scan` again extends
it from where it stopped as the index grows. Its `MutableLineSet` charges
`RESULTS`:

| Format | Set | A line continues the record when |
|---|---|---|
| CSV, TSV | Continuation lines, usually none | It starts inside a quoted field, by the parser's quote rules |
| `log4j-xml` | Record starts, line 0 included | Its first chars other than spaces and tabs are not `<log4j:event` |

- A record holds at most 10,000 lines (`MAX_RECORD_LINES`), and a line
  starting 1 MiB (`MAX_RECORD_BYTES`) or more after the record's first byte
  starts a new one, so a stray quote cannot make the rest of the file one
  record. `RecordBounds` applies these bounds and the forced starts below.
- The scan reads blocks of whole lines into one buffer from the caller's
  `ReadBuffers`. A CSV block with no quote byte, starting outside quotes, is
  counted with `ByteScan` alone. Otherwise, in a one-byte encoding, quotes
  and LFs are read 8 bytes at a time and a line whose last quote follows a
  byte other than a quote or the delimiter ends outside quotes, whatever state
  it started in; only the other lines with quotes run the quote rules char by
  char. A line whose leading blanks fill a block is not an event start.
- A line in the forced starts `scan` takes starts a record outside quotes.
  `LogDocument.scanRecords` passes a rotated set's member starts
  (`memberStarts`), so no record spans members, and reads through the
  indexing buffers (`Indexer.buffers()`).
- A partial last line is left for the next pass. A `RESULTS` refusal stops the
  scan for good (`refused()`); lines from the refused entry on stay
  unclassified.
- The scan compares encoded units. In a charset where the quote, delimiter,
  space, tab or event tag does not encode to one unit per char, every line is
  a record.

`snapshot()` returns `RecordLines` over the set and the classified line count;
lines from that count on are one record each, and set entries past it are not
read, so the snapshot stays valid while the scan goes on. A search
([Record search](search.md#record-search)) and `RecordRows` (`core.model`)
read it. `RecordRows` maps rows to record first lines by `rank` and `select`
on the set, or on its complement (a binary search over `rank`) for a
continuation set, with the `rowOf`, `lineAt` and `cursor` calls `FoldMap` has.

## Column search

A column term tests one column's value; see
[Column terms](search.md#column-terms). On a verbatim column the prefilter
(`RecordEscapes.mayHold`) parses a record only when its bytes can put the
term's literal in a value. A CSV, TSV or log4j value is a run of the record's
chars with markup removed or replaced, so an occurrence of the literal that the
bytes lack spans such markup, and its chars before the first one are the
bytes right before it:

| Format | Parsed when the record holds the literal or | Columns not verbatim |
|---|---|---|
| CSV, TSV | A quote right after a proper prefix of the literal | None |
| `clf` | Nothing else | None |
| `clef` | `\` | Level (defaulted), Message (rendered from `@mt`), Properties |
| `log4j-xml` | `<![CDATA[`, `]]>` or a reference `&...;` right after a proper prefix of the literal; a reference whose char starts the literal | Location, Properties |
| `time` | Nothing else | None |

The CSV and log4j tests find the literal and the markup in one pass, 8 bytes
at a time, in a byte-aligned, ASCII-compatible encoding. In any other encoding
a record is parsed when it holds the literal or any of
`ColumnSchema.escapes()` (`"` for CSV and TSV; `&` and `<![CDATA[` for
`log4j-xml`).

## Column-view rows

`CellReader` (`core.model`) reads the column view's visible rows through a
`LineReader`. A `ColumnRead` carries what it needs besides the lines:

| Component | Content |
|---|---|
| `layout` | `ColumnLayout`: the schema columns shown, in order, each width in grid columns, and the frozen cell count. `ColumnLayout.of` shows every column not hidden by default: Time 24, Message, Text and Exception 100, the others 16 or their name's length plus one, at most 60 |
| `context` | The zone and file date the parser reads stamps with |
| `time` | `TimeCell`: the display zone and the tab's time offset |
| `records` | The `RecordLines` of a multi-line format, or null when a line is a record |
| `headers` | A rotated set's repeated member headers |

- A row's line reads as the whole record holding it, from the record's first
  line (`RecordLines.startOf`), its lines joined by LF and cut at
  `LineReader.MAX_LINE_BYTES` chars, so a filter tab row on a continuation
  line shows its record. `record(index, line, view)` returns that parse as a
  `CellRecord`; copy, Show Record and quick find read through it.
- A parsed record composes into cells: each value cut to its cell's width
  less one column and padded, the last column left for the rule between
  cells. A tab in a value shows as spaces to the cell's next tab stop and a
  line feed as its control box. A record that does not parse shows its raw
  text from column 0 across every cell, as wide as it is; CSV fields
  past the last column are already joined into it by the parser.
- The Time cell shows the parsed instant plus the offset in the display zone
  (`TimeCell`), `yyyy-MM-dd HH:mm:ss.SSS` with more digits when the stamp
  has them; a stamp that did not parse shows as written.
- Row text holds the frozen cells, then the window of the scrolled cells;
  `LineBlock.frozenColumns()` gives the split, `cellCount` and `cellStart` the
  cells in composed columns (one array shared by every parsed row).
  `unparsed`, `header` (line 0 of a file with a header line, and the repeated
  headers) and `cut` flag a row. A record is cut, and shows the truncation
  marker, when it reached `MAX_RECORD_LINES` lines or `MAX_RECORD_BYTES` bytes,
  where the scan starts a new record whatever the content, or when its text
  was cut.
- Highlights: a whole-line rule tests the raw record, or the value of the
  column it names; a match-only rule colors matches inside each shown cell's
  value, or only in its column's cells. A rule naming a column the format
  lacks colors nothing in column view. Syntax tokens run over each cell's
  value. Spans are in composed columns, clipped to each cell's shown chars.
- `LineFinder.find` with a `ColumnRead` searches each shown cell's full value
  in cell order and reports the match in composed columns, cut to the cell's
  shown columns.
- Only the listed rows are read. Finished rows stay in a cache of at most 512
  rows and 512 Ki chars of text and spans, not charged to a share. A row is
  reused while its record has the same lines and byte range and the read has
  the same `ColumnRead`, window, highlighter, syntax-color and ANSI settings;
  any other read empties the cache.
- Cost, measured with `ColumnProbe` (`compose`): see
  [qlogg-bench](../../qlogg-bench/README.md).

