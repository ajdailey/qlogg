package io.github.ajdailey.qlogg.app.cli;

import java.nio.file.Path;

/**
 * A file to open.
 *
 * @param path the path as given, resolved against the working directory and not yet validated
 * @param line the 1-based line to open at, or 0 when not given
 */
public record FileRequest(Path path, long line) {}
