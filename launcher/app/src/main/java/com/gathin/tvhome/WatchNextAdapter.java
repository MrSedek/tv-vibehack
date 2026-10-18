package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cards of the continue-watching shelf. */
public class WatchNextAdapter extends RecyclerView.Adapter<WatchNextAdapter.Holder> {

    interface Listener {
        void onItemClick(WatchNextManager.WatchItem item);

        void onItemLongClick(WatchNextManager.WatchItem item, View card);

        void onItemFocused(View card);
    }

    static final float FOCUS_SCALE = 1.08f;

    private final Context ctx;
    private final List<WatchNextManager.WatchItem> items = new ArrayList<>();
    private Listener listener;

    // Thumbnails are small JPEGs from the bridge; decode off the main thread and keep the shelf's worth.
    private final LruCache<Uri, Bitmap> thumbs = new LruCache<>(24);
    /** App banners for cards without artwork (VK reports none), by package. */
    private final LruCache<String, Drawable> banners = new LruCache<>(16);
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    WatchNextAdapter(Context ctx) {
        this.ctx = ctx;
        reload();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void reload() {
        items.clear();
        items.addAll(WatchNextManager.getItems(ctx));
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_watch_next, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        WatchNextManager.WatchItem it = items.get(position);
        // VK reports no title, only where you stopped.
        h.title.setText(!it.title.isEmpty() ? it.title : ctx.getString(R.string.resume_from, formatTime(it.positionMs)));
        h.badge.setText(it.source);
        h.progressBar.setProgress(it.progress());
        h.progressBar.setVisibility(it.durationMs > 0 ? View.VISIBLE : View.INVISIBLE);
        long leftMin = (it.remainingMs() + 59_999) / 60_000;
        if (it.durationMs > 0 && leftMin > 0) {
            h.remaining.setText(ctx.getString(R.string.time_left, formatMinutes(leftMin)));
            h.remaining.setVisibility(View.VISIBLE);
        } else {
            h.remaining.setVisibility(View.GONE);
        }
        bindArt(h, it);
        h.card.setTag(it);
    }

    /** The bridge's thumbnail when there is one, else the app's own banner, dimmed. */
    private void bindArt(Holder h, WatchNextManager.WatchItem it) {
        Uri uri = it.artUri;
        h.thumb.setTag(uri);
        if (uri == null) {
            Drawable banner = banner(it.pkg);
            if (banner != null) {
                h.thumb.setImageDrawable(banner);
                h.thumb.setImageAlpha(140);
                showArt(h, true);
            } else {
                showArt(h, false);
            }
            return;
        }
        h.thumb.setImageAlpha(255);
        Bitmap cached = thumbs.get(uri);
        if (cached != null) {
            h.thumb.setImageBitmap(cached);
            showArt(h, true);
            return;
        }
        showArt(h, false);
        decoder.execute(() -> {
            Bitmap bmp = null;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
            }
            if (bmp == null) return;
            Bitmap done = bmp;
            main.post(() -> {
                thumbs.put(uri, done);
                if (uri.equals(h.thumb.getTag())) {
                    h.thumb.setImageBitmap(done);
                    h.thumb.setImageAlpha(255);
                    showArt(h, true);
                }
            });
        });
    }

    private static void showArt(Holder h, boolean has) {
        h.thumb.setVisibility(has ? View.VISIBLE : View.GONE);
        h.scrim.setVisibility(has ? View.VISIBLE : View.GONE);
        h.playIcon.setVisibility(has ? View.GONE : View.VISIBLE);
    }

    private Drawable banner(String pkg) {
        Drawable d = banners.get(pkg);
        if (d != null) return d;
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            ResolveInfo ri = launch != null ? pm.resolveActivity(launch, 0) : null;
            if (ri != null && ri.activityInfo != null) d = ri.activityInfo.loadBanner(pm);
            if (d == null) d = pm.getApplicationBanner(pkg);
        } catch (Exception ignored) {
        }
        if (d != null) banners.put(pkg, d);
        return d;
    }

    private String formatMinutes(long minutes) {
        return minutes >= 60
                ? ctx.getString(R.string.hours_minutes, minutes / 60, minutes % 60)
                : ctx.getString(R.string.minutes_only, minutes);
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** Picks up where the item left off, through the intent the bridge prepared. */
    void launch(WatchNextManager.WatchItem it) {
        try {
            Intent intent = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(ctx, ctx.getString(R.string.could_not_open, it.source), Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens the item's app at its own home screen. */
    void openApp(WatchNextManager.WatchItem it) {
        Intent launch = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
        if (launch == null) launch = ctx.getPackageManager().getLaunchIntentForPackage(it.pkg);
        try {
            if (launch == null) throw new IllegalStateException();
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(launch);
        } catch (Exception e) {
            Toast.makeText(ctx, ctx.getString(R.string.could_not_open, it.source), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView thumb;
        final View scrim;
        final View playIcon;
        final TextView badge;
        final TextView remaining;
        final TextView title;
        final ProgressBar progressBar;

        Holder(View v, Listener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            thumb = v.findViewById(R.id.thumb);
            scrim = v.findViewById(R.id.scrim);
            playIcon = v.findViewById(R.id.playIcon);
            badge = v.findViewById(R.id.badge);
            remaining = v.findViewById(R.id.remaining);
            title = v.findViewById(R.id.title);
            progressBar = v.findViewById(R.id.progressBar);

            final float radius = v.getResources().getDisplayMetrics().density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnClickListener(view -> {
                WatchNextManager.WatchItem it = (WatchNextManager.WatchItem) view.getTag();
                if (it != null && listener != null) listener.onItemClick(it);
            });
            card.setOnLongClickListener(view -> {
                WatchNextManager.WatchItem it = (WatchNextManager.WatchItem) view.getTag();
                if (it == null || listener == null) return false;
                listener.onItemLongClick(it, view);
                return true;
            });
            card.setOnFocusChangeListener((view, focused) -> {
                // Grow right and down, into the gap to the next card and the list's spare height: growing
                // from the centre, the first card is cut off at the column's edge and the top.
                view.setPivotX(0f);
                view.setPivotY(0f);
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 150 : 120)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.1f : 0f))
                        .start();
                if (focused && listener != null) listener.onItemFocused(view);
            });
        }
    }
}
