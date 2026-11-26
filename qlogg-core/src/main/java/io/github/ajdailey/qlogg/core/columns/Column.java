package io.github.ajdailey.qlogg.core.columns;

/**
 * One column of a {@link ColumnSchema}.
 *
 * @param name the header or format name, at most {@link ColumnSchema#MAX_NAME_CHARS} chars
 * @param hidden true when the column is hidden until the user shows it
 */
public record Column(String name, boolean hidden) {}
