package com.example.autolinkprobe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Foreground GPS source. Unlike Activity callbacks this continues after Home,
 * screen lock or switching to the OEM AutoLink app.
 */
public final class SpeedometerLocationService extends Service {
    private static final String CHANNEL = "gaz_speedometer_location";
    private static final int NOTIFICATION_ID = 1408;
    private LocationManager manager;
    private LocationListener listener;
    private boolean running;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Спидометр — GPS в фоне", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Поддерживает измерение скорости при свёрнутом приложении");
            channel.setShowBadge(false);
            notifications.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop_gps".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(
                        Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, SpeedometerLocationService.class).setAction("stop_gps");
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("GAZ Спидометр")
                .setContentText("GPS измеряет скорость для магнитолы")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Остановить GPS", stopAction);
        Notification notification = builder.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            subscribe();
        } catch (RuntimeException e) {
            Log.e("GazSpeedometer", "Cannot start foreground GPS", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void subscribe() {
        if (running) return;
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) return;
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                SpeedometerDashboard.get().onLocation(location);
            }
            @Override public void onStatusChanged(String provider, int status,
                    android.os.Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        try {
            // Genuine satellite GPS; data connection is not required.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    250L, 0f, listener, Looper.getMainLooper());
            running = true;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e("GazSpeedometer", "GPS subscription failed", e);
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        if (manager != null && listener != null) {
            try { manager.removeUpdates(listener); }
            catch (SecurityException ignored) {}
        }
        running = false;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
