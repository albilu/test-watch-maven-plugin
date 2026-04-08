package org.testwatch.plugin.model;

public enum WatchEventType {
    /**
     * A specific set of files changed. Event carries a non-null, non-empty path
     * set.
     */
    CHANGED,
    /** Sentinel: run all tests. No path payload. */
    ALL,
    /** Sentinel: rerun last-failed tests only. No path payload. */
    FAILED
}
