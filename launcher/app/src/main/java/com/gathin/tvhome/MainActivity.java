package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
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
 * Modern TV Home for Xiaomi Stick 4K:
 * - VisionOS/tvOS glass aesthetic with spring physics
 * - Continue Watching (Watch Next) shelf synced with media sessions
 * - VibeBridge Web-to-TV integration (port 8989)
 * - Category filter tabs (All, Media, Tools)
 * - Built-in RAM monitor and 1-click cleaner for 2GB stick
 * - Interactive D-pad Remote settings and context menu
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
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
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
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
    private float density;

    // Continue Watching shelf
    private View layoutWatchNext;
    private RecyclerView listWatchNext;
    private WatchNextAdapter watchNextAdapter;
    private boolean showWatchNext = true;
    private ContentObserver bridgeObserver;
    private SharedPreferences uiPrefs;

    // Category Tabs
    private TextView tabAll, tabMedia, tabTools;

    // Web-to-TV Bridge
    private VibeBridgeServer bridgeServer;

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
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
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
        uiPrefs = getSharedPreferences("tvhome_ui", MODE_PRIVATE);
        showWatchNext = uiPrefs.getBoolean("show_watch_next", true);

        // Continue Watching shelf setup
        layoutWatchNext = findViewById(R.id.layoutWatchNext);
        listWatchNext = findViewById(R.id.listWatchNext);
        listWatchNext.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        watchNextAdapter = new WatchNextAdapter(this, item -> watchNextAdapter.launch(item));
        watchNextAdapter.setOnItemLongClickListener(this::showShelfItemMenu);
        listWatchNext.setAdapter(watchNextAdapter);
        bridgeObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                refreshWatchNext();
            }
        };

        // Start VibeBridge local server (port 8989)
        bridgeServer = new VibeBridgeServer(this, item -> {
            if (watchNextAdapter != null) {
                watchNextAdapter.addItem(item);
                updateShelfVisibility();
            }
        });
        bridgeServer.start();

        // Main App Grid setup
        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // Reread the app list only when a package comes or goes, not on every Home press.
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        registerReceiver(packageReceiver, packages);

        // Layout adjustment for dock shelf
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);
            int want = Math.max(0, h - dockRow - (int) (20 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            grid.post(this::positionShelf);
        });
        layoutWatchNext.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) positionShelf();
        });

        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
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

        // Category Tabs
        tabAll = findViewById(R.id.tabAll);
        tabMedia = findViewById(R.id.tabMedia);
        tabTools = findViewById(R.id.tabTools);

        tabAll.setOnClickListener(v -> selectCategory(AppAdapter.Category.ALL, tabAll));
        tabMedia.setOnClickListener(v -> selectCategory(AppAdapter.Category.MEDIA, tabMedia));
        tabTools.setOnClickListener(v -> selectCategory(AppAdapter.Category.TOOLS, tabTools));
        selectCategory(AppAdapter.Category.ALL, tabAll);
        // Down from a tab: the shelf when it is showing, else the dock tile we came from.
        View.OnKeyListener tabKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (layoutWatchNext.getVisibility() == View.VISIBLE && listWatchNext.getChildCount() > 0) {
                    listWatchNext.getChildAt(0).findViewById(R.id.card).requestFocus();
                } else {
                    focusTile(lastDockFocused);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v == tabAll) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == tabTools;
        };
        tabAll.setOnKeyListener(tabKeys);
        tabMedia.setOnKeyListener(tabKeys);
        tabTools.setOnKeyListener(tabKeys);

        // Top HUD buttons
        View btnSearch = findViewById(R.id.btnSearch);
        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);

        btnSearch.setOnClickListener(v -> launchSearch());
        btnInputs.setOnClickListener(v -> showInputs());
        // A dongle has no HDMI inputs or tuner: the pill would only say "No inputs found".
        btnInputs.setVisibility(hasInputs() ? View.VISIBLE : View.GONE);
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> showLauncherSettings());

        // Top bar D-Pad bounds
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile we came from, not whatever the focus finder picks below.
                focusTile(lastDockFocused);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnSearch) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnSearch.setOnKeyListener(pillKeys);
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            // Shelf cards share the tiles' "card" id; the glass belongs to grid tiles only.
            boolean onTile = newF != null && newF.getId() == R.id.card && grid.findContainingViewHolder(newF) != null;
            if (!onTile) hideGlass();
        });
    }

    private void selectCategory(AppAdapter.Category cat, TextView selectedTab) {
        adapter.setCategory(cat);
        tabAll.setAlpha(0.6f);
        tabMedia.setAlpha(0.6f);
        tabTools.setAlpha(0.6f);
        if (selectedTab != null) selectedTab.setAlpha(1.0f);
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
                Toast.makeText(this, "Поиск не поддерживается", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void cleanRam() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo pi : procs) {
                        if (!pi.processName.equals(getPackageName())) {
                            am.killBackgroundProcesses(pi.processName);
                        }
                    }
                }
            }
            System.gc();
            Toast.makeText(this, "Память очищена! " + freeRamText(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Очистка RAM выполнена", Toast.LENGTH_SHORT).show();
        }
    }

    private String freeRamText() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mi);
                return (mi.availMem / (1024 * 1024)) + "M RAM";
            }
        } catch (Exception ignored) {
        }
        return "RAM";
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
                if (pos == RecyclerView.NO_POSITION || pos >= COLUMNS) {
                    focusTile(lastDockFocused);
                    return;
                }
            }
            if (pos != RecyclerView.NO_POSITION) {
                // Still on a tile: re-anchor the glass and hero without touching the list.
                onTileFocused(pos, focused);
                return;
            }
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        if (bridgeServer != null) {
            bridgeServer.stop();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(minuteTick);
        getContentResolver().unregisterContentObserver(bridgeObserver);
    }

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

    private void showShelfItemMenu(WatchNextManager.WatchItem item, View card) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = new PopupWindow(panel, (int) (260 * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        addMenuRow(rows, popup, R.drawable.ic_hide, getString(R.string.remove_from_shelf), () -> {
            if (item.fromBridge()) {
                WatchNextManager.removeBridgeItem(this, item);
            } else {
                WatchNextManager.removeItem(this, watchNextAdapter.localIndexOf(item));
            }
            refreshWatchNext();
            listWatchNext.post(() -> {
                if (layoutWatchNext.getVisibility() == View.VISIBLE && listWatchNext.getChildCount() > 0) {
                    listWatchNext.getChildAt(0).findViewById(R.id.card).requestFocus();
                } else {
                    focusTile(lastFocused);
                }
            });
        });
        popup.showAsDropDown(card, 0, (int) (8 * density), Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

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

    @Override
    public boolean onTileHorizontalKey(int position, int keyCode) {
        // Stay put at a row's ends: the focus finder would otherwise jump to the top bar or a tab.
        int col = position % COLUMNS;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return col == 0;
        return col == COLUMNS - 1 || position == adapter.getItemCount() - 1;
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        if (position < COLUMNS) lastDockFocused = position;
        setDockParked(position < COLUMNS);
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
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
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    @Override
    public void onAmbientColorReady(int position, int color) {
        if (position == lastFocused && position < COLUMNS && heroShown) {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(400).start();
        }
    }

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
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
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
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

    // ---- Context Menu & TV Remote Actions --------------------------------------------------------

    @Override
    public void onTileLongClicked(int position, View card, AppAdapter.Entry entry) {
        if (entry == null) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        int width = (int) (220 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(16f * density);

        AppPreferences prefs = adapter.getPreferences();
        boolean isDocked = position < COLUMNS;

        if (isDocked) {
            if (position > 0) {
                addMenuRow(rows, popup, R.drawable.ic_move, getString(R.string.move_left), () -> {
                    prefs.moveDockApp(entry.pkg, -1);
                    adapter.reload();
                    grid.post(() -> focusTile(position - 1));
                });
            }
            if (position < COLUMNS - 1) {
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
                prefs.pinToDock(entry.pkg, COLUMNS);
                adapter.reload();
                grid.post(() -> focusTile(0));
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
        panel.animate().alpha(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    // ---- Launcher Settings Modal -----------------------------------------------------------------

    private void showLauncherSettings() {
        View dialog = getLayoutInflater().inflate(R.layout.dialog_settings, root, false);

        int width = (int) (480 * density);
        final PopupWindow popup = new PopupWindow(dialog, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(24f * density);

        // VibeBridge can't listen yet (the launcher has no INTERNET permission), so don't advertise its address.
        dialog.findViewById(R.id.bridgeInfo).setVisibility(View.GONE);

        // RAM Clean Row
        TextView lblRamClean = dialog.findViewById(R.id.lblRamClean);
        lblRamClean.setText("Очистить оперативную память (" + freeRamText() + ")");
        dialog.findViewById(R.id.rowRamClean).setOnClickListener(v -> {
            cleanRam();
            lblRamClean.setText("Память очищена! " + freeRamText());
        });

        // Watch Next Toggle Row
        TextView lblToggle = dialog.findViewById(R.id.lblWatchNextToggle);
        lblToggle.setText("Полка «Продолжить просмотр»: " + (showWatchNext ? "Вкл" : "Выкл"));
        dialog.findViewById(R.id.rowToggleWatchNext).setOnClickListener(v -> {
            showWatchNext = !showWatchNext;
            uiPrefs.edit().putBoolean("show_watch_next", showWatchNext).apply();
            refreshWatchNext();
            lblToggle.setText("Полка «Продолжить просмотр»: " + (showWatchNext ? "Вкл" : "Выкл"));
        });

        // Unhide All Apps Row
        AppPreferences prefs = adapter.getPreferences();
        Set<String> hidden = prefs.getHiddenApps();
        TextView lblUnhide = dialog.findViewById(R.id.lblUnhideAll);
        lblUnhide.setText("Восстановить скрытые приложения (" + hidden.size() + ")");
        dialog.findViewById(R.id.rowUnhideAll).setOnClickListener(v -> {
            for (String p : new ArrayList<>(hidden)) {
                prefs.unhideApp(p);
            }
            adapter.reload();
            lblUnhide.setText("Все приложения восстановлены");
            Toast.makeText(this, "Все приложения восстановлены", Toast.LENGTH_SHORT).show();
        });

        // Reset Dock Order
        dialog.findViewById(R.id.rowResetDock).setOnClickListener(v -> {
            prefs.resetToDefaults();
            adapter.reload();
            Toast.makeText(this, "Порядок Dock сброшен", Toast.LENGTH_SHORT).show();
            popup.dismiss();
        });

        // System Settings
        dialog.findViewById(R.id.rowSystemSettings).setOnClickListener(v -> {
            popup.dismiss();
            open(new Intent(Settings.ACTION_SETTINGS));
        });

        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        dialog.findViewById(R.id.rowRamClean).requestFocus();
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
        btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
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
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
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
