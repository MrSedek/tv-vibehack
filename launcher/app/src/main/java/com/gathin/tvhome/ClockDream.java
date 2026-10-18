package com.gathin.tvhome;

import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;

import java.util.Random;

/**
 * Screensaver: a large clock on black with a glow in the colour of the last dock app, drifting a
 * little every minute so nothing burns in. Chosen with
 * {@code settings put secure screensaver_components com.gathin.tvhome/.ClockDream} (the install
 * script does it).
 */
public class ClockDream extends DreamService {

    private static final long DRIFT_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View block;
    private float density;
    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            float range = 48 * density;
            block.animate()
                    .translationX((random.nextFloat() * 2 - 1) * range)
                    .translationY((random.nextFloat() * 2 - 1) * range)
                    .setDuration(2400)
                    .start();
            handler.postDelayed(this, DRIFT_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        density = getResources().getDisplayMetrics().density;
        block = findViewById(R.id.dreamBlock);

        int color = getSharedPreferences(MainActivity.UI_PREFS, MODE_PRIVATE).getInt(MainActivity.PREF_AMBIENT, 0xFF2C3E50);
        int c = color & 0x00FFFFFF;
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.5f, 0.55f);
        glow.setColors(new int[]{0x33000000 | c, 0x14000000 | c, 0x00000000 | c});
        View ambient = findViewById(R.id.dreamAmbient);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> glow.setGradientRadius((r - l) * 0.5f));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        handler.postDelayed(drift, DRIFT_MS);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(drift);
        super.onDreamingStopped();
    }
}
