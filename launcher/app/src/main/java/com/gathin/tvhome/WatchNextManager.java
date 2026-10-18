package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Items for the continue-watching shelf: what the bridge app (launcher/bridge) recorded from the
 * players' media sessions and fetched from Jellyfin. Empty when the bridge isn't installed or this
 * build isn't signed with the same key.
 */
final class WatchNextManager {

    private static final String TAG = "WatchNext";
    /** The bridge's history (launcher/bridge HistoryProvider). */
    static final Uri BRIDGE_ITEMS = Uri.parse("content://com.gathin.tvbridge.history/items");

    static final class WatchItem {
        final String key;
        final String pkg;
        /** The app's name, for the badge. */
        final String source;
        final String title;
        final String subtitle;
        /** Ready-to-start intent (URI form) that reopens the item in its app. */
        final String intentUri;
        final Uri artUri;
        final long positionMs;
        final long durationMs;

        WatchItem(String key, String pkg, String source, String title, String subtitle, String intentUri,
                  Uri artUri, long positionMs, long durationMs) {
            this.key = key;
            this.pkg = pkg;
            this.source = source;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle;
            this.intentUri = intentUri;
            this.artUri = artUri;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }

        int progress() {
            return durationMs > 0 ? (int) Math.min(100, positionMs * 100 / durationMs) : 0;
        }

        long remainingMs() {
            return durationMs > 0 ? Math.max(0, durationMs - positionMs) : 0;
        }
    }

    private WatchNextManager() {
    }

    static List<WatchItem> getItems(Context ctx) {
        List<WatchItem> list = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        try (Cursor c = ctx.getContentResolver().query(BRIDGE_ITEMS, null, null, null, null)) {
            if (c == null) return list;
            while (c.moveToNext()) {
                String pkg = c.getString(c.getColumnIndexOrThrow("package"));
                String art = c.getString(c.getColumnIndexOrThrow("art_uri"));
                list.add(new WatchItem(
                        c.getString(c.getColumnIndexOrThrow("key")),
                        pkg,
                        appLabel(pm, pkg),
                        c.getString(c.getColumnIndexOrThrow("title")),
                        c.getString(c.getColumnIndexOrThrow("subtitle")),
                        c.getString(c.getColumnIndexOrThrow("intent")),
                        art != null ? Uri.parse(art) : null,
                        c.getLong(c.getColumnIndexOrThrow("position_ms")),
                        c.getLong(c.getColumnIndexOrThrow("duration_ms"))));
            }
        } catch (Exception e) {
            Log.i(TAG, "bridge history unavailable: " + e.getMessage());
        }
        return list;
    }

    /** Drops an item from the bridge history; the shelf reloads through the change notification. */
    static void remove(Context ctx, WatchItem item) {
        try {
            ctx.getContentResolver().delete(Uri.withAppendedPath(BRIDGE_ITEMS, Uri.encode(item.key)), null, null);
        } catch (Exception e) {
            Log.w(TAG, "could not remove " + item.key, e);
        }
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
