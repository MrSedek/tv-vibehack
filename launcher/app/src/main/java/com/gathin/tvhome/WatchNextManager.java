package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Items for the "Continue Watching" shelf: what the bridge app (launcher/bridge) recorded from the
 * players' media sessions, followed by links sent through VibeBridge.
 */
public class WatchNextManager {

    private static final String TAG = "WatchNext";
    private static final String PREFS_NAME = "tvhome_watch_next";
    // v1 held demo items seeded on first run; starting a new key drops them.
    private static final String KEY_ITEMS = "items_json_v2";

    /** The bridge's history (launcher/bridge HistoryProvider). */
    static final Uri BRIDGE_ITEMS = Uri.parse("content://com.gathin.tvbridge.history/items");

    public static class WatchItem {
        public final String title;
        public final String source;
        public final String url;
        public final int progress; // 0..100

        /** Set for items from the bridge. */
        String key;
        String pkg;
        String subtitle;
        String intentUri;
        Uri artUri;
        long positionMs;
        long durationMs;

        public WatchItem(String title, String source, String url, int progress) {
            this.title = title;
            this.source = source;
            this.url = url;
            this.progress = Math.max(0, Math.min(100, progress));
        }

        boolean fromBridge() {
            return key != null;
        }

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("title", title);
                o.put("source", source);
                o.put("url", url);
                o.put("progress", progress);
                return o;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static WatchItem fromJson(JSONObject o) {
            return new WatchItem(
                    o.optString("title", "Видео"),
                    o.optString("source", "Медиа"),
                    o.optString("url", ""),
                    o.optInt("progress", 0)
            );
        }
    }

    public static List<WatchItem> getItems(Context ctx) {
        List<WatchItem> list = new ArrayList<>(getBridgeItems(ctx));
        list.addAll(getLocalItems(ctx));
        return list;
    }

    /** Empty when the bridge isn't installed or this build isn't signed with the same key. */
    static List<WatchItem> getBridgeItems(Context ctx) {
        List<WatchItem> list = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        try (Cursor c = ctx.getContentResolver().query(BRIDGE_ITEMS, null, null, null, null)) {
            if (c == null) return list;
            while (c.moveToNext()) {
                String pkg = c.getString(c.getColumnIndexOrThrow("package"));
                long pos = c.getLong(c.getColumnIndexOrThrow("position_ms"));
                long dur = c.getLong(c.getColumnIndexOrThrow("duration_ms"));
                String title = c.getString(c.getColumnIndexOrThrow("title"));
                int progress = dur > 0 ? (int) (pos * 100 / dur) : 0;
                WatchItem it = new WatchItem(title != null ? title : "", appLabel(pm, pkg), "", progress);
                it.key = c.getString(c.getColumnIndexOrThrow("key"));
                it.pkg = pkg;
                it.subtitle = c.getString(c.getColumnIndexOrThrow("subtitle"));
                it.intentUri = c.getString(c.getColumnIndexOrThrow("intent"));
                String art = c.getString(c.getColumnIndexOrThrow("art_uri"));
                it.artUri = art != null ? Uri.parse(art) : null;
                it.positionMs = pos;
                it.durationMs = dur;
                list.add(it);
            }
        } catch (Exception e) {
            Log.i(TAG, "bridge history unavailable: " + e.getMessage());
        }
        return list;
    }

    /** Drops an item from the bridge history; the shelf reloads through the change notification. */
    static void removeBridgeItem(Context ctx, WatchItem item) {
        if (!item.fromBridge()) return;
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

    private static List<WatchItem> getLocalItems(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_ITEMS, null);
        List<WatchItem> list = new ArrayList<>();
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    list.add(WatchItem.fromJson(arr.getJSONObject(i)));
                }
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    public static void saveItems(Context ctx, List<WatchItem> items) {
        JSONArray arr = new JSONArray();
        for (WatchItem it : items) {
            if (!it.fromBridge()) arr.put(it.toJson());
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    public static void addItem(Context ctx, WatchItem item) {
        List<WatchItem> current = getLocalItems(ctx);
        // Put newest at the front
        current.add(0, item);
        if (current.size() > 10) current = current.subList(0, 10);
        saveItems(ctx, current);
    }

    public static void removeItem(Context ctx, int index) {
        List<WatchItem> current = getLocalItems(ctx);
        if (index >= 0 && index < current.size()) {
            current.remove(index);
            saveItems(ctx, current);
        }
    }
}
