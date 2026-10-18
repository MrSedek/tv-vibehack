package com.gathin.tvbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** History persisted as JSON in the app's files, with one small JPEG of artwork per entry. */
final class HistoryStore {

    private static final String TAG = "TvBridge";
    private static final int ART_WIDTH = 400;
    private static HistoryStore instance;

    private final Context ctx;
    private final File file;
    private final File artDir;
    private final History history = new History();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    static synchronized HistoryStore get(Context ctx) {
        if (instance == null) instance = new HistoryStore(ctx.getApplicationContext());
        return instance;
    }

    private HistoryStore(Context ctx) {
        this.ctx = ctx;
        this.file = new File(ctx.getFilesDir(), "history.json");
        this.artDir = new File(ctx.getFilesDir(), "art");
        history.restore(load());
    }

    synchronized List<WatchItem> items() {
        return history.items();
    }

    /**
     * Records a snapshot; the artwork, if any, is fetched in the background. For a YouTube video
     * the player didn't name, the title is looked up by id.
     */
    synchronized void record(WatchItem snap, Bitmap art, boolean youtube) {
        if (!history.record(snap)) return;
        commit();
        if (youtube && snap.title == null) {
            String key = snap.key(), id = snap.mediaId;
            io.execute(() -> fetchYouTubeTitle(key, id));
        }
        File target = artFile(snap.key());
        if (art != null) {
            io.execute(() -> saveArt(target, art));
        } else if (!target.exists() && snap.artUrl != null && snap.artUrl.startsWith("http")) {
            String url = snap.artUrl;
            io.execute(() -> fetchArt(target, url));
        }
    }

    synchronized boolean remove(String key) {
        if (!history.remove(key)) return false;
        commit();
        return true;
    }

    /** Artwork for an entry, by the hash used in content URIs. */
    File artFileForHash(String hash) {
        return new File(artDir, hash + ".jpg");
    }

    File artFile(String key) {
        return artFileForHash(hash(key));
    }

    static String hash(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private void commit() {
        for (String gone : history.takeRemovedKeys()) {
            //noinspection ResultOfMethodCallIgnored
            artFile(gone).delete();
        }
        save(history.items());
        HistoryProvider.notifyChanged(ctx);
    }

    // ---- JSON ------------------------------------------------------------------------------------

    private List<WatchItem> load() {
        List<WatchItem> out = new ArrayList<>();
        if (!file.exists()) return out;
        try {
            JSONArray arr = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                WatchItem w = new WatchItem();
                w.pkg = o.getString("pkg");
                w.mediaId = o.optString("mediaId", null);
                w.title = o.optString("title", null);
                w.subtitle = o.optString("subtitle", null);
                w.artUrl = o.optString("artUrl", null);
                w.durationMs = o.optLong("durationMs");
                w.positionMs = o.optLong("positionMs");
                w.updatedAt = o.optLong("updatedAt");
                out.add(w);
            }
        } catch (Exception e) {
            Log.w(TAG, "history.json unreadable, starting empty", e);
        }
        return out;
    }

    private void save(List<WatchItem> items) {
        try {
            JSONArray arr = new JSONArray();
            for (WatchItem w : items) {
                JSONObject o = new JSONObject();
                o.put("pkg", w.pkg);
                o.putOpt("mediaId", w.mediaId);
                o.putOpt("title", w.title);
                o.putOpt("subtitle", w.subtitle);
                o.putOpt("artUrl", w.artUrl);
                o.put("durationMs", w.durationMs);
                o.put("positionMs", w.positionMs);
                o.put("updatedAt", w.updatedAt);
                arr.put(o);
            }
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) Log.w(TAG, "could not replace history.json");
        } catch (Exception e) {
            Log.w(TAG, "could not save history", e);
        }
    }

    private synchronized void fillIn(String key, String title, String subtitle) {
        if (history.fillIn(key, title, subtitle)) commit();
    }

    /** YouTube's oEmbed needs no key: the title and channel of a public video. */
    private void fetchYouTubeTitle(String key, String videoId) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D" + videoId).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                JSONObject o = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                String title = o.optString("title", null);
                if (title != null && !title.isEmpty()) fillIn(key, title, o.optString("author_name", null));
            }
        } catch (Exception e) {
            Log.i(TAG, "no title for " + videoId + ": " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---- artwork ---------------------------------------------------------------------------------

    private void fetchArt(File target, String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp != null) saveArt(target, bmp);
            }
        } catch (Exception e) {
            Log.i(TAG, "no artwork from " + url + ": " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void saveArt(File target, Bitmap src) {
        try {
            //noinspection ResultOfMethodCallIgnored
            artDir.mkdirs();
            Bitmap bmp = src;
            if (src.getWidth() > ART_WIDTH) {
                int h = Math.round(src.getHeight() * (ART_WIDTH / (float) src.getWidth()));
                bmp = Bitmap.createScaledBitmap(src, ART_WIDTH, Math.max(1, h), true);
            }
            File tmp = new File(target.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            }
            if (tmp.renameTo(target)) HistoryProvider.notifyChanged(ctx);
        } catch (Exception e) {
            Log.w(TAG, "could not save artwork", e);
        }
    }
}
