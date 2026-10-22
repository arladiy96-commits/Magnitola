package com.example.autolinkprobe;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.layers.PropertyFactory;
import org.osmdroid.util.GeoPoint;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Genuine vector-map navigator running exclusively on the encoder-backed
 * virtual display. Driver HUD remains independent from the physical phone UI.
 *
 * Routes are optional. Without one: free-drive speed, bearing, location and
 * road network all work. NavigationLocationService owns locked-screen GPS.
 */
final class NavigatorPresentation extends Presentation {
    private static final Locale RU = new Locale("ru", "KZ");
    private static final int BLUE = 0xFF1676F8;
    private static final String STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
    private static final String ROUTE_SOURCE = "gaz-route-source";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MapView mapView;
    private MapLibreMap map;
    private boolean styleReady;
    private DriverArrowView arrow;
    private TextView gpsStatus, speedText, nextArrow, nextDistance, nextInstruction;
    private TextView remainingText, etaText, etaCaption, freeModeHint;
    private TextView mapLoading, mapAttribution;
    private volatile Location lastFix;
    private RoutePlanner.Route activeRoute;
    private double[] metersToFinish;
    private int lastRouteVertex;
    private boolean bearingKnown;
    private float smoothedBearing;
    private long lastCameraUpdate;
    private final NavigationLocationService.Observer gpsObserver = this::onGps;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (mapView == null) return;
            Location fix = lastFix;
            if (fix == null || SystemClock.elapsedRealtimeNanos() -
                    fix.getElapsedRealtimeNanos() > 12_000_000_000L) {
                speedText.setText("—");
                gpsStatus.setText("GPS: нет сигнала");
                gpsStatus.setTextColor(0xFFFFBC9A);
            }
            ui.postDelayed(this, 3500);
        }
    };

    NavigatorPresentation(Context context, Display display) {
        super(context, display);
    }

    private int dp(float value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView txt(String value, int size, int color, boolean bold) {
        TextView v = new TextView(getContext());
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MapLibre.getInstance(getContext());
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(0xFFDFE8E5);

        MapLibreMapOptions options = new MapLibreMapOptions()
                .textureMode(true) // composited reliably onto MediaCodec VirtualDisplay
                .zoomGesturesEnabled(false).scrollGesturesEnabled(false)
                .rotateGesturesEnabled(false).tiltGesturesEnabled(false);
        mapView = new MapView(getContext(), options);
        mapView.onCreate(null);
        root.addView(mapView, new FrameLayout.LayoutParams(-1, -1));

        // The arrow stays on the GPS center. Route and vector roads rotate
        // together based on course over ground (while moving).
        arrow = new DriverArrowView(getContext());
        arrow.setVisibility(View.INVISIBLE);
        root.addView(arrow, new FrameLayout.LayoutParams(dp(82), dp(92), Gravity.CENTER));

        mapLoading = txt("Загружаю векторные дороги… нужен интернет", 22, 0xFF26404F, true);
        mapLoading.setGravity(Gravity.CENTER);
        mapLoading.setBackground(bg(0xEFFFFFFF, 12));
        FrameLayout.LayoutParams loadingParams =
                new FrameLayout.LayoutParams(dp(550), dp(56), Gravity.CENTER);
        root.addView(mapLoading, loadingParams);

        LinearLayout top = new LinearLayout(getContext());
        top.setPadding(dp(15), dp(10), dp(15), dp(10));
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(bg(0xEE102431, 14));
        FrameLayout.LayoutParams topParams =
                new FrameLayout.LayoutParams(-1, dp(102), Gravity.TOP);
        topParams.setMargins(dp(12), dp(10), dp(12), 0);
        root.addView(top, topParams);

        nextArrow = txt("↑", 52, Color.WHITE, true);
        nextArrow.setGravity(Gravity.CENTER);
        nextArrow.setBackground(bg(0xFF176CDA, 11));
        top.addView(nextArrow, new LinearLayout.LayoutParams(dp(78), -1));

        LinearLayout maneuver = new LinearLayout(getContext());
        maneuver.setGravity(Gravity.CENTER_VERTICAL);
        maneuver.setPadding(dp(15), 0, dp(8), 0);
        maneuver.setOrientation(LinearLayout.VERTICAL);
        nextDistance = txt("СВОБОДНАЯ ЕЗДА", 31, Color.WHITE, true);
        nextInstruction = txt("Маршрут не нужен — просто едь", 19, 0xFFB7D2E6, false);
        nextInstruction.setPadding(0, dp(5), 0, 0);
        maneuver.addView(nextDistance);
        maneuver.addView(nextInstruction);
        top.addView(maneuver, new LinearLayout.LayoutParams(0, -1, 1));

        gpsStatus = txt("Ожидаю GPS", 17, 0xFFC7E5F4, true);
        gpsStatus.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        top.addView(gpsStatus, new LinearLayout.LayoutParams(dp(148), -1));

        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setPadding(dp(20), dp(5), dp(18), dp(5));
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(bg(0xF0102431, 14));
        FrameLayout.LayoutParams bottomParams =
                new FrameLayout.LayoutParams(-1, dp(112), Gravity.BOTTOM);
        bottomParams.setMargins(dp(12), 0, dp(12), dp(10));
        root.addView(bottom, bottomParams);

        LinearLayout speedBox = new LinearLayout(getContext());
        speedBox.setOrientation(LinearLayout.VERTICAL);
        speedBox.setGravity(Gravity.CENTER_VERTICAL);
        speedText = txt("—", 65, Color.WHITE, true);
        TextView speedLabel = txt("км/ч • GPS", 17, 0xFFB7D2E6, false);
        speedBox.addView(speedText);
        speedBox.addView(speedLabel);
        bottom.addView(speedBox, new LinearLayout.LayoutParams(dp(210), -1));

        View divider = new View(getContext());
        divider.setBackgroundColor(0xFF45616F);
        bottom.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(76)));

        LinearLayout details = new LinearLayout(getContext());
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setPadding(dp(25), 0, dp(8), 0);
        remainingText = txt("Карта по курсу", 29, Color.WHITE, true);
        freeModeHint = txt("Твоё положение и скорость", 16, 0xFFB7D2E6, false);
        details.addView(remainingText);
        details.addView(freeModeHint);
        bottom.addView(details, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout etaBox = new LinearLayout(getContext());
        etaBox.setOrientation(LinearLayout.VERTICAL);
        etaBox.setGravity(Gravity.CENTER_VERTICAL);
        etaValuePlaceholder(etaBox);
        bottom.addView(etaBox, new LinearLayout.LayoutParams(dp(175), -1));

        mapAttribution = txt("© OpenStreetMap contributors · OpenFreeMap", 13, Color.WHITE, false);
        mapAttribution.setPadding(dp(8), dp(4), dp(8), dp(4));
        mapAttribution.setBackground(bg(0xD0102530, 4));
        FrameLayout.LayoutParams attrParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.RIGHT | Gravity.BOTTOM);
        attrParams.setMargins(0, 0, dp(14), dp(131));
        root.addView(mapAttribution, attrParams);

        setContentView(root);
        mapView.onStart();
        mapView.onResume();
        mapView.getMapAsync(readyMap -> {
            if (mapView == null) return;
            map = readyMap;
            readyMap.getUiSettings().setAllGesturesEnabled(false);
            readyMap.setStyle(STYLE_URL, style -> {
                if (mapView == null) return;
                styleReady = true;
                mapLoading.setVisibility(View.GONE);
                if (activeRoute != null) installRoute(style, activeRoute);
                Location fix = NavigationLocationService.latestLocation();
                if (fix != null) onGps(fix);
            });
            Location fix = NavigationLocationService.latestLocation();
            if (fix != null) onGps(fix);
        });
        NavigationLocationService.subscribe(gpsObserver);
        ui.postDelayed(watchdog, 3500);
    }

    private void etaValuePlaceholder(LinearLayout etaBox) {
        etaText = txt("—:—", 36, Color.WHITE, true);
        etaCaption = txt("Время прибытия", 16, 0xFFB7D2E6, false);
        etaBox.addView(etaText);
        etaBox.addView(etaCaption);
    }

    private void onGps(Location location) {
        if (mapView == null) return;
        lastFix = new Location(location);
        gpsStatus.setTextColor(0xFFC7E5F4);
        gpsStatus.setText(location.hasAccuracy()
                ? String.format(RU, "GPS ±%.0f м", location.getAccuracy())
                : "GPS работает");
        speedText.setText(location.hasSpeed()
                ? String.format(RU, "%.0f", location.getSpeed() * 3.6 < 3. ? 0.
                        : location.getSpeed() * 3.6)
                : "—");
        arrow.setVisibility(View.VISIBLE);
        // Real GNSS bearing is meaningful only while moving; parking jitter
        // must not spin the roads continuously.
        if (location.hasBearing() && location.hasSpeed() && location.getSpeed() > 2.75f) {
            float candidate = (location.getBearing() + 360f) % 360f;
            if (!bearingKnown) {
                smoothedBearing = candidate;
                bearingKnown = true;
            } else {
                float delta = ((candidate - smoothedBearing + 540f) % 360f) - 180f;
                smoothedBearing = (smoothedBearing + delta * .35f + 360f) % 360f;
            }
        }
        if (map != null) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastCameraUpdate > 600) {
                lastCameraUpdate = now;
                double zoom = location.hasSpeed() && location.getSpeed() > 22.0 ? 15.8 : 17.1;
                CameraPosition camera = new CameraPosition.Builder()
                        .target(new LatLng(location.getLatitude(), location.getLongitude()))
                        .zoom(zoom)
                        .bearing(bearingKnown ? smoothedBearing : 0)
                        .tilt(bearingKnown ? 35 : 0)
                        .build();
                map.moveCamera(CameraUpdateFactory.newCameraPosition(camera));
            }
        }
        if (activeRoute != null) updateManeuver(location);
    }

    Location latestLocation() {
        return NavigationLocationService.latestLocation();
    }

    void setRoute(RoutePlanner.Route route) {
        if (mapView == null || route == null) return;
        activeRoute = route;
        lastRouteVertex = 0;
        metersToFinish = new double[route.geometry.size()];
        for (int i = metersToFinish.length - 2; i >= 0; i--) {
            metersToFinish[i] = metersToFinish[i + 1]
                    + RoutePlanner.meters(route.geometry.get(i), route.geometry.get(i + 1));
        }
        etaText.setText(etaTime(route.durationSeconds));
        remainingText.setText(formatDistance(route.distanceMeters));
        freeModeHint.setText("Осталось до финиша");
        etaCaption.setText("Прибытие ≈");
        if (map != null && styleReady) {
            Style style = map.getStyle();
            if (style != null) installRoute(style, route);
        }
        if (lastFix != null) updateManeuver(lastFix);
    }

    private void installRoute(Style style, RoutePlanner.Route route) {
        try {
            // Drop layers before source; otherwise style errors on new routes.
            if (style.getLayer("gaz-route-fill") != null) style.removeLayer("gaz-route-fill");
            if (style.getLayer("gaz-route-outline") != null) style.removeLayer("gaz-route-outline");
            if (style.getSource(ROUTE_SOURCE) != null) style.removeSource(ROUTE_SOURCE);
            JSONArray points = new JSONArray();
            for (GeoPoint p : route.geometry) {
                points.put(new JSONArray().put(p.getLongitude()).put(p.getLatitude()));
            }
            JSONObject geo = new JSONObject();
            geo.put("type", "Feature");
            geo.put("geometry", new JSONObject().put("type", "LineString")
                    .put("coordinates", points));
            geo.put("properties", new JSONObject());
            style.addSource(new GeoJsonSource(ROUTE_SOURCE, geo.toString()));
            LineLayer casing = new LineLayer("gaz-route-outline", ROUTE_SOURCE);
            casing.setProperties(PropertyFactory.lineColor(Color.WHITE),
                    PropertyFactory.lineWidth(13f));
            style.addLayer(casing);
            LineLayer fill = new LineLayer("gaz-route-fill", ROUTE_SOURCE);
            fill.setProperties(PropertyFactory.lineColor(BLUE),
                    PropertyFactory.lineWidth(8f));
            style.addLayer(fill);
        } catch (Exception e) {
            nextInstruction.setText("Ошибка отображения линии маршрута");
        }
    }

    private static String formatDistance(double meters) {
        if (meters >= 1000) return String.format(RU, "%.1f км", meters / 1000);
        return String.format(RU, "%.0f м", Math.max(0, meters));
    }

    private static String etaTime(double seconds) {
        long when = System.currentTimeMillis() + (long) (Math.max(0, seconds) * 1000);
        return new SimpleDateFormat("HH:mm", RU).format(new Date(when));
    }

    private void updateManeuver(Location location) {
        RoutePlanner.Route route = activeRoute;
        if (route == null || route.geometry.size() < 2) return;
        int best = lastRouteVertex;
        double nearest = Double.POSITIVE_INFINITY;
        int from = Math.max(0, lastRouteVertex - 30);
        int to = Math.min(route.geometry.size(), lastRouteVertex + 450);
        for (int i = from; i < to; i++) {
            double d = approxMeters(location.getLatitude(), location.getLongitude(), route.geometry.get(i));
            if (d < nearest) { nearest = d; best = i; }
        }
        if (nearest > 180) {
            nearest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < route.geometry.size(); i++) {
                double d = approxMeters(location.getLatitude(), location.getLongitude(), route.geometry.get(i));
                if (d < nearest) { nearest = d; best = i; }
            }
        }
        lastRouteVertex = best;
        if (nearest > Math.max(100, location.hasAccuracy() ? 2 * location.getAccuracy() : 100)) {
            nextArrow.setText("!");
            nextDistance.setText("ВНЕ МАРШРУТА");
            nextInstruction.setText("Построй маршрут снова");
            etaText.setText("—:—");
            return;
        }
        double left = metersToFinish[best];
        remainingText.setText(formatDistance(left));
        double fraction = route.distanceMeters > 1 ?
                Math.min(1, left / route.distanceMeters) : 0;
        double seconds = fraction * route.durationSeconds;
        etaText.setText(etaTime(seconds));
        etaCaption.setText("Прибытие ≈");
        if (left < 35) {
            nextArrow.setText("✓");
            nextDistance.setText("ПРИЕХАЛИ");
            nextInstruction.setText(route.target);
            return;
        }
        RoutePlanner.Maneuver next = null;
        double toTurn = left;
        for (RoutePlanner.Maneuver m : route.maneuvers) {
            if (m.pathIndex >= best + 1) {
                next = m;
                toTurn = Math.max(0, left - metersToFinish[m.pathIndex]);
                break;
            }
        }
        nextArrow.setText(directionArrow(next));
        nextDistance.setText(formatDistance(toTurn));
        nextInstruction.setText(next == null ? "Двигайся до пункта назначения" : next.instruction);
    }

    private static String directionArrow(RoutePlanner.Maneuver maneuver) {
        if (maneuver == null) return "↑";
        String line = maneuver.instruction.toLowerCase(RU);
        if (line.contains("развер")) return "↶";
        if (line.contains("круг")) return "⟳";
        if (line.contains("налево")) return "↰";
        if (line.contains("направо")) return "↱";
        return "↑";
    }

    private static double approxMeters(double lat, double lon, GeoPoint road) {
        double y = (lat - road.getLatitude()) * 111132.;
        double x = (lon - road.getLongitude()) * 111320.
                * Math.cos(Math.toRadians(lat));
        return Math.hypot(x, y);
    }

    private static final class DriverArrowView extends View {
        private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        DriverArrowView(Context c) {
            super(c);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            float scale = Math.min(getWidth() / 82f, getHeight() / 92f);
            canvas.scale(scale, scale);
            pen.setStyle(Paint.Style.FILL);
            pen.setColor(0x4C1676F8);
            canvas.drawCircle(0, 0, 36, pen);
            path.reset();
            path.moveTo(0, -36);
            path.lineTo(25, 29);
            path.lineTo(0, 16);
            path.lineTo(-25, 29);
            path.close();
            pen.setColor(Color.WHITE);
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(8f);
            pen.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, pen);
            pen.setStyle(Paint.Style.FILL);
            pen.setColor(BLUE);
            canvas.drawPath(path, pen);
            canvas.restore();
        }
    }

    @Override protected void onStop() {
        ui.removeCallbacks(watchdog);
        NavigationLocationService.unsubscribe(gpsObserver);
        lastFix = null;
        activeRoute = null;
        if (mapView != null) {
            mapView.onPause();
            mapView.onStop();
            mapView.onDestroy();
            mapView = null;
        }
        map = null;
        super.onStop();
    }
}
