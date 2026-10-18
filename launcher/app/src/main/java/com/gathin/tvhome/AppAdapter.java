package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App tiles with category filtering, asynchronous palette calculation,
 * TV-first context menu, and adaptive generative cards.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    public enum Category { ALL, MEDIA, TOOLS }

    interface TileListener {
        void onTileFocused(int position, View card);
        boolean onTileVerticalKey(int position, int keyCode);
        /** Called for D-pad Left/Right on a tile. Return true to keep focus where it is. */
        boolean onTileHorizontalKey(int position, int keyCode);
        void onTileLongClicked(int position, View card, Entry entry);
        void onAmbientColorReady(int position, int color);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    private final AppPreferences preferences;
    private final List<Entry> allItems = new ArrayList<>();
    private final List<Entry> items = new ArrayList<>();
    private final float density;
    private Category currentCategory = Category.ALL;

    // Background thread pool and memory cache for non-blocking color calculation
    private final LruCache<String, Integer> colorCache = new LruCache<>(80);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.preferences = new AppPreferences(ctx);
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    AppPreferences getPreferences() {
        return preferences;
    }

    public void setCategory(Category category) {
        if (this.currentCategory == category) return;
        this.currentCategory = category;
        applyCategoryFilter();
    }

    public Category getCategory() {
        return currentCategory;
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);

        List<String> favourites = preferences.getDockApps();
        Set<String> hidden = preferences.getHiddenApps();

        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            // One app with a broken banner must not take the home screen down with it: the stock
            // launcher is disabled, so a crash loop here means a black screen until someone has adb.
            try {
                Drawable banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                Drawable icon = ri.loadIcon(pm);
                String label = String.valueOf(ri.loadLabel(pm));
                fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
            } catch (Throwable t) {
                android.util.Log.w("TvHome", "skipping " + pkg, t);
            }
        }

        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });

        allItems.clear();
        allItems.addAll(fresh);

        applyCategoryFilter();
        prefetchColors();
    }

    private void applyCategoryFilter() {
        items.clear();
        if (currentCategory == Category.ALL) {
            items.addAll(allItems);
        } else if (currentCategory == Category.MEDIA) {
            // Keep dock items + media apps
            for (int i = 0; i < allItems.size(); i++) {
                Entry e = allItems.get(i);
                if (i < dockSize || isMediaApp(e)) {
                    items.add(e);
                }
            }
        } else if (currentCategory == Category.TOOLS) {
            for (int i = 0; i < allItems.size(); i++) {
                Entry e = allItems.get(i);
                if (i < dockSize || isToolApp(e)) {
                    items.add(e);
                }
            }
        }
        notifyDataSetChanged();
    }

    private boolean isMediaApp(Entry e) {
        String p = (e.pkg + " " + e.label).toLowerCase();
        return p.contains("tube") || p.contains("video") || p.contains("tv") || p.contains("kino") ||
                p.contains("media") || p.contains("lampa") || p.contains("vimu") || p.contains("cinema") ||
                p.contains("netflix") || p.contains("rezka") || p.contains("film") || p.contains("movie") ||
                p.contains("prime") || p.contains("zona") || p.contains("show") || p.contains("hd");
    }

    private boolean isToolApp(Entry e) {
        String p = (e.pkg + " " + e.label).toLowerCase();
        return p.contains("torr") || p.contains("setting") || p.contains("file") || p.contains("tool") ||
                p.contains("browser") || p.contains("cleaner") || p.contains("speed") || p.contains("manager") ||
                p.contains("input") || p.contains("ime") || p.contains("remote");
    }

    private void prefetchColors() {
        executor.execute(() -> {
            for (int i = 0; i < allItems.size(); i++) {
                final int pos = i;
                if (pos >= allItems.size()) break;
                Entry e = allItems.get(pos);
                if (e.ambient != 0) continue;
                Integer cached = colorCache.get(e.pkg);
                if (cached != null) {
                    e.ambient = cached;
                    continue;
                }
                int color = vividColor(e.banner != null ? e.banner : e.icon);
                colorCache.put(e.pkg, color);
                e.ambient = color;
            }
        });
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);
        h.name.setAlpha(0f);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);

        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.fallbackContainer.setVisibility(View.GONE);
        } else {
            h.banner.setVisibility(View.GONE);
            h.fallbackContainer.setVisibility(View.VISIBLE);
            h.icon.setImageDrawable(e.icon);
            h.label.setText(e.label);

            int color = ambientColorAt(position);
            int topColor = 0x50000000 | (color & 0x00FFFFFF);
            int bottomColor = 0xFF141418;
            GradientDrawable gd = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{topColor, bottomColor}
            );
            gd.setCornerRadius(16f * density);
            h.fallbackBg.setBackground(gd);
        }

        h.card.setOnClickListener(v -> launch(e));
    }

    public void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open) + " " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    Entry getEntry(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF3D3D3D;
        Entry e = items.get(position);
        if (e.ambient != 0) return e.ambient;

        Integer cached = colorCache.get(e.pkg);
        if (cached != null) {
            e.ambient = cached;
            return cached;
        }

        executor.execute(() -> {
            int computed = vividColor(e.banner != null ? e.banner : e.icon);
            colorCache.put(e.pkg, computed);
            e.ambient = computed;
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onAmbientColorReady(position, computed);
                }
            });
        });

        return 0xFF3A3A3C;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF3D3D3D;
        try {
            Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
            Rect saved = new Rect(d.getBounds());
            int n = 12;
            Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, n, n);
            d.draw(c);
            d.setBounds(saved);
            float[] hsv = new float[3];
            int best = 0xFF444444;
            float bestScore = -1f;
            long r = 0, g = 0, b = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int p = bmp.getPixel(x, y);
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    Color.colorToHSV(p, hsv);
                    float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);
                    if (score > bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }
            }
            bmp.recycle();
            if (bestScore < 0.25f) {
                int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
                Color.colorToHSV(avg, hsv);
                hsv[2] = Math.max(hsv[2], 0.55f);
                return Color.HSVToColor(hsv);
            }
            Color.colorToHSV(best, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.1f);
            hsv[2] = Math.max(0.5f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Throwable ignored) {
            return 0xFF444444;
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final View fallbackContainer;
        final View fallbackBg;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            fallbackContainer = v.findViewById(R.id.fallbackContainer);
            fallbackBg = v.findViewById(R.id.fallbackBg);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || listener == null) return false;

                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    AppAdapter adapter = (AppAdapter) getBindingAdapter();
                    if (adapter != null) {
                        Entry entry = adapter.getEntry(pos);
                        if (entry != null) {
                            listener.onTileLongClicked(pos, view, entry);
                            return true;
                        }
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return listener.onTileVerticalKey(pos, keyCode);
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return listener.onTileHorizontalKey(pos, keyCode);
                }
                return false;
            });

            card.setOnLongClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    AppAdapter adapter = (AppAdapter) getBindingAdapter();
                    if (adapter != null) {
                        Entry entry = adapter.getEntry(pos);
                        if (entry != null) {
                            listener.onTileLongClicked(pos, view, entry);
                            return true;
                        }
                    }
                }
                return false;
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                // Short enough to finish between D-pad auto-repeats.
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 150 : 120)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.1f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(150).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
