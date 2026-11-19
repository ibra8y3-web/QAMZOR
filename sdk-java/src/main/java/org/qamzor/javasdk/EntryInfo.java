package org.qamzor.javasdk;

/** One archive entry. {@code type} is {@code "file"} or {@code "directory"}. */
public record EntryInfo(String path, String type, long size) {
    public boolean isDirectory() {
        return "directory".equals(type);
    }
}
