package com.example.autolinkprobe;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.Style;
import org.maplibre.android.snapshotter.MapSnapshot;
import org.maplibre.android.snapshotter.MapSnapshotter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Offscreen MapLibre -> ordinary Bitmap/ImageView -> PRIVATE VirtualDisplay.
 *
 * MapLibre's TextureView/GLSurfaceView was invisible through the specific
 * Sunplus/AutoLink MediaCodec virtual-display path while normal HUD Views
 * appeared. MapSnapshotter renders the vector map offscreen and returns a
 * Bitmap, which Android then composites together with the already-working
 * speedometer and HUD using the normal View hierarchy.
 *
 * This is a compatibility mode, not yet a high-FPS production renderer:
 * moving ~1 image / 1.5 s (depending on MapLibre render duration).
 */
final class NavigatorPresentation extends Presentation {
    private static final Locale RU = new Locale("ru", "KZ");
    private static final String STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
    private static final int ROUTE_BLUE = 0xFF1682FB;
    private static final int SNAP_WIDTH = 1024;
    private static final int SNAP_HEIGHT = 600;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> report;
    private ImageView mapImage;
    private TextView mapStatus, gpsState, speedValue;
    private TextView turnIcon, turnDistance, turnInstruction, distanceRemaining, eta, routeLabel;
    private View carArrow;
    private MapSnapshotter snapshotter;
    private RoutePlanner.Route activeRoute;
    private volatile Location lastFix;
    private double[] metersToFinish;
    private int lastRouteVertex;
    private int snapshotCount;
    private int snapshotFailures;
    private boolean snapshotInFlight;
    private long requestStartedAt;
    private long latestBitmapAt;
    private int currentGeneration;
    private float bearing;
    private boolean headingValid;
    private boolean alive;
    private final NavigationLocationService.Observer gpsObserver = this::onGps;

    private final Runnable scheduler = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            if (snapshotInFlight && SystemClock.elapsedRealtime() - requestStartedAt > 18000) {
                report.accept("КАРТА: MapSnapshotter завис дольше 18 с, пересоздаю рендерер");
                replaceSnapshotter();
            }
            if (!snapshotInFlight && lastFix != null) {
                boolean moving = lastFix.hasSpeed() && lastFix.getSpeed() > 1.3f;
                long minDelay = moving ? 1500L : 7500L;
                if (snapshotCount == 0 || SystemClock.elapsedRealtime() - latestBitmapAt >= minDelay) {
                    takeSnapshot();
                }
            }
            main.postDelayed(this, 650);
        }
    };

    NavigatorPresentation(Context context, Display display, Consumer<String> logger) {
        super(context, display);
        report = logger;
    }

    private int dp(float value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    private GradientDrawable background(int argb, int r) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(argb);
        shape.setCornerRadius(dp(r));
        return shape;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        alive = true;
        MapLibre.getInstance(getContext().getApplicationContext());
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(0xFFDDE9E7);
        mapImage = new ImageView(getContext());
        mapImage.setScaleType(ImageView.ScaleType.FIT_XY);
        mapImage.setBackgroundColor(0xFFE7ECE7);
        root.addView(mapImage, new FrameLayout.LayoutParams(-1, -1));

        // Separate standard Android Views work on the head unit even where
        // the original MapLibre hardware TextureView did not.
        carArrow = new Arrow(getContext());
        carArrow.setVisibility(View.INVISIBLE);
        root.addView(carArrow, new FrameLayout.LayoutParams(dp(88), dp(96), Gravity.CENTER));

        mapStatus = text("GPS: ожидаю координаты для карты", 21, 0xFF1B3C51, true);
        mapStatus.setGravity(Gravity.CENTER);
        mapStatus.setBackground(background(0xEAFFFFFF, 12));
        FrameLayout.LayoutParams progress = new FrameLayout.LayoutParams(dp(660), dp(62), Gravity.CENTER);
        root.addView(mapStatus, progress);

        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(15), dp(12), dp(15), dp(12));
        top.setBackground(background(0xF3112837, 13));
        FrameLayout.LayoutParams topLayout = new FrameLayout.LayoutParams(-1, dp(100), Gravity.TOP);
        topLayout.setMargins(dp(12), dp(10), dp(12), 0);
        root.addView(top, topLayout);

        turnIcon = text("↑", 51, Color.WHITE, true);
        turnIcon.setGravity(Gravity.CENTER);
        turnIcon.setBackground(background(0xFF1168D2, 10));
        top.addView(turnIcon, new LinearLayout.LayoutParams(dp(83), -1));

        LinearLayout turn = new LinearLayout(getContext());
        turn.setOrientation(LinearLayout.VERTICAL);
        turn.setGravity(Gravity.CENTER_VERTICAL);
        turn.setPadding(dp(16), 0, dp(6), 0);
        turnDistance = text("СВОБОДНАЯ ЕЗДА", 30, Color.WHITE, true);
        turnInstruction = text("Маршрут не нужен — просто едь", 19, 0xFFBED9EB, false);
        turnInstruction.setPadding(0, dp(5), 0, 0);
        turn.addView(turnDistance);
        turn.addView(turnInstruction);
        top.addView(turn, new LinearLayout.LayoutParams(0, -1, 1f));

        gpsState = text("GPS…", 17, 0xFFC5E4F9, true);
        gpsState.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        top.addView(gpsState, new LinearLayout.LayoutParams(dp(148), -1));

        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setPadding(dp(19), dp(7), dp(17), dp(7));
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(background(0xF2112837, 13));
        FrameLayout.LayoutParams bottomLayout =
                new FrameLayout.LayoutParams(-1, dp(112), Gravity.BOTTOM);
        bottomLayout.setMargins(dp(12), 0, dp(12), dp(10));
        root.addView(bottom, bottomLayout);

        LinearLayout speed = new LinearLayout(getContext());
        speed.setOrientation(LinearLayout.VERTICAL);
        speed.setGravity(Gravity.CENTER_VERTICAL);
        speedValue = text("—", 65, Color.WHITE, true);
        speed.addView(speedValue);
        speed.addView(text("км/ч · GPS", 17, 0xFFB8D3E5, false));
        bottom.addView(speed, new LinearLayout.LayoutParams(dp(204), -1));

        View separator = new View(getContext());
        separator.setBackgroundColor(0xFF526D7A);
        bottom.addView(separator, new LinearLayout.LayoutParams(dp(1), dp(74)));

        LinearLayout rest = new LinearLayout(getContext());
        rest.setOrientation(LinearLayout.VERTICAL);
        rest.setGravity(Gravity.CENTER_VERTICAL);
        rest.setPadding(dp(20), 0, 0, 0);
        distanceRemaining = text("Карта по курсу", 27, Color.WHITE, true);
        routeLabel = text("Положение и скорость", 17, 0xFFB8D3E5, false);
        rest.addView(distanceRemaining);
        rest.addView(routeLabel);
        bottom.addView(rest, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout time = new LinearLayout(getContext());
        time.setOrientation(LinearLayout.VERTICAL);
        time.setGravity(Gravity.CENTER_VERTICAL);
        eta = text("—:—", 38, Color.WHITE, true);
        time.addView(eta);
        time.addView(text("Прибытие ≈", 17, 0xFFB8D3E5, false));
        bottom.addView(time, new LinearLayout.LayoutParams(dp(185), -1));

        TextView credits = text("© OpenStreetMap contributors · OpenFreeMap", 13, Color.WHITE, false);
        credits.setBackground(background(0xC8112431, 3));
        credits.setPadding(dp(6), dp(3), dp(6), dp(3));
        FrameLayout.LayoutParams creditsLayout =
                new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
        creditsLayout.setMargins(0, 0, dp(15), dp(130));
        root.addView(credits, creditsLayout);

        setContentView(root);
        NavigationLocationService.subscribe(gpsObserver);
        main.postDelayed(scheduler, 600);
    }

    private void onGps(Location fix) {
        if (!alive || mapImage == null) return;
        lastFix = new Location(fix);
        gpsState.setTextColor(0xFFC5E4F9);
        gpsState.setText(fix.hasAccuracy()
                ? String.format(RU, "GPS ±%.0f м", fix.getAccuracy())
                : "GPS работает");
        if (fix.hasSpeed()) {
            double kmh = fix.getSpeed() * 3.6;
            speedValue.setText(String.format(RU, "%.0f", kmh < 3.0 ? 0 : kmh));
        } else {
            speedValue.setText("—");
        }
        carArrow.setVisibility(View.VISIBLE);

        if (fix.hasBearing() && fix.hasSpeed() && fix.getSpeed() >= 2.7f
                && (!fix.hasAccuracy() || fix.getAccuracy() < 50f)) {
            float next = (fix.getBearing() + 360f) % 360f;
            if (!headingValid) {
                bearing = next;
                headingValid = true;
            } else {
                float difference = ((next - bearing + 540f) % 360f) - 180f;
                bearing = (bearing + difference * .40f + 360f) % 360f;
            }
        }

        if (activeRoute != null) updateProgress(fix);
    }

    private CameraPosition cameraFor(Location fix) {
        double zoom = fix.hasSpeed() && fix.getSpeed() > 22f ? 15.8 : 17.1;
        return new CameraPosition.Builder()
                .target(new LatLng(fix.getLatitude(), fix.getLongitude()))
                .bearing(headingValid ? bearing : 0.0)
                .tilt(headingValid ? 30.0 : 0.0)
                .zoom(zoom)
                .build();
    }

    private void replaceSnapshotter() {
        currentGeneration++;
        if (snapshotter != null) {
            snapshotter.cancel();
            snapshotter = null;
        }
        snapshotInFlight = false;
        Location fix = lastFix;
        if (!alive || fix == null) return;
        try {
            MapSnapshotter.Options options = new MapSnapshotter.Options(SNAP_WIDTH, SNAP_HEIGHT)
                    .withPixelRatio(1f)
                    .withStyleBuilder(new Style.Builder().fromUri(STYLE_URL))
                    .withCameraPosition(cameraFor(fix));
            snapshotter = new MapSnapshotter(getContext().getApplicationContext(), options);
            report.accept("КАРТА: новый оффскрин MapSnapshotter готов");
        } catch (RuntimeException exception) {
            snapshotFailures++;
            mapStatus.setText("Карта: ошибка MapSnapshotter");
            report.accept("КАРТА: создание Snapshotter: " + exception);
        }
    }

    private void takeSnapshot() {
        if (!alive || lastFix == null || snapshotInFlight) return;
        if (snapshotter == null) replaceSnapshotter();
        if (snapshotter == null) return;
        snapshotInFlight = true;
        requestStartedAt = SystemClock.elapsedRealtime();
        final int generation = currentGeneration;
        try {
            snapshotter.setCameraPosition(cameraFor(lastFix));
            snapshotter.start(
                    snapshot -> {
                        if (!alive || generation != currentGeneration || mapImage == null) return;
                        snapshotInFlight = false;
                        latestBitmapAt = SystemClock.elapsedRealtime();
                        snapshotCount++;
                        Bitmap image = snapshot.getBitmap();
                        if (image != null) {
                            // Drawing the route directly on the bitmap avoids
                            // a second fragile OpenGL layer on the virtual display.
                            if (activeRoute != null) drawRouteOnSnapshot(snapshot, image);
                            mapImage.setImageBitmap(image);
                            mapStatus.setVisibility(View.GONE);
                            if (snapshotCount == 1 || snapshotCount % 20 == 0) {
                                report.accept("КАРТА: готово " + snapshotCount
                                        + " Bitmap-кадров, размер="
                                        + image.getWidth() + "x" + image.getHeight());
                            }
                        } else {
                            mapStatus.setText("Карта вернула пустой Bitmap");
                            report.accept("КАРТА: снимок без Bitmap");
                        }
                    },
                    error -> {
                        if (!alive || generation != currentGeneration) return;
                        snapshotInFlight = false;
                        latestBitmapAt = SystemClock.elapsedRealtime();
                        snapshotFailures++;
                        mapStatus.setVisibility(View.VISIBLE);
                        mapStatus.setText("Ошибка карты: " + error);
                        report.accept("КАРТА: ошибка снимка #" + snapshotFailures + ": " + error);
                    });
        } catch (RuntimeException error) {
            snapshotInFlight = false;
            latestBitmapAt = SystemClock.elapsedRealtime();
            report.accept("КАРТА: исключение MapSnapshotter: " + error);
            mapStatus.setText("MapLibre: ошибка построения кадра");
        }
    }

    private void drawRouteOnSnapshot(MapSnapshot snapshot, Bitmap bitmap) {
        if (!bitmap.isMutable() || activeRoute == null) return;
        // Avoid huge UI-thread work for intercity routes.
        int points = activeRoute.geometry.size();
        if (points < 2) return;
        int stride = Math.max(1, (int) Math.ceil(points / 2500.0));
        Path road = new Path();
        try {
            boolean begun = false;
            for (int i = 0; i < points; i += stride) {
                PointF p = snapshot.pixelForLatLng(activeRoute.geometry.get(i));
                if (!Float.isFinite(p.x) || !Float.isFinite(p.y)) continue;
                if (!begun) { road.moveTo(p.x, p.y); begun = true; }
                else road.lineTo(p.x, p.y);
            }
            PointF end = snapshot.pixelForLatLng(
                    activeRoute.geometry.get(points - 1));
            road.lineTo(end.x, end.y);
            Canvas canvas = new Canvas(bitmap);
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(Color.WHITE);
            stroke.setStrokeWidth(13);
            canvas.drawPath(road, stroke);
            stroke.setColor(ROUTE_BLUE);
            stroke.setStrokeWidth(8);
            canvas.drawPath(road, stroke);
        } catch (RuntimeException error) {
            report.accept("КАРТА: маршрут поверх Bitmap: " + error);
        }
    }

    Location latestLocation() {
        return NavigationLocationService.latestLocation();
    }

    void setRoute(RoutePlanner.Route route) {
        if (!alive || route == null) return;
        activeRoute = route;
        lastRouteVertex = 0;
        metersToFinish = new double[route.geometry.size()];
        for (int i = metersToFinish.length - 2; i >= 0; i--) {
            metersToFinish[i] = metersToFinish[i + 1]
                    + RoutePlanner.meters(route.geometry.get(i), route.geometry.get(i + 1));
        }
        distanceRemaining.setText(distance(route.distanceMeters));
        routeLabel.setText("Осталось до финиша");
        eta.setText(etaAt(route.durationSeconds));
        if (lastFix != null) updateProgress(lastFix);
        latestBitmapAt = 0;
    }

    void clearRoute() {
        activeRoute = null;
        metersToFinish = null;
        lastRouteVertex = 0;
        turnIcon.setText("↑");
        turnDistance.setText("СВОБОДНАЯ ЕЗДА");
        turnInstruction.setText("Маршрут не нужен — просто едь");
        distanceRemaining.setText("Карта по курсу");
        routeLabel.setText("Положение и скорость");
        eta.setText("—:—");
        latestBitmapAt = 0;
    }

    private static String distance(double meters) {
        return meters >= 1000 ? String.format(RU, "%.1f км", meters / 1000.0)
                : String.format(RU, "%.0f м", Math.max(0, meters));
    }

    private static String etaAt(double seconds) {
        return new SimpleDateFormat("HH:mm", RU)
                .format(new Date(System.currentTimeMillis() + (long) (Math.max(0, seconds) * 1000)));
    }

    private void updateProgress(Location fix) {
        RoutePlanner.Route route = activeRoute;
        if (route == null || route.geometry.size() < 2 || metersToFinish == null) return;
        int best = lastRouteVertex;
        double nearest = Double.POSITIVE_INFINITY;
        int from = Math.max(0, lastRouteVertex - 40);
        int to = Math.min(route.geometry.size(), lastRouteVertex + 470);
        for (int i = from; i < to; i++) {
            double d = approxMeters(fix.getLatitude(), fix.getLongitude(), route.geometry.get(i));
            if (d < nearest) { nearest = d; best = i; }
        }
        if (nearest > 160) {
            for (int i = 0; i < route.geometry.size(); i++) {
                double d = approxMeters(fix.getLatitude(), fix.getLongitude(), route.geometry.get(i));
                if (d < nearest) { nearest = d; best = i; }
            }
        }
        lastRouteVertex = best;
        if (nearest > Math.max(100, fix.hasAccuracy() ? 2 * fix.getAccuracy() : 100)) {
            turnIcon.setText("!");
            turnDistance.setText("ВНЕ МАРШРУТА");
            turnInstruction.setText("Перестрой маршрут на телефоне");
            eta.setText("—:—");
            return;
        }
        double left = metersToFinish[best];
        distanceRemaining.setText(distance(left));
        eta.setText(etaAt(route.distanceMeters > 0
                ? Math.min(1, left / route.distanceMeters) * route.durationSeconds : 0));
        if (left < 35) {
            turnIcon.setText("✓");
            turnDistance.setText("ПРИЕХАЛИ");
            turnInstruction.setText(route.target);
            return;
        }
        double toTurn = left;
        RoutePlanner.Maneuver next = null;
        for (RoutePlanner.Maneuver m : route.maneuvers) {
            if (m.pathIndex >= best + 1) {
                next = m;
                toTurn = Math.max(0, left - metersToFinish[m.pathIndex]);
                break;
            }
        }
        turnIcon.setText(turnArrow(next));
        turnDistance.setText(distance(toTurn));
        turnInstruction.setText(next == null ? "Двигайся к пункту назначения" : next.instruction);
    }

    private static String turnArrow(RoutePlanner.Maneuver m) {
        if (m == null) return "↑";
        String name = m.instruction.toLowerCase(RU);
        if (name.contains("развер")) return "↶";
        if (name.contains("круг")) return "⟳";
        if (name.contains("налево")) return "↰";
        if (name.contains("направо")) return "↱";
        return "↑";
    }

    private static double approxMeters(double lat, double lon, LatLng road) {
        double x = (lon - road.getLongitude()) * 111320 * Math.cos(Math.toRadians(lat));
        double y = (lat - road.getLatitude()) * 111132;
        return Math.hypot(x, y);
    }

    private static final class Arrow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Arrow(Context ctx) { super(ctx); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            canvas.scale(Math.min(getWidth() / 88f, getHeight() / 96f),
                    Math.min(getWidth() / 88f, getHeight() / 96f));
            paint.setColor(0x501682FB);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(0, 0, 39, paint);
            path.reset();
            path.moveTo(0, -40);
            path.lineTo(29, 33);
            path.lineTo(0, 18);
            path.lineTo(-29, 33);
            path.close();
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(9);
            canvas.drawPath(path, paint);
            paint.setColor(ROUTE_BLUE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);
            canvas.restore();
        }
    }

    @Override protected void onStop() {
        alive = false;
        main.removeCallbacks(scheduler);
        NavigationLocationService.unsubscribe(gpsObserver);
        currentGeneration++;
        if (snapshotter != null) {
            snapshotter.cancel();
            snapshotter = null;
        }
        snapshotInFlight = false;
        if (mapImage != null) mapImage.setImageDrawable(null);
        mapImage = null;
        lastFix = null;
        activeRoute = null;
        metersToFinish = null;
        super.onStop();
    }
}
