package com.gmail;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * Живой фон: три медленно движущихся световых пятна (полярное сияние)
 * и мерцающие звёзды. Рисуется только тогда, когда View на экране.
 */
public class AuroraView extends View {

    private static final int STAR_COUNT = 34;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starRadius = new float[STAR_COUNT];
    private final float[] starPhase = new float[STAR_COUNT];

    private final int[] glows = {
            Color.parseColor("#7C6BFF"),
            Color.parseColor("#3DE8C5"),
            Color.parseColor("#FF74B0")
    };

    private ValueAnimator animator;
    private float phase;
    private int widthPx;
    private int heightPx;

    public AuroraView(Context context) {
        super(context);
        init();
    }

    public AuroraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AuroraView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        Random random = new Random(20241116L);
        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] = random.nextFloat();
            starY[i] = random.nextFloat() * 0.85f;
            starRadius[i] = 0.6f + random.nextFloat() * 1.1f;
            starPhase[i] = random.nextFloat() * 6.28f;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        widthPx = w;
        heightPx = h;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    private void start() {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(18000L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (widthPx <= 0 || heightPx <= 0) {
            return;
        }

        double t = phase * Math.PI * 2.0;
        float big = Math.max(widthPx, heightPx);

        drawGlow(canvas,
                (float) (widthPx * (0.18 + 0.10 * Math.sin(t))),
                (float) (heightPx * (0.14 + 0.06 * Math.cos(t))),
                big * 0.70f, glows[0], 52);

        drawGlow(canvas,
                (float) (widthPx * (0.86 + 0.08 * Math.sin(t + 2.1))),
                (float) (heightPx * (0.34 + 0.10 * Math.cos(t * 2.0 + 1.3))),
                big * 0.58f, glows[1], 34);

        drawGlow(canvas,
                (float) (widthPx * (0.44 + 0.16 * Math.cos(t + 1.0))),
                (float) (heightPx * (0.80 + 0.08 * Math.sin(t * 2.0 + 0.4))),
                big * 0.52f, glows[2], 24);

        for (int i = 0; i < STAR_COUNT; i++) {
            float twinkle = (float) Math.abs(Math.sin(t + starPhase[i]));
            int alpha = (int) (28 + twinkle * 105);
            paint.setColor(Color.argb(alpha, 226, 233, 255));
            canvas.drawCircle(starX[i] * widthPx, starY[i] * heightPx,
                    starRadius[i] * 1.35f, paint);
        }
    }

    private void drawGlow(Canvas canvas, float cx, float cy, float radius, int color, int alpha) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        RadialGradient gradient = new RadialGradient(
                cx, cy, radius,
                new int[]{Color.argb(alpha, r, g, b), Color.argb(0, r, g, b)},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP);
        paint.setShader(gradient);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
    }
}
