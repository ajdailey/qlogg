/**
 * Built-in column formats: {@link ColumnFormat}, the {@link ColumnSchema} of a file, a {@link
 * FieldParser} per format and thread, and {@link ColumnDetector}. Parsers are hand-written scanners
 * over decoded chars: no regex and no XML parser, so no input backtracks and nothing reads a DTD or
 * an external reference. Depends on {@code core.text}, {@code core.format} and {@code core.time}.
 */
package io.github.ajdailey.qlogg.core.columns;
