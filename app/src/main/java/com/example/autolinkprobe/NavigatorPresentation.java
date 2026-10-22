package com.example.autolinkprobe;

import android.Manifest;
import android.app.Presentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Driver-first, 1024x600 navigation HUD on a private VirtualDisplay.
 * All UI updates happen on the main looper; the phone's own display is untouched.
 * Speed = real Location.getSpeed() when available; ETA = approximate route duration.
 * This is not a production turn-by-turn navigation engine: no traffic,
 * no speed-limit data, and no automatic rerouting yet.
 */
final class NavigatorPresentation extends Presentation {
    private static final int NAV_BLUE = 0xFF1682FF;
    private static final int HUD_DARK = 0xED132431;
    private static final Locale RU = new Locale("ru", "KZ");
    private final Handler ui = new Handler(Looper.getMainLooper());

    private MapView map;
    private View carArrow;
    private TextView turnArrow;
    private TextView turnDistance;
    private TextView turnInstruction;
    private TextView gpsState;
    private TextView speedValue;
    private TextView remainingValue;
    private TextView etaValue;
    private TextView etaCaption;
    private TextView destinationLabel;

    private Polyline routeOutline;
    private Polyline routeLine;
    private Marker routeDestinationMarker;
    private RoutePlanner.Route activeRoute;
    private volatile Location lastFix;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private double[] metersToFinish;
    private int lastRouteVertex;
    private float smoothedBearing;
    private boolean bearingKnown;
    private final Runnable gpsWatchdog = new Runnable() {
        @Override public void run() {
            if (map == null) return;
            Location fix = lastFix;
            if (fix == null || SystemClock.elapsedRealtimeNanos() -
                    fix.getElapsedRealtimeNanos() > 12_000_000_000L) {
                speedValue.setText("—");
                gpsState.setText("GPS: нет сигнала");
                gpsState.setTextColor(0xFFFFC989);
            }
            ui.postDelayed(this, 4000);
        }
    };

    NavigatorPresentation(Context context, Display display) {
        super(context, display);
    }

    private int dp(float v) {
        return Math.round(v * getContext().getResources().getDisplayMetrics().density);
    }

    private GradientDrawable panel(int color, float radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private void addColumn(LinearLayout parent, TextView first, TextView second, int width) {
        LinearLayout stack = new LinearLayout(getContext());
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER_VERTICAL);
        stack.addView(first);
        second.setPadding(0, dp(4), 0, 0);
        stack.addView(second);
        parent.addView(stack, new LinearLayout.LayoutParams(
                width < 0 ? 0 : dp(width), ViewGroup.LayoutParams.MATCH_PARENT,
                width < 0 ? 1 : 0));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Configuration.getInstance().setUserAgentValue(
                "GazAutoLinkNavigation/0.2 (" + getContext().getPackageName() + ")");

        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(0xFFDFE7E7);
        map = new MapView(getContext());
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(false);
        map.getController().setZoom(17.0);
        // No fake initial GPS position: start with a generic wide map until first fix.
        map.getController().setCenter(new GeoPoint(0, 0));
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        // Navigation arrow stays at the actual map center (GPS point).
        // Rotating the map to -bearing makes the arrow point up along travel.
        carArrow = new DriverArrowView(getContext());
        carArrow.setVisibility(View.INVISIBLE);
        root.addView(carArrow, new FrameLayout.LayoutParams(
                dp(94), dp(104), Gravity.CENTER));

        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(13), dp(18), dp(13));
        top.setBackground(panel(HUD_DARK, 16));
        FrameLayout.LayoutParams topParams =
                new FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP);
        topParams.setMargins(dp(12), dp(10), dp(12), 0);
        root.addView(top, topParams);

        turnArrow = text("↑", 64, Color.WHITE, true);
        turnArrow.setGravity(Gravity.CENTER);
        turnArrow.setBackground(panel(0xFF0874DC, 13));
        top.addView(turnArrow, new LinearLayout.LayoutParams(dp(99), -1));
        LinearLayout maneuver = new LinearLayout(getContext());
        maneuver.setOrientation(LinearLayout.VERTICAL);
        maneuver.setGravity(Gravity.CENTER_VERTICAL);
        maneuver.setPadding(dp(18), 0, dp(10), 0);
        turnDistance = text("КАРТА ГОТОВА", 33, Color.WHITE, true);
        turnInstruction = text("Введи адрес на телефоне", 22, 0xFFD1E6F3, false);
        turnInstruction.setPadding(0, dp(7), 0, 0);
        maneuver.addView(turnDistance);
        maneuver.addView(turnInstruction);
        top.addView(maneuver, new LinearLayout.LayoutParams(0, -1, 1f));

        gpsState = text("Ожидаю GPS", 18, 0xFFB2E2FB, true);
        gpsState.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        gpsState.setMaxLines(2);
        top.addView(gpsState, new LinearLayout.LayoutParams(dp(165), -1));

        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(18), dp(8), dp(18), dp(8));
        bottom.setBackground(panel(HUD_DARK, 16));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, dp(117), Gravity.BOTTOM);
        bottomParams.setMargins(dp(12), 0, dp(12), dp(10));
        root.addView(bottom, bottomParams);

        LinearLayout speedBox = new LinearLayout(getContext());
        speedBox.setGravity(Gravity.CENTER_VERTICAL);
        speedBox.setOrientation(LinearLayout.VERTICAL);
        speedValue = text("—", 63, Color.WHITE, true);
        TextView speedUnit = text("км/ч · GPS", 17, 0xFFABCBE0, false);
        speedBox.addView(speedValue);
        speedBox.addView(speedUnit);
        bottom.addView(speedBox, new LinearLayout.LayoutParams(dp(194), -1));
        View divider1 = new View(getContext());
        divider1.setBackgroundColor(0xFF49616D);
        bottom.addView(divider1, new LinearLayout.LayoutParams(dp(1), dp(78)));

        LinearLayout remainingBox = new LinearLayout(getContext());
        remainingBox.setPadding(dp(22), 0, dp(12), 0);
        remainingBox.setOrientation(LinearLayout.VERTICAL);
        remainingBox.setGravity(Gravity.CENTER_VERTICAL);
        remainingValue = text("—", 43, Color.WHITE, true);
        destinationLabel = text("До пункта назначения", 16, 0xFFABCBE0, false);
        remainingBox.addView(remainingValue);
        destinationLabel.setPadding(0, dp(5), 0, 0);
        remainingBox.addView(destinationLabel);
        bottom.addView(remainingBox, new LinearLayout.LayoutParams(0, -1, 1f));
        View divider2 = new View(getContext());
        divider2.setBackgroundColor(0xFF49616D);
        bottom.addView(divider2, new LinearLayout.LayoutParams(dp(1), dp(78)));

        LinearLayout etaBox = new LinearLayout(getContext());
        etaBox.setOrientation(LinearLayout.VERTICAL);
        etaBox.setGravity(Gravity.CENTER_VERTICAL);
        etaBox.setPadding(dp(18), 0, 0, 0);
        etaValue = text("—:—", 42, Color.WHITE, true);
        etaCaption = text("Прибытие ~", 17, 0xFFABCBE0, false);
        etaBox.addView(etaValue);
        etaBox.addView(etaCaption);
        bottom.addView(etaBox, new LinearLayout.LayoutParams(dp(195), -1));

        TextView credits = text("© OpenStreetMap contributors  •  OSRM demo", 13, Color.WHITE, false);
        credits.setPadding(dp(8), dp(4), dp(8), dp(4));
        credits.setBackground(panel(0xD0102027, 5));
        FrameLayout.LayoutParams creditsParams = new FrameLayout.LayoutParams(
                -2, -2, Gravity.RIGHT | Gravity.BOTTOM);
        creditsParams.setMargins(0, 0, dp(16), dp(134));
        root.addView(credits, creditsParams);

        setContentView(root);
        map.onResume();
        startLocationUpdates();
        ui.postDelayed(gpsWatchdog, 4000);
    }

    private void startLocationUpdates() {
        if (getContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && getContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            gpsState.setText("Нет разрешения GPS");
            return;
        }
        locationManager =
                (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;

        locationListener = location -> {
            if (map == null || !Double.isFinite(location.getLatitude())
                    || !Double.isFinite(location.getLongitude())) return;
            // Do not let a coarse network estimate drag the map off a fresh GPS fix.
            Location previous = lastFix;
            boolean network = LocationManager.NETWORK_PROVIDER.equals(location.getProvider());
            if (network && previous != null
                    && LocationManager.GPS_PROVIDER.equals(previous.getProvider())
                    && SystemClock.elapsedRealtimeNanos() - previous.getElapsedRealtimeNanos()
                    < 7_000_000_000L) return;
            if (previous != null && location.getElapsedRealtimeNanos()
                    < previous.getElapsedRealtimeNanos() && !network) return;
            lastFix = new Location(location);
            gpsState.setTextColor(0xFFB2E2FB);
            gpsState.setText(location.hasAccuracy()
                    ? String.format(RU, "GPS  ±%.0f м", location.getAccuracy())
                    : "GPS активен");

            // Never invent speed: show an em dash if Location provides no speed.
            if (location.hasSpeed() && location.getSpeed() >= 0) {
                float kmh = location.getSpeed() * 3.6f;
                speedValue.setText(String.format(RU, "%.0f", kmh < 3f ? 0f : kmh));
            } else {
                speedValue.setText("—");
            }
            if (carArrow.getVisibility() != View.VISIBLE) carArrow.setVisibility(View.VISIBLE);

            // Course over ground is unreliable when parked. Keep the last good
            // heading rather than spinning the map while GPS drifts.
            if (location.hasBearing() && location.hasSpeed()
                    && location.getSpeed() >= 2.8f
                    && (!location.hasAccuracy() || location.getAccuracy() < 45f)) {
                float target = ((location.getBearing() % 360) + 360) % 360;
                if (!bearingKnown) {
                    smoothedBearing = target;
                    bearingKnown = true;
                } else {
                    float delta = ((target - smoothedBearing + 540f) % 360f) - 180f;
                    smoothedBearing = (smoothedBearing + delta * 0.40f + 360f) % 360f;
                }
                map.setMapOrientation(-smoothedBearing);
            }

            // For now keep actual GPS at the center, exactly under the blue arrow.
            map.getController().setCenter(
                    new GeoPoint(location.getLatitude(), location.getLongitude()));
            if (location.hasSpeed()) {
                double targetZoom = location.getSpeed() > 22f ? 16.2 : 17.1;
                if (Math.abs(map.getZoomLevelDouble() - targetZoom) > .35) {
                    map.getController().setZoom(targetZoom);
                }
            }
            updateRouteProgress(location);
            map.invalidate();
        };

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Location cached = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (cached != null && System.currentTimeMillis() - cached.getTime() < 15000L) {
                    locationListener.onLocationChanged(cached);
                }
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000L, 2f,
                        locationListener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 3000L, 10f,
                        locationListener, Looper.getMainLooper());
            }
        } catch (SecurityException error) {
            gpsState.setText("GPS: нет разрешения");
        } catch (IllegalArgumentException error) {
            gpsState.setText("GPS недоступен");
        }
    }

    Location latestLocation() {
        Location fix = lastFix;
        return fix == null ? null : new Location(fix);
    }

    /** The calling video session dispatches route updates on the UI thread. */
    void setRoute(RoutePlanner.Route route) {
        if (map == null || route == null) return;
        if (routeLine != null) map.getOverlays().remove(routeLine);
        if (routeOutline != null) map.getOverlays().remove(routeOutline);
        if (routeDestinationMarker != null) map.getOverlays().remove(routeDestinationMarker);
        activeRoute = route;
        lastRouteVertex = 0;

        // Wide contrasting stroke remains readable on mixed-color OSM tiles.
        routeOutline = new Polyline(map);
        routeOutline.setPoints(route.geometry);
        routeOutline.setColor(0xFFFFFFFF);
        routeOutline.setWidth(dp(17));
        map.getOverlays().add(routeOutline);

        routeLine = new Polyline(map);
        routeLine.setPoints(route.geometry);
        routeLine.setColor(NAV_BLUE);
        routeLine.setWidth(dp(11));
        map.getOverlays().add(routeLine);

        routeDestinationMarker = new Marker(map);
        routeDestinationMarker.setTitle(route.target);
        routeDestinationMarker.setPosition(route.destination);
        routeDestinationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        map.getOverlays().add(routeDestinationMarker);

        metersToFinish = new double[route.geometry.size()];
        metersToFinish[metersToFinish.length - 1] = 0;
        for (int i = metersToFinish.length - 2; i >= 0; i--) {
            metersToFinish[i] = metersToFinish[i + 1] +
                    RoutePlanner.meters(route.geometry.get(i), route.geometry.get(i + 1));
        }
        turnDistance.setText("МАРШРУТ ПОСТРОЕН");
        turnInstruction.setText(route.target);
        destinationLabel.setText("До пункта назначения");
        remainingValue.setText(distance(route.distanceMeters));
        etaValue.setText(etaTime(route.durationSeconds));
        etaCaption.setText(String.format(RU, "≈ %.0f мин в пути", route.durationSeconds / 60));
        if (lastFix != null) updateRouteProgress(lastFix);
        map.invalidate();
    }

    private static String distance(double meters) {
        return meters >= 1000
                ? String.format(RU, "%.1f км", meters / 1000.)
                : String.format(RU, "%.0f м", Math.max(0, meters));
    }

    private static String etaTime(double remainingSeconds) {
        long etaMillis = System.currentTimeMillis() +
                (long) (Math.max(0, remainingSeconds) * 1000.);
        return new SimpleDateFormat("HH:mm", RU).format(new Date(etaMillis));
    }

    private void updateRouteProgress(Location location) {
        RoutePlanner.Route route = activeRoute;
        if (route == null || route.geometry.size() < 2 || metersToFinish == null) return;
        int best = lastRouteVertex;
        double nearest = Double.POSITIVE_INFINITY;
        int from = Math.max(0, lastRouteVertex - 45);
        int to = Math.min(route.geometry.size(), lastRouteVertex + 500);
        for (int i = from; i < to; i++) {
            double d = approxMeters(location.getLatitude(), location.getLongitude(),
                    route.geometry.get(i));
            if (d < nearest) { nearest = d; best = i; }
        }
        if (nearest > 150) {
            nearest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < route.geometry.size(); i++) {
                double d = approxMeters(location.getLatitude(), location.getLongitude(),
                        route.geometry.get(i));
                if (d < nearest) { nearest = d; best = i; }
            }
        }
        lastRouteVertex = best;
        if (nearest > Math.max(100.0,
                location.hasAccuracy() ? 2 * location.getAccuracy() : 100.0)) {
            turnArrow.setText("!");
            turnDistance.setText("ВНЕ МАРШРУТА");
            turnInstruction.setText("Перестрой маршрут на телефоне");
            etaCaption.setText("Время прибытия неизвестно");
            return;
        }

        double rest = metersToFinish[best];
        remainingValue.setText(distance(rest));
        double ratio = route.distanceMeters > 1 ? Math.min(1, rest / route.distanceMeters) : 0;
        double seconds = ratio * route.durationSeconds;
        etaValue.setText(etaTime(seconds));
        etaCaption.setText(String.format(RU, "≈ %.0f мин в пути", Math.ceil(seconds / 60.)));

        if (rest < 35) {
            turnArrow.setText("✓");
            turnDistance.setText("ПРИЕХАЛИ");
            turnInstruction.setText(route.target);
            return;
        }

        RoutePlanner.Maneuver upcoming = null;
        double beforeTurn = rest;
        for (RoutePlanner.Maneuver maneuver : route.maneuvers) {
            if (maneuver.pathIndex >= best + 1) {
                upcoming = maneuver;
                beforeTurn = Math.max(0, rest - metersToFinish[maneuver.pathIndex]);
                break;
            }
        }
        turnArrow.setText(arrowFor(upcoming));
        turnDistance.setText(distance(beforeTurn));
        turnInstruction.setText(upcoming == null ? "Двигайтесь к пункту назначения"
                : upcoming.instruction);
    }

    private static String arrowFor(RoutePlanner.Maneuver maneuver) {
        if (maneuver == null) return "↑";
        String instruction = maneuver.instruction.toLowerCase(RU);
        if (instruction.contains("развер")) return "↶";
        if (instruction.contains("кругов")) return "⟳";
        if (instruction.contains("налево")) return "↰";
        if (instruction.contains("направо")) return "↱";
        if (instruction.contains("прибыли")) return "✓";
        return "↑";
    }

    private static double approxMeters(double lat, double lon, GeoPoint vertex) {
        double latDelta = (lat - vertex.getLatitude()) * 111132.;
        double lonDelta = (lon - vertex.getLongitude()) * 111320. *
                Math.cos(Math.toRadians(lat));
        return Math.hypot(latDelta, lonDelta);
    }

    /** Fixed-size navigation chevron, designed for legibility at 1024x600. */
    private static final class DriverArrowView extends View {
        private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();

        DriverArrowView(Context c) {
            super(c);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate(getWidth() * .5f, getHeight() * .5f);
            float scale = Math.min(getWidth() / 94f, getHeight() / 104f);
            canvas.scale(scale, scale);
            pen.clearShadowLayer();
            pen.setStyle(Paint.Style.FILL);
            pen.setColor(0x551687FF);
            canvas.drawCircle(0, 0, 39, pen);
            shape.reset();
            shape.moveTo(0, -43);
            shape.lineTo(30, 33);
            shape.lineTo(0, 20);
            shape.lineTo(-30, 33);
            shape.close();
            pen.setColor(Color.WHITE);
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeJoin(Paint.Join.ROUND);
            pen.setStrokeWidth(9f);
            pen.setShadowLayer(6f, 0, 3f, 0x99000000);
            canvas.drawPath(shape, pen);
            pen.clearShadowLayer();
            pen.setStyle(Paint.Style.FILL);
            pen.setColor(NAV_BLUE);
            canvas.drawPath(shape, pen);
            canvas.restore();
        }
    }

    @Override protected void onStop() {
        ui.removeCallbacks(gpsWatchdog);
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        locationListener = null;
        locationManager = null;
        lastFix = null;
        activeRoute = null;
        routeLine = null;
        routeOutline = null;
        routeDestinationMarker = null;
        metersToFinish = null;
        if (map != null) {
            map.onPause();
            map.onDetach();
            map = null;
        }
        super.onStop();
    }
}
