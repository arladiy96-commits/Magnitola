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
    private static final SpeedometerDashboard SINGLETON = new SpeedometerDashboard();
    static SpeedometerDashboard get() { return SINGLETON; }
    private static final int CYAN = 0xff29e9f3, GOLD = 0xffffbe59;
    private static final int WHITE = 0xfff4f9fc, MUTED = 0xff91a6b8;
    private final Object gpsLock = new Object();
    private final Bitmap background;
    private double filteredKmh;
    private double tripKm;
    private double altitude = Double.NaN;
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
            if (fix.hasAltitude() && Double.isFinite(fix.getAltitude())) altitude = fix.getAltitude();
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
        double speed, km, elevation;
        long elapsed, last, move;
        boolean valid;
        int acc;
        float course;
        synchronized (gpsLock) {
            speed = filteredKmh;
            km = tripKm;
            elevation = altitude;
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
        if (valid) PremiumDialArt.progress(canvas, p, animatedSpeed);
        String amount = valid ? String.valueOf(Math.round(speed)) : "—";
        write(canvas, p, amount, 640, 425, 198, WHITE, true);
        write(canvas, p, "км/ч", 640, 486, 35, MUTED, false);
        write(canvas, p, new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date()), 56, 76, 49, WHITE, false, Paint.Align.LEFT);
        write(canvas, p, "GPS", 1065, 60, 21, WHITE, true, Paint.Align.LEFT);
        write(canvas, p, gpsFresh ? "±" + acc + " м" : "ПОИСК СПУТНИКОВ",
                1065, 92, 19, gpsFresh ? CYAN : GOLD, false, Paint.Align.LEFT);
        write(canvas, p, "ПОЕЗДКА", 88, 263, 20, MUTED, false, Paint.Align.LEFT);
        write(canvas, p, String.format(Locale.getDefault(), "%.1f", km),
                88, 305, 43, WHITE, true, Paint.Align.LEFT);
        write(canvas, p, "км", 245, 304, 22, MUTED, false, Paint.Align.LEFT);
        line(canvas, p, 88, 330, 315, 330, 0xff283b4b, 1);
        write(canvas, p, "В ПУТИ", 88, 370, 19, MUTED, false, Paint.Align.LEFT);
        write(canvas, p, String.format(Locale.getDefault(), "%d:%02d",
                        elapsed / 3600000, elapsed / 60000 % 60),
                88, 418, 31, WHITE, false, Paint.Align.LEFT);
        line(canvas, p, 88, 443, 315, 443, 0xff283b4b, 1);
        write(canvas, p, "СРЕДНЯЯ", 88, 473, 19, MUTED, false, Paint.Align.LEFT);
        write(canvas, p, movingMsSpeed(km, move), 88, 510, 30, WHITE, false, Paint.Align.LEFT);
        write(canvas, p, "НАПРАВЛЕНИЕ", 1105, 254, 19, MUTED, false, Paint.Align.CENTER);
        String compass = gpsFresh && !Float.isNaN(course) ? heading(course) : "—";
        write(canvas, p, compass, 1105, 436, 42, WHITE, true);
        if (gpsFresh && !Float.isNaN(course)) {
            canvas.save();
            canvas.rotate(course, 1105, 354);
            Path arrow = new Path();
            arrow.moveTo(1105, 310); arrow.lineTo(1087, 373);
            arrow.lineTo(1105, 360); arrow.lineTo(1123, 373); arrow.close();
            p.setColor(GOLD); p.setStyle(Paint.Style.FILL);
            canvas.drawPath(arrow, p);
            canvas.restore();
        }
        write(canvas, p, gpsFresh && !Float.isNaN(course)
                ? Math.round(course) + "°" : "Нет курса", 1105, 485, 23, MUTED, false);
        // Only real on-device data: no invented fuel usage or navigation.
        write(canvas, p, "ВЫСОТА", 420, 662, 17, MUTED, false);
        write(canvas, p, gpsFresh && Double.isFinite(elevation)
                ? Math.round(elevation) + " м" : "—", 420, 699, 28, WHITE, false);
        write(canvas, p, "ПРОБЕГ", 640, 662, 17, MUTED, false);
        write(canvas, p, String.format(Locale.getDefault(), "%.1f км", km),
                640, 699, 28, WHITE, false);
        write(canvas, p, "ВРЕМЯ", 865, 662, 17, MUTED, false);
        write(canvas, p, String.format(Locale.getDefault(), "%d:%02d",
                elapsed / 3600000, elapsed / 60000 % 60),
                865, 699, 28, WHITE, false);
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
    private static void drawBackground(Canvas c) { PremiumDialArt.draw(c); }
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
