package org.qamzor.javasdk;

/**
 * A verified preview of one file. The whole file was authenticated even when
 * {@code truncated} is true and {@code data} holds only the first bytes.
 */
public record PreviewResult(String path, byte[] data, long bytesTotal, boolean truncated) { }
