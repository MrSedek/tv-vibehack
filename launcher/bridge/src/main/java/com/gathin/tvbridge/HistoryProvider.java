package com.gathin.tvbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;

/**
 * The history for the launcher (signature permission, see the manifest): read it, or drop an entry.
 *
 *   content://com.gathin.tvbridge.history/items          one row per entry, newest first
 *   content://com.gathin.tvbridge.history/items/<key>    delete to drop an entry
 *   content://com.gathin.tvbridge.history/art/<hash>     the entry's artwork (JPEG)
 *
 * The intent column is the ready-to-start intent (URI form) that reopens the item in its app.
 */
public class HistoryProvider extends ContentProvider {

    static final String AUTHORITY = "com.gathin.tvbridge.history";
    static final Uri ITEMS = Uri.parse("content://" + AUTHORITY + "/items");
    private static final String[] COLUMNS = {
            "_id", "key", "package", "title", "subtitle", "position_ms", "duration_ms", "updated_at", "art_uri", "intent"};

    static void notifyChanged(Context ctx) {
        ctx.getContentResolver().notifyChange(ITEMS, null);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!isItems(uri)) return null;
        HistoryStore store = HistoryStore.get(getContext());
        List<String> youtube = Arrays.asList(getContext().getResources().getStringArray(R.array.youtube_apps));
        MatrixCursor c = new MatrixCursor(COLUMNS);
        long id = 0;
        for (WatchItem w : store.items()) {
            Intent open = resumeIntent(w, youtube);
            if (open == null) continue;   // app uninstalled or disabled
            String hash = HistoryStore.hash(w.key());
            File art = store.artFileForHash(hash);
            c.addRow(new Object[]{
                    id++, w.key(), w.pkg, w.title, w.subtitle, w.positionMs, w.durationMs, w.updatedAt,
                    art.exists() ? "content://" + AUTHORITY + "/art/" + hash : null,
                    open.toUri(Intent.URI_INTENT_SCHEME)});
        }
        c.setNotificationUri(getContext().getContentResolver(), ITEMS);
        return c;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !"items".equals(seg.get(0))) return 0;
        return HistoryStore.get(getContext()).remove(seg.get(1)) ? 1 : 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !"art".equals(seg.get(0)) || !seg.get(1).matches("[0-9a-f]{1,40}")) {
            throw new FileNotFoundException(uri.toString());
        }
        File f = HistoryStore.get(getContext()).artFileForHash(seg.get(1));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** SmartTube reopens the exact video at the saved second; other players just open where they left off. */
    private Intent resumeIntent(WatchItem w, List<String> youtube) {
        PackageManager pm = getContext().getPackageManager();
        if (youtube.contains(w.pkg) && w.mediaId != null && w.mediaId.matches("[A-Za-z0-9_-]{6,20}")) {
            String url = "https://www.youtube.com/watch?v=" + w.mediaId;
            if (w.positionMs >= 1000) url += "&t=" + (w.positionMs / 1000) + "s";
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(w.pkg);
            if (i.resolveActivity(pm) != null) return i;
        }
        Intent launch = pm.getLeanbackLaunchIntentForPackage(w.pkg);
        return launch != null ? launch : pm.getLaunchIntentForPackage(w.pkg);
    }

    private static boolean isItems(Uri uri) {
        List<String> seg = uri.getPathSegments();
        return seg.size() == 1 && "items".equals(seg.get(0));
    }

    @Override
    public String getType(Uri uri) {
        return uri.getPathSegments().size() == 2 && "art".equals(uri.getPathSegments().get(0)) ? "image/jpeg" : null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
