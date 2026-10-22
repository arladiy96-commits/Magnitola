package com.example.autolinkprobe;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Explicit, user-initiated route prototype.
 * Android's installed Geocoder resolves place names (no external geocoder API key).
 * A single route request uses the public OSRM demo only for personal testing.
 * No background polling or automatic rerouting.
 */
final class RoutePlanner {
    interface Callback {
        void onReady(Route route);
        void onError(String message);
    }

    static final class Maneuver {
        final GeoPoint point;
        final String instruction;
        int pathIndex;
        Maneuver(GeoPoint p, String text) {
            point = p;
            instruction = text;
        }
    }

    static final class Route {
        final String target;
        final GeoPoint destination;
        final ArrayList<GeoPoint> geometry;
        final ArrayList<Maneuver> maneuvers;
        final double distanceMeters, durationSeconds;

        Route(String target, GeoPoint destination, ArrayList<GeoPoint> geometry,
              ArrayList<Maneuver> maneuvers, double distanceMeters, double durationSeconds) {
            this.target = target;
            this.destination = destination;
            this.geometry = geometry;
            this.maneuvers = maneuvers;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            // Assign maneuver route indices in order, avoiding snapping a turn to
            // an earlier parallel stretch of road.
            int start = 0;
            for (Maneuver maneuver : maneuvers) {
                int best = start;
                float bestDistance = Float.MAX_VALUE;
                for (int i = start; i < geometry.size(); i++) {
                    float distance = meters(geometry.get(i), maneuver.point);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = i;
                    }
                    if (i > best + 400) break;
                }
                maneuver.pathIndex = best;
                start = best;
            }
        }
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final String AGENT =
            "GazAutoLinkNavigator/0.1 (personal prototype; github.com/arladiy96-commits/Magnitola)";
    private static volatile long lastRequestAt;

    static void build(Context context, String query, Location origin, Callback callback) {
        if (origin == null) {
            callback.onError("Пока нет GPS. Дождись координат и попробуй ещё раз.");
            return;
        }
        if (query == null || query.trim().isEmpty()) {
            callback.onError("Введи адрес или координаты назначения.");
            return;
        }
        final String destinationText = query.trim();
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                GeoPoint goal = resolve(appContext, destinationText);
                if (goal == null) {
                    fail(callback, "Адрес не найден. Попробуй точнее или введи широту, долготу.");
                    return;
                }
                Route route = fetchRoute(destinationText, origin, goal);
                UI.post(() -> callback.onReady(route));
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                fail(callback, "Не удалось построить маршрут: " + reason);
            }
        }, "navigator-route-request").start();
    }

    private static GeoPoint resolve(Context context, String text) throws Exception {
        // Also works when the Android system has no geocoding backend.
        String[] components = text.split("[,;]");
        if (components.length == 2) {
            try {
                double lat = Double.parseDouble(components[0].trim().replace(' ', '.'));
                double lon = Double.parseDouble(components[1].trim().replace(' ', '.'));
                if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
                    return new GeoPoint(lat, lon);
                }
            } catch (NumberFormatException ignored) { }
        }
        if (!Geocoder.isPresent()) {
            throw new IllegalStateException("На телефоне нет службы поиска адресов. Введи координаты.");
        }
        Geocoder geocoder = new Geocoder(context, new Locale("ru", "KZ"));
        @SuppressWarnings("deprecation")
        List<Address> results = geocoder.getFromLocationName(text, 1);
        if (results == null || results.isEmpty()) return null;
        Address address = results.get(0);
        if (!address.hasLatitude() || !address.hasLongitude()) return null;
        return new GeoPoint(address.getLatitude(), address.getLongitude());
    }

    private static Route fetchRoute(String target, Location origin, GeoPoint goal) throws Exception {
        String coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f",
                origin.getLongitude(), origin.getLatitude(), goal.getLongitude(), goal.getLatitude());
        String url = "https://router.project-osrm.org/route/v1/driving/" + coords
                + "?overview=full&geometries=geojson&steps=true";
        // One explicit press creates at most one request; no requests triggered
        // by every GPS fix. Throttle repeated presses to protect demo capacity.
        synchronized (RoutePlanner.class) {
            long elapsed = android.os.SystemClock.elapsedRealtime() - lastRequestAt;
            if (lastRequestAt > 0 && elapsed < 1500) {
                throw new IllegalStateException("Слишком быстро. Повтори через пару секунд.");
            }
            lastRequestAt = android.os.SystemClock.elapsedRealtime();
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", AGENT);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new IllegalStateException("сервер маршрутов HTTP " + status);
            byte[] bytes;
            try (InputStream stream = conn.getInputStream()) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 4000000) throw new IllegalStateException("слишком длинный маршрут");
                }
                bytes = output.toByteArray();
            }
            JSONObject response = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!"Ok".equals(response.optString("code"))) {
                throw new IllegalStateException("дорога не найдена (" + response.optString("code") + ")");
            }
            JSONArray routes = response.getJSONArray("routes");
            if (routes.length() == 0) throw new IllegalStateException("нет автомобильного маршрута");
            JSONObject best = routes.getJSONObject(0);
            JSONArray coordsArray = best.getJSONObject("geometry").getJSONArray("coordinates");
            ArrayList<GeoPoint> path = new ArrayList<>();
            for (int i = 0; i < coordsArray.length(); i++) {
                JSONArray pos = coordsArray.getJSONArray(i);
                path.add(new GeoPoint(pos.getDouble(1), pos.getDouble(0)));
            }
            if (path.size() < 2) throw new IllegalStateException("маршрут не содержит дороги");
            ArrayList<Maneuver> maneuvers = new ArrayList<>();
            JSONArray legs = best.getJSONArray("legs");
            for (int k = 0; k < legs.length(); k++) {
                JSONArray steps = legs.getJSONObject(k).getJSONArray("steps");
                for (int j = 0; j < steps.length(); j++) {
                    JSONObject step = steps.getJSONObject(j);
                    JSONObject maneuver = step.getJSONObject("maneuver");
                    JSONArray pos = maneuver.getJSONArray("location");
                    String type = maneuver.optString("type");
                    String modifier = maneuver.optString("modifier");
                    String road = step.optString("name");
                    if ("depart".equals(type)) continue;
                    String instruction = translate(type, modifier, road);
                    maneuvers.add(new Maneuver(new GeoPoint(pos.getDouble(1), pos.getDouble(0)), instruction));
                }
            }
            return new Route(target, goal, path, maneuvers,
                    best.getDouble("distance"), best.getDouble("duration"));
        } finally {
            conn.disconnect();
        }
    }

    private static String translate(String type, String modifier, String road) {
        if ("arrive".equals(type)) return "Вы прибыли в пункт назначения";
        String direction;
        if (modifier.contains("left")) direction = "налево";
        else if (modifier.contains("right")) direction = "направо";
        else if ("uturn".equals(modifier)) direction = "развернитесь";
        else direction = "прямо";
        String base;
        if ("roundabout".equals(type) || "rotary".equals(type)) base = "Круговое движение";
        else if ("merge".equals(type)) base = "Перестройтесь " + direction;
        else if ("fork".equals(type)) base = "Держитесь " + direction;
        else if ("continue".equals(type) || "new name".equals(type)) base = "Продолжайте " + direction;
        else if ("on ramp".equals(type) || "off ramp".equals(type)) base = "Съезд " + direction;
        else base = "Поверните " + direction;
        if (road != null && !road.isEmpty()) base += " • " + road;
        return base;
    }

    static float meters(GeoPoint a, GeoPoint b) {
        float[] result = new float[1];
        Location.distanceBetween(a.getLatitude(), a.getLongitude(),
                b.getLatitude(), b.getLongitude(), result);
        return result[0];
    }

    private static void fail(Callback callback, String message) {
        UI.post(() -> callback.onError(message));
    }

    private RoutePlanner() {}
}
