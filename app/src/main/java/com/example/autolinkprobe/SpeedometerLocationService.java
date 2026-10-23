package com.example.autolinkprobe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Lightweight, system-only GPS source for Android 7 and newer.
 * No Google Play Services, mobile data or map SDK is necessary.
 */
public final class SpeedometerLocationService extends Service {
    private static final String CHANNEL = "gaz_speedometer_location";
    private static final int NOTIFICATION_ID = 1408;

    private LocationManager manager;
    private LocationListener listener;
    private GnssStatus.Callback satelliteListener;
    private BroadcastReceiver providersReceiver;
    private boolean subscribed;
    private boolean satelliteCallbackRegistered;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Спидометр — GPS в фоне", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Поддерживает измерение скорости при свёрнутом приложении");
            channel.setShowBadge(false);
            notifications.createNotificationChannel(channel);
        }

        // On Android 7 a disabled GPS provider must be observed and re-armed
        // automatically when the user enables location in system settings.
        providersReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                synchronizeGpsProvider();
            }
        };
        IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(providersReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(providersReceiver, filter);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop_gps".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            SpeedometerDashboard.get().setGpsStatus("НЕТ РАЗРЕШЕНИЯ");
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
                .setContentText("Системный GPS · работает без интернета")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Остановить GPS", stopAction);
        try {
            Notification notification = builder.build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            synchronizeGpsProvider();
        } catch (RuntimeException e) {
            Log.e("GazSpeedometer", "Cannot start foreground GPS", e);
            SpeedometerDashboard.get().setGpsStatus("ОШИБКА GPS-СЛУЖБЫ");
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void synchronizeGpsProvider() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            unsubscribeLocation();
            SpeedometerDashboard.get().setGpsStatus("НЕТ РАЗРЕШЕНИЯ");
            return;
        }
        if (manager == null) {
            manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        }
        if (manager == null) {
            SpeedometerDashboard.get().setGpsStatus("GPS НЕДОСТУПЕН");
            return;
        }
        try {
            if (manager.getProvider(LocationManager.GPS_PROVIDER) == null) {
                unsubscribeLocation();
                SpeedometerDashboard.get().setGpsStatus("НЕТ GPS-МОДУЛЯ");
                return;
            }
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                unsubscribeLocation();
                SpeedometerDashboard.get().setGpsStatus("ВКЛЮЧИ GPS");
                return;
            }
            if (subscribed) return;
            if (listener == null) {
                listener = new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        // Never claim network-based estimates are real satellite speed.
                        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                            SpeedometerDashboard.get().onLocation(location);
                        }
                    }
                    @Override public void onStatusChanged(String provider, int status,
                            Bundle extras) {
                        if (subscribed && status == LocationProvider.OUT_OF_SERVICE) {
                            SpeedometerDashboard.get().setGpsStatus("НЕТ GPS-СИГНАЛА");
                        } else if (subscribed
                                && status == LocationProvider.TEMPORARILY_UNAVAILABLE) {
                            SpeedometerDashboard.get().setGpsStatus("ПОИСК СПУТНИКОВ");
                        }
                    }
                    @Override public void onProviderEnabled(String provider) {
                        synchronizeGpsProvider();
                    }
                    @Override public void onProviderDisabled(String provider) {
                        synchronizeGpsProvider();
                    }
                };
            }
            // Old tablet: native GPS_PROVIDER, main looper, no Play Services.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    500L, 0f, listener, Looper.getMainLooper());
            subscribed = true;
            SpeedometerDashboard.get().setGpsStatus("ПОИСК СПУТНИКОВ");
            registerSatelliteListener();
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e("GazSpeedometer", "GPS provider unavailable", e);
            unsubscribeLocation();
            SpeedometerDashboard.get().setGpsStatus("ОШИБКА ДОСТУПА GPS");
        } catch (RuntimeException e) {
            Log.e("GazSpeedometer", "GPS subscribe failed", e);
            unsubscribeLocation();
            SpeedometerDashboard.get().setGpsStatus("ОШИБКА GPS-СЛУЖБЫ");
        }
    }

    private void registerSatelliteListener() {
        if (satelliteCallbackRegistered || manager == null) return;
        // API 24 is Android 7. Callback is diagnostic only; failure cannot
        // prevent the GPS location stream from working.
        if (satelliteListener == null) {
            satelliteListener = new GnssStatus.Callback() {
                @Override public void onStarted() {
                    if (subscribed) SpeedometerDashboard.get()
                            .setGpsStatus("ПОИСК СПУТНИКОВ");
                }
                @Override public void onStopped() {
                    if (subscribed) SpeedometerDashboard.get()
                            .setGpsStatus("НЕТ GPS-СИГНАЛА");
                }
                @Override public void onSatelliteStatusChanged(GnssStatus status) {
                    if (!subscribed) return;
                    int count = status.getSatelliteCount();
                    int used = 0;
                    for (int i = 0; i < count; ++i) {
                        if (status.usedInFix(i)) used++;
                    }
                    SpeedometerDashboard.get().setSatellites(count, used);
                }
                @Override public void onFirstFix(int ttffMillis) {
                    if (subscribed) SpeedometerDashboard.get()
                            .setGpsStatus("GPS: ЕСТЬ КООРДИНАТЫ");
                }
            };
        }
        try {
            satelliteCallbackRegistered = manager.registerGnssStatusCallback(
                    satelliteListener, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException e) {
            Log.w("GazSpeedometer", "Satellite diagnostic unavailable", e);
        }
    }

    private void unsubscribeLocation() {
        if (manager != null && listener != null && subscribed) {
            try { manager.removeUpdates(listener); }
            catch (SecurityException | IllegalArgumentException ignored) {}
        }
        subscribed = false;
        if (manager != null && satelliteListener != null && satelliteCallbackRegistered) {
            manager.unregisterGnssStatusCallback(satelliteListener);
        }
        satelliteCallbackRegistered = false;
        SpeedometerDashboard.get().clearSatellites();
    }

    @Override public void onDestroy() {
        unsubscribeLocation();
        if (providersReceiver != null) {
            unregisterReceiver(providersReceiver);
            providersReceiver = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
