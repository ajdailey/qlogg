package io.github.ajdailey.qlogg.core.rotate;

import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.file.Path;

/**
 * One file of a rotated set as discovery found it.
 *
 * @param path the file, in the set's directory; a symbolic link stays a link
 * @param token the rotation token, or null for the base file
 * @param modifiedMillis last-modified time at discovery
 * @param size bytes at discovery
 * @param encoding the set's charset with this file's own byte order mark length
 */
public record Member(
    Path path, String token, long modifiedMillis, long size, TextEncoding encoding) {}
