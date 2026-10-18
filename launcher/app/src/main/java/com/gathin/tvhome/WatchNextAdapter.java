package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
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

/**
 * Adapter for the "Continue Watching" shelf.
 */
public class WatchNextAdapter extends RecyclerView.Adapter<WatchNextAdapter.Holder> {

    public interface OnItemClickListener {
        void onItemClick(WatchNextManager.WatchItem item);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(WatchNextManager.WatchItem item, View card);
    }

    private final Context ctx;
    private final List<WatchNextManager.WatchItem> items = new ArrayList<>();
    private final OnItemClickListener listener;
    private OnItemLongClickListener longClickListener;
    private final float density;

    // Thumbnails are small JPEGs from the bridge; decode off the main thread and keep the shelf's worth.
    private final LruCache<Uri, Bitmap> thumbs = new LruCache<>(24);
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public WatchNextAdapter(Context ctx, OnItemClickListener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.density = ctx.getResources().getDisplayMetrics().density;
        reload();
    }

    public void reload() {
        items.clear();
        items.addAll(WatchNextManager.getItems(ctx));
        notifyDataSetChanged();
    }

    /** Position among the VibeBridge items, which follow the bridge's. */
    int localIndexOf(WatchNextManager.WatchItem item) {
        int local = 0;
        for (WatchNextManager.WatchItem it : items) {
            if (it == item) return local;
            if (!it.fromBridge()) local++;
        }
        return -1;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener l) {
        longClickListener = l;
    }

    public void addItem(WatchNextManager.WatchItem item) {
        items.add(0, item);
        notifyItemInserted(0);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_watch_next, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        WatchNextManager.WatchItem it = items.get(position);
        // VK reports no title, only where you stopped.
        h.title.setText(!it.title.isEmpty() ? it.title
                : ctx.getString(R.string.resume_from, formatTime(it.positionMs)));
        h.badge.setText(it.source);
        h.progressBar.setProgress(it.progress);
        h.progressBar.setVisibility(!it.fromBridge() || it.durationMs > 0 ? View.VISIBLE : View.INVISIBLE);
        bindThumb(h, it.artUri);

        // Stylish dynamic gradient backdrop
        int c1 = 0xFF1C1C24;
        int c2 = 0xFF0E0E12;
        if (it.source.toLowerCase().contains("tube")) {
            c1 = 0xFF2A1515;
        } else if (it.source.toLowerCase().contains("кино")) {
            c1 = 0xFF281E12;
        }
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{c1, c2});
        gd.setCornerRadius(16f * density);
        h.backdrop.setBackground(gd);

        h.card.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(it);
            } else {
                launch(it);
            }
        });
        h.card.setOnLongClickListener(v -> {
            if (longClickListener == null) return false;
            longClickListener.onItemLongClick(it, v);
            return true;
        });
    }

    private void bindThumb(Holder h, Uri uri) {
        h.thumb.setTag(uri);
        Bitmap cached = uri != null ? thumbs.get(uri) : null;
        showThumb(h, cached);
        if (uri == null || cached != null) return;
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
                if (uri.equals(h.thumb.getTag())) showThumb(h, done);
            });
        });
    }

    private static void showThumb(Holder h, Bitmap bmp) {
        h.thumb.setImageBitmap(bmp);
        int vis = bmp != null ? View.VISIBLE : View.GONE;
        h.thumb.setVisibility(vis);
        h.scrim.setVisibility(vis);
        h.playIcon.setVisibility(bmp != null ? View.GONE : View.VISIBLE);
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    public void launch(WatchNextManager.WatchItem it) {
        if (it.intentUri != null) {
            try {
                Intent intent = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(ctx, "Не открылось: " + it.source, Toast.LENGTH_SHORT).show();
            }
        } else if (it.url != null && !it.url.isEmpty()) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(it.url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(ctx, "Запуск: " + it.title, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(ctx, "Сессия возобновлена: " + it.title, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final View backdrop;
        final ImageView thumb;
        final View scrim;
        final View playIcon;
        final TextView badge;
        final TextView title;
        final ProgressBar progressBar;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            backdrop = v.findViewById(R.id.backdrop);
            thumb = v.findViewById(R.id.thumb);
            scrim = v.findViewById(R.id.scrim);
            playIcon = v.findViewById(R.id.playIcon);
            badge = v.findViewById(R.id.badge);
            title = v.findViewById(R.id.title);
            progressBar = v.findViewById(R.id.progressBar);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnFocusChangeListener((view, focused) -> {
                // Grow to the right, into the gap to the next card: growing left, the first card is cut
                // off at the column's edge.
                view.setPivotX(0f);
                view.setPivotY(view.getHeight() / 2f);
                float s = focused ? 1.08f : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 240 : 160)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.5f : 0f))
                        .start();
            });
        }
    }
}
