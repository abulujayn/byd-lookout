package com.overdrive.app.weather;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.mqtt.ProxyHelper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared outside-air temperature from the Open-Meteo weather API, keyed on GPS
 * coordinates. Single source of truth for weather temp across the daemon (the
 * automation temperature fallback, ABRP {@code ext_temp}, …) so there is ONE
 * cache and ONE network path, not a copy per caller.
 *
 * <p>Design for the automation use-case: the value is read from the telemetry
 * poll thread (via BydEvent), which must NEVER block on the network. So the
 * public {@link #getCached()} / {@link #refreshAsync} split is:
 * <ul>
 *   <li>{@link #getCached()} returns the last fetched value instantly (NaN if none
 *       yet / too stale), never touching the network.</li>
 *   <li>{@link #refreshAsync(double, double)} kicks a background fetch if the cache
 *       is stale and no fetch is already in flight. Cheap to call every poll.</li>
 * </ul>
 * On ACC-off the head-unit data interface briefly drops ~17s after park but
 * self-recovers ~3 min later while still parked, so an async fetch that fails is
 * simply retried on a later poll; the cache rides out the transient blackout.
 */
public final class WeatherTemperature {
    private static final DaemonLogger logger = DaemonLogger.getInstance("WeatherTemperature");

    /** Serve the cached value for this long before a refresh is attempted. */
    private static final long CACHE_MS = 10 * 60 * 1000; // 10 min
    /** Discard the cache entirely past this age (a parked car's local weather
     *  doesn't change fast, but a day-old value shouldn't drive an automation). */
    private static final long STALE_MS = 60 * 60 * 1000; // 1 h

    private static volatile double cachedTemp = Double.NaN;
    private static volatile long lastFetchTime = 0;
    private static final AtomicBoolean fetchInFlight = new AtomicBoolean(false);

    private WeatherTemperature() {}

    /**
     * The last fetched outside temperature (°C), or NaN if none has been fetched
     * yet or the cached value is older than {@link #STALE_MS}. Never blocks.
     */
    public static double getCached() {
        double t = cachedTemp;
        if (Double.isNaN(t)) return Double.NaN;
        if (System.currentTimeMillis() - lastFetchTime > STALE_MS) return Double.NaN;
        return t;
    }

    /**
     * Kick a background refresh if the cache is stale ({@literal >}{@link #CACHE_MS})
     * and no fetch is already running. Returns immediately. Safe to call every poll.
     *
     * @param lat last-known latitude  (parked car doesn't move, so last-known is fine)
     * @param lon last-known longitude
     */
    public static void refreshAsync(final double lat, final double lon) {
        if (lat == 0.0 && lon == 0.0) return; // no location yet
        long now = System.currentTimeMillis();
        if (!Double.isNaN(cachedTemp) && (now - lastFetchTime) < CACHE_MS) return; // fresh enough
        if (!fetchInFlight.compareAndSet(false, true)) return; // one fetch at a time
        Thread t = new Thread(() -> {
            try {
                fetchNow(lat, lon);
            } finally {
                fetchInFlight.set(false);
            }
        }, "weather-temp-fetch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Synchronous fetch — for callers already off the hot path (e.g. ABRP's own
     * upload thread). Updates the shared cache. Returns the fetched value, or the
     * existing cache (possibly NaN) on failure. Bounded to a few seconds.
     */
    public static double fetchNow(double lat, double lon) {
        if (lat == 0.0 && lon == 0.0) return getCached();
        long now = System.currentTimeMillis();
        // Serve fresh cache without a network round-trip.
        if (!Double.isNaN(cachedTemp) && (now - lastFetchTime) < CACHE_MS) return cachedTemp;
        try {
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m",
                    lat, lon);
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "OverDrive/1.0")
                    .build();
            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(ProxyHelper.getHttpProxy())
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    JSONObject current = new JSONObject(body).optJSONObject("current");
                    if (current != null && current.has("temperature_2m")) {
                        double temp = current.getDouble("temperature_2m");
                        cachedTemp = temp;
                        lastFetchTime = System.currentTimeMillis();
                        logger.debug("weather temp = " + String.format(Locale.US, "%.1f", temp) + "°C");
                        return temp;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("weather fetch failed: " + e.getMessage());
        }
        return getCached(); // stale cache or NaN
    }
}
