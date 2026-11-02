package io.github.ajdailey.qlogg.core.text;

/**
 * Result of {@link EncodingDetector}.
 *
 * @param encoding the chosen encoding
 * @param reason the rule that chose it
 */
public record EncodingDetection(TextEncoding encoding, DetectionReason reason) {}
