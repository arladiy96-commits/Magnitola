package com.example.autolinkprobe;

import android.app.Activity;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Temporary one-tap control test on the PHYSICAL phone display.
 * Same MapLibre, same stylesheet and TextureView mode as the head unit.
 * If roads work here but not on the head unit, network and source are not
 * the cause; investigate VirtualDisplay/MediaCodec GPU composition.
 */
public final class MapDiagnosticsActivity extends Activity {
    private static final String STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
    private MapView mapView;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger loaded = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicInteger frames = new AtomicInteger();
    private boolean styleReady;
    private String errorText = "";
    private long lastUpdate;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MapLibre.getInstance(this);
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(0xFFDFE8E5);
        setContentView(page);

        MapLibreMapOptions options = new MapLibreMapOptions()
                .textureMode(true)
                .zoomGesturesEnabled(true)
                .scrollGesturesEnabled(true)
                .rotateGesturesEnabled(true);
        mapView = new MapView(this, options);
        page.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
        mapView.onCreate(null);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(16, 12, 16, 12);
        top.setBackgroundColor(0xEF142A3B);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setText("Проверяю векторную карту на экране телефона…");
        status.setMaxLines(5);
        Button close = new Button(this);
        close.setText("Закрыть проверку карты");
        close.setOnClickListener(v -> finish());
        top.addView(status);
        top.addView(close, new LinearLayout.LayoutParams(-1, -2));
        page.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        mapView.addOnDidFailLoadingMapListener(error -> {
            errorText = error;
            report();
        });
        mapView.addOnTileActionListener((op, x, y, z, wrap, overscaledZ, sourceId) -> {
            String event = String.valueOf(op);
            if (event.contains("LoadFromNetwork") || event.contains("LoadFromCache")
                    || event.contains("LoadFromDatabase")) loaded.incrementAndGet();
            else if (event.contains("Error")) errors.incrementAndGet();
            report();
        });
        mapView.addOnDidFinishRenderingFrameListener(
                (fully, frameEncode, frameRender) -> {
                    frames.incrementAndGet();
                    report();
                });
        mapView.getMapAsync(map -> {
            Location gps = NavigationLocationService.latestLocation();
            // Astana is explicitly a TEST location, not a claimed GPS reading.
            LatLng center = gps == null
                    ? new LatLng(51.1694, 71.4491)
                    : new LatLng(gps.getLatitude(), gps.getLongitude());
            map.moveCamera(CameraUpdateFactory.newCameraPosition(
                    new CameraPosition.Builder().target(center).zoom(14).build()));
            map.setStyle(STYLE_URL, style -> {
                styleReady = true;
                report();
            });
        });
        report();
    }

    private void report() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastUpdate < 800) return;
        lastUpdate = now;
        ui.post(() -> {
            if (status == null) return;
            StringBuilder s = new StringBuilder("КАРТА НА ТЕЛЕФОНЕ (контроль)\n");
            s.append("Стиль: ").append(styleReady ? "загружен" : "ожидается");
            s.append(" • тайлы: ").append(loaded.get());
            s.append(" • ошибки: ").append(errors.get());
            s.append(" • кадры GL: ").append(frames.get());
            if (!errorText.isEmpty()) s.append("\nОшибка: ").append(errorText);
            else s.append("\nЕсли дороги здесь ЕСТЬ, а на Газели НЕТ — причина в выводе на виртуальный экран.");
            status.setText(s.toString());
        });
    }

    @Override protected void onStart() {
        super.onStart();
        if (mapView != null) mapView.onStart();
    }
    @Override protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }
    @Override protected void onPause() {
        if (mapView != null) mapView.onPause();
        super.onPause();
    }
    @Override protected void onStop() {
        if (mapView != null) mapView.onStop();
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (mapView != null) {
            mapView.onDestroy();
            mapView = null;
        }
        super.onDestroy();
    }
}
