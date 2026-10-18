package com.gathin.tvbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** The continue-watching list: newest first, one entry per video, finished and stale videos dropped. */
final class History {

    static final int MAX_ITEMS = 20;
    /** Below this there is nothing worth resuming (and SmartTube reports 0 while a video loads). */
    static final long MIN_POSITION_MS = 10_000;
    /** A session entry nobody came back to in this long is gone. */
    static final long MAX_AGE_MS = 30L * 24 * 3600 * 1000;

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

    /** Applies a snapshot from a player's media session. Returns true when the list changed. */
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
        trim();
        return true;
    }

    /**
     * Replaces every entry of one app with a server's list (Jellyfin's resume and next-up items), then
     * orders everything by time again. Returns false when the server's list is what we already had.
     */
    boolean replaceSource(String pkg, List<WatchItem> fresh) {
        List<String> before = new ArrayList<>(), after = new ArrayList<>();
        for (WatchItem w : items) {
            if (pkg.equals(w.pkg)) before.add(signature(w));
        }
        for (WatchItem w : fresh) after.add(signature(w));
        if (before.equals(after)) return false;

        for (Iterator<WatchItem> it = items.iterator(); it.hasNext(); ) {
            WatchItem w = it.next();
            if (pkg.equals(w.pkg)) {
                removedKeys.add(w.key());
                it.remove();
            }
        }
        for (WatchItem w : fresh) {
            w.fromServer = true;
            items.add(w);
        }
        Collections.sort(items, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        trim();
        return true;
    }

    /** Drops session entries older than MAX_AGE_MS. Server entries are the server's business. */
    boolean expire(long nowMs) {
        boolean changed = false;
        for (Iterator<WatchItem> it = items.iterator(); it.hasNext(); ) {
            WatchItem w = it.next();
            if (!w.fromServer && w.updatedAt > 0 && nowMs - w.updatedAt > MAX_AGE_MS) {
                removedKeys.add(w.key());
                it.remove();
                changed = true;
            }
        }
        return changed;
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

    private void trim() {
        while (items.size() > MAX_ITEMS) removedKeys.add(items.remove(items.size() - 1).key());
    }

    private static String signature(WatchItem w) {
        return w.key() + "|" + w.positionMs + "|" + w.durationMs + "|" + w.updatedAt + "|" + w.title;
    }

    private int indexOf(String key) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equals(key)) return i;
        }
        return -1;
    }
}
