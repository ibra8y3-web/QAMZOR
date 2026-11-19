package org.qamzor.javasdk;

/** Outcome of a successful full verification. {@code version} is e.g. {@code "1.3"}. */
public record VerifyResult(int filesChecked, long bytesChecked, String version) { }
