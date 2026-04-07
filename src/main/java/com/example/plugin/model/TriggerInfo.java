package com.example.plugin.model;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents a queued or running test trigger in the watch queue.
 */
public class TriggerInfo {

    private final int id;
    private final String description;
    private volatile TriggerStatus status;
    private final FileChangeEvent event;

    public TriggerInfo(int id, FileChangeEvent event) {
        this.id = id;
        this.event = event;
        this.status = TriggerStatus.QUEUED;
        this.description = buildDescription(event);
    }

    public int getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public TriggerStatus getStatus() {
        return status;
    }

    public FileChangeEvent getEvent() {
        return event;
    }

    public void setStatus(TriggerStatus status) {
        this.status = status;
    }

    private static String buildDescription(FileChangeEvent event) {
        switch (event.getType()) {
            case ALL:
                return "all tests";
            case FAILED:
                return "failed tests";
            case CHANGED:
                Set<Path> files = event.getChangedFiles();
                if (files.isEmpty())
                    return "unknown";
                String names = files.stream()
                        .map(p -> p.getFileName().toString())
                        .limit(3)
                        .collect(Collectors.joining(", "));
                if (files.size() > 3) {
                    names += " +" + (files.size() - 3) + " more";
                }
                return names;
            default:
                return "unknown";
        }
    }

    @Override
    public String toString() {
        return "Trigger " + id + ": " + description + " (" + status.name().toLowerCase() + ")";
    }
}
