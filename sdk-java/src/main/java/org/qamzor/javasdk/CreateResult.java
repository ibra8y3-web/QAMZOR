package org.qamzor.javasdk;

import java.io.File;

/** Outcome of {@link CreateBuilder#execute()}. */
public record CreateResult(File archive, int entries, int files, long originalBytes, long storedBytes) { }
