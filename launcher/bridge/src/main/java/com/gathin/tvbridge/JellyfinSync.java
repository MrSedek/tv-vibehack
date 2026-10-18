package com.gathin.tvbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Jellyfin's own continue-watching list, pulled from the server: the Jellyfin app leaves its media
 * session empty, but the server knows exactly where every film and episode stopped, from every
 * device. Polled every 15 minutes and a few seconds after the Jellyfin app pauses or stops.
 *
 * Configured once over adb (see README):
 * <pre>
 *   am broadcast -n com.gathin.tvbridge/.ConfigReceiver -a com.gathin.tvbridge.CONFIGURE \
 *       --es jellyfin_url http://192.168.1.10:8096 --es jellyfin_key &lt;api key&gt;
 * </pre>
 */
final class JellyfinSync {

    static final String PKG = "org.jellyfin.androidtv";
    private static final String TAG = "TvBridge";
    private static final String PREFS = "jellyfin";
    private static final long PERIOD_MS = 15 * 60_000L;
    private static final int LIMIT = 12;
    private static final String FIELDS = "SeriesName,ParentIndexNumber,IndexNumber,ProductionYear,RunTimeTicks,BackdropImageTags,UserData";
    private static JellyfinSync instance;

    private final Context ctx;
    private final Handler handler;
    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            sync();
            handler.postDelayed(this, PERIOD_MS);
        }
    };
    private final Runnable oneShot = this::sync;

    static synchronized JellyfinSync get(Context ctx) {
        if (instance == null) instance = new JellyfinSync(ctx.getApplicationContext());
        return instance;
    }

    private JellyfinSync(Context ctx) {
        this.ctx = ctx;
        HandlerThread thread = new HandlerThread("jellyfin-sync");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Stores the server and key; an empty url turns the source off. The user id is looked up unless given. */
    static void configure(Context ctx, String url, String key, String userId) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (url != null) e.putString("url", url.trim().replaceAll("/+$", ""));
        if (key != null) e.putString("key", key.trim());
        e.remove("user_id");
        if (userId != null && !userId.trim().isEmpty()) e.putString("user_id", userId.trim());
        e.apply();
        if (url != null && url.trim().isEmpty()) HistoryStore.get(ctx).replaceSource(PKG, new ArrayList<>());
        get(ctx).syncSoon(0);
    }

    static boolean configured(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !p.getString("url", "").isEmpty() && !p.getString("key", "").isEmpty();
    }

    void start() {
        handler.removeCallbacks(periodic);
        handler.post(periodic);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
    }

    void syncSoon(long delayMs) {
        handler.removeCallbacks(oneShot);
        handler.postDelayed(oneShot, delayMs);
    }

    private void sync() {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String base = p.getString("url", ""), key = p.getString("key", "");
        if (base.isEmpty() || key.isEmpty()) return;
        try {
            String userId = p.getString("user_id", "");
            if (userId.isEmpty()) {
                userId = firstUser(base, key);
                if (userId == null) {
                    Log.w(TAG, "jellyfin: no enabled user on " + base);
                    return;
                }
                p.edit().putString("user_id", userId).apply();
            }

            List<WatchItem> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            long now = System.currentTimeMillis(), oldest = now;
            JSONArray resume = new JSONObject(get(base + "/Users/" + userId + "/Items/Resume?Limit=" + LIMIT
                    + "&MediaTypes=Video&Fields=" + FIELDS, key)).optJSONArray("Items");
            for (int i = 0; resume != null && i < resume.length(); i++) {
                WatchItem w = toItem(resume.getJSONObject(i), base, key, now);
                if (!seen.add(w.mediaId)) continue;
                out.add(w);
                oldest = Math.min(oldest, w.updatedAt);
            }
            // Next episodes have no play date of their own; keep them after the resumes, in the server's order.
            JSONArray next = new JSONObject(get(base + "/Shows/NextUp?UserId=" + userId + "&Limit=" + LIMIT
                    + "&Fields=" + FIELDS, key)).optJSONArray("Items");
            for (int i = 0, n = 0; next != null && i < next.length(); i++) {
                WatchItem w = toItem(next.getJSONObject(i), base, key, now);
                if (!seen.add(w.mediaId)) continue;
                w.updatedAt = oldest - 1000L * (++n);
                out.add(w);
            }
            HistoryStore.get(ctx).replaceSource(PKG, out);
            Log.i(TAG, "jellyfin: " + out.size() + " items");
        } catch (Exception e) {
            Log.w(TAG, "jellyfin sync failed: " + e);
        }
    }

    private static String firstUser(String base, String key) throws Exception {
        JSONArray users = new JSONArray(get(base + "/Users", key));
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.getJSONObject(i);
            JSONObject policy = u.optJSONObject("Policy");
            if (policy != null && policy.optBoolean("IsDisabled")) continue;
            return u.getString("Id");
        }
        return null;
    }

    private static WatchItem toItem(JSONObject o, String base, String key, long now) {
        WatchItem w = new WatchItem();
        w.pkg = PKG;
        w.mediaId = o.optString("Id");
        String type = o.optString("Type"), name = o.optString("Name");
        if ("Episode".equals(type)) {
            String series = o.optString("SeriesName", "");
            int season = o.optInt("ParentIndexNumber", 0), episode = o.optInt("IndexNumber", 0);
            w.title = series.isEmpty() ? name : series;
            w.subtitle = (season > 0 && episode > 0 ? "S" + season + "E" + episode + " · " : "") + name;
        } else {
            w.title = name;
            int year = o.optInt("ProductionYear", 0);
            w.subtitle = year > 0 ? String.valueOf(year) : null;
        }
        JSONObject ud = o.optJSONObject("UserData");
        w.positionMs = ud != null ? ud.optLong("PlaybackPositionTicks") / 10_000 : 0;
        w.durationMs = o.optLong("RunTimeTicks") / 10_000;
        w.updatedAt = ud != null ? parseDate(ud.optString("LastPlayedDate", null), now) : now;
        // Episodes have a 16:9 primary image; films a poster, so take their backdrop when there is one.
        JSONArray backdrops = o.optJSONArray("BackdropImageTags");
        String image = "Episode".equals(type) || backdrops == null || backdrops.length() == 0
                ? "/Images/Primary" : "/Images/Backdrop/0";
        w.artUrl = base + "/Items/" + w.mediaId + image + "?maxWidth=400&api_key=" + key;
        w.fromServer = true;
        w.intentUri = new Intent()
                .setComponent(new ComponentName(PKG, PKG + ".ui.startup.StartupActivity"))
                .putExtra("ItemId", w.mediaId)
                .toUri(Intent.URI_INTENT_SCHEME);
        return w;
    }

    private static long parseDate(String s, long fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(s).toInstant().toEpochMilli();
            } catch (Exception e2) {
                return fallback;
            }
        }
    }

    private static String get(String url, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + key
                + "\", Client=\"tv-vibehack bridge\", Device=\"Android TV\", DeviceId=\"tvbridge\", Version=\"1.1\"");
        c.setRequestProperty("X-Emby-Token", key);
        c.setRequestProperty("Accept", "application/json");
        try (InputStream in = c.getInputStream()) {
            return new String(HistoryStore.readAll(in), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
