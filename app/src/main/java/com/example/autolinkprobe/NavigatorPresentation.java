package com.example.autolinkprobe;

import android.Manifest;
import android.app.Presentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
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
import java.util.Locale;

/**
 * Own navigation canvas for AutoLink's PRIVATE virtual display.
 * Nothing on the physical phone display is captured.
 * Stage 1: OpenStreetMap and live GPS; routing and touchscreen input follow later.
 */
final class NavigatorPresentation extends Presentation {
    private MapView map;
    private Marker position;
    private TextView gpsState;
    private TextView nextTurn;
    private Polyline routeLine;
    private Marker routeDestinationMarker;
    private RoutePlanner.Route activeRoute;
    private volatile Location lastFix;
    private double[] metersToFinish;
    private int lastRouteVertex;
    private LocationManager locationManager;
    private LocationListener locationListener;

    NavigatorPresentation(Context context, Display display) {
        super(context, display);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue("AutoLinkNavigation/0.1 (" + getContext().getPackageName() + ")");
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.BLACK);

        map = new MapView(getContext());
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(false);
        map.getController().setZoom(15.0);
        // Only used until GPS obtains the real location; not an inferred user address.
        map.getController().setCenter(new GeoPoint(51.1694, 71.4491));
        root.addView(map, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(24, 12, 24, 12);
        header.setBackgroundColor(0xD9142028);
        TextView title = new TextView(getContext());
        title.setText("НАВИГАЦИЯ");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        gpsState = new TextView(getContext());
        gpsState.setText("Ожидаю GPS...  •  Маршруты пока не реализованы");
        gpsState.setTextColor(0xFFB9DDEA);
        gpsState.setTextSize(16);
        nextTurn = new TextView(getContext());
        nextTurn.setText("Карта готова. Введи пункт назначения на телефоне.");
        nextTurn.setTextColor(0xFFFAE2A5);
        nextTurn.setTextSize(22);
        nextTurn.setPadding(0, 4, 0, 0);
        header.addView(title);
        header.addView(gpsState);
        header.addView(nextTurn);
        root.addView(header, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        TextView attribution = new TextView(getContext());
        attribution.setText("© OpenStreetMap contributors • маршруты: OSRM demo");
        attribution.setBackgroundColor(0xB8000000);
        attribution.setPadding(10, 5, 10, 5);
        attribution.setTextSize(12);
        attribution.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams attributionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT);
        root.addView(attribution, attributionParams);
        setContentView(root);

        map.onResume();
        startLocationUpdates();
    }

    private void startLocationUpdates() {
        if (getContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && getContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            gpsState.setText("Нет разрешения GPS • Карта без геопозиции");
            return;
        }
        locationManager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (map == null) return;
                lastFix = new Location(location);
                GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
                if (position == null) {
                    position = new Marker(map);
                    position.setTitle("Моё местоположение");
                    position.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                    map.getOverlays().add(position);
                }
                position.setPosition(point);
                map.getController().setCenter(point);
                map.invalidate();
                gpsState.setText(String.format(Locale.US,
                        "GPS • %.1f км/ч • точность %.0f м", Math.max(0, location.getSpeed() * 3.6f),
                        location.hasAccuracy() ? location.getAccuracy() : 0));
                updateRouteProgress(location);
            }
        };
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Location previous = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (previous != null) locationListener.onLocationChanged(previous);
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f,
                        locationListener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2500L, 5f,
                        locationListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            gpsState.setText("GPS: не выдано разрешение");
        } catch (IllegalArgumentException e) {
            gpsState.setText("GPS: источник местоположения недоступен");
        }
    }

    Location latestLocation() {
        Location fix = lastFix;
        return fix == null ? null : new Location(fix);
    }

    /** Always invoked on the main thread (Presentation/MapView requirement). */
    void setRoute(RoutePlanner.Route route) {
        if (map == null || route == null) return;
        if (routeLine != null) map.getOverlays().remove(routeLine);
        if (routeDestinationMarker != null) map.getOverlays().remove(routeDestinationMarker);
        activeRoute = route;
        lastRouteVertex = 0;
        routeLine = new Polyline(map);
        routeLine.setPoints(route.geometry);
        routeLine.setColor(0xFF0878EE);
        routeLine.setWidth(10f);
        map.getOverlays().add(routeLine);

        routeDestinationMarker = new Marker(map);
        routeDestinationMarker.setTitle(route.target);
        routeDestinationMarker.setPosition(route.destination);
        routeDestinationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        map.getOverlays().add(routeDestinationMarker);

        // Precompute remaining road distance for each polyline vertex once,
        // rather than walking the whole route on every GPS update.
        metersToFinish = new double[route.geometry.size()];
        metersToFinish[metersToFinish.length - 1] = 0;
        for (int i = metersToFinish.length - 2; i >= 0; i--) {
            metersToFinish[i] = metersToFinish[i + 1] +
                    RoutePlanner.meters(route.geometry.get(i), route.geometry.get(i + 1));
        }
        nextTurn.setText(String.format(Locale.US, "Маршрут: %.1f км • ~%.0f мин",
                route.distanceMeters / 1000.0, route.durationSeconds / 60.0));
        if (lastFix != null) updateRouteProgress(lastFix);
        map.invalidate();
    }

    /** GPS stays on the PRIVATE screen; never intercepts touch on the phone. */
    private void updateRouteProgress(Location fix) {
        RoutePlanner.Route route = activeRoute;
        if (route == null || route.geometry.size() < 2 || nextTurn == null) return;
        // Find the nearest road vertex; constrain search to nearby points when moving.
        // Fall back to the full route only if we are far away (reconnected GPS).
        int best = lastRouteVertex;
        double nearest = Double.POSITIVE_INFINITY;
        int from = Math.max(0, lastRouteVertex - 30);
        int to = Math.min(route.geometry.size(), lastRouteVertex + 451);
        for (int i = from; i < to; i++) {
            double d = quickMeters(fix.getLatitude(), fix.getLongitude(), route.geometry.get(i));
            if (d < nearest) { nearest = d; best = i; }
        }
        if (nearest > 180.0) {
            nearest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < route.geometry.size(); i++) {
                double d = quickMeters(fix.getLatitude(), fix.getLongitude(), route.geometry.get(i));
                if (d < nearest) { nearest = d; best = i; }
            }
        }
        lastRouteVertex = Math.max(0, best);
        if (nearest > Math.max(100.0, fix.hasAccuracy() ? 2 * fix.getAccuracy() : 100.0)) {
            nextTurn.setText("Вне маршрута • перестрой маршрут на телефоне");
            return;
        }
        double remainingMeters = metersToFinish[best];
        if (remainingMeters < 35) {
            nextTurn.setText("Вы прибыли в пункт назначения");
            return;
        }
        String next = "Двигайтесь по маршруту";
        double beforeTurn = remainingMeters;
        for (RoutePlanner.Maneuver maneuver : route.maneuvers) {
            if (maneuver.pathIndex >= best + 1) {
                next = maneuver.instruction;
                beforeTurn = Math.max(0, remainingMeters - metersToFinish[maneuver.pathIndex]);
                break;
            }
        }
        String distance = beforeTurn >= 1000
                ? String.format(Locale.US, "%.1f км", beforeTurn / 1000.0)
                : String.format(Locale.US, "%.0f м", beforeTurn);
        nextTurn.setText(distance + " • " + next
                + String.format(Locale.US, "\nДо финиша %.1f км", remainingMeters / 1000.0));
    }

    private static double quickMeters(double lat, double lon, GeoPoint road) {
        double deltaLat = (lat - road.getLatitude()) * 111132.0;
        double deltaLon = (lon - road.getLongitude()) * 111320.0 *
                Math.cos(Math.toRadians(lat));
        return Math.hypot(deltaLat, deltaLon);
    }

    @Override protected void onStop() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        locationListener = null;
        locationManager = null;
        lastFix = null;
        activeRoute = null;
        routeLine = null;
        routeDestinationMarker = null;
        metersToFinish = null;
        if (map != null) {
            map.onPause();
            map.onDetach();
            map = null;
            position = null;
        }
        super.onStop();
    }
}
