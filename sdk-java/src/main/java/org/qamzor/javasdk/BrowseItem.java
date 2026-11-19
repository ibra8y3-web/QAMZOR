package org.qamzor.javasdk;

/** An immediate child of a virtual directory inside an archive. */
public record BrowseItem(String path, String name, String type, long size) {
    public boolean isDirectory() {
        return "directory".equals(type);
    }
}
