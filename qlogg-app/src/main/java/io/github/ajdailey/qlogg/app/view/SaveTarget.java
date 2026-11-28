package io.github.ajdailey.qlogg.app.view;

import java.nio.file.Path;

/**
 * A save chooser's answer in column view.
 *
 * @param path the chosen path, not yet validated
 * @param original true for each row's lines as in the file, false for the shown cells tab-separated
 */
public record SaveTarget(Path path, boolean original) {}
