package com.gathin.tvbridge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** The continue-watching list: newest first, one entry per video, finished videos dropped. */
final class History {

    static final int MAX_ITEMS = 20;
    /** Below this there is nothing worth resuming (and SmartTube reports 0 while a video loads). */
    static final long MIN_POSITION_MS = 10_000;

    private final List<WatchItem> items = new ArrayList<>();
    private final List<String> removedKeys = new ArrayList<>();

    /** Items newest first. */
    List<WatchItem> items() {
        return new ArrayList<>(items);
    }

    /** Loads stored items, assumed newest first. */
    void restore(List<WatchItem> stored) {
        items.clear();
        items.addAll(stored);
    }

    /** Applies a snapshot. Returns true when the list changed. */
    boolean record(WatchItem snap) {
        String key = snap.key();
        int at = indexOf(key);
        if (isFinished(snap.positionMs, snap.durationMs)) {
            if (at < 0) return false;
            removedKeys.add(items.remove(at).key());
            return true;
        }
        if (snap.positionMs < MIN_POSITION_MS) return false;
        if (at >= 0) {
            WatchItem old = items.remove(at);
            // A player may report less the second time (SmartTube opened from a link has no title).
            if (snap.title == null) snap.title = old.title;
            if (snap.subtitle == null) snap.subtitle = old.subtitle;
            if (snap.artUrl == null) snap.artUrl = old.artUrl;
            if (snap.durationMs <= 0) snap.durationMs = old.durationMs;
        }
        items.add(0, snap);
        while (items.size() > MAX_ITEMS) removedKeys.add(items.remove(items.size() - 1).key());
        return true;
    }

    /** Sets the title and subtitle of an entry that has none, keeping its place. Returns true when it changed. */
    boolean fillIn(String key, String title, String subtitle) {
        int at = indexOf(key);
        if (at < 0 || items.get(at).title != null) return false;
        WatchItem w = items.get(at);
        w.title = title;
        if (w.subtitle == null) w.subtitle = subtitle;
        return true;
    }

    /** Removes an entry by key. Returns true when it was there. */
    boolean remove(String key) {
        int at = indexOf(key);
        if (at < 0) return false;
        removedKeys.add(items.remove(at).key());
        return true;
    }

    /** Keys dropped since the last call, so their artwork can be deleted. */
    List<String> takeRemovedKeys() {
        List<String> out = new ArrayList<>(removedKeys);
        removedKeys.clear();
        // A key that came back after being dropped still has a live entry: keep its artwork.
        for (Iterator<String> it = out.iterator(); it.hasNext(); ) {
            if (indexOf(it.next()) >= 0) it.remove();
        }
        return out;
    }

    /** In the last 30 s, or the last 5 % of something long. Unknown duration is never finished. */
    static boolean isFinished(long positionMs, long durationMs) {
        if (durationMs <= 0) return false;
        long margin = Math.max(30_000, durationMs / 20);
        return positionMs >= durationMs - margin;
    }

    /** Where playback is now, given the last reported position (PlaybackState semantics). */
    static long positionAt(long positionMs, long updatedAtMs, float speed, boolean playing, long nowMs) {
        if (!playing || updatedAtMs <= 0) return positionMs;
        return positionMs + (long) ((nowMs - updatedAtMs) * speed);
    }

    private int indexOf(String key) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equals(key)) return i;
        }
        return -1;
    }
}
