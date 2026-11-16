package com.gmail;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/** Конфетти на экране успешной отправки. Анимация останавливается сама. */
public class ConfettiView extends View {

    private static final int COUNT = 56;

    private static final int[] PALETTE = {
            Color.parseColor("#7C6BFF"),
            Color.parseColor("#3DE8C5"),
            Color.parseColor("#FF74B0"),
            Color.parseColor("#FFC16B"),
            Color.parseColor("#AC9DFF"),
            Color.parseColor("#FFFFFF")
    };

    private static final class Particle {
        float x;
        float y;
        float speed;
        float size;
        float spin;
        float seed;
        int color;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Particle[] particles = new Particle[COUNT];
    private ValueAnimator animator;
    private float progress;
    private boolean running;

    public ConfettiView(Context context) {
        super(context);
        init();
    }

    public ConfettiView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ConfettiView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        for (int i = 0; i < COUNT; i++) {
            particles[i] = new Particle();
        }
    }

    public void start() {
        Random random = new Random(System.nanoTime());
        for (int i = 0; i < COUNT; i++) {
            Particle p = particles[i];
            p.x = random.nextFloat();
            p.y = -0.25f - random.nextFloat() * 0.55f;
            p.speed = 0.75f + random.nextFloat() * 0.9f;
            p.size = 2.5f + random.nextFloat() * 4f;
            p.spin = 0.4f + random.nextFloat() * 1.4f;
            p.seed = random.nextFloat() * 6.28f;
            p.color = PALETTE[random.nextInt(PALETTE.length)];
        }

        if (animator != null) {
            animator.cancel();
        }
        progress = 0f;
        running = true;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(3000L);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                running = false;
            }
        });
        animator.start();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!running || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        float fade = progress < 0.72f ? 1f : Math.max(0f, (1f - progress) / 0.28f);
        float w = getWidth();
        float h = getHeight();

        for (Particle p : particles) {
            float py = (p.y + progress * p.speed) * h;
            if (py < -20f || py > h + 20f) {
                continue;
            }
            float px = p.x * w + (float) Math.sin(progress * 7f + p.seed) * w * 0.035f;
            float rotation = (progress * 760f * p.spin + p.seed * 55f) % 360f;

            paint.setColor(p.color);
            paint.setAlpha((int) (225 * fade));
            canvas.save();
            canvas.rotate(rotation, px, py);
            canvas.drawRoundRect(px - p.size, py - p.size * 1.9f,
                    px + p.size, py + p.size * 1.9f,
                    p.size * 0.45f, p.size * 0.45f, paint);
            canvas.restore();
        }
    }
}
