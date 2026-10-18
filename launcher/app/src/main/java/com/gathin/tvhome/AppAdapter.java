package com.gathin.tvhome;

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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App tiles: the dock (the favourites that are installed, in their order) first, then the rest
 * alphabetically or by last launch. Tile sizes follow the grid width: a dock tile takes the row in
 * dockSize parts, a grid tile in columns parts, so the dock can be wider than the grid.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Called for D-pad Left/Right on a tile. Return true to keep focus where it is. */
        boolean onTileHorizontalKey(int position, int keyCode);

        void onTileLongClicked(int position, View card, Entry entry);

        void onAmbientColorReady(int position, int color);

        /** The dock size changed: the layout manager's span count must follow. */
        void onGridChanged(GridMath math);
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
    /** Space between tiles. */
    private static final float GAP_DP = 28f;
    private static final float MIN_CARD_DP = 120f;
    private static final String TAG = "TvHome";

    private final Context ctx;
    private final TileListener listener;
    private final int columns;
    private final AppPreferences preferences;
    private final List<Entry> items = new ArrayList<>();
    private final float density;
    private GridMath math;
    private RecyclerView recycler;

    private final LruCache<String, Integer> colorCache = new LruCache<>(80);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    AppAdapter(Context ctx, TileListener listener, int columns) {
        this.ctx = ctx;
        this.listener = listener;
        this.columns = Math.max(1, columns);
        this.preferences = new AppPreferences(ctx);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.math = new GridMath(0, this.columns);
    }

    AppPreferences getPreferences() {
        return preferences;
    }

    GridMath math() {
        return math;
    }

    int dockSize() {
        return math.dockSize;
    }

    boolean isDock(int position) {
        return math.isDock(position);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        recycler = recyclerView;
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
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
                Log.w(TAG, "skipping " + pkg, t);
            }
        }

        // The dock holds the favourites that are installed, in the favourites' order.
        List<Entry> dock = new ArrayList<>();
        for (String fav : preferences.getDockApps()) {
            for (Entry e : fresh) {
                if (e.pkg.equals(fav) && !dock.contains(e)) {
                    dock.add(e);
                    break;
                }
            }
        }
        while (dock.size() > columns) dock.remove(dock.size() - 1);

        List<Entry> rest = new ArrayList<>();
        for (Entry e : fresh) {
            if (!dock.contains(e)) rest.add(e);
        }
        final boolean recent = preferences.isSortByRecent();
        final Map<String, Long> launches = recent ? preferences.getLaunches() : Collections.emptyMap();
        Collections.sort(rest, (a, b) -> {
            if (recent) {
                Long la = launches.get(a.pkg), lb = launches.get(b.pkg);
                int c = Long.compare(lb == null ? 0 : lb, la == null ? 0 : la);
                if (c != 0) return c;
            }
            return a.label.compareToIgnoreCase(b.label);
        });

        items.clear();
        items.addAll(dock);
        items.addAll(rest);

        GridMath m = new GridMath(dock.size(), columns);
        if (!m.equals(math)) {
            math = m;
            if (listener != null) listener.onGridChanged(m);
        }
        notifyDataSetChanged();
        prefetchColors();
    }

    private void prefetchColors() {
        List<Entry> snapshot = new ArrayList<>(items);
        executor.execute(() -> {
            for (Entry e : snapshot) {
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

    // ---- tile sizes ------------------------------------------------------------------------------

    /** Card width for a position: its share of the grid's width minus the gap. */
    int cardWidthPx(int position) {
        int inner = recycler != null ? recycler.getWidth() - recycler.getPaddingLeft() - recycler.getPaddingRight() : 0;
        if (inner <= 0) return (int) (180 * density);
        int parts = math.isDock(position) ? Math.max(1, math.dockSize) : math.columns;
        int cell = inner / parts;
        return Math.max((int) (MIN_CARD_DP * density), cell - (int) (GAP_DP * density));
    }

    static int cardHeightPx(int widthPx) {
        return Math.round(widthPx * 9f / 16f);
    }

    /** Height of a dock card (the band and the shelf are placed from it). */
    int dockCardHeightPx() {
        return cardHeightPx(cardWidthPx(0));
    }

    private void size(Holder h, int position) {
        int w = cardWidthPx(position), ht = cardHeightPx(w);
        ViewGroup.LayoutParams lp = h.card.getLayoutParams();
        if (lp.width != w || lp.height != ht) {
            lp.width = w;
            lp.height = ht;
            h.card.setLayoutParams(lp);
        }
        FrameLayout.LayoutParams nl = (FrameLayout.LayoutParams) h.name.getLayoutParams();
        int top = ht + (int) (7 * density);
        if (nl.width != w || nl.topMargin != top) {
            nl.width = w;
            nl.topMargin = top;
            h.name.setLayoutParams(nl);
        }
        int iconPx = Math.min((int) (50 * density), ht / 2);
        ViewGroup.LayoutParams il = h.icon.getLayoutParams();
        if (il.width != iconPx) {
            il.width = iconPx;
            il.height = iconPx;
            h.icon.setLayoutParams(il);
        }
    }

    // ---- binding ---------------------------------------------------------------------------------

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        size(h, position);
        h.name.setText(e.label);
        h.name.setVisibility(isDock(position) ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        h.name.setAlpha(0f);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);

        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.fallbackContainer.setVisibility(View.GONE);
        } else {
            // No TV banner: the icon on a wash of its own colour.
            h.banner.setVisibility(View.GONE);
            h.fallbackContainer.setVisibility(View.VISIBLE);
            h.icon.setImageDrawable(e.icon);
            h.label.setText(e.label);
            int color = ambientColorAt(position);
            GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x50000000 | (color & 0x00FFFFFF), 0xFF141418});
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
            preferences.recordLaunch(e.pkg);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.could_not_open, e.label), Toast.LENGTH_SHORT).show();
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

    /** A vivid colour sampled from the app's banner (or icon); computed in the background when not cached. */
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
                if (listener != null) listener.onAmbientColorReady(position, computed);
            });
        });
        return 0xFF3A3A3C;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF3D3D3D;
        try {
            // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
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
                    float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                    if (score > bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }
            }
            bmp.recycle();
            if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
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
                    Entry entry = adapter != null ? adapter.getEntry(pos) : null;
                    if (entry != null) {
                        listener.onTileLongClicked(pos, view, entry);
                        return true;
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
                AppAdapter adapter = (AppAdapter) getBindingAdapter();
                Entry entry = adapter != null && pos != RecyclerView.NO_POSITION ? adapter.getEntry(pos) : null;
                if (entry == null || listener == null) return false;
                listener.onTileLongClicked(pos, view, entry);
                return true;
            });

            card.setOnFocusChangeListener((view, focused) -> {
                // Short enough to finish between D-pad auto-repeats.
                float s = focused ? FOCUS_SCALE : 1.0f;
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
