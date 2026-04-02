package com.example.plugin.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

/**
 * Event posted to the WatchLoop queue.
 * <ul>
 *   <li>type=CHANGED: changedFiles is non-empty, contains the modified .java paths</li>
 *   <li>type=ALL or FAILED: changedFiles is empty (sentinel events)</li>
 * </ul>
 */
public class FileChangeEvent {

    private final WatchEventType type;
    private final Set<Path> changedFiles;

    public FileChangeEvent(WatchEventType type, Set<Path> changedFiles) {
        this.type = type;
        this.changedFiles = Collections.unmodifiableSet(changedFiles);
    }

    /** Convenience constructor for sentinel events (ALL, FAILED). */
    public FileChangeEvent(WatchEventType type) {
        this(type, Collections.emptySet());
    }

    public WatchEventType getType() { return type; }
    public Set<Path> getChangedFiles() { return changedFiles; }

    @Override
    public String toString() {
        return "FileChangeEvent{type=" + type + ", files=" + changedFiles + "}";
    }
}
