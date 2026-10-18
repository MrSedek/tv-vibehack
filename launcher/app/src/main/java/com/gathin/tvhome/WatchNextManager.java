package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the "Continue Watching" (Watch Next) shelf.
 * Synced via VibeBridge, Android MediaSession, or populated with user history.
 */
public class WatchNextManager {

    private static final String PREFS_NAME = "tvhome_watch_next";
    private static final String KEY_ITEMS = "items_json";

    public static class WatchItem {
        public final String title;
        public final String source;
        public final String url;
        public final int progress; // 0..100

        public WatchItem(String title, String source, String url, int progress) {
            this.title = title;
            this.source = source;
            this.url = url;
            this.progress = Math.max(0, Math.min(100, progress));
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
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_ITEMS, null);
        List<WatchItem> list = new ArrayList<>();
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    list.add(WatchItem.fromJson(arr.getJSONObject(i)));
                }
                return list;
            } catch (Exception ignored) {
            }
        }

        // Default demo / starter items to immediately show the feature
        list.add(new WatchItem("SmartTube: Новые ролики", "SmartTube", "https://youtube.com", 65));
        list.add(new WatchItem("Кинопоиск / Фильм", "Кино и ТВ", "", 42));
        list.add(new WatchItem("Веб-трансляция", "VibeBridge", "http://127.0.0.1:8989", 80));
        saveItems(ctx, list);
        return list;
    }

    public static void saveItems(Context ctx, List<WatchItem> items) {
        JSONArray arr = new JSONArray();
        for (WatchItem it : items) {
            arr.put(it.toJson());
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    public static void addItem(Context ctx, WatchItem item) {
        List<WatchItem> current = getItems(ctx);
        // Put newest at the front
        current.add(0, item);
        if (current.size() > 10) current = current.subList(0, 10);
        saveItems(ctx, current);
    }

    public static void removeItem(Context ctx, int index) {
        List<WatchItem> current = getItems(ctx);
        if (index >= 0 && index < current.size()) {
            current.remove(index);
            saveItems(ctx, current);
        }
    }
}
