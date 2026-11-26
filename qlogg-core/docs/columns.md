# Columns

`core.columns` splits a record into column values for the built-in formats.
It depends on `core.text`, `core.format` and `core.time`, not on `core.model`
or `core.search`. Parsers are hand-written scanners: no regex, so no input
backtracks, and no XML parser, so nothing reads a DTD, an entity declaration
or an external reference. Module overview: [qlogg-core](../README.md).

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
name is cut at 256 chars.

## Field parsers

`ColumnSchema.parser(TimeContext)` creates a `FieldParser` for one thread.
`parse(record)` returns false for a record that does not parse, which shows
its raw text across all columns. After a parse, `values()` gives each
column's span in one reused buffer, and `nanos()` the Time column's instant
(`TimestampParser.NONE` without one). The next parse overwrites the buffer and
every view `FieldValues.value` returned.

- A parser reads each record char a bounded number of times through `charAt`,
  so a cancelable `CharSequence` cancels it, and grows only its reused buffers.
- Values stop at `FieldValues.MAX_CHARS` (1 Mi chars); `truncated()` reports
  the cut.
- A newline inside a value stays in the value.

| Parser | Rules |
|---|---|
| CSV, TSV | RFC 4180 quoting. A quote opens a quoted field only at a field start; elsewhere it is literal, so a stray quote does not join lines. `""` inside quotes is one quote. Fields past the last column join it with the delimiter; missing fields are empty. A leading BOM is skipped. Every record parses |
| `clf` | `host ident user [time] "request" status size`, optionally `"referer" "user agent"`. Quoted values stay as written, backslash escapes included; `-` alone is empty. Trailing text or any other shape does not parse |
| `clef` | One JSON object; its top-level members. Message is `@m`, else `@mt` with `{Name}`, `{@Name}`, `{$Name}`, `{Name:format}` and `{Name,width}` replaced by the property's value and `{{` `}}` unescaped; the first 1,024 tokens are replaced, an unknown name stays as written. Level defaults to `Information`. Properties lists the members not named with a single leading `@` as `name=value`, a `@@` name with one `@`. Nested values stay raw text, at most 64 deep; deeper, a malformed object or trailing text does not parse. Strings unescape through `JsonStrings`, a malformed escape kept as written |
| `log4j-xml` | The record must start with the `<log4j:event` start tag. Attributes `timestamp` (1 to 13 digits of epoch milliseconds), `level`, `logger`, `thread`; text of `log4j:message`, `log4j:throwable` and `log4j:NDC`, CDATA as written and other text through `XmlEntities`; `log4j:locationInfo` as `class.method(file:line)`; `log4j:data` pairs as `name=value`. Other elements, comments and text between elements are skipped; a record cut before `</log4j:event>` keeps what it holds |
| `time` | Time is the line up to `TimestampParser.end()`, Text the rest after a `]` closing a leading `[` and the blanks after it. A line without a stamp does not parse |

## Detection

`ColumnDetector.detect(head, encoding, fileName, context)` reads the first 200
non-empty lines (CSV and TSV: records) of the head `LogDocument.open` reads.
The first rule that matches wins; text logs match none.

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
