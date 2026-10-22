package com.example.autolinkprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;

import org.maplibre.android.geometry.LatLng;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Plain Android Canvas raster-road renderer for the AutoLink VirtualDisplay.
 *
 * The phone could display MapLibre roads, but the GPU layer was blank on the
 * head unit. MapSnapshotter produced white PNGs there, too. This renderer
 * fetches only visible 256px OSM raster tiles and paints them as ordinary
 * software bitmaps, just like the working route/hud Android Views.
 *
 * Service usage: exact OSM tile URL, app-identifying UA, >=7-day local cache,
 * visible copyright attribution in NavigatorPresentation, no tile prefetch.
 * OSM standard is a TEMPORARY free-road fallback, not offline navigation.
 */
final class RoadTileView extends View {
    private static final int TILE = 256;
    private static final double MAX_LAT = 85.05112878;
    private static final long TTL_MS = 7L * 24 * 3600 * 1000;
    private static final String USER_AGENT =
            "GazelleNavigator/0.5 (+https://github.com/arladiy96-commits/Magnitola)";
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LruCache<String, Bitmap> ram = new LruCache<String, Bitmap>(36 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private final ConcurrentHashMap<String, Boolean> waiting = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
            new ThreadPoolExecutor.DiscardPolicy());
    private final File cacheDir;
    private final Consumer<String> report;
    private volatile int successfulTiles, failedTiles;
    private volatile String lastError = "";
    private volatile boolean closed;
    private double latitude = Double.NaN, longitude = Double.NaN;
    private float direction;
    private int zoom = 17;
    private RoutePlanner.Route route;
    private int lastPaintedTiles;

    RoadTileView(Context context, Consumer<String> reporter) {
        super(context);
        report = reporter;
        cacheDir = new File(context.getCacheDir(), "gazelle_road_tiles");
        cacheDir.mkdirs();
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    int getSuccessfulTiles() { return successfulTiles; }
    String getLastError() { return lastError; }
    int getLastPaintedTiles() { return lastPaintedTiles; }

    void update(Location fix, float mapBearing, boolean hasBearing) {
        if (closed) return;
        latitude = Math.max(-MAX_LAT, Math.min(MAX_LAT, fix.getLatitude()));
        longitude = fix.getLongitude();
        direction = hasBearing ? mapBearing : 0f;
        // Nearby streets should be clearly visible without overly zooming in
        // at highway speeds. Use integer zoom to avoid over-scaling PNG labels.
        int nextZoom = fix.hasSpeed() && fix.getSpeed() > 20f ? 16 : 17;
        if (zoom != nextZoom) zoom = nextZoom;
        invalidate();
    }

    void setRoute(RoutePlanner.Route next) {
        route = next;
        invalidate();
    }

    void release() {
        closed = true;
        pool.shutdownNow();
        ram.evictAll();
        waiting.clear();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFFE9EDEA);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final int z = zoom;
        final int n = 1 << z;
        final double world = (double) TILE * n;
        final double centerX = worldX(longitude, world);
        final double centerY = worldY(latitude, world);
        final double radians = Math.toRadians(direction);
        final double c = Math.abs(Math.cos(radians)), s = Math.abs(Math.sin(radians));
        final double halfX = c * w / 2.0 + s * h / 2.0;
        final double halfY = s * w / 2.0 + c * h / 2.0;
        int x0 = (int) Math.floor((centerX - halfX) / TILE);
        int x1 = (int) Math.floor((centerX + halfX) / TILE);
        int y0 = Math.max(0, (int) Math.floor((centerY - halfY) / TILE));
        int y1 = Math.min(n - 1, (int) Math.floor((centerY + halfY) / TILE));

        canvas.save();
        canvas.rotate(-direction, w / 2f, h / 2f);
        int painted = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                final int tileX = (x % n + n) % n;
                final String key = z + "/" + tileX + "/" + y;
                Bitmap tile = ram.get(key);
                final float left = (float) (w / 2.0 + x * TILE - centerX);
                final float top = (float) (h / 2.0 + y * TILE - centerY);
                if (tile != null && !tile.isRecycled()) {
                    canvas.drawBitmap(tile, left, top, paint);
                    painted++;
                } else {
                    queueTile(z, tileX, y, key);
                }
            }
        }
        drawRoute(canvas, centerX, centerY, world, w, h);
        canvas.restore();
        lastPaintedTiles = painted;
    }

    private void drawRoute(Canvas canvas, double centerX, double centerY,
                           double world, int w, int h) {
        RoutePlanner.Route planned = route;
        if (planned == null || planned.geometry.size() < 2) return;
        Path line = new Path();
        boolean started = false;
        int size = planned.geometry.size();
        int stride = Math.max(1, size / 4500);
        for (int i = 0; i < size; i += stride) {
            LatLng p = planned.geometry.get(i);
            float x = (float) (w / 2.0 + worldX(p.getLongitude(), world) - centerX);
            float y = (float) (h / 2.0 + worldY(p.getLatitude(), world) - centerY);
            if (!Float.isFinite(x) || !Float.isFinite(y)) continue;
            if (!started) { line.moveTo(x, y); started = true; }
            else line.lineTo(x, y);
        }
        LatLng finalPoint = planned.geometry.get(size - 1);
        line.lineTo((float) (w / 2.0 + worldX(finalPoint.getLongitude(), world) - centerX),
                (float) (h / 2.0 + worldY(finalPoint.getLatitude(), world) - centerY));
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeJoin(Paint.Join.ROUND);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeWidth(13f);
        routePaint.setColor(Color.WHITE);
        canvas.drawPath(line, routePaint);
        routePaint.setStrokeWidth(8f);
        routePaint.setColor(0xFF1780F7);
        canvas.drawPath(line, routePaint);
    }

    private static double worldX(double lon, double world) {
        return (lon + 180.0) / 360.0 * world;
    }
    private static double worldY(double lat, double world) {
        double radians = Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, lat)));
        return (1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI)
                * 0.5 * world;
    }

    private void queueTile(int z, int x, int y, String key) {
        if (closed || waiting.size() >= 32 || waiting.putIfAbsent(key, Boolean.TRUE) != null) return;
        try {
            pool.execute(() -> {
                try {
                    Bitmap image = obtainTile(z, x, y);
                    if (closed) return;
                    if (image != null) {
                        ram.put(key, image);
                        int count = ++successfulTiles;
                        if (count == 1 || count == 4 || count == 12 || count % 48 == 0) {
                            report.accept("ДОРОГИ: загружено PNG-плиток " + count
                                    + ", ошибок " + failedTiles);
                        }
                        postInvalidate();
                    }
                } catch (Exception error) {
                    failedTiles++;
                    lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
                    if (failedTiles <= 3 || failedTiles % 20 == 0)
                        report.accept("ДОРОГИ: ошибка плитки " + key + ": " + lastError);
                } finally {
                    waiting.remove(key);
                }
            });
        } catch (RuntimeException ignored) {
            waiting.remove(key);
        }
    }

    private Bitmap obtainTile(int z, int x, int y) throws IOException {
        File dir = new File(cacheDir, z + "/" + x);
        File file = new File(dir, y + ".png");
        long now = System.currentTimeMillis();
        if (file.isFile() && file.length() > 0
                && now - file.lastModified() < TTL_MS) {
            try (FileInputStream in = new FileInputStream(file)) {
                Bitmap cached = BitmapFactory.decodeStream(in);
                if (cached != null) return cached;
            } catch (IOException ignored) { }
        }

        URL url = new URL("https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(6500);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "image/png");
        if (file.isFile()) connection.setIfModifiedSince(file.lastModified());
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && file.isFile()) {
                file.setLastModified(now);
                try (FileInputStream in = new FileInputStream(file)) {
                    return BitmapFactory.decodeStream(in);
                }
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(32 * 1024);
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[8192];
                int len;
                while ((len = input.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, len);
                    if (buffer.size() > 600_000) throw new IOException("Tile response too large");
                }
            }
            byte[] bytes = buffer.toByteArray();
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (decoded == null) throw new IOException("PNG decode failed");
            if (dir.exists() || dir.mkdirs()) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                } catch (IOException ignored) { /* Still display downloaded tile */ }
            }
            return decoded;
        } finally {
            connection.disconnect();
        }
    }
}
