package com.example.plugin;

import com.example.plugin.model.FileChangeEvent;
import com.example.plugin.model.TriggerInfo;
import com.example.plugin.model.TriggerStatus;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Thread-safe wrapper around a blocking queue that tracks trigger lifecycle
 * (QUEUED → RUNNING → DONE) and exposes the visible list for the bottom panel.
 */
public class TriggerQueue {

    private final BlockingQueue<TriggerInfo> queue = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<TriggerInfo> allTriggers = new CopyOnWriteArrayList<>();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    /** Listener notified whenever the trigger list changes. */
    private volatile Runnable onChange;

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /**
     * Enqueue a file change event, creating a new trigger with QUEUED status.
     */
    public void enqueue(FileChangeEvent event) {
        TriggerInfo trigger = new TriggerInfo(idCounter.getAndIncrement(), event);
        allTriggers.add(trigger);
        queue.offer(trigger);
        fireChange();
    }

    /**
     * Blocking take — returns the next trigger and marks it RUNNING.
     */
    public TriggerInfo takeNext() throws InterruptedException {
        TriggerInfo trigger = queue.take();
        trigger.setStatus(TriggerStatus.RUNNING);
        fireChange();
        return trigger;
    }

    /**
     * Mark a trigger as done and remove it from the visible list.
     */
    public void markDone(int triggerId) {
        allTriggers.removeIf(t -> t.getId() == triggerId);
        fireChange();
    }

    /**
     * Returns a snapshot of QUEUED + RUNNING triggers (most recent first).
     */
    public List<TriggerInfo> getVisibleTriggers() {
        return allTriggers.stream()
                .filter(t -> t.getStatus() != TriggerStatus.DONE)
                .collect(Collectors.toList());
    }

    private void fireChange() {
        Runnable cb = onChange;
        if (cb != null)
            cb.run();
    }
}
