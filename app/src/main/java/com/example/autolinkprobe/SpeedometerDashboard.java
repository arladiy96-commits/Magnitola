package com.example.autolinkprobe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.location.Location;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A real GPS dashboard shared by the phone preview and the H.264 encoder.
 *  All measurements are derived from Android Location, never demo values.
 */
final class SpeedometerDashboard {
    private static final int W = 1280, H = 720;
    private static final int CYAN = 0xff29e9f3, GOLD = 0xffffbe59;
    private static final int WHITE = 0xfff4f9fc, MUTED = 0xff91a6b8;
    private final Object gpsLock = new Object();
    private final Bitmap background;
    private double filteredKmh;
    private double tripKm;
    private long movingMs;
    private long firstFixMs = -1, lastFixMs = -1;
    private Location previous;
    private boolean speedValid;
    private float bearing = Float.NaN;
    private int accuracy = -1;

    SpeedometerDashboard() {
        background = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        drawBackground(new Canvas(background));
    }

    void onLocation(Location fix) {
        if (fix == null || !fix.hasAccuracy() || fix.getAccuracy() > 80f) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (gpsLock) {
            if (lastFixMs > now) return;
            accuracy = Math.round(fix.getAccuracy());
            if (firstFixMs < 0) firstFixMs = now;
            if (fix.hasSpeed() && Float.isFinite(fix.getSpeed())) {
                double kmh = Math.max(0, Math.min(240, fix.getSpeed() * 3.6));
                double dt = lastFixMs < 0 ? 1000 : Math.max(100, now - lastFixMs);
                double alpha = 1.0 - Math.exp(-dt / 550.0);
                filteredKmh = !speedValid || lastFixMs < 0 || dt > 5000
                        ? kmh : filteredKmh + alpha * (kmh - filteredKmh);
                if (kmh < 1.3 && filteredKmh < 2) filteredKmh = 0;
                speedValid = true;
            } else {
                speedValid = false;
            }
            if (fix.hasBearing() && fix.hasSpeed() && fix.getSpeed() > 1.2f)
                bearing = fix.getBearing();
            if (previous != null && lastFixMs > 0) {
                long delta = now - lastFixMs;
                float metres = previous.distanceTo(fix);
                if (delta > 0 && delta < 12000 && metres > 1.5f
                        && metres < Math.max(5, delta * 0.045f)
                        && fix.getAccuracy() < 25f && previous.getAccuracy() < 25f
                        && fix.hasSpeed() && fix.getSpeed() > 0.9f) {
                    tripKm += metres / 1000.0;
                    movingMs += delta;
                }
            }
            previous = new Location(fix);
            lastFixMs = now;
        }
    }

    void draw(Canvas canvas, int width, int height) {
        canvas.save();
        canvas.drawColor(0xff030a10);
        canvas.scale(width / (float) W, height / (float) H);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setFilterBitmap(true);
        canvas.drawBitmap(background, 0, 0, p);
        double speed, km;
        long elapsed, last, move;
        boolean valid;
        int acc;
        float course;
        synchronized (gpsLock) {
            speed = filteredKmh;
            km = tripKm;
            last = lastFixMs;
            elapsed = firstFixMs < 0 ? 0 : SystemClock.elapsedRealtime() - firstFixMs;
            move = movingMs;
            valid = speedValid;
            course = bearing;
            acc = accuracy;
        }
        boolean gpsFresh = last >= 0 && SystemClock.elapsedRealtime() - last < 5000;
        if (!gpsFresh) valid = false;
        float animatedSpeed = (float) Math.min(160, speed);
        if (valid) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(17);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setShader(new LinearGradient(365, 530, 919, 155,
                    CYAN, 0xff62f5f1, Shader.TileMode.CLAMP));
            RectF arc = new RectF(375, 102, 905, 632);
            canvas.drawArc(arc, 151, 238 * animatedSpeed / 160f, false, p);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
        }
        String amount = valid ? String.valueOf(Math.round(speed)) : "—";
        write(canvas, p, amount, 640, 425, 198, WHITE, true);
        write(canvas, p, "км/ч", 640, 486, 35, MUTED, false);
        write(canvas, p, new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date()), 56, 76, 49, WHITE, false, Paint.Align.LEFT);
        write(canvas, p, "GPS", 1065, 60, 21, WHITE, true, Paint.Align.LEFT);
        write(canvas, p, gpsFresh ? "±" + acc + " м" : "ПОИСК СПУТНИКОВ",
                1065, 92, 19, gpsFresh ? CYAN : GOLD, false, Paint.Align.LEFT);
        write(canvas, p, "ПОЕЗДКА", 88, 242, 20, MUTED, false, Paint.Align.LEFT);
        write(canvas, p, String.format(Locale.getDefault(), "%.1f", km),
                88, 305, 43, WHITE, true, Paint.Align.LEFT);
        write(canvas, p, "км", 245, 304, 22, MUTED, false, Paint.Align.LEFT);
        line(canvas, p, 88, 330, 315, 330, 0xff283b4b, 1);
        write(canvas, p, "В ПУТИ", 88, 370, 19, MUTED, false, Paint.Align.LEFT);
        write(canvas, p, String.format(Locale.getDefault(), "%d:%02d",
                        elapsed / 3600000, elapsed / 60000 % 60),
                88, 418, 31, WHITE, false, Paint.Align.LEFT);
        line(canvas, p, 88, 443, 315, 443, 0xff283b4b, 1);
        write(canvas, p, "СРЕДНЯЯ", 88, 483, 19, MUTED, false, Paint.Align.LEFT);
        write(canvas, p, movingMsSpeed(km, move), 88, 531, 30, WHITE, false, Paint.Align.LEFT);
        write(canvas, p, "НАПРАВЛЕНИЕ", 1010, 245, 19, MUTED, false, Paint.Align.CENTER);
        String compass = gpsFresh && !Float.isNaN(course) ? heading(course) : "—";
        write(canvas, p, compass, 1010, 402, 64, WHITE, true);
        if (gpsFresh && !Float.isNaN(course)) {
            canvas.save();
            canvas.rotate(course, 1010, 334);
            Path arrow = new Path();
            arrow.moveTo(1010, 287); arrow.lineTo(992, 347);
            arrow.lineTo(1010, 333); arrow.lineTo(1028, 347); arrow.close();
            p.setColor(GOLD); p.setStyle(Paint.Style.FILL);
            canvas.drawPath(arrow, p);
            canvas.restore();
        }
        write(canvas, p, gpsFresh && !Float.isNaN(course)
                ? Math.round(course) + "°" : "Нет курса", 1010, 457, 23, MUTED, false);
        // A restrained lower readout, not a fictional fuel gauge or navigation.
        write(canvas, p, gpsFresh ? "GPS • АКТИВЕН" : "GPS • НЕТ СИГНАЛА",
                640, 667, 19, gpsFresh ? CYAN : GOLD, false);
        canvas.restore();
    }

    private String movingMsSpeed(double km, long ms) {
        return ms > 0 ? String.format(Locale.getDefault(), "%.0f км/ч",
                Math.min(240, km * 3600000.0 / ms)) : "— км/ч";
    }
    private static String heading(float angle) {
        String[] sectors = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};
        return sectors[Math.round(angle / 45f) % 8];
    }
    private static void drawBackground(Canvas c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, 720,
                new int[]{0xff06131b, 0xff02080d, 0xff0a1821},
                null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1280, 720, p); p.setShader(null);
        p.setShader(new RadialGradient(640, 298, 670,
                new int[]{0xff123342, 0x00102434},
                null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1280, 720, p); p.setShader(null);
        // Subtle horizon / dashboard wings from the approved automotive reference.
        Path wing = new Path(); wing.moveTo(0, 555);
        wing.cubicTo(190, 540, 295, 590, 465, 577);
        wing.cubicTo(550, 569, 575, 590, 640, 590);
        wing.cubicTo(820, 583, 995, 540, 1280, 555);
        wing.lineTo(1280, 720); wing.lineTo(0, 720); wing.close();
        p.setShader(new LinearGradient(0, 555, 0, 720,
                0xff16252e, 0xff040c12, Shader.TileMode.CLAMP));
        c.drawPath(wing, p); p.setShader(null);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2);
        p.setColor(0x6646dbe9);
        c.drawArc(new RectF(210, 530, 1070, 900), 192, 156, false, p);
        p.setColor(0x88e0a64a);
        c.drawArc(new RectF(-450, 572, 550, 920), 204, 58, false, p);
        c.drawArc(new RectF(720, 572, 1720, 920), 278, 58, false, p);
        p.setStyle(Paint.Style.FILL);
        // Bezel, accurately spaced marks, and premium glow restraint.
        RectF arc = new RectF(375, 102, 905, 632);
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xff1c3541); p.setStrokeWidth(18);
        c.drawArc(arc, 151, 238, false, p);
        p.setStrokeWidth(1.4f); p.setColor(0xff294551);
        c.drawCircle(640, 367, 223, p);
        p.setColor(0xff102735); c.drawCircle(640, 367, 214, p);
        for (int i = 0; i <= 160; i += 2) {
            double a = Math.toRadians(151 + 238.0 * i / 160);
            int tick = i % 20 == 0 ? 23 : i % 10 == 0 ? 14 : 6;
            float x1 = (float) (640 + Math.cos(a) * (288 - tick));
            float y1 = (float) (367 + Math.sin(a) * (288 - tick));
            float x2 = (float) (640 + Math.cos(a) * 288);
            float y2 = (float) (367 + Math.sin(a) * 288);
            line(c, p, x1, y1, x2, y2,
                    i < 95 ? 0xff649eac : 0xffbd9557, i % 20 == 0 ? 3 : 1.2f);
        }
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3);
        p.setColor(0x99e7a752); c.drawArc(arc, 318, 71, false, p);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i <= 160; i += 20) {
            double a = Math.toRadians(151 + 238.0 * i / 160);
            float x = (float) (640 + Math.cos(a) * 239);
            float y = (float) (367 + Math.sin(a) * 239 + 10);
            write(c, p, String.valueOf(i), x, y, 24, 0xffa6b7c1, false);
        }
        p.setColor(0xff1b3542); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1);
        c.drawRoundRect(new RectF(57, 209, 337, 550), 25, 25, p);
        c.drawRoundRect(new RectF(920, 211, 1108, 484), 27, 27, p);
        p.setStyle(Paint.Style.FILL);
        line(c, p, 54, 110, 280, 110, 0xff23404e, 1.5f);
        line(c, p, 975, 112, 1225, 112, 0xff23404e, 1.5f);
        line(c, p, 465, 629, 815, 629, 0x9968b7bf, 1f);
    }
    private static void line(Canvas c, Paint p, float x1, float y1,
                             float x2, float y2, int color, float width) {
        p.setShader(null); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width); p.setColor(color);
        c.drawLine(x1, y1, x2, y2, p);
        p.setStyle(Paint.Style.FILL);
    }
    private static void write(Canvas c, Paint p, String s, float x, float y,
                              float size, int color, boolean bold) {
        write(c, p, s, x, y, size, color, bold, Paint.Align.CENTER);
    }
    private static void write(Canvas c, Paint p, String s, float x, float y,
                              float size, int color, boolean bold, Paint.Align align) {
        p.setShader(null); p.clearShadowLayer(); p.setStyle(Paint.Style.FILL);
        p.setColor(color); p.setTextSize(size);
        p.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif-light",
                Typeface.NORMAL));
        p.setTextAlign(align);
        c.drawText(s, x, y, p);
    }
}
