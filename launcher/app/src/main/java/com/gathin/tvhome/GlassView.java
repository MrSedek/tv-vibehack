package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * High-performance translucent glass highlight:
 * - Fluid VisionOS/tvOS glass sheen
 * - Crisp specular rim highlight
 * - Outer glow without expensive software clipPath or multiple canvas overdraws
 * - Optimized for Mali-G31 / Cortex-A35 GPU hardware rendering pipeline.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF glowRect = new RectF();
    private final RectF specularRect = new RectF();
    private final float density;
    private float radius;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;

        // Hardware-accelerated rendering
        setLayerType(LAYER_TYPE_HARDWARE, null);

        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.2f * density);
        rim.setColor(0xE6FFFFFF);

        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(3.5f * density);
        glow.setColor(0x28FFFFFF);
    }

    void setRadius(float px) {
        if (Math.abs(radius - px) > 0.5f) {
            radius = px;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        // Soft glass gradient: luminous at top, airy translucent at bottom
        fill.setShader(new LinearGradient(
                0, 0, 0, h,
                new int[]{0x3AFFFFFF, 0x14FFFFFF, 0x0AFFFFFF},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP
        ));

        // Specular sheen for top half of tile
        specular.setShader(new LinearGradient(
                0, 0, 0, h * 0.40f,
                0x4DFFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 5f * density;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // 1. Single soft ambient outer glow pass (replaces 3 heavy overdraw loops)
        glowRect.set(rect.left - 1.5f * density, rect.top - 1.5f * density,
                rect.right + 1.5f * density, rect.bottom + 1.5f * density);
        canvas.drawRoundRect(glowRect, radius + 1.5f * density, radius + 1.5f * density, glow);

        // 2. Translucent glass substrate
        canvas.drawRoundRect(rect, radius, radius, fill);

        // 3. Specular upper highlight without expensive clipPath
        specularRect.set(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.40f);
        canvas.drawRoundRect(specularRect, radius, radius, specular);

        // 4. Razor-sharp glass rim
        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
