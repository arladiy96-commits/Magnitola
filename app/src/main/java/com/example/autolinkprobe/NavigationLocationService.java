package com.example.autolinkprobe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground GPS owner. Started by visible MainActivity only AFTER the user
 * grants location; can keep GPS updates when the phone is locked / on Home.
 * The AutoLink USB owner is still MainActivity in this iteration.
 */
public final class NavigationLocationService extends Service {
    interface Observer {
        void onLocation(Location fix);
    }
    static final String ACTION_STOP = "com.example.autolinkprobe.STOP_NAVIGATION";
    private static final String CHANNEL = "gaz_navigation";
    private static final int NOTIFICATION_ID = 301;
    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<>();
    private static volatile Location latest;
    private LocationManager locationManager;
    private LocationListener listener;

    static void subscribe(Observer observer) {
        if (!OBSERVERS.contains(observer)) OBSERVERS.add(observer);
        Location previous = latestLocation();
        if (previous != null) observer.onLocation(previous);
    }

    static void unsubscribe(Observer observer) {
        OBSERVERS.remove(observer);
    }

    static Location latestLocation() {
        Location x = latest;
        if (x == null) return null;
        // Invalid readings must not be silently reused after leaving the app.
        if (SystemClock.elapsedRealtimeNanos() - x.getElapsedRealtimeNanos()
                > 15_000_000_000L) return null;
        return new Location(x);
    }

    static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Навигация на Газели", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!hasPermission(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openApp = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Навигация Газели")
                .setContentText("GPS активен • можно заблокировать телефон")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notice,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notice);
        }
        startLocation();
        return START_NOT_STICKY;
    }

    private void startLocation() {
        if (listener != null) return;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;
        listener = location -> {
            if (!Double.isFinite(location.getLatitude())
                    || !Double.isFinite(location.getLongitude())) return;
            Location prev = latest;
            if (prev != null) {
                // Prefer an actual GPS fix over a newer, but rough, network fix.
                boolean network = LocationManager.NETWORK_PROVIDER.equals(location.getProvider());
                if (network && LocationManager.GPS_PROVIDER.equals(prev.getProvider())
                        && SystemClock.elapsedRealtimeNanos() - prev.getElapsedRealtimeNanos()
                        < 8_000_000_000L) return;
                if (location.getElapsedRealtimeNanos() < prev.getElapsedRealtimeNanos()) return;
            }
            Location copy = new Location(location);
            latest = copy;
            for (Observer observer : OBSERVERS) observer.onLocation(new Location(copy));
        };
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Location initial = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (initial != null && SystemClock.elapsedRealtimeNanos()
                        - initial.getElapsedRealtimeNanos() < 10_000_000_000L) {
                    listener.onLocationChanged(initial);
                }
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        1000, 0f, listener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        3500, 3f, listener, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Location can be turned off by the driver while the service runs.
        }
    }

    @Override public void onDestroy() {
        if (locationManager != null && listener != null) {
            try { locationManager.removeUpdates(listener); }
            catch (SecurityException ignored) {}
        }
        listener = null;
        latest = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
