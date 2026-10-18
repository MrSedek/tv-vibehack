package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, the app grid below it, a continue-watching shelf above
 * it, and one glass highlight that springs to whatever has focus.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    static final String UI_PREFS = "tvhome_ui";
    /** Colour of the last focused dock app; the screensaver glows with it. */
    static final String PREF_AMBIENT = "ambient";
    private static final String PREF_SHOW_SHELF = "show_watch_next";
    private static final long MINUTE_MS = 60_000L;

    private int columns = 4;
    private String liveTvPkg;
    private float density;

    private AppAdapter adapter;
    private GridLayoutManager layout;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnWifi;
    private String wifiText = "";
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    /** The glass sits on a shelf card, not a grid tile; scrolling of the other list must not move it. */
    private boolean glassOnShelf;
    private int lastFocused = 0;
    /** Last dock tile in focus; Back and Home return there. */
    private int lastDockFocused = 0;
    /** The grid sits at rest with the dock at the bottom (not scrolled into the app rows). */
    private boolean dockParked = true;
    /** Home was pressed while we were away (or in the grid): land on the dock. */
    private boolean returnToDock;
    /** The app list needs rereading: first show, or a package came or went. */
    private boolean appsDirty = true;
    private boolean everResumed;
    private boolean resumed;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            if (resumed) {
                appsDirty = false;
                adapter.reload();
            }
        }
    };

    // Continue watching shelf
    private View layoutWatchNext;
    private RecyclerView listWatchNext;
    private WatchNextAdapter watchNextAdapter;
    private boolean showWatchNext = true;
    private ContentObserver bridgeObserver;
    private SharedPreferences uiPrefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        columns = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        uiPrefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        showWatchNext = uiPrefs.getBoolean(PREF_SHOW_SHELF, true);

        // Continue watching shelf
        layoutWatchNext = findViewById(R.id.layoutWatchNext);
        listWatchNext = findViewById(R.id.listWatchNext);
        listWatchNext.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        watchNextAdapter = new WatchNextAdapter(this);
        watchNextAdapter.setListener(new WatchNextAdapter.Listener() {
            @Override
            public void onItemClick(WatchNextManager.WatchItem item) {
                watchNextAdapter.launch(item);
            }

            @Override
            public void onItemLongClick(WatchNextManager.WatchItem item, View card) {
                showShelfItemMenu(item, card);
            }

            @Override
            public void onItemFocused(View card) {
                onShelfCardFocused(card);
            }
        });
        listWatchNext.setAdapter(watchNextAdapter);
        listWatchNext.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown || !glassOnShelf) return;
                glassTargetX -= dx;
                if (springX.isRunning()) springX.animateToFinalPosition(glassTargetX);
                else glass.setTranslationX(glassTargetX);
            }
        });
        bridgeObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                refreshWatchNext();
            }
        };

        // App grid: dock row first, in spans so the dock and the grid can differ in width.
        grid = findViewById(R.id.grid);
        adapter = new AppAdapter(this, this, columns);
        layout = new GridLayoutManager(this, adapter.math().spanCount());
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.math().spanSize(position);
            }
        });
        grid.setLayoutManager(layout);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(adapter, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        grid.setAdapter(adapter);

        // Reread the app list only when a package comes or goes, not on every Home press.
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        registerReceiver(packageReceiver, packages);

        // tvOS layout: the dock row sits at the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            updateGridPadding();
            grid.post(this::positionShelf);
        });
        layoutWatchNext.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) positionShelf();
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown || glassOnShelf) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            // Firm enough to keep up with D-pad auto-repeat; the old 260/0.58 drifted for ~0.4 s.
            s.setSpring(new SpringForce().setStiffness(650f).setDampingRatio(0.75f));
        }

        // Top bar
        View btnSearch = findViewById(R.id.btnSearch);
        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnSearch.setOnClickListener(v -> launchSearch());
        btnInputs.setOnClickListener(v -> showInputs());
        // A dongle has no HDMI inputs or tuner: the pill would only say "No inputs found".
        btnInputs.setVisibility(hasInputs() ? View.VISIBLE : View.GONE);
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        // Just the icon until focused; then the network name.
        btnWifi.setOnFocusChangeListener((v, focused) -> applyWifiPill(focused));
        applyWifiPill(false);
        btnSettings.setOnClickListener(v -> showLauncherSettings());

        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to where we came from, not whatever the focus finder picks below.
                if (!focusFirstShelfCard()) focusTile(lastDockFocused);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnSearch) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnSearch.setOnKeyListener(pillKeys);
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles and the shelf cards (they share the "card" id).
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onCard = newF != null && newF.getId() == R.id.card
                    && (grid.findContainingViewHolder(newF) != null || listWatchNext.findContainingViewHolder(newF) != null);
            if (!onCard) hideGlass();
        });
    }

    /** The grid's top padding parks the dock row at the bottom of the screen. */
    private void updateGridPadding() {
        int dockRow = adapter.dockCardHeightPx() + (int) ((45 + 6 + 12) * density);   // item padding, label, decoration
        int want = Math.max(0, grid.getHeight() - dockRow - (int) (20 * density));
        if (grid.getPaddingTop() != want) {
            grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
        }
    }

    @Override
    public void onGridChanged(GridMath math) {
        layout.setSpanCount(math.spanCount());
        grid.invalidateItemDecorations();
        updateGridPadding();
    }

    private void launchSearch() {
        Intent i = new Intent(Intent.ACTION_ASSIST);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Intent search = new Intent(Intent.ACTION_SEARCH);
            search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(search);
            } catch (Exception ex) {
                Toast.makeText(this, R.string.search_unavailable, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean hasInputs() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) return true;
            }
        }
        return !liveTvPkg.isEmpty() && getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg) != null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // No reload and no entrance animation on an ordinary return: on this CPU they made every
        // Home press look like the launcher restarting.
        if (appsDirty) {
            appsDirty = false;
            adapter.reload();
            if (!everResumed) grid.scheduleLayoutAnimation();
        }
        everResumed = true;
        refreshWatchNext();
        try {
            // Picks up the pause the bridge records just after you press Home.
            getContentResolver().registerContentObserver(WatchNextManager.BRIDGE_ITEMS, false, bridgeObserver);
        } catch (SecurityException e) {
            // Bridge not installed.
        }
        updateGreeting();
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);

        grid.post(() -> {
            View focused = getCurrentFocus();
            RecyclerView.ViewHolder vh = focused != null ? grid.findContainingViewHolder(focused) : null;
            int pos = vh != null ? vh.getBindingAdapterPosition() : RecyclerView.NO_POSITION;
            if (returnToDock) {
                returnToDock = false;
                if (pos == RecyclerView.NO_POSITION || !adapter.isDock(pos)) {
                    focusTile(lastDockFocused);
                    return;
                }
            }
            if (pos != RecyclerView.NO_POSITION) {
                // Still on a tile: re-anchor the glass and hero without touching the list.
                onTileFocused(pos, focused);
                return;
            }
            if (focused != null && listWatchNext.findContainingViewHolder(focused) != null) {
                onShelfCardFocused(focused);
                return;
            }
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(minuteTick);
        getContentResolver().unregisterContentObserver(bridgeObserver);
    }

    // ---- continue watching shelf -----------------------------------------------------------------

    /** Reloads the shelf and shows it when it has something to continue. */
    private void refreshWatchNext() {
        watchNextAdapter.reload();
        updateShelfVisibility();
    }

    private boolean shelfHasItems() {
        return showWatchNext && watchNextAdapter.getItemCount() > 0;
    }

    /** The shelf stands above the dock, so it goes away while the grid is scrolled into the app rows. */
    private void updateShelfVisibility() {
        boolean show = shelfHasItems() && dockParked;
        int vis = show ? View.VISIBLE : (shelfHasItems() ? View.INVISIBLE : View.GONE);
        if (layoutWatchNext.getVisibility() == vis) return;
        if (show) {
            layoutWatchNext.setAlpha(0f);
            layoutWatchNext.setVisibility(View.VISIBLE);
            layoutWatchNext.animate().alpha(1f).setDuration(220).start();
        } else {
            layoutWatchNext.animate().cancel();
            layoutWatchNext.setVisibility(vis);
        }
    }

    /**
     * Puts the shelf right above the dock band (the grid's top padding is where the band starts).
     * A layout margin, not a translation: D-pad focus search only sees layout positions.
     */
    private void positionShelf() {
        if (layoutWatchNext.getHeight() == 0) return;
        Rect r = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, r);
        int top = Math.round(r.top + grid.getPaddingTop() - 18 * density - layoutWatchNext.getHeight());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) layoutWatchNext.getLayoutParams();
        if (lp.topMargin != top) {
            lp.topMargin = top;
            layoutWatchNext.setLayoutParams(lp);
        }
    }

    private void setDockParked(boolean parked) {
        if (dockParked == parked) return;
        dockParked = parked;
        updateShelfVisibility();
    }

    private boolean focusFirstShelfCard() {
        if (layoutWatchNext.getVisibility() != View.VISIBLE || listWatchNext.getChildCount() == 0) return false;
        View card = listWatchNext.getChildAt(0).findViewById(R.id.card);
        return card != null && card.requestFocus();
    }

    private void onShelfCardFocused(View card) {
        glassOnShelf = true;
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        // Shelf cards grow from their top-left corner (see WatchNextAdapter), so the glass does too.
        float pad = 6f * density, s = WatchNextAdapter.FOCUS_SCALE;
        moveGlass(r.left - pad, r.top - pad, card.getWidth() * s + 2 * pad, card.getHeight() * s + 2 * pad,
                16f * density * s + pad);
    }

    private void showShelfItemMenu(WatchNextManager.WatchItem item, View card) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = new PopupWindow(panel, (int) (260 * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        addMenuRow(rows, popup, R.drawable.ic_livetv, getString(R.string.open_app), () -> watchNextAdapter.openApp(item));
        addMenuRow(rows, popup, R.drawable.ic_hide, getString(R.string.remove_from_shelf), () -> {
            WatchNextManager.remove(this, item);
            refreshWatchNext();
            listWatchNext.post(() -> {
                if (!focusFirstShelfCard()) focusTile(lastDockFocused);
            });
        });
        popup.showAsDropDown(card, 0, (int) (8 * density), Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    // ---- Home and Back ---------------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        focusTile(lastDockFocused);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home lands on the dock, not wherever in the grid you were. The window usually isn't
        // focused yet here, so onResume finishes the job.
        returnToDock = true;
        lastFocused = lastDockFocused;
        if (hasWindowFocus()) focusTile(lastDockFocused);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    private void moveGlass(float x, float y, float w, float h, float radius) {
        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(radius);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        boolean dock = adapter.isDock(position);
        if (dock) lastDockFocused = position;
        setDockParked(dock);
        glassOnShelf = false;
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        moveGlass(r.exactCenterX() - w / 2f, r.exactCenterY() - h / 2f, w, h, 16f * density * AppAdapter.FOCUS_SCALE + pad);

        View item = (View) card.getParent();
        if (dock) {
            // Park the dock at the bottom of the screen and show the hero name for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    @Override
    public void onAmbientColorReady(int position, int color) {
        if (position == lastFocused && adapter.isDock(position) && heroShown) {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(400).start();
        }
    }

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        // The shelf stands where the name would go.
        float heroAlpha = shelfHasItems() ? 0f : 1f;

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(heroAlpha).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(heroAlpha).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
            heroName.animate().alpha(heroAlpha).setDuration(160).start();
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x3D000000 | c, 0x1A000000 | c, 0x00000000 | c});
        uiPrefs.edit().putInt(PREF_AMBIENT, color).apply();
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        GridMath m = adapter.math();
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            int target = m.up(position);
            if (target < 0) return false;          // dock row: let focus move to the shelf or the top bar
            focusTile(target);
            return true;
        }
        int target = m.down(position, adapter.getItemCount());
        if (target != position) focusTile(target);
        return true;
    }

    @Override
    public boolean onTileHorizontalKey(int position, int keyCode) {
        // Stay put at a row's ends: the focus finder would otherwise jump to the top bar.
        GridMath m = adapter.math();
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return m.atLeftEdge(position);
        return m.atRightEdge(position, adapter.getItemCount());
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- long-press menu on a tile ---------------------------------------------------------------

    @Override
    public void onTileLongClicked(int position, View card, AppAdapter.Entry entry) {
        if (entry == null) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        final PopupWindow popup = new PopupWindow(panel, (int) (220 * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(16f * density);

        AppPreferences prefs = adapter.getPreferences();
        if (adapter.isDock(position)) {
            if (position > 0) {
                addMenuRow(rows, popup, R.drawable.ic_move, getString(R.string.move_left), () -> {
                    prefs.moveDockApp(entry.pkg, -1);
                    adapter.reload();
                    grid.post(() -> focusTile(position - 1));
                });
            }
            if (position < adapter.dockSize() - 1) {
                addMenuRow(rows, popup, R.drawable.ic_move, getString(R.string.move_right), () -> {
                    prefs.moveDockApp(entry.pkg, 1);
                    adapter.reload();
                    grid.post(() -> focusTile(position + 1));
                });
            }
            addMenuRow(rows, popup, R.drawable.ic_unpin, getString(R.string.unpin_from_dock), () -> {
                prefs.unpinFromDock(entry.pkg);
                adapter.reload();
                grid.post(() -> focusTile(Math.min(position, adapter.getItemCount() - 1)));
            });
        } else {
            addMenuRow(rows, popup, R.drawable.ic_pin, getString(R.string.pin_to_dock), () -> {
                prefs.pinToDock(entry.pkg, columns);
                adapter.reload();
                grid.post(() -> focusTile(Math.max(0, adapter.dockSize() - 1)));
            });
        }

        addSeparator(rows);

        addMenuRow(rows, popup, R.drawable.ic_hide, getString(R.string.hide_app), () -> {
            prefs.hideApp(entry.pkg);
            adapter.reload();
            grid.post(() -> focusTile(Math.min(position, adapter.getItemCount() - 1)));
        });

        addMenuRow(rows, popup, R.drawable.ic_info, getString(R.string.app_info), () -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + entry.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
        });

        panel.setAlpha(0f);
        panel.setScaleY(0.92f);
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.animate().alpha(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    // ---- launcher settings -----------------------------------------------------------------------

    private void showLauncherSettings() {
        View dialog = getLayoutInflater().inflate(R.layout.dialog_settings, root, false);
        final PopupWindow popup = new PopupWindow(dialog, (int) (480 * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(24f * density);
        AppPreferences prefs = adapter.getPreferences();

        TextView lblShelf = dialog.findViewById(R.id.lblShelf);
        Runnable shelfText = () -> lblShelf.setText(showWatchNext ? R.string.shelf_on : R.string.shelf_off);
        shelfText.run();
        dialog.findViewById(R.id.rowShelf).setOnClickListener(v -> {
            showWatchNext = !showWatchNext;
            uiPrefs.edit().putBoolean(PREF_SHOW_SHELF, showWatchNext).apply();
            refreshWatchNext();
            shelfText.run();
        });

        TextView lblSort = dialog.findViewById(R.id.lblSort);
        Runnable sortText = () -> lblSort.setText(prefs.isSortByRecent() ? R.string.sort_recent : R.string.sort_alpha);
        sortText.run();
        dialog.findViewById(R.id.rowSort).setOnClickListener(v -> {
            prefs.setSortByRecent(!prefs.isSortByRecent());
            adapter.reload();
            sortText.run();
        });

        Set<String> hidden = prefs.getHiddenApps();
        TextView lblUnhide = dialog.findViewById(R.id.lblUnhide);
        lblUnhide.setText(getString(R.string.unhide_all_count, hidden.size()));
        dialog.findViewById(R.id.rowUnhide).setOnClickListener(v -> {
            for (String p : new ArrayList<>(hidden)) prefs.unhideApp(p);
            adapter.reload();
            lblUnhide.setText(R.string.all_restored);
        });

        dialog.findViewById(R.id.rowResetDock).setOnClickListener(v -> {
            prefs.resetToDefaults();
            adapter.reload();
            Toast.makeText(this, R.string.dock_reset, Toast.LENGTH_SHORT).show();
            popup.dismiss();
        });

        dialog.findViewById(R.id.rowSystem).setOnClickListener(v -> {
            popup.dismiss();
            open(new Intent(Settings.ACTION_SETTINGS));
        });

        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        dialog.findViewById(R.id.rowShelf).requestFocus();
    }

    private void addMenuRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** The Wi-Fi network name when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        wifiText = text;
        applyWifiPill(btnWifi.hasFocus());
    }

    /** Collapsed to a round icon; the network name unfolds on focus. */
    private void applyWifiPill(boolean expanded) {
        btnWifi.setText(expanded ? wifiText : "");
        btnWifi.setCompoundDrawablePadding(expanded ? (int) (9 * density) : 0);
        btnWifi.setPadding((int) ((expanded ? 16 : 11) * density), 0, (int) ((expanded ? 20 : 11) * density), 0);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // Some TVs report "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
