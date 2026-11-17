package com.prath.carlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Palette, soft card surface, hand-drawn icons and the speed arc. No image assets. */
final class Draw {
    private Draw() {}

    static final class Pal {
        final int bg1, bg2, card, well, screen, shLight, shDark, text, dim, accent, onAccent;
        final boolean dark;
        Pal(boolean dark, int bg1, int bg2, int card, int well, int screen, int shLight, int shDark,
            int text, int dim, int accent, int onAccent) {
            this.dark = dark; this.bg1 = bg1; this.bg2 = bg2; this.card = card; this.well = well;
            this.screen = screen; this.shLight = shLight; this.shDark = shDark; this.text = text;
            this.dim = dim; this.accent = accent; this.onAccent = onAccent;
        }
    }

    static final Pal DAY = new Pal(false,
            0xFFF3F5F9, 0xFFE6EAF1, 0xFFF0F3F8, 0xFFE2E7EF, 0xFF1E242D,
            0xFFFFFFFF, 0x45A5B1C4, 0xFF161D27, 0xFF7A8597, 0xFF0EA898, 0xFFFFFFFF);
    static final Pal NIGHT = new Pal(true,
            0xFF13171D, 0xFF0B0E12, 0xFF171C23, 0xFF0F1318, 0xFF171C23,
            0x10FFFFFF, 0xD0000000, 0xFFE8ECF2, 0xFF7F899A, 0xFF2DD4BF, 0xFF0B0E12);

    /** Soft raised surface. Shadows are drawn inside the bounds (inset), so nothing gets clipped. */
    static final class Soft extends Drawable {
        final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG), hi = new Paint(Paint.ANTI_ALIAS_FLAG),
                lo = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float radius, inset;
        final int fill, pressedFill;
        boolean pressed;

        Soft(float dp, float radiusDp, int fill, Pal p) {
            radius = radiusDp * dp;
            inset = 11 * dp;
            this.fill = fill;
            pressedFill = p.well;
            float off = 5 * dp, blur = 12 * dp;
            hi.setColor(fill);
            hi.setShadowLayer(blur, -off, -off, p.shLight);
            lo.setColor(fill);
            lo.setShadowLayer(blur, off, off, p.shDark);
        }

        @Override public void draw(Canvas c) {
            RectF r = new RectF(getBounds());
            r.inset(inset, inset);
            if (!pressed) {
                c.drawRoundRect(r, radius, radius, lo);
                c.drawRoundRect(r, radius, radius, hi);
            }
            base.setColor(pressed ? pressedFill : fill);
            c.drawRoundRect(r, radius, radius, base);
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] state) {
            boolean p = false;
            for (int s : state) if (s == android.R.attr.state_pressed) p = true;
            if (p == pressed) return false;
            pressed = p;
            invalidateSelf();
            return true;
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter f) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Icons drawn on a 24-unit grid, scaled to whatever size the view gives them. */
    static final class Glyph extends Drawable {
        static final int PLUS = 0, PREV = 1, NEXT = 2, PLAY = 3, PAUSE = 4, GRID = 5, SUN = 6, MOON = 7, NOTE = 8,
                SPEAKER = 9, BACK = 10, CAMERA = 11;
        final int kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(int kind, int color) {
            this.kind = kind;
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas c) {
            float s = Math.min(getBounds().width(), getBounds().height()), u = s / 24f;
            c.save();
            c.translate(getBounds().centerX() - 12 * u, getBounds().centerY() - 12 * u);
            c.scale(u, u);
            p.setStrokeWidth(2f);
            switch (kind) {
                case PLUS:
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(12, 5, 12, 19, p);
                    c.drawLine(5, 12, 19, 12, p);
                    break;
                case PLAY:
                    fillPath(c, 8, 5, 19, 12, 8, 19);
                    break;
                case PAUSE:
                    p.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(6, 5, 10, 19, 1.5f, 1.5f, p);
                    c.drawRoundRect(14, 5, 18, 19, 1.5f, 1.5f, p);
                    break;
                case PREV:
                    fillPath(c, 18, 6, 9.5f, 12, 18, 18);
                    c.drawRoundRect(6, 6, 8, 18, 1f, 1f, p);
                    break;
                case NEXT:
                    fillPath(c, 6, 6, 14.5f, 12, 6, 18);
                    c.drawRoundRect(16, 6, 18, 18, 1f, 1f, p);
                    break;
                case GRID:
                    p.setStyle(Paint.Style.FILL);
                    for (int y = 6; y <= 18; y += 6) for (int x = 6; x <= 18; x += 6) c.drawCircle(x, y, 1.8f, p);
                    break;
                case SUN:
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(12, 12, 4.2f, p);
                    p.setStyle(Paint.Style.STROKE);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        float cx = (float) Math.cos(a), sy = (float) Math.sin(a);
                        c.drawLine(12 + 7 * cx, 12 + 7 * sy, 12 + 9.5f * cx, 12 + 9.5f * sy, p);
                    }
                    break;
                case MOON: {
                    Path a = new Path(), b = new Path();
                    a.addCircle(12, 12, 8, Path.Direction.CW);
                    b.addCircle(16.5f, 8.5f, 7, Path.Direction.CW);
                    a.op(b, Path.Op.DIFFERENCE);
                    p.setStyle(Paint.Style.FILL);
                    c.drawPath(a, p);
                    break;
                }
                case CAMERA: {
                    p.setStyle(Paint.Style.STROKE);
                    Path body = new Path();
                    body.moveTo(8, 7); body.lineTo(9.5f, 4.5f); body.lineTo(14.5f, 4.5f); body.lineTo(16, 7);
                    c.drawPath(body, p);
                    c.drawRoundRect(3, 7, 21, 19.5f, 2.5f, 2.5f, p);
                    c.drawCircle(12, 13.2f, 3.6f, p);
                    break;
                }
                case BACK:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.4f);
                    c.drawLine(5, 12, 19, 12, p);
                    c.drawLine(5, 12, 11, 6, p);
                    c.drawLine(5, 12, 11, 18, p);
                    break;
                case SPEAKER:
                    fillPath(c, 3, 9.5f, 7, 9.5f, 12, 5, 12, 19, 7, 14.5f, 3, 14.5f);
                    p.setStyle(Paint.Style.STROKE);
                    c.drawArc(9, 8, 17, 16, -50, 100, false, p);
                    c.drawArc(9, 4.5f, 20.5f, 19.5f, -50, 100, false, p);
                    break;
                case NOTE:
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(15, 17, 15, 5, p);
                    c.drawLine(15, 5, 19.5f, 7, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(12, 17, 3, p);
                    break;
            }
            c.restore();
        }

        private void fillPath(Canvas c, float... xy) {
            Path path = new Path();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
            path.close();
            p.setStyle(Paint.Style.FILL_AND_STROKE);
            p.setStrokeWidth(1.5f); // rounds the triangle corners
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(2f);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Pill slider: icon on the left, accent fill, percentage on the right. Drag anywhere to set. */
    static final class Meter extends View {
        interface OnChange { void set(float fraction); }

        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Glyph iconOn, iconOff;
        final RectF r = new RectF();
        final float dp;
        float value;
        boolean dragging;
        OnChange onChange;

        Meter(Context ctx, float dp, Pal p, int glyph) {
            super(ctx);
            this.dp = dp;
            track.setColor(p.well);
            fill.setColor(p.accent);
            label.setColor(p.dim);
            label.setTextSize(13 * dp * ctx.getResources().getConfiguration().fontScale);
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            label.setTextAlign(Paint.Align.RIGHT);
            iconOn = new Glyph(glyph, p.onAccent);
            iconOff = new Glyph(glyph, p.text);
        }

        /** Update from outside (e.g. steering-wheel volume); ignored while the finger is on it. */
        void setValue(float f) {
            if (dragging) return;
            value = Math.max(0, Math.min(1, f));
            invalidate();
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    // fall through
                case android.view.MotionEvent.ACTION_MOVE:
                    float h = getHeight();
                    value = Math.max(0, Math.min(1, (e.getX() - h / 2) / (getWidth() - h)));
                    invalidate();
                    if (onChange != null) onChange.set(value);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
            }
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2;
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, track);
            r.set(0, 0, h + (w - h) * value, h);
            c.drawRoundRect(r, rad, rad, fill);
            int pad = Math.round(h * 0.26f);
            Glyph icon = value * (w - h) > h * 0.1f ? iconOn : iconOff;
            icon.setBounds(pad, pad, Math.round(h) - pad, Math.round(h) - pad);
            icon.draw(c);
            label.setColor(r.right > w - 2.2f * rad ? iconOn.p.getColor() : iconOff.p.getColor() & 0xB0FFFFFF);
            float y = h / 2 - (label.descent() + label.ascent()) / 2;
            c.drawText(Math.round(value * 100) + "%", w - rad + 4 * dp, y, label);
        }
    }

    /** Thin progress line for the current track; tap or drag to seek. */
    static final class Bar extends View {
        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final float thick;
        float value;
        boolean dragging;
        Meter.OnChange onSeek; // called on release with the chosen fraction

        Bar(Context ctx, float dp, Pal p) {
            super(ctx);
            thick = 5 * dp;
            track.setColor(p.well);
            fill.setColor(p.accent);
        }

        void setValue(float f) {
            if (dragging) return;
            value = Math.max(0, Math.min(1, f));
            invalidate();
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            value = Math.max(0, Math.min(1, e.getX() / getWidth()));
            dragging = a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_MOVE;
            if (a == android.view.MotionEvent.ACTION_UP && onSeek != null) onSeek.set(value);
            invalidate();
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            float y = getHeight() / 2f, h = dragging ? thick * 1.6f : thick;
            r.set(0, y - h / 2, getWidth(), y + h / 2);
            c.drawRoundRect(r, h / 2, h / 2, track);
            r.right = Math.max(h, getWidth() * value);
            c.drawRoundRect(r, h / 2, h / 2, fill);
        }
    }

    /** 270° arc that sweeps smoothly to the current speed. */
    static final class Gauge extends View {
        static final float MAX = 160; // km/h at full sweep
        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        float shown;
        ValueAnimator anim;

        Gauge(Context ctx, float dp, Pal p) {
            super(ctx);
            for (Paint x : new Paint[]{track, arc}) {
                x.setStyle(Paint.Style.STROKE);
                x.setStrokeWidth(9 * dp);
                x.setStrokeCap(Paint.Cap.ROUND);
            }
            track.setColor((p.dim & 0x00FFFFFF) | 0x33000000);
            arc.setColor(p.accent);
        }

        void set(float kmh, boolean animate) {
            float to = Math.max(0, Math.min(kmh, MAX));
            if (anim != null) anim.cancel();
            if (!animate) { shown = to; invalidate(); return; }
            anim = ValueAnimator.ofFloat(shown, to).setDuration(800);
            anim.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        @Override protected void onDraw(Canvas c) {
            float half = track.getStrokeWidth() / 2;
            float d = Math.min(getWidth(), getHeight()) - 2 * half;
            float left = (getWidth() - d) / 2, top = (getHeight() - d) / 2;
            r.set(left, top, left + d, top + d);
            c.drawArc(r, 135, 270, false, track);
            if (shown > 0.5f) c.drawArc(r, 135, 270 * shown / MAX, false, arc);
        }
    }
}
