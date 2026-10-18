package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for the "Continue Watching" shelf.
 */
public class WatchNextAdapter extends RecyclerView.Adapter<WatchNextAdapter.Holder> {

    public interface OnItemClickListener {
        void onItemClick(WatchNextManager.WatchItem item);
    }

    private final Context ctx;
    private final List<WatchNextManager.WatchItem> items = new ArrayList<>();
    private final OnItemClickListener listener;
    private final float density;

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
        h.title.setText(it.title);
        h.badge.setText(it.source);
        h.progressBar.setProgress(it.progress);

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
    }

    public void launch(WatchNextManager.WatchItem it) {
        if (it.url != null && !it.url.isEmpty()) {
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
        final TextView badge;
        final TextView title;
        final ProgressBar progressBar;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            backdrop = v.findViewById(R.id.backdrop);
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
                float s = focused ? 1.08f : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 240 : 160)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.5f : 0f))
                        .start();
            });
        }
    }
}
