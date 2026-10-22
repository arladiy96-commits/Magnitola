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

/**
 * Own navigation canvas for AutoLink's PRIVATE virtual display.
 * Nothing on the physical phone display is captured.
 * Stage 1: OpenStreetMap and live GPS; routing and touchscreen input follow later.
 */
final class NavigatorPresentation extends Presentation {
    private MapView map;
    private Marker position;
    private TextView gpsState;
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
        header.addView(title);
        header.addView(gpsState);
        root.addView(header, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        TextView attribution = new TextView(getContext());
        attribution.setText("© OpenStreetMap contributors");
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
                if (map == null || !location.hasAccuracy() && location.getLatitude() == 0) return;
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
                gpsState.setText(String.format(java.util.Locale.US,
                        "GPS • %.1f км/ч • точность %.0f м", Math.max(0, location.getSpeed() * 3.6f),
                        location.hasAccuracy() ? location.getAccuracy() : 0));
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

    @Override protected void onStop() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        locationListener = null;
        locationManager = null;
        if (map != null) {
            map.onPause();
            map.onDetach();
            map = null;
            position = null;
        }
        super.onStop();
    }
}
