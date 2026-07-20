package com.overdrive.app.byd;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.Messages;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Universal BYD Data Collector — singleton that initializes ALL BYD device types,
 * reads initial values, registers listeners for live updates, and exposes a
 * thread-safe BydVehicleData snapshot.
 * 
 * Every device init and every method call is individually try/caught — one device
 * failing never affects others. Never crashes.
 */
public class BydDataCollector {

    private static final String TAG = "BydDataCollector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static BydDataCollector instance;
    private static final Object lock = new Object();

    private final AtomicReference<BydVehicleData> snapshot = new AtomicReference<>();
    private Context context;
    private volatile boolean initialized = false;

    // Device references (all nullable)
    private Object bodyworkDevice;
    // Volatile: read by the cluster speed overlay's 2 Hz thread in readCurrentSpeedKmh()
    // without the monitor, while init() (synchronized) may reassign it on an ACC-on
    // re-init. Volatile gives the happens-before so the overlay never sees a stale ref
    // (matches speedHwFactor/hwUnitDetected, read on the same path).
    private volatile Object speedDevice;
    private Object engineDevice;
    private Object statisticDevice;
    private Object energyDevice;
    private Object tyreDevice;
    private Object chargingDevice;
    private Object doorLockDevice;
    private Object instrumentDevice;
    private Object otaDevice;
    private Object sensorDevice;
    private Object gearboxDevice;
    private Object safetyBeltDevice;
    private Object acDevice;
    private Object lightDevice;
    private Object adasDevice;
    private Object radarDevice;
    private Object powerDevice;
    private Object settingDevice;
    private Object multimediaDevice;

    // Unit conversion: BYD APIs return values in the user's configured unit.
    // If the user set miles on the instrument cluster, mileage/speed/range come back in miles/mph.
    // We detect this once at init and convert everything to km at the ingestion boundary.
    private static final double MILES_TO_KM = 1.60934;
    private volatile double distanceToKmFactor = 1.0;  // 1.0 = already km, 1.60934 = miles→km
    private boolean unitDetected = false;
    // HARDWARE-ONLY SDK→km factor for the cluster speed badge. Unlike
    // distanceToKmFactor (which setDistanceUnitOverride drives from the user's APP
    // display preference and so can diverge from the cluster's real unit), this
    // tracks ONLY the authoritative getMileageUnit() hardware detection — so
    // readCurrentSpeedKmh() returns TRUE km/h and the overlay's single mph conversion
    // isn't double-applied. When hardware detection never succeeds (hwUnitDetected
    // stays false) readCurrentSpeedKmh() returns NaN ("--") — it NEVER falls back to
    // the app override (distanceToKmFactor), since that can be unit-contaminated and
    // the app preference can't disambiguate the raw cluster unit. Volatile: read from
    // the overlay's 2 Hz thread, written on the init/API threads.
    private volatile double speedHwFactor = 1.0;
    private volatile boolean hwUnitDetected = false;

    // PHEV half-scale energy correction. FIELD-CONFIRMED (owner ground truth,
    // multiple BYD PHEVs): on EVERY PHEV the BYD HAL reports remaining battery
    // energy at HALF the true (gross-nameplate) scale — a constant ~0.497
    // fraction across pack sizes, the fingerprint of a fixed scaling artifact,
    // not real degradation (e.g. ~9.1 kWh read on an 18.3 kWh gross pack at full
    // charge; ×2 = 18.2 ≈ nameplate). This is NOT a "usable window" — every
    // remaining-energy getter (getBatteryRemainPowerEV / getRemainingBatteryPower
    // / getBatteryPowerHEV / getBatteryCapacity) is affected. We correct it ONCE,
    // at the read boundary in collectBodywork, so the single corrected remainKwh
    // flows in the true gross frame into trips, MQTT, and SOH. BEV is never
    // touched (gated on isPhevForKwh). Applied BEFORE the validation gates so a
    // gross value at full charge passes the impliedCap[10,130] check instead of
    // failing it (a half value implies ~9 kWh and would be rejected).
    private static final double PHEV_ENERGY_HALF_SCALE_CORRECTION = 2.0;

    // Throttle for the INFO-level PHEV energy diagnostic (all raw getters + SOC).
    // Lets a captured on-device log prove which getter tracks SOC and which is
    // stale, without spamming every 5s poll.
    private volatile long lastPhevEnergyDiagMs = 0;
    private static final long PHEV_ENERGY_DIAG_INTERVAL_MS = 60_000;

    private final List<String> availableDevices = new ArrayList<>();
    private final List<String> unavailableDevices = new ArrayList<>();

    // ==================== EVENT LISTENERS ====================
    // Subscribers receive door/lock events from the typed BYD HAL listeners.
    // Use these instead of polling the snapshot when you need immediate
    // notification of state transitions (e.g. surveillance arming gates).

    /** Raw SDK door-open/close events from the bodywork HAL. */
    public interface DoorStateListener {
        /** @param area BYD area constant. @param state 0=closed,1=open per SDK. */
        void onDoorStateChanged(int area, int state);
    }

    /** Raw SDK lock events from the doorlock HAL. */
    public interface DoorLockListener {
        /** @param area BYD area constant. @param sdkState SDK semantics: INVALID=0,UNLOCK=1,LOCK=2. */
        void onDoorLockStatusChanged(int area, int sdkState);
    }

    /** Snapshot-level lock summary listener — called on every snapshot update
     *  whose lock data may have changed. Use this when you want a single
     *  cohesive view of all areas rather than per-area events. */
    public interface LockSnapshotListener {
        void onLockSnapshotUpdated(BydVehicleData snapshot);
    }

    /** Raw BMS charging-state edges from the charging HAL. Fires only on
     *  transitions (current != previous), not on every poll. State values
     *  match {@code ChargingStateData.CHARGING_BATTERY_STATE_*}. */
    public interface ChargingStateListener {
        void onChargingStateChanged(int previousState, int newState);
    }

    private final java.util.concurrent.CopyOnWriteArrayList<DoorStateListener> doorStateListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<DoorLockListener> doorLockListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<LockSnapshotListener> lockSnapshotListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<ChargingStateListener> chargingStateListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addDoorStateListener(DoorStateListener l) { if (l != null) doorStateListeners.addIfAbsent(l); }
    public void removeDoorStateListener(DoorStateListener l) { doorStateListeners.remove(l); }
    public void addDoorLockListener(DoorLockListener l) { if (l != null) doorLockListeners.addIfAbsent(l); }
    public void removeDoorLockListener(DoorLockListener l) { doorLockListeners.remove(l); }
    public void addLockSnapshotListener(LockSnapshotListener l) { if (l != null) lockSnapshotListeners.addIfAbsent(l); }
    public void removeLockSnapshotListener(LockSnapshotListener l) { lockSnapshotListeners.remove(l); }
    public void addChargingStateListener(ChargingStateListener l) { if (l != null) chargingStateListeners.addIfAbsent(l); }
    public void removeChargingStateListener(ChargingStateListener l) { chargingStateListeners.remove(l); }

    private void notifyDoorStateListeners(int area, int state) {
        for (DoorStateListener l : doorStateListeners) {
            try { l.onDoorStateChanged(area, state); }
            catch (Exception e) { logger.debug("DoorStateListener error: " + e.getMessage()); }
        }
    }

    private void notifyDoorLockListeners(int area, int sdkState) {
        for (DoorLockListener l : doorLockListeners) {
            try { l.onDoorLockStatusChanged(area, sdkState); }
            catch (Exception e) { logger.debug("DoorLockListener error: " + e.getMessage()); }
        }
    }

    private void notifyLockSnapshotListeners(BydVehicleData snap) {
        for (LockSnapshotListener l : lockSnapshotListeners) {
            try { l.onLockSnapshotUpdated(snap); }
            catch (Exception e) { logger.debug("LockSnapshotListener error: " + e.getMessage()); }
        }
    }

    private void notifyChargingStateListeners(int previousState, int newState) {
        for (ChargingStateListener l : chargingStateListeners) {
            try { l.onChargingStateChanged(previousState, newState); }
            catch (Exception e) { logger.debug("ChargingStateListener error: " + e.getMessage()); }
        }
    }

    private BydDataCollector() {}

    public static BydDataCollector getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) instance = new BydDataCollector();
            }
        }
        return instance;
    }

    /** Get the latest vehicle data snapshot. Thread-safe. */
    public BydVehicleData getData() {
        return snapshot.get();
    }

    /** Check if the collector has been initialized. */
    public boolean isInitialized() {
        return initialized;
    }

    // ==================== INITIALIZATION ====================

    /**
     * Initialize all BYD devices. Each device is independent — failures are logged and skipped.
     */
    public synchronized void init(Context context) {
        if (initialized && this.context == context) {
            return;
        }
        this.context = context;
        logger.info("=== BYD Data Collector Initializing ===");
        long start = System.currentTimeMillis();

        // Re-init: tear down state that would otherwise accumulate.
        availableDevices.clear();
        unavailableDevices.clear();
        if (pollScheduler != null) {
            pollScheduler.shutdownNow();
            pollScheduler = null;
        }

        // Initialize each device type
        bodyworkDevice = initDevice("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", "Bodywork");
        speedDevice = initDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice", "Speed");
        engineDevice = initDevice("android.hardware.bydauto.engine.BYDAutoEngineDevice", "Engine");
        statisticDevice = initDevice("android.hardware.bydauto.statistic.BYDAutoStatisticDevice", "Statistic");
        chargingDevice = initDevice("android.hardware.bydauto.charging.BYDAutoChargingDevice", "Charging");
        instrumentDevice = initDevice("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", "Instrument");
        otaDevice = initDevice("android.hardware.bydauto.ota.BYDAutoOtaDevice", "OTA");
        gearboxDevice = initDevice("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", "Gearbox");
        acDevice = initDevice("android.hardware.bydauto.ac.BYDAutoAcDevice", "AC");
        lightDevice = initDevice("android.hardware.bydauto.light.BYDAutoLightDevice", "Light");
        adasDevice = initDevice("android.hardware.bydauto.adas.BYDAutoADASDevice", "ADAS");
        powerDevice = initDevice("android.hardware.bydauto.power.BYDAutoPowerDevice", "Power");
        safetyBeltDevice = initDevice("android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice", "SafetyBelt");
        tyreDevice = initDevice("android.hardware.bydauto.tyre.BYDAutoTyreDevice", "Tyre");
        doorLockDevice = initDevice("android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice", "DoorLock");
        sensorDevice = initDevice("android.hardware.bydauto.sensor.BYDAutoSensorDevice", "Sensor");
        energyDevice = initDevice("android.hardware.bydauto.energy.BYDAutoEnergyDevice", "Energy");
        radarDevice = initDevice("android.hardware.bydauto.radar.BYDAutoRadarDevice", "Radar");
        settingDevice = initDevice("android.hardware.bydauto.setting.BYDAutoSettingDevice", "Setting");
        multimediaDevice = initMultimediaDevice();

        logger.info("Devices available: " + availableDevices.size() + "/" + 
            (availableDevices.size() + unavailableDevices.size()));
        if (!unavailableDevices.isEmpty()) {
            logger.info("Unavailable: " + String.join(", ", unavailableDevices));
        }

        // Detect mileage unit from instrument cluster
        detectMileageUnit();

        // If auto-detection failed, fall back to user's persisted preference
        if (!unitDetected) {
            try {
                com.overdrive.app.trips.TripConfig tripConfig = new com.overdrive.app.trips.TripConfig();
                tripConfig.load();
                String savedUnit = tripConfig.getDistanceUnit();
                if ("mi".equals(savedUnit)) {
                    distanceToKmFactor = MILES_TO_KM;
                    unitDetected = true;
                    logger.info("Mileage unit: MILES (from user config override, factor=" + MILES_TO_KM + ")");
                }
            } catch (Exception e) {
                logger.info("Could not load distance unit from TripConfig: " + e.getMessage());
            }
        }

        // Read initial values (full collection including display-only devices)
        collectAllFull();

        // Dump all battery/energy related getter methods on key devices
        // to discover the correct remaining kWh API at runtime
        // Discovery methods removed — getBatteryRemainPowerEV() confirmed as correct BEV API.
        // BYD light/setting APIs have no write access from UID 2000.

        // Register listeners
        registerAllListeners();

        // Runtime receiver for power-cable plug edges. Manifest receiver
        // BootReceiver already covers cold-boot delivery, but Android
        // delivers POWER_CONNECTED/DISCONNECTED to runtime-registered
        // receivers more reliably while the process is alive — and the
        // ChargingDetector needs these edges within milliseconds of the
        // user plugging in so the fused state doesn't lag a 5s collect
        // cycle waiting for BMS to catch up.
        registerPlugEdgeReceiver();

        // Bridge BYD door-state events to push notifications. Safe to start
        // here — the door listener is only invoked once the bodywork HAL
        // fires onDoorStateChanged, which requires registerAllListeners to
        // have run first.
        com.overdrive.app.notifications.DoorEventNotifier.start();
        com.overdrive.app.notifications.ChargingEventNotifier.start();

        // Start periodic polling to keep data fresh (listeners may not fire for all values)
        startPolling();

        long elapsed = System.currentTimeMillis() - start;
        logger.info("=== BYD Data Collector Ready (" + elapsed + "ms) ===");
        initialized = true;
    }

    /**
     * Detect whether the BYD instrument cluster is configured for miles or km.
     * getMileageUnit() returns 1 for km, 0 for miles.
     * If detection fails, defaults to km (factor = 1.0).
     */
    private void detectMileageUnit() {
        if (instrumentDevice == null) {
            logger.info("Mileage unit: defaulting to km (no instrument device)");
            return;
        }
        try {
            Object unitVal = BydDeviceHelper.callGetter(instrumentDevice, "getMileageUnit");
            if (unitVal instanceof Number) {
                int unit = ((Number) unitVal).intValue();
                if (unit == 0) {
                    // Miles mode
                    distanceToKmFactor = MILES_TO_KM;
                    unitDetected = true;
                    // Authoritative HARDWARE factor for the speed badge (never touched
                    // by the app-preference override).
                    speedHwFactor = MILES_TO_KM;
                    hwUnitDetected = true;
                    logger.info("Mileage unit: MILES detected (factor=" + MILES_TO_KM + ")");
                } else if (unit == 1) {
                    // km mode
                    distanceToKmFactor = 1.0;
                    unitDetected = true;
                    speedHwFactor = 1.0;
                    hwUnitDetected = true;
                    logger.info("Mileage unit: KM detected (factor=1.0)");
                } else {
                    // Unrecognized / in-band SDK sentinel (getMileageUnit can return a
                    // non-zero garbage value on flaky trims, e.g. SDK_NOT_AVAILABLE).
                    // Do NOT latch the HARDWARE flag: leave hwUnitDetected=false so the
                    // speed badge shows "--" instead of a possibly-1.6×-wrong number
                    // (readCurrentSpeedKmh returns NaN when the true cluster unit is
                    // unknown — it does NOT consult the app override, which can't
                    // disambiguate the raw unit).
                    // For the DISPLAY factor (distanceToKmFactor, used by odometer/
                    // distance reads): default to km ONLY on a FRESH detect. If a PRIOR
                    // init already detected a good unit (unitDetected), PRESERVE it — a
                    // flaky re-init returning garbage must not clobber a known-good MILES
                    // factor back to km and silently halve every distance read.
                    if (!unitDetected) {
                        distanceToKmFactor = 1.0;
                        logger.info("Mileage unit: unrecognized getMileageUnit=" + unit
                                + " — defaulting display to km, HW unit undetected");
                    } else {
                        logger.info("Mileage unit: unrecognized getMileageUnit=" + unit
                                + " on re-init — preserving prior factor=" + distanceToKmFactor);
                    }
                }
            } else {
                logger.info("Mileage unit: defaulting to km (getMileageUnit returned null)");
            }
        } catch (Exception e) {
            logger.info("Mileage unit: defaulting to km (detection failed: " + e.getMessage() + ")");
        }
    }

    /**
     * Get the distance-to-km conversion factor.
     * Returns 1.0 if km, 1.60934 if miles.
     * Used by OdometerReader and other components that read BYD distance values directly.
     */
    public double getDistanceToKmFactor() {
        return distanceToKmFactor;
    }

    /**
     * Speed-unit factor for INTERPRETING a raw {@code getCurrentSpeed()} reading
     * (recording overlay + trip telemetry path). The raw reading's unit is fixed
     * by the CLUSTER hardware ({@code getMileageUnit}), NOT the app's km/mi display
     * preference — so this returns the HARDWARE factor ({@link #speedHwFactor}) when
     * hardware detection succeeded, mirroring {@link #readCurrentSpeedKmh()}.
     *
     * <p>Using {@link #distanceToKmFactor} here (as the telemetry path historically
     * did) is a bug: {@link #setDistanceUnitOverride} drives it from the user's
     * DISPLAY preference, which is ambiguous about the raw unit. When the display
     * preference diverges from the cluster's real unit (e.g. km cluster, user picks
     * mi), the raw reading gets scaled by ~1.6× before the overlay re-derives the
     * display value — showing a confidently-wrong speed.
     *
     * <p>Falls back to {@link #distanceToKmFactor} ONLY when hardware detection never
     * succeeded ({@code !hwUnitDetected}) — best-effort on trims where
     * {@code getMileageUnit} is unavailable; behavior there is unchanged.
     */
    public double getSpeedToKmhFactor() {
        return hwUnitDetected ? speedHwFactor : distanceToKmFactor;
    }

    /**
     * Override the distance unit from user settings. Called when the user
     * explicitly selects km or miles in the Trip Settings UI. This fixes the
     * case where auto-detection via getMileageUnit() fails (instrumentDevice
     * null, SDK returns null, etc.) and the raw miles values pass through
     * unconverted.
     *
     * @param unit "mi" for miles (factor=1.60934), "km" for km (factor=1.0)
     */
    public void setDistanceUnitOverride(String unit) {
        if ("mi".equals(unit)) {
            distanceToKmFactor = MILES_TO_KM;
            unitDetected = true;
            logger.info("Distance unit OVERRIDE: MILES (factor=" + MILES_TO_KM + ")");
        } else {
            distanceToKmFactor = 1.0;
            unitDetected = true;
            logger.info("Distance unit OVERRIDE: KM (factor=1.0)");
        }
    }

    /**
     * Returns true if the vehicle's instrument cluster is configured for miles.
     * Used by the /status API to tell the web UI which display unit to use.
     */
    public boolean isMilesMode() {
        return distanceToKmFactor > 1.0;
    }

    private java.util.concurrent.ScheduledExecutorService pollScheduler;
    private static final long POLL_INTERVAL_MS = 5000; // 5 seconds when ACC on
    private static final long POLL_INTERVAL_PARKED_MS = 90000; // 90 seconds when ACC off — listener callbacks keep the snapshot fresh between polls
    private String lastSummaryHash = "";

    // ==================== RoadSense fast dynamics poll ====================
    // RoadSense needs brake/accel/gear event-aligned to ~200 ms jolts (R-PERF-4),
    // but the main 5 s poll is far too coarse and we must NOT speed the whole poll
    // up (battery/SDK load) just for one consumer. So we expose an OPT-IN, narrowly
    // scoped fast poll that reads ONLY the four signals RoadSense uses
    // (brake %, accel %, gear, speed) via the device handles the collector already
    // holds, and publishes them to a SEPARATE lightweight atomic — never touching
    // the main snapshot, so no other consumer's freshness/values change. Started by
    // RoadSenseController only while RoadSense is ENABLED and the regime is DRIVING;
    // stopped otherwise. Zero cost when RoadSense is off.

    /** Immutable fast-dynamics tuple — only the fields RoadSense rejection needs. */
    public static final class FastDynamics {
        public final double speedKmh;
        public final int accelPercent;
        public final int brakePercent;
        public final int gearMode;
        public final long timestamp;
        FastDynamics(double speedKmh, int accelPercent, int brakePercent, int gearMode, long timestamp) {
            this.speedKmh = speedKmh; this.accelPercent = accelPercent;
            this.brakePercent = brakePercent; this.gearMode = gearMode; this.timestamp = timestamp;
        }
    }

    private final java.util.concurrent.atomic.AtomicReference<FastDynamics> fastDynamics =
            new java.util.concurrent.atomic.AtomicReference<>(null);
    private java.util.concurrent.ScheduledExecutorService fastPollScheduler;
    /** Fast-poll cadence: 250 ms ≈ event-aligned for ~200 ms jolts without hammering
     *  the SDK (4 Hz on three cheap getters, vs the 5 s full poll). */
    private static final long FAST_POLL_INTERVAL_MS = 250;

    /**
     * Latest RoadSense fast-dynamics tuple, or null if the fast poll isn't running
     * (RoadSense disabled / not driving). Consumers must treat null as "use the main
     * snapshot instead". Lock-free.
     */
    public FastDynamics getFastDynamics() {
        return fastDynamics.get();
    }

    /**
     * Current vehicle speed in km/h for the cluster speed badge — self-contained, so
     * it does NOT depend on RoadSense's {@link #startFastDynamicsPoll() fast poll}
     * being active (that poll only runs while RoadSense is enabled + driving).
     *
     * <p>This is a SINGLE live SDK read of {@code getCurrentSpeed}, scaled ONLY by the
     * HARDWARE-detected unit factor ({@link #speedHwFactor} from {@code getMileageUnit}).
     * It is NEVER scaled by {@link #distanceToKmFactor} when that has been driven by the
     * app's km/mi DISPLAY preference, because the app preference is fundamentally
     * AMBIGUOUS about the raw unit — "user picked mi" could mean "my cluster reads
     * miles" OR "my cluster reads km but I want mph shown", and those are
     * indistinguishable. Only hardware detection knows the true raw unit.
     *
     * <p>So:
     * <ul>
     *   <li>hardware unit detected ({@link #hwUnitDetected}) → scale by
     *       {@link #speedHwFactor} → TRUE km/h; the overlay then applies the single
     *       display km↔mph conversion.</li>
     *   <li>hardware unit NOT detected (getMileageUnit failed / returned garbage) →
     *       the raw unit is genuinely UNKNOWN, so return {@link Double#NaN} → the badge
     *       shows "--". Assuming km would read ~1.6× LOW on a real miles cluster, and
     *       the app preference can't disambiguate it — a blank speedometer is safer than
     *       a confidently-wrong one (matches this collector's "-- over a wrong number"
     *       philosophy).</li>
     * </ul>
     * The cached {@code fastDynamics}/{@code snapshot} values are deliberately NOT used
     * as a fallback (they are pre-scaled by the possibly-overridden
     * {@link #distanceToKmFactor}, so they can be unit-contaminated). A transient SDK
     * miss therefore returns NaN → the badge shows "--" for that ~500 ms tick and
     * self-corrects. NaN is also returned when the trim has no speed device / SDK
     * unavailable / ACC off. Lock-free; safe from the overlay's 2 Hz thread.
     */
    public double readCurrentSpeedKmh() {
        // Only a HARDWARE-detected unit is trustworthy for the raw value. Without it the
        // unit is unknown → NaN ("--"), never a guess (km would be ~1.6× low on a miles
        // cluster; the app preference can't disambiguate the raw unit).
        if (!hwUnitDetected) return Double.NaN;
        try {
            if (speedDevice != null) {
                Object sp = BydDeviceHelper.callGetter(speedDevice, "getCurrentSpeed");
                if (sp instanceof Number) {
                    double v = ((Number) sp).doubleValue();
                    if (v != BydFeatureIds.SDK_NOT_AVAILABLE && !Double.isNaN(v)) {
                        return v * speedHwFactor;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return Double.NaN;
    }

    /**
     * Start the narrowly-scoped fast dynamics poll (idempotent). Reads ONLY
     * brake/accel/gear/speed from the already-resolved device handles. Safe to call
     * from any thread; a no-op if already running or if the speed device isn't
     * available on this trim.
     */
    public synchronized void startFastDynamicsPoll() {
        if (fastPollScheduler != null) return;       // already running
        if (speedDevice == null && gearboxDevice == null) return; // nothing to poll on this trim
        fastPollScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RoadSenseFastPoll");
            t.setDaemon(true);
            return t;
        });
        fastPollScheduler.scheduleWithFixedDelay(() -> {
            try {
                double speedKmh = Double.NaN;
                int accel = BydVehicleData.UNAVAILABLE;
                int brake = BydVehicleData.UNAVAILABLE;
                int gear = BydVehicleData.UNAVAILABLE;
                if (speedDevice != null) {
                    Object sp = BydDeviceHelper.callGetter(speedDevice, "getCurrentSpeed");
                    if (sp instanceof Number) {
                        double v = ((Number) sp).doubleValue();
                        if (v != BydFeatureIds.SDK_NOT_AVAILABLE) speedKmh = v * distanceToKmFactor;
                    }
                    Object ac = BydDeviceHelper.callGetter(speedDevice, "getAccelerateDeepness");
                    if (ac instanceof Number) accel = ((Number) ac).intValue();
                    Object br = BydDeviceHelper.callGetter(speedDevice, "getBrakeDeepness");
                    if (br instanceof Number) brake = ((Number) br).intValue();
                }
                if (gearboxDevice != null) {
                    Object g = BydDeviceHelper.callGetter(gearboxDevice, "getGearboxAutoModeType");
                    if (g instanceof Number) gear = ((Number) g).intValue();
                }
                // Fall back to the last main-snapshot value for any field the fast
                // read couldn't get, so a momentary SDK miss doesn't blank a signal.
                BydVehicleData snap = snapshot.get();
                if (Double.isNaN(speedKmh) && snap != null) speedKmh = snap.speedKmh;
                if (accel == BydVehicleData.UNAVAILABLE && snap != null) accel = snap.accelPercent;
                if (brake == BydVehicleData.UNAVAILABLE && snap != null) brake = snap.brakePercent;
                if (gear == BydVehicleData.UNAVAILABLE && snap != null) gear = snap.gearMode;
                fastDynamics.set(new FastDynamics(speedKmh, accel, brake, gear, System.currentTimeMillis()));
            } catch (Throwable t) {
                logger.debug("Fast dynamics poll error: " + t.getMessage());
            }
        }, 0, FAST_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        logger.info("RoadSense fast dynamics poll started (" + FAST_POLL_INTERVAL_MS + "ms)");
    }

    /** Stop the fast dynamics poll and clear its snapshot (idempotent). */
    public synchronized void stopFastDynamicsPoll() {
        if (fastPollScheduler != null) {
            fastPollScheduler.shutdownNow();
            fastPollScheduler = null;
            logger.info("RoadSense fast dynamics poll stopped");
        }
        fastDynamics.set(null);
    }

    // ── Turn-indicator read (Blind Spot) ─────────────────────────────────────
    // The main light poll runs on the 5s full-snapshot cadence — far too slow to
    // pop the blind-spot overlay the instant the driver flicks the indicator.
    // The overlay (app process, no BYD handles) instead reads the lamps on-demand
    // over the daemon's loopback /api/stream/turn at its own 250ms tick, so there
    // is exactly ONE cadence and NO background scheduler here — the daemon just
    // answers each read inline.

    /** Read the turn lamps inline (no background scheduler), packed bit0=L, bit1=R
     *  (so 0=none, 1=left, 2=right, 3=both/hazard). Returns -1 if the light device
     *  is unavailable.
     *
     *  Uses getTurnLightFlashState() — a SINGLE combined enum — NOT the per-side
     *  getTurnLightState(1/2), which on this BYD firmware does not reflect the
     *  blinking indicator (it returned 0 even with the indicator on, so the
     *  blind-spot overlay never popped on a real turn signal — only debugPreview
     *  worked). TelemetryDataCollector uses this same getter+enum and is proven to
     *  detect turn signals reliably. Flash-state enum: 2|3=left, 4|5=right,
     *  6|7=hazard (both). Caller bridges the blink off-phase (the lamp toggles
     *  ~1.5Hz) via its own off-debounce. */
    public int readTurnNow() {
        if (lightDevice == null) return -1;
        try {
            Object fs = BydDeviceHelper.callGetter(lightDevice, "getTurnLightFlashState");
            if (!(fs instanceof Number)) return 0;
            int flashState = ((Number) fs).intValue();
            boolean left = (flashState == 2 || flashState == 3);
            boolean right = (flashState == 4 || flashState == 5);
            if (flashState == 6 || flashState == 7) { left = true; right = true; }  // hazard
            int packed = 0;
            if (left) packed |= 0x1;
            if (right) packed |= 0x2;
            return packed;
        } catch (Throwable t) {
            logger.debug("readTurnNow error: " + t.getMessage());
            return -1;
        }
    }

    // ── Fast dynamic-input reads (accelerator / brake / steering) ─────────────
    // Single live SDK reads mirroring readTurnNow(), for the self-gated DynamicsEvent
    // fast poll so an "accelerator > X%" / "steering past Y°" automation fires promptly
    // (the 5s telemetry snapshot lagged it by up to that long). Each guards the
    // SDK_NOT_AVAILABLE sentinel so a miss returns UNAVAILABLE (the caller skips the
    // publish) rather than a bogus value. Only called while a matching automation exists.

    /** Live accelerator deepness 0-100, or UNAVAILABLE on a miss/sentinel. */
    public int readAccelNow() {
        if (speedDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object ac = BydDeviceHelper.callGetter(speedDevice, "getAccelerateDeepness");
            if (ac instanceof Number) {
                int a = ((Number) ac).intValue();
                if (a != BydFeatureIds.SDK_NOT_AVAILABLE) return a;
            }
        } catch (Throwable t) { logger.debug("readAccelNow error: " + t.getMessage()); }
        return BydVehicleData.UNAVAILABLE;
    }

    /** Live brake deepness 0-100, or UNAVAILABLE on a miss/sentinel. */
    public int readBrakeNow() {
        if (speedDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object br = BydDeviceHelper.callGetter(speedDevice, "getBrakeDeepness");
            if (br instanceof Number) {
                int b = ((Number) br).intValue();
                if (b != BydFeatureIds.SDK_NOT_AVAILABLE) return b;
            }
        } catch (Throwable t) { logger.debug("readBrakeNow error: " + t.getMessage()); }
        return BydVehicleData.UNAVAILABLE;
    }

    /** Live signed steering angle in degrees (clamped ±780), or UNAVAILABLE on a
     *  miss/sentinel/out-of-range. Returned as an int (rounded) to match the published
     *  STEERING_ANGLE event's integer value. */
    public int readSteeringNow() {
        if (bodyworkDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object s = BydDeviceHelper.callGetter(bodyworkDevice, "getSteeringWheelValue", 1);
            if (s instanceof Number) {
                double angle = ((Number) s).doubleValue();
                if (angle != BydFeatureIds.SDK_NOT_AVAILABLE && angle >= -780 && angle <= 780) {
                    return (int) Math.round(angle);
                }
            }
        } catch (Throwable t) { logger.debug("readSteeringNow error: " + t.getMessage()); }
        return BydVehicleData.UNAVAILABLE;
    }

    private void startPolling() {
        pollScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BydDataPoll");
            t.setDaemon(true);
            return t;
        });
        pollScheduler.scheduleAtFixedRate(() -> {
            try {
                collectAll();
                // Log when data actually changes
                BydVehicleData d = snapshot.get();
                if (d != null) {
                    String hash = String.format("%.1f|%.2f|%.1f/%.1f/%.1f|%.3f/%.3f",
                        d.socPercent, d.voltage12v, d.highCellTempC, d.lowCellTempC, d.avgCellTempC,
                        d.highCellVoltage, d.lowCellVoltage);
                    if (!hash.equals(lastSummaryHash)) {
                        logger.info("Data changed: SOC=" + d.socPercent + "% 12V=" + d.voltage12v + "V" +
                            " Temp=" + d.highCellTempC + "/" + d.lowCellTempC + "/" + d.avgCellTempC + "°C" +
                            " CellV=" + d.highCellVoltage + "/" + d.lowCellVoltage + "V");
                        lastSummaryHash = hash;
                    }
                }
            } catch (Throwable t) {
                logger.debug("Poll error: " + t.getMessage());
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (pollScheduler != null) {
            pollScheduler.shutdownNow();
            pollScheduler = null;
        }
        stopFastDynamicsPoll();
        unregisterPlugEdgeReceiver();
        initialized = false;
    }

    private android.content.BroadcastReceiver plugEdgeReceiver;

    private void registerPlugEdgeReceiver() {
        if (context == null) return;
        // Idempotent — re-init flow tears down and re-registers.
        unregisterPlugEdgeReceiver();
        plugEdgeReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                com.overdrive.app.monitor.ChargingDetector det =
                    com.overdrive.app.monitor.ChargingDetector.getInstance();
                switch (intent.getAction()) {
                    case android.content.Intent.ACTION_POWER_CONNECTED:
                        det.onPowerConnected();
                        break;
                    case android.content.Intent.ACTION_POWER_DISCONNECTED:
                        det.onPowerDisconnected();
                        break;
                }
            }
        };
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_POWER_CONNECTED);
            f.addAction(android.content.Intent.ACTION_POWER_DISCONNECTED);
            context.registerReceiver(plugEdgeReceiver, f);
            logger.info("Plug-edge receiver registered (CONNECTED/DISCONNECTED)");
        } catch (Exception e) {
            logger.debug("registerPlugEdgeReceiver failed: " + e.getMessage());
            plugEdgeReceiver = null;
        }
    }

    private void unregisterPlugEdgeReceiver() {
        if (context == null || plugEdgeReceiver == null) return;
        try {
            context.unregisterReceiver(plugEdgeReceiver);
        } catch (Exception ignored) {}
        plugEdgeReceiver = null;
    }

    private Object initDevice(String className, String shortName) {
        Object device = BydDeviceHelper.getDevice(className, context);
        if (device != null) {
            availableDevices.add(shortName);
        } else {
            unavailableDevices.add(shortName);
        }
        return device;
    }

    /**
     * Initialize the multimedia device with multiple context strategies.
     * BYDAutoMultimediaDevice does NOT extend AbsBYDAutoDevice — it's a separate class
     * that connects to a binder service and may require a specific package identity.
     */
    private Object initMultimediaDevice() {
        String className = "android.hardware.bydauto.multimedia.BYDAutoMultimediaDevice";

        // Strategy 1: Use our normal context (works for all other devices)
        Object device = BydDeviceHelper.getDevice(className, context);
        if (device != null) {
            availableDevices.add("Multimedia");
            return device;
        }

        // Strategy 2: Try with a proper app context for com.overdrive.app
        // The daemon runs via app_process with a synthetic context. But the actual app
        // is installed — createPackageContext gives us a real app context with proper
        // service bindings that the multimedia device might need.
        try {
            android.content.Context appPkgCtx = context.createPackageContext(
                "com.overdrive.app",
                android.content.Context.CONTEXT_INCLUDE_CODE | android.content.Context.CONTEXT_IGNORE_SECURITY);
            if (appPkgCtx != null) {
                device = BydDeviceHelper.getDevice(className, appPkgCtx);
                if (device != null) {
                    logger.info("Multimedia device OK via com.overdrive.app package context");
                    availableDevices.add("Multimedia");
                    return device;
                }
            }
        } catch (Exception e) {
            logger.debug("Multimedia strategy 2 (overdrive package context) failed: " + e.getMessage());
        }

        // Strategy 3: Try with system context directly (with timeout — can deadlock)
        try {
            final Object[] result = new Object[1];
            Thread t = new Thread(() -> {
                try {
                    Class<?> atClass = Class.forName("android.app.ActivityThread");
                    java.lang.reflect.Method currentAt = atClass.getMethod("currentActivityThread");
                    Object at = currentAt.invoke(null);
                    if (at != null) {
                        java.lang.reflect.Method getSystemContext = atClass.getMethod("getSystemContext");
                        android.content.Context sysCtx = (android.content.Context) getSystemContext.invoke(at);
                        if (sysCtx != null) {
                            result[0] = BydDeviceHelper.getDevice(className, sysCtx);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Multimedia strategy 3 inner: " + e.getMessage());
                }
            }, "MultimediaInit-SysCtx");
            t.setDaemon(true);
            t.start();
            t.join(3000); // 3s timeout — abort if it hangs
            if (t.isAlive()) {
                logger.warn("Multimedia strategy 3 timed out (3s) — skipping to avoid freeze");
                t.interrupt();
            } else if (result[0] != null) {
                device = result[0];
                logger.info("Multimedia device OK via system context");
                availableDevices.add("Multimedia");
                return device;
            }
        } catch (Exception e) {
            logger.debug("Multimedia strategy 3 (system context) failed: " + e.getMessage());
        }

        // Strategy 4: Try a DIFFERENT context object than the one already tried.
        // The daemon's PermissionBypassContext.getApplicationContext() returns the
        // wrapper itself (so its BYD-permission overrides survive SDK re-normalization),
        // which means getApplicationContext() == context here and would make this
        // fallback a no-op. Unwrap to the underlying base context in that case so
        // Strategy 4 genuinely tries a distinct handle. Compare identity to skip when
        // there's nothing new to try.
        try {
            android.content.Context appCtx = context.getApplicationContext();
            if (appCtx == context && context instanceof android.content.ContextWrapper) {
                android.content.Context base = ((android.content.ContextWrapper) context).getBaseContext();
                if (base != null) appCtx = base;
            }
            if (appCtx != null && appCtx != context) {
                device = BydDeviceHelper.getDevice(className, appCtx);
                if (device != null) {
                    logger.info("Multimedia device OK via alternate (app/base) context");
                    availableDevices.add("Multimedia");
                    return device;
                }
            }
        } catch (Exception e) {
            logger.debug("Multimedia strategy 4 (app/base context) failed: " + e.getMessage());
        }

        unavailableDevices.add("Multimedia");
        return null;
    }

    // ==================== DATA COLLECTION ====================

    // Core data polled every 5s. Display-only data updated via listeners only (no polling).
    // Core = fields consumed by ABRP, MQTT, trip analytics, SOC history.
    // Display = fields only shown on the web dashboard — updated by BYD HAL listener callbacks
    //           or on-demand via collectAllFull() when the HTTP API is queried.

    // Hard throttle: never poll devices more frequently than this, even if listeners fire.
    // Listener callbacks update individual values directly in the snapshot without polling.
    // This guard prevents any code path from triggering a full device sweep within the interval.
    private volatile long lastCoreCollectTime = 0;
    private static final long MIN_COLLECT_INTERVAL_MS = 5000; // 5 seconds

    // ACC state: when off, skip polling speed/engine/gearbox (always 0 when parked)
    private volatile boolean accIsOn = true;

    /**
     * Threshold below which a post-ACC-OFF engine-power reading is treated
     * as plausible "current flowing into pack" (plug-in charging) rather
     * than ECU residue. Values more positive than this (above the deadband)
     * are rejected when accIsOn==false because the ICE cannot be running
     * with the key removed — those readings are stale/noisy.
     */
    private static final double ENGINE_POWER_CHARGING_DEADBAND = 0.3;

    /** Throttle for the collectEngine power-resolution diagnostic (1/min). */
    private long lastEnginePowerLogMs = 0;

    /** Called by CameraDaemon when ACC state changes. Adjusts poll rate accordingly. */
    public void setAccState(boolean isOn) {
        boolean wasOn = this.accIsOn;
        this.accIsOn = isOn;

        // Notify the fused charging detector first so it can invalidate
        // ACC-dependent signals (enginePowerKw goes stale once ACC is off
        // and must not be reused as charging evidence).
        com.overdrive.app.monitor.ChargingDetector.getInstance().updateAccState(isOn);

        // ACC just transitioned OFF: also clear the snapshot's enginePowerKw
        // so any consumer reading the snapshot directly (not through the
        // detector) doesn't see a stale value from the last drive while the
        // car sits parked. Other ACC-gated fields (speedKmh, brake/accel, gear)
        // are refreshed by the next poll cycle which already skips collectSpeed
        // / collectGearbox when ACC is off — only enginePower needs an
        // explicit wipe because we *deliberately* keep collecting it on the
        // ACC-off "possibly charging" branch and a stale value there would
        // confuse the detector's inference layer.
        if (wasOn && !isOn) {
            BydVehicleData current = snapshot.get();
            if (current != null && !Double.isNaN(current.enginePowerKw)) {
                snapshot.set(current.toBuilder().enginePowerKw(Double.NaN).build());
                logger.info("ACC OFF: invalidated stale enginePowerKw");
            }
        }

        // Restart poll scheduler at the appropriate rate
        if (pollScheduler != null && !pollScheduler.isShutdown()) {
            pollScheduler.shutdownNow();
            long interval = isOn ? POLL_INTERVAL_MS : POLL_INTERVAL_PARKED_MS;
            pollScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BydDataPoll");
                t.setDaemon(true);
                return t;
            });
            pollScheduler.scheduleAtFixedRate(() -> {
                try {
                    collectAll();
                    BydVehicleData d = snapshot.get();
                    if (d != null) {
                        String hash = String.format("%.1f|%.2f|%.1f/%.1f/%.1f|%.3f/%.3f",
                            d.socPercent, d.voltage12v, d.highCellTempC, d.lowCellTempC, d.avgCellTempC,
                            d.highCellVoltage, d.lowCellVoltage);
                        if (!hash.equals(lastSummaryHash)) {
                            logger.info("Data changed: SOC=" + d.socPercent + "% 12V=" + d.voltage12v + "V" +
                                " Temp=" + d.highCellTempC + "/" + d.lowCellTempC + "/" + d.avgCellTempC + "°C" +
                                " CellV=" + d.highCellVoltage + "/" + d.lowCellVoltage + "V");
                            lastSummaryHash = hash;
                        }
                    }
                } catch (Throwable t) {
                    logger.debug("Poll error: " + t.getMessage());
                }
            }, 0, interval, java.util.concurrent.TimeUnit.MILLISECONDS);
            logger.info("BydDataPoll rate changed to " + (interval / 1000) + "s (ACC " + (isOn ? "ON" : "OFF") + ")");
        }
    }

    /**
     * Current ACC (ignition) state as last set by {@link #setAccState} on the ACC edge.
     * Defaults to {@code true} (fail toward polling) until the first edge is observed.
     * Used by the fast dynamics/turn-signal pollers to skip their live SDK reads while
     * parked — accelerator / brake / steering / turn-signal are all inert with the key
     * removed, so reading them 2–4×/sec on a parked car is pure waste. The reads resume
     * on the very next 250/500ms tick once ACC returns, so trigger latency is unchanged.
     */
    public boolean isAccOn() {
        return accIsOn;
    }

    /**
     * Collect core telemetry data from devices into the snapshot.
     * Safe to call from any thread.
     * 
     * Hard-throttled: will not poll devices if called within 5 seconds of the last poll.
     * 
     * Only polls CORE devices (used by ABRP, MQTT, trips, SOC history).
     * When ACC is off, skips speed/engine/gearbox (always 0 when parked).
     * Display-only devices are NOT polled — updated via listeners or on-demand.
     */
    /** True if ANY of the given automation events is referenced by an enabled automation.
     *  Used to self-gate the display-only device polls below so they cost nothing (no SDK
     *  read) unless a rule actually keys off that signal. Never throws. */
    private static boolean anyReferenced(com.overdrive.app.automation.condition.EventData... keys) {
        try {
            for (com.overdrive.app.automation.condition.EventData k : keys) {
                if (com.overdrive.app.automation.Automations.isEventReferenced(k)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    public void collectAll() {
        long now = System.currentTimeMillis();

        // Hard throttle: skip if called within MIN_COLLECT_INTERVAL_MS of last poll.
        if (now - lastCoreCollectTime < MIN_COLLECT_INTERVAL_MS) {
            return;
        }
        lastCoreCollectTime = now;

        BydVehicleData.Builder b = (snapshot.get() != null) ? snapshot.get().toBuilder() : new BydVehicleData.Builder();
        b.availableDevices(availableDevices.toArray(new String[0]));
        b.unavailableDevices(unavailableDevices.toArray(new String[0]));

        // ALWAYS needed: battery, SOC, charging, temperature, 12V
        collectBodywork(b);     // SOC, 12V, remainKwh, powerLevel
        collectStatistic(b);    // SOC, mileage, range, cellTemps, cellVoltages
        collectCharging(b);     // chargingState, gunState, chargingPower
        collectInstrument(b);   // outsideTemp, externalChargingPower
        collectOta(b);          // 12V voltage (precise)
        collectTyre(b);         // pressure (kPa), pressure/leak/signal state per wheel

        // DRIVING ONLY: skip most when ACC is off (values are always 0/stale when parked).
        // EXCEPTION: enginePower remains meaningful when the car is plugged in and
        // charging — current flowing into the pack reads negative on the engine
        // bus and is the most authoritative charging signal we have on PHEVs
        // (where chargingGunState is often UNAVAILABLE and chargingState is
        // stuck at 15=IDLE due to firmware bugs). Detect "probably charging"
        // from the listener-delivered chargingPower / externalChargingPower
        // values populated from typed callbacks even while ACC is off.
        if (accIsOn) {
            collectSpeed(b);        // speed, accel, brake
            collectEngine(b);       // enginePower, motorSpeed/torque
            collectGearbox(b);      // gearMode
            collectSteeringAngle(b);// live steering angle (init-only otherwise → dead trigger)
        } else {
            boolean possiblyCharging =
                (!Double.isNaN(b.chargingPowerKw) && Math.abs(b.chargingPowerKw) > 0.1)
                || (!Double.isNaN(b.externalChargingPowerKw) && b.externalChargingPowerKw > 0.1)
                || b.chargingState == 1   // BMS explicitly says CHARGING
                || b.chargingGunState == 2 || b.chargingGunState == 3
                || b.chargingGunState == 4 || b.chargingGunState == 5;
            if (possiblyCharging) {
                collectEngine(b);   // adds enginePowerKw → confirms direction
            }
        }

        // Extended data consumed by ABRP/MQTT/trips
        collectStatisticExtended(b);   // SOH, driving time, key battery
        collectInstrumentExtended(b);  // cabin temp, trip data, consumption
        // AC device: insideTempC + acStartState/fan. Normally a "display-only,
        // listener-driven" device, but its listener (onDisplayCallback) is a
        // deliberate no-op, so without polling here insideTempC/acStartState were
        // read exactly once at init (collectAllFull) and then carried forward
        // unchanged on every toBuilder() poll. That froze the cabin-temp value:
        // the "temperature" and "ac" automation events (BydEvent.update) never saw
        // a transition, so a temperature-threshold automation could never fire, and
        // the climate API / MQTT inside_temp showed the boot-time value forever.
        // Poll it every cycle (ACC on AND off — cabin temp matters most when parked,
        // e.g. child/pet temperature alerts). ~6 cheap HAL getters, self-guarded and
        // wrapped in try/catch. Runs before mergeCloudData so the cloud temp
        // fallback (Double.isNaN(insideTempC)) still fills in on a HAL read miss.
        collectAc(b);
        // Charging rest time (time-to-full) HAL fallback. The primary feature-ID
        // read lives in collectInstrument() above, but many trims/firmware leave
        // those instrument IDs at 255/not-available while charging — so the
        // dashboard "Time to full" stayed blank because the chargingDevice
        // getChargingRestTime() fallback only ran once at init (collectAllFull).
        // Run it every poll here so a live charge populates rest time. The method
        // self-guards on chargingRestTimeHours==UNAVAILABLE, so it only fills the
        // gap and never clobbers a good feature-ID value.
        collectChargingExtended(b);    // charging rest time (fallback)

        // Key proximity probe — runs every poll (ACC on or off) so we keep observing
        // fob state across the parked-charging window and any "approach unlock" event.
        collectKeyProximity(b);

        // Door open/close POLL fallback — every cycle, ACC on AND off. The HAL stops
        // pushing onDoorStateChanged callbacks when parked, so a door automation only
        // fired with the car on; polling getDoorState(area) here keeps it working parked.
        // Self-guards on "no door listeners" so it's a true no-op without a door rule.
        collectDoorStates(b);

        // ── Display-only device polls, self-gated per automation event ──────────────
        // These devices were polled ONLY in collectAllFull() (daemon init + on-demand
        // HTTP), so after startup their fields were frozen: the toBuilder() snapshot
        // carried the init value forward and the corresponding automation events never
        // transitioned — so a trigger/condition on them could never fire (field-reported
        // for seatbelt; the same root cause as the gear-P bug). Their HAL listeners cover
        // only a subset (light→DRL only, adas→SLW, settings→CPD/ambient/seatHeat), leaving
        // the rest stale. Poll each on the LIVE path, but ONLY when an enabled automation
        // references its event — anyReferenced() gates the SDK read to zero cost otherwise.
        // Cheap HAL getters, each self-guarded + try/catch inside its collector.
        // Seatbelt (buckled/unbuckled) — instrument device.
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.SEATBELT_DRIVER,
                          com.overdrive.app.automation.condition.BydEvent.SEATBELT_PASSENGER)) {
            collectSafetyBelt(b);
        }
        // Drive mode + powertrain (EV/HEV) — energy/drive-config device.
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.DRIVE_MODE,
                          com.overdrive.app.automation.condition.BydEvent.POWERTRAIN_MODE)) {
            collectEnergy(b);
        }
        // Lights (hazard / high-beam / low-beam) + auto-lights — light device. The light
        // callback refreshes only DRL, so these need the poll. (DRL stays callback-fed.)
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.LIGHTS_HAZARD,
                          com.overdrive.app.automation.condition.BydEvent.LIGHTS_HIGH_BEAM,
                          com.overdrive.app.automation.condition.BydEvent.LIGHTS_LOW_BEAM,
                          com.overdrive.app.automation.condition.BydEvent.AUTO_LIGHTS)) {
            collectLight(b);
        }
        // Slope (incline degrees) — sensor device.
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.SLOPE)) {
            collectSensor(b);
        }
        // Nearest radar obstacle (cm) — radar/PDC device (parked-radar dependent).
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.RADAR_NEAREST)) {
            collectRadar(b);
        }

        // Cloud data merge (when toggle enabled and data is fresh)
        mergeCloudData(b);

        BydVehicleData built = b.build();
        snapshot.set(built);
        pushChargingEvidence(built);
    }

    /**
     * Force a full collection of ALL data including display-only fields.
     * Bypasses the 5-second throttle. Called by the HTTP API when a client
     * explicitly requests the full vehicle data, or during init().
     */
    public void collectAllFull() {
        lastCoreCollectTime = 0;  // Bypass throttle

        BydVehicleData.Builder b = (snapshot.get() != null) ? snapshot.get().toBuilder() : new BydVehicleData.Builder();
        b.availableDevices(availableDevices.toArray(new String[0]));
        b.unavailableDevices(unavailableDevices.toArray(new String[0]));

        // Core devices
        collectBodywork(b);
        collectSpeed(b);
        collectEngine(b);
        collectStatistic(b);
        collectCharging(b);
        collectInstrument(b);
        collectOta(b);
        collectGearbox(b);

        // Display-only devices (normally listener-driven, polled here on-demand)
        collectAc(b);
        collectLight(b);
        collectAdas(b);
        collectSettings(b);
        collectPower(b);
        collectSafetyBelt(b);
        collectTyre(b);
        collectDoorLock(b);
        collectSensor(b);
        collectEnergy(b);
        collectRadar(b);

        // Extended data — core + display-only
        collectStatisticExtended(b);   // SOH, driving time, key battery
        collectInstrumentExtended(b);  // cabin temp, trip data, consumption
        collectChargingExtended(b);    // charging rest time
        collectBodyworkExtended(b);    // steering, auto system, 12V level, sunroof, sunshade
        collectEngineExtended(b);      // coolant, oil, engine code

        // Cloud data merge (when toggle enabled and data is fresh)
        mergeCloudData(b);

        BydVehicleData built = b.build();
        snapshot.set(built);
        pushChargingEvidence(built);
        lastCoreCollectTime = System.currentTimeMillis();
    }

    /**
     * Push the latest snapshot into the fused ChargingDetector so its
     * inference layer can reason about fresh power-flow / gun / gear data.
     * The detector's L1 (BMS edge) and L2 (Power.isCharging) inputs come
     * from listener callbacks and the explicit poll above; this method
     * supplies L3 evidence.
     */
    private void pushChargingEvidence(BydVehicleData built) {
        if (built == null) return;
        // Resolve gear from authoritative GearMonitor (returns last-known
        // value even when its monitor stops on ACC OFF). On a parked car
        // that's always P. The detector uses gear==P as an L3 guard.
        int gearNow;
        try {
            com.overdrive.app.monitor.GearMonitor gm =
                com.overdrive.app.monitor.GearMonitor.getInstance();
            gearNow = gm.getCurrentGear();
        } catch (Exception e) {
            gearNow = (built.gearMode != BydVehicleData.UNAVAILABLE)
                ? built.gearMode
                : com.overdrive.app.monitor.GearMonitor.GEAR_P;
        }
        com.overdrive.app.monitor.ChargingDetector.getInstance()
            .updatePollEvidence(built, gearNow,
                com.overdrive.app.monitor.GearMonitor.GEAR_P);

        // Feed the ring-buffer power estimator (FALLBACK power source for models
        // that report no direct/external charging power). It accumulates ONLY
        // while the fused detector says CHARGING and the car is in Park, and only
        // from a rising charge-energy counter — so regen (gear D/R) and V2L
        // discharge can never produce a phantom reading. See ChargingPowerEstimator.
        try {
            boolean fusedCharging =
                com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging();
            boolean inPark = (gearNow == com.overdrive.app.monitor.GearMonitor.GEAR_P);
            // SOC-derived energy = SOC × nominal × SOH. The SOC gauge is the ONE
            // signal that reliably tracks charging on every drivetrain, and it's the
            // estimator's PREFERRED source now: on PHEV the hardware energy getters
            // lie (getBatteryRemainPowerEV=0, getBatteryPowerHEV constant,
            // getRemainingBatteryPower FREEZES for tens of minutes while charging),
            // and externalChargingPower reports the EVSE's rated capacity, not the
            // real draw — so SOC-rate is the only truthful charging power on those
            // trims. NaN when SOC or nominal isn't known yet, so the estimator falls
            // back to the remain/cap counters exactly as before.
            // PHEV ONLY. Pass raw SOC% + the SOC→energy scale (nominal × SOH); the
            // estimator FREEZES the scale at session start so socE moves only with
            // SOC, not with mid-charge SohEstimator revisions. On BEV we pass
            // socScaleKwh = NaN so the estimator's socE stays NaN and behaves EXACTLY
            // as before (remain-first): the BEV's remainKwh is verified full-scale and
            // finer-grained than the 1%-quantised SOC, so the BEV fix must not regress.
            // Only PHEV — whose hardware energy counters freeze/lie during charge —
            // needs the SOC-derived source.
            double socPctForEst = built.socPercent;
            double socScaleKwh = Double.NaN;
            if (isPhev(built)) {
                try {
                    com.overdrive.app.abrp.SohEstimator soh =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                    double nominal = (soh != null) ? soh.getNominalCapacityKwh() : 0;
                    if (nominal > 0) {
                        double sohFrac = (soh != null && soh.hasDisplaySoh())
                            ? soh.getDisplaySoh() / 100.0 : 1.0;
                        if (sohFrac <= 0) sohFrac = 1.0;
                        socScaleKwh = nominal * sohFrac;
                    }
                } catch (Throwable ignored) { /* leave NaN → estimator uses remain/cap */ }
            }
            com.overdrive.app.monitor.ChargingPowerEstimator.getInstance().sample(
                System.currentTimeMillis(),
                built.chargingCapacityKwh,
                built.remainKwh,
                socPctForEst, socScaleKwh,
                fusedCharging, inPark);
        } catch (Exception e) {
            logger.info("ChargingPowerEstimator.sample failed: " + e.getMessage());
        }
    }

    /**
     * Resolve a raw PHEV remaining-energy reading to the true GROSS frame.
     *
     * <p>The BYD HAL reports PHEV remaining energy at HALF the gross-nameplate
     * scale on its PRIMARY getter ({@code getBatteryRemainPowerEV}). But that
     * getter goes stale when the ICE is running, and the priority cascade then
     * falls back to {@code getRemainingBatteryPower} / {@code getBatteryCapacity},
     * which are NOT necessarily in the same half frame — field-confirmed: on a
     * 21.5 kWh-gross Tang-class DM-i, the live card read a correct ~16.5 kWh at
     * 77% SOC most of the time (half primary ×2), but intermittently jumped to
     * ~22 kWh for the ICE-running window because a near-gross fallback getter was
     * being blindly doubled. That doubled remainKwh also poisoned the SOC capacity
     * heuristic (estimatedCapacity = remainKwh/SOC) and per-trip kWh, so BOTH the
     * displayed remaining AND trip consumption read exactly double until detection
     * re-anchored — hence the "sometimes, especially after a SOH reset" symptom.
     *
     * <p>So a BLANKET ×2 is wrong. Instead, when we have a trustworthy nominal
     * capacity anchor and a valid SOC, pick whichever frame — raw, or raw×2 —
     * implies a pack capacity CLOSEST to nominal. A genuine half reading (implied
     * cap ≈ nominal/2) doubles cleanly; an already-gross fallback (implied cap ≈
     * nominal) is left alone. When a reading can't be placed in either frame
     * within tolerance, return NaN so the caller skips it and keeps the last
     * known-good value rather than writing a wrong one.
     *
     * <p>Fallback when no nominal anchor is available yet (cold boot, before
     * capacity detection): apply the historical ×2, since the primary getter is
     * the one that fills remainKwh first and it is the half-frame source. BEV
     * never calls this (callers gate on {@code isPhevForKwh}).
     *
     * @param rawKwh the raw HAL reading (already in kWh, e.g. rawVal/10 for the
     *               0.1-kWh-unit getters)
     * @param socPercent current display SOC, or NaN if unknown this cycle
     * @return the gross-frame kWh, or NaN if the reading is frame-ambiguous and
     *         should be skipped
     */
    private double phevGrossRemainKwh(double rawKwh, double socPercent) {
        if (Double.isNaN(rawKwh) || rawKwh <= 0) return rawKwh;

        double nominal = 0;
        try {
            com.overdrive.app.abrp.SohEstimator sohEst =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (sohEst != null) nominal = sohEst.getNominalCapacityKwh();
        } catch (Throwable ignored) { /* nominal stays 0 → ×2 fallback below */ }

        // No anchor (or no SOC) → can't disambiguate frames. Apply the historical
        // ×2: the primary half-frame getter is what seeds remainKwh first, and a
        // doubled value is what downstream capacity detection then anchors on.
        if (nominal <= 0 || Double.isNaN(socPercent) || socPercent <= 5) {
            return rawKwh * PHEV_ENERGY_HALF_SCALE_CORRECTION;
        }

        // The discriminator is the IMPLIED CAPACITY = remainKwh / SOC, NOT the
        // remaining kWh itself. A reading in the true gross frame implies a pack
        // capacity equal to nominal × SOH; in the half frame it implies half that.
        // The two candidate frames (raw, raw×2) are therefore exactly 2× apart in
        // implied capacity, while a real pack's implied capacity sits in a bounded
        // band below nominal — so at most ONE frame can land in the band, making
        // the choice unambiguous given a trustworthy nominal + SOC.
        //
        // Worked example (the user's challenge): raw = 8.5 kWh.
        //  - at 40% SOC, 8.5 is the TRUE gross value: impliedRaw = 8.5/0.40 = 21.25
        //    (≈ nominal, in band) while impliedDoubled = 42.5 (≫ nominal, rejected)
        //    → return 8.5, NOT doubled. Correct.
        //  - at 77% SOC, 8.5 is the HALF value: impliedRaw = 11.0 (too low, below
        //    band) while impliedDoubled = 17/0.77 = 22.1 (≈ nominal, in band)
        //    → return 17. Correct. SOC breaks the tie; doubling a genuine gross
        //    reading always implies ~2× the pack, which never qualifies.
        double socFraction = socPercent / 100.0;
        double impliedRaw = rawKwh / socFraction;                               // capacity if raw is gross
        double impliedDoubled = (rawKwh * PHEV_ENERGY_HALF_SCALE_CORRECTION) / socFraction; // capacity if raw is half
        double errRaw = Math.abs(impliedRaw - nominal);        // how well "raw is gross" fits the pack
        double errDoubled = Math.abs(impliedDoubled - nominal);// how well "raw is half" fits the pack

        // Pick the frame whose implied capacity fits the known pack, but ONLY when
        // the fit is BOTH (a) within a plausible-capacity tolerance of nominal, and
        // (b) DECISIVELY better than the other frame. The two frames are exactly 2×
        // apart, so for a genuine reading one fits tightly while the other implies
        // ~2× (or ~0.5×) the pack — a clear winner. When the two errors are
        // comparable, the reading is frame-ambiguous (a stale / decoupled getter
        // value that fits neither clean frame), so we SKIP it (return NaN) and the
        // caller keeps the last known-good remainKwh — never writing a doubled value.
        //
        // Tolerance 0.45·nominal on the absolute fit accommodates a degraded pack
        // (implied cap = nominal × SOH, SOH down to ~0.6) plus SOC-curve slop,
        // without being so wide both frames qualify. "Decisive" = the loser's error
        // is at least 2× the winner's — guarantees we only correct/keep when the
        // frame is unambiguous.
        double fitTol = 0.45 * nominal;
        boolean halfWins = errDoubled < errRaw;
        double winErr = halfWins ? errDoubled : errRaw;
        double loseErr = halfWins ? errRaw : errDoubled;
        boolean decisive = winErr <= fitTol && loseErr >= 2.0 * winErr;

        if (!decisive) {
            // Ambiguous — e.g. raw ≈ 11 at 77% on a 21.5 pack implies 14.3 (raw)
            // vs 28.6 (doubled): both ~7 off nominal, neither clean → skip.
            return Double.NaN;
        }
        double chosen = halfWins ? rawKwh * PHEV_ENERGY_HALF_SCALE_CORRECTION : rawKwh;

        // Hard physical ceiling: a pack cannot hold more than its nameplate (plus a
        // little top-balancing / measurement slop). A STALE getter that froze at a
        // full-charge value while SOC has since dropped implies a capacity far above
        // nominal (field bug: 22.4 kWh frozen at 77% SOC → implies 29 kWh on a 21.5
        // pack, ratio 1.35). The fit tolerance above can let such a value through as
        // "already gross", so enforce the ceiling explicitly: if the CHOSEN frame
        // still implies > ~1.12× nominal, reject it (return NaN) so the caller keeps
        // last-good and the SOC-synthesized remaining (which tracks live) takes over.
        double chosenImpliedCap = chosen / socFraction;
        if (chosenImpliedCap > 1.12 * nominal) {
            return Double.NaN;
        }
        return chosen;
    }

    private void collectBodywork(BydVehicleData.Builder b) {
        if (bodyworkDevice == null) return;
        try {
            // VIN
            Object vin = BydDeviceHelper.callGetter(bodyworkDevice, "getAutoVIN");
            if (vin instanceof String) b.vin((String) vin);

            // 12V auxiliary battery voltage (0-255 → 0-25.5V)
            // NOTE: getBatteryPowerValue() returns 12V battery voltage, NOT traction battery SOC.
            // SOC comes from StatisticDevice.getElecPercentageValue() — see collectStatistic().
            Object battPowerRaw = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerValue");
            if (battPowerRaw instanceof Number) {
                double rawVal = ((Number) battPowerRaw).doubleValue();
                double voltage12v = rawVal > 100 ? rawVal / 10.0 : rawVal;
                // Only treat as 12V voltage if it's in a plausible range (8-16V)
                if (voltage12v >= 8.0 && voltage12v <= 16.0 && Double.isNaN(b.voltage12v)) {
                    b.voltage12v(voltage12v);
                }
            }

            // Battery remaining energy — try multiple APIs in priority order.
            // PHEV-first: when computeIsPhev() reports PHEV, BodyworkDevice.getBatteryPowerHEV()
            // is the authoritative source. The Power/Statistic getters echo SOC% in the kWh
            // field on Sealion-class PHEVs (the "SOC-as-kWh" firmware bug), so even with
            // SOC-mimic guards they only ever produce rejects on PHEV. Skipping straight
            // to HEV avoids two reflective probe attempts every cycle and removes the
            // window where a freshly-classified-PHEV vehicle could still latch a bogus
            // BEV reading before computeIsPhev() updates.
            //
            // NOTE: We deliberately do NOT gate the priority reads on
            // `Double.isNaN(b.remainKwh)`. Because `b` is built from the previous snapshot
            // via toBuilder(), gating on NaN means we only ever read these getters ONCE
            // (the very first poll after init), and the value freezes thereafter —
            // observable as "Remaining kWh stuck at last seen value when the vehicle is
            // off". The validation block below already protects the cached value from HAL
            // garbage: out-of-range readings are skipped (not written), so the last-known
            // good value is preserved when the BYD HAL goes flaky after ACC OFF.
            //
            // We track whether any priority wrote a fresh kWh this cycle so the
            // capacity fallback (older SDKs only) doesn't clobber it.
            boolean kwhWrittenThisCycle = false;
            boolean isPhevForKwh = isPhev(b);

            // PHEV: read getBatteryPowerHEV ONLY to populate socHevPercent (telemetry)
            // and STASH it as a last-resort remainKwh fallback — it is not the
            // PHEV-primary energy source. Like every PHEV energy getter it reports at
            // HALF the true gross scale (see PHEV_ENERGY_HALF_SCALE_CORRECTION), so the
            // stashed value is ×2-corrected to the gross frame below — matching the
            // Priority 1/2 sources. getBatteryRemainPowerEV (Priority 1 below) is the
            // preferred PHEV source; HEV is retained only as a fallback so firmwares
            // where the EV getter genuinely echoes SOC% (the SOC-as-kWh bug) still get
            // *some* reading rather than none.
            double phevHevKwh = Double.NaN;
            boolean phevHevKwhUsable = false;
            if (isPhevForKwh && bodyworkDevice != null) {
                try {
                    Object hev = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerHEV");
                    if (hev instanceof Number) {
                        double hevVal = ((Number) hev).doubleValue();
                        if (hevVal >= 0) {
                            // socHevPercent telemetry + the SOC-mimic check stay on the
                            // RAW value (the check detects firmware echoing SOC% in the
                            // kWh field — comparing a corrected value to SOC% would break
                            // it). The stashed remainKwh fallback is frame-resolved to the
                            // gross frame (raw vs raw×2 vs nominal anchor — see
                            // phevGrossRemainKwh) so a near-gross HEV reading isn't blindly
                            // doubled.
                            b.socHevPercent(hevVal);
                            double soc = b.socPercent;
                            boolean looksLikeSocPercent = !Double.isNaN(soc)
                                    && soc > 0 && Math.abs(hevVal - soc) < 3.0;
                            double hevKwh = phevGrossRemainKwh(hevVal, soc);
                            if (!looksLikeSocPercent && !Double.isNaN(hevKwh)
                                    && hevKwh > 1 && hevKwh < 120) {
                                phevHevKwh = hevKwh;
                                phevHevKwhUsable = true;
                            } else if (looksLikeSocPercent) {
                                logger.debug("getBatteryPowerHEV returned " +
                                    String.format("%.1f", hevVal) + " ≈ SOC " +
                                    String.format("%.1f", soc) + "% — treating as SOC%, not kWh");
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("getBatteryPowerHEV (PHEV socHev/stash) failed: " + e.getMessage());
                }
            }

            // Priority 1 (BEV and PHEV): PowerDevice.getBatteryRemainPowerEV() — the
            // most accurate remaining-energy source on both drivetrains. On PHEVs the
            // HAL reports it at HALF the true gross scale, so we apply the half-scale
            // correction (×2) immediately on read — the gross value then passes the
            // implied-capacity gate below (a half value implies ~9 kWh and would fail
            // it). It may go stale when the ICE is running, which the implied-capacity
            // gate rejects. The SOC-as-kWh guard skips firmwares that echo SOC% here
            // (checked against the RAW value), falling through to the PHEV HEV
            // last-resort fallback further down.
            if (!kwhWrittenThisCycle && powerDevice != null) {
                try {
                    Object evKwh = BydDeviceHelper.callGetter(powerDevice, "getBatteryRemainPowerEV");
                    if (evKwh instanceof Number) {
                        double evRaw = ((Number) evKwh).doubleValue();
                        // Frame-resolve on PHEV (raw may be half OR already gross when
                        // the ICE-running fallback wins). NaN = frame-ambiguous → the
                        // `evVal > 1` guard below skips it, keeping the last good value.
                        double evVal = isPhevForKwh
                                ? phevGrossRemainKwh(evRaw, b.socPercent) : evRaw;
                        if (evVal > 1 && evVal < 120) {
                            // Validate: implied capacity should be within 50-150% of any BYD pack
                            double soc = b.socPercent;
                            if (!Double.isNaN(soc) && soc > 5) {
                                // SOC-as-kWh PHEV firmware bug: HAL echoes SOC% in the kWh field.
                                // Reject before the implied-capacity range check, because at
                                // SOC=84 the bogus 84.1 produces impliedCap=100, which falls
                                // inside the 10-130 BEV-friendly window and would otherwise
                                // be accepted. When this is the SOC-mimic bug, let the
                                // slot stay NaN so a later priority / the PHEV HEV
                                // last-resort fallback fills it. Checked against evRaw —
                                // the half-scale ×2 would push a genuine SOC echo out of
                                // the ±5 window and defeat the guard.
                                boolean looksLikeSocMimic = Math.abs(evRaw - soc) < 5.0;
                                double impliedCap = evVal / (soc / 100.0);
                                if (looksLikeSocMimic) {
                                    logger.debug("getBatteryRemainPowerEV rejected: raw " +
                                        String.format("%.1f", evRaw) + " ≈ SOC " +
                                        String.format("%.0f", soc) + "% — SOC-as-kWh firmware bug");
                                } else if (impliedCap >= 10 && impliedCap <= 130) {
                                    b.remainKwh(evVal);
                                    kwhWrittenThisCycle = true;
                                    logger.debug("remainKwh from getBatteryRemainPowerEV: " +
                                        String.format("%.1f", evVal));
                                } else {
                                    logger.debug("getBatteryRemainPowerEV rejected: " +
                                        String.format("%.1f", evVal) + " kWh at " +
                                        String.format("%.0f", soc) + "% SOC → implied " +
                                        String.format("%.1f", impliedCap) + " kWh");
                                }
                            } else if (Double.isNaN(b.remainKwh) && !isPhevForKwh) {
                                // No SOC to validate against (cold boot, SOC read by the
                                // later collectStatistic). Accept the unvalidated reading
                                // ONLY on BEV first-poll — a BEV's getBatteryRemainPowerEV
                                // is its authoritative source and a one-poll seed is safe.
                                // On PHEV we deliberately DON'T accept here: this getter is
                                // PHEV-primary now, and without SOC we cannot run the
                                // SOC-mimic guard — a firmware echoing SOC% would seed a
                                // bogus remainKwh that flows raw into MQTT/trip accounting
                                // before the downstream ratio gates ever see it. Defer one
                                // poll until SOC arrives and the guarded branch can run.
                                b.remainKwh(evVal);
                                kwhWrittenThisCycle = true;
                            } else if (Double.isNaN(b.remainKwh) && isPhevForKwh) {
                                logger.debug("getBatteryRemainPowerEV " +
                                    String.format("%.1f", evVal) + " kWh held on PHEV — no SOC "
                                    + "yet to validate against; deferring one poll");
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("getBatteryRemainPowerEV failed: " + e.getMessage());
                }
            }

            // Priority 2: StatisticDevice.getRemainingBatteryPower() — returns int (0.1 kWh units)
            // Only consulted when Priority 1 did NOT succeed this cycle. Without this guard,
            // both sources race every cycle and last-writer-wins; on Seal we observed
            // Priority 1 reporting 16.5 kWh (correct → 82.5 kWh nominal at 20% SOC) being
            // overwritten by Priority 2 reporting 20.6 kWh (wrong → 103 kWh implied), which
            // poisoned every downstream auto-detection.
            if (!kwhWrittenThisCycle && statisticDevice != null) {
                try {
                    Object rawPower = BydDeviceHelper.callGetter(statisticDevice, "getRemainingBatteryPower");
                    if (rawPower instanceof Number) {
                        int rawVal = ((Number) rawPower).intValue();
                        if (rawVal > 10 && rawVal < 1200) {  // 1-120 kWh in 0.1 units
                            double kwhRaw = rawVal / 10.0;
                            // Frame-resolve on PHEV (raw may be half OR already gross).
                            // BEV unchanged. NaN = ambiguous → skipped by the impliedCap
                            // gate below, keeping the last good value.
                            double kwh = isPhevForKwh
                                    ? phevGrossRemainKwh(kwhRaw, b.socPercent) : kwhRaw;
                            // Validate against SOC
                            double soc = b.socPercent;
                            if (!Double.isNaN(soc) && soc > 5) {
                                // SOC-as-kWh PHEV firmware bug — same guard as Priority 1.
                                // The Sealion 6 DM-i HAL returns raw=841 (84.1 kWh) at 84% SOC;
                                // impliedCap=100 passes a generic [10,130] gate even though
                                // the pack is only 18.3 kWh. Reject so a later priority /
                                // the PHEV HEV last-resort fallback fills this slot. Checked
                                // against the RAW kWh — the ×2 would defeat the ±5 SOC window.
                                boolean looksLikeSocMimic = Math.abs(kwhRaw - soc) < 5.0;
                                double impliedCap = kwh / (soc / 100.0);
                                if (looksLikeSocMimic) {
                                    logger.debug("getRemainingBatteryPower rejected: raw " +
                                        String.format("%.1f", kwhRaw) + " ≈ SOC " +
                                        String.format("%.0f", soc) + "% — SOC-as-kWh firmware bug (raw=" +
                                        rawVal + ")");
                                } else if (impliedCap >= 10 && impliedCap <= 130) {
                                    b.remainKwh(kwh);
                                    kwhWrittenThisCycle = true;
                                    logger.debug("remainKwh from getRemainingBatteryPower: " +
                                        String.format("%.1f", kwh) + " (raw=" + rawVal + ")");
                                }
                            } else if (Double.isNaN(b.remainKwh) && !isPhevForKwh) {
                                // No SOC to validate. BEV first-poll seed only — on PHEV we
                                // defer until SOC arrives so the SOC-mimic guard can run
                                // (see Priority 1 note: raw remainKwh flows into MQTT/trip
                                // accounting ungated, so an unvalidated PHEV seed is unsafe).
                                b.remainKwh(kwh);
                                kwhWrittenThisCycle = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("getRemainingBatteryPower failed: " + e.getMessage());
                }
            }

            // PHEV last-resort fallback: the getBatteryPowerHEV value stashed above
            // (already ×2-corrected to the gross frame). Only used when
            // getBatteryRemainPowerEV (Priority 1) and getRemainingBatteryPower
            // (Priority 2) both failed to produce a usable reading this cycle — e.g.
            // a firmware where the EV getter genuinely echoes SOC% and gets rejected.
            if (isPhevForKwh && !kwhWrittenThisCycle && phevHevKwhUsable
                    && !Double.isNaN(phevHevKwh)) {
                b.remainKwh(phevHevKwh);
                kwhWrittenThisCycle = true;
                logger.debug("remainKwh from getBatteryPowerHEV (PHEV last-resort fallback, "
                    + "may under-report): " + String.format("%.1f", phevHevKwh));
            }

            // BEV-side fallback: BodyworkDevice.getBatteryPowerHEV() also runs for BEVs
            // when Priority 1/2 didn't yield a value, in case a particular BEV firmware
            // exposes remaining kWh here too. Skipped when PHEV-priority above already
            // handled this getter.
            if (!isPhevForKwh && !kwhWrittenThisCycle && bodyworkDevice != null) {
                try {
                    Object hev = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerHEV");
                    if (hev instanceof Number) {
                        double hevVal = ((Number) hev).doubleValue();
                        if (hevVal >= 0) {
                            b.socHevPercent(hevVal);
                            double soc = b.socPercent;
                            boolean looksLikeSocPercent = !Double.isNaN(soc)
                                    && soc > 0 && Math.abs(hevVal - soc) < 3.0;
                            if (!looksLikeSocPercent && hevVal > 1 && hevVal < 120) {
                                b.remainKwh(hevVal);
                                kwhWrittenThisCycle = true;
                                logger.debug("remainKwh from getBatteryPowerHEV (BEV-fallback): " +
                                    String.format("%.1f", hevVal) + " (soc=" +
                                    String.format("%.1f", soc) + "%)");
                            } else if (looksLikeSocPercent) {
                                logger.debug("getBatteryPowerHEV returned " +
                                    String.format("%.1f", hevVal) + " ≈ SOC " +
                                    String.format("%.1f", soc) + "% — treating as SOC%, not kWh");
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("getBatteryPowerHEV failed: " + e.getMessage());
                }
            }

            // getBatteryCapacity() — semantics vary by model:
            // - Newer models: returns Ah rating (fixed, e.g. 150 for Atto 3)
            // - Older models: returns remaining energy in 0.1 kWh units (changes with SOC)
            // Used as remainKwh fallback when prior priorities haven't filled it.
            Object cap = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryCapacity");
            if (cap instanceof Number) {
                double capVal = ((Number) cap).doubleValue();
                if (capVal > 0) b.capacityAh(capVal);

                // Fallback for older models where Priorities 1/2 are unavailable:
                // getBatteryCapacity() returns remaining energy in 0.1 kWh units (changes
                // with SOC). Skip when the value looks like a static Ah rating (50-350
                // range, handled by the SOH-feed block above) — otherwise we'd overwrite
                // a real kWh reading with an Ah number scaled by 10. Also skip if
                // priorities 1 or 2 already wrote a fresh validated kWh this cycle, so
                // this fallback truly stays a fallback.
                //
                // No `Double.isNaN(b.remainKwh)` guard here: on the older SDKs that need
                // this fallback, this is the only signal, and gating on NaN would freeze
                // it at the first poll's value (the bug this whole block was rewritten
                // to fix). The 1-120 kWh sanity window protects against junk readings.
                boolean looksLikeAhRating = (capVal >= 50 && capVal <= 350);
                if (!kwhWrittenThisCycle && !looksLikeAhRating && capVal > 0) {
                    // Frame-resolve on PHEV (raw may be half OR already gross).
                    // BEV unchanged. NaN (frame-ambiguous) is excluded by the
                    // 1-120 window below, keeping the last good value.
                    double kwhFromCap = isPhevForKwh
                            ? phevGrossRemainKwh(capVal / 10.0, b.socPercent)
                            : (capVal / 10.0);
                    // Plausible remaining energy range for any BYD model: 1-120 kWh
                    if (kwhFromCap > 1.0 && kwhFromCap < 120.0) {
                        b.remainKwh(kwhFromCap);
                    }
                }
            }

            // ── PHEV energy diagnostic (INFO, throttled) ───────────────────
            // Dumps every remaining-energy getter's RAW value side-by-side with
            // SOC and the resolved remainKwh, so a captured log proves which
            // getter tracks SOC live and which goes stale (the frozen-22.4 bug).
            // PHEV-only, ~once/min, never on the hot path otherwise.
            if (isPhevForKwh) {
                long nowDiag = System.currentTimeMillis();
                if (nowDiag - lastPhevEnergyDiagMs >= PHEV_ENERGY_DIAG_INTERVAL_MS) {
                    lastPhevEnergyDiagMs = nowDiag;
                    try {
                        String evStr = "n/a", rbpStr = "n/a", hevStr = "n/a", capStr = "n/a";
                        if (powerDevice != null) {
                            Object o = BydDeviceHelper.callGetter(powerDevice, "getBatteryRemainPowerEV");
                            if (o instanceof Number) evStr = String.format("%.2f", ((Number) o).doubleValue());
                        }
                        if (statisticDevice != null) {
                            Object o = BydDeviceHelper.callGetter(statisticDevice, "getRemainingBatteryPower");
                            if (o instanceof Number) rbpStr = ((Number) o).intValue() + " (raw/10=" + String.format("%.1f", ((Number) o).intValue() / 10.0) + ")";
                        }
                        if (bodyworkDevice != null) {
                            Object o = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerHEV");
                            if (o instanceof Number) hevStr = String.format("%.2f", ((Number) o).doubleValue());
                            Object c = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryCapacity");
                            if (c instanceof Number) capStr = String.format("%.2f", ((Number) c).doubleValue());
                        }
                        double socNow = b.socPercent;
                        double nominalNow = 0;
                        try {
                            com.overdrive.app.abrp.SohEstimator se =
                                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                            if (se != null) nominalNow = se.getNominalCapacityKwh();
                        } catch (Throwable ignored) {}
                        logger.info("[phev-energy] SOC=" + String.format("%.1f", socNow)
                            + "% nominal=" + String.format("%.1f", nominalNow)
                            + " | getBatteryRemainPowerEV=" + evStr
                            + " | getRemainingBatteryPower=" + rbpStr
                            + " | getBatteryPowerHEV=" + hevStr
                            + " | getBatteryCapacity=" + capStr
                            + " | RESOLVED remainKwh=" + (Double.isNaN(b.remainKwh) ? "NaN" : String.format("%.2f", b.remainKwh))
                            + " (impliedCap=" + (socNow > 5 && !Double.isNaN(b.remainKwh)
                                ? String.format("%.1f", b.remainKwh / (socNow / 100.0)) : "n/a") + ")");
                    } catch (Throwable t) {
                        logger.debug("[phev-energy] diagnostic failed: " + t.getMessage());
                    }
                }
            }

            // Power level
            Object pl = BydDeviceHelper.callGetter(bodyworkDevice, "getPowerLevel");
            if (pl instanceof Number) b.powerLevel(((Number) pl).intValue());

            // getEnergyType removed — observed returning 1 on both BEV and PHEV
            // firmwares, so it cannot be trusted as a drivetrain discriminator.
            // PHEV detection now uses live fuel HAL signals (computeIsPhev).

            // Battery temp from bodywork (feature ID 300941320, Double.TYPE)
            Object battTemp = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODYWORK_BATTERY_METRIC, Double.class);
            if (battTemp != null) {
                double tempVal = BydDeviceHelper.getDoubleValue(battTemp);
                if (!Double.isNaN(tempVal) && tempVal > -50 && tempVal < 80) b.bodyworkBattTempC(tempVal);
            }

            // Battery range from bodywork (feature ID 300941336, Double.TYPE → intValue)
            Object battRange = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODYWORK_BATTERY_RANGE, Double.class);
            if (battRange != null) {
                int rangeVal = BydDeviceHelper.getIntValue(battRange);
                if (rangeVal >= 0 && rangeVal <= 1016) b.bodyworkRangeKm((int) Math.round(rangeVal * distanceToKmFactor));
            }

            // Window open percent (positions 1-6)
            int[] windows = new int[6];
            for (int i = 0; i < 6; i++) {
                Object wp = BydDeviceHelper.callGetter(bodyworkDevice, "getWindowOpenPercent", i + 1);
                windows[i] = (wp instanceof Number) ? ((Number) wp).intValue() : -1;
            }
            b.windowOpenPercent(windows);

            // Emergency alarm
            Object alarm = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODYWORK_EMERGENCY_ALARM, Integer.class);
            if (alarm != null) b.emergencyAlarmState(BydDeviceHelper.getIntValue(alarm));

        } catch (Exception e) {
            logger.debug("collectBodywork error: " + e.getMessage());
        }
    }

    private void collectSpeed(BydVehicleData.Builder b) {
        if (speedDevice == null) return;
        try {
            Object speed = BydDeviceHelper.callGetter(speedDevice, "getCurrentSpeed");
            if (speed instanceof Number) {
                double v = ((Number) speed).doubleValue();
                if (v != BydFeatureIds.SDK_NOT_AVAILABLE) b.speedKmh(v * distanceToKmFactor);
            }
            // Guard the SDK_NOT_AVAILABLE sentinel exactly like getCurrentSpeed above:
            // getAccelerateDeepness/getBrakeDeepness can return it, and it is NOT the
            // BydVehicleData.UNAVAILABLE (Integer.MIN_VALUE) that the automation
            // publish-guard checks — so an unguarded sentinel would flow through as a
            // bogus ~-2.1e9 pedal value and false-fire a "pedal < N" automation.
            Object accel = BydDeviceHelper.callGetter(speedDevice, "getAccelerateDeepness");
            if (accel instanceof Number) {
                int a = ((Number) accel).intValue();
                if (a != BydFeatureIds.SDK_NOT_AVAILABLE) b.accelPercent(a);
            }
            Object brake = BydDeviceHelper.callGetter(speedDevice, "getBrakeDeepness");
            if (brake instanceof Number) {
                int br = ((Number) brake).intValue();
                if (br != BydFeatureIds.SDK_NOT_AVAILABLE) b.brakePercent(br);
            }
        } catch (Exception e) {
            logger.debug("collectSpeed error: " + e.getMessage());
        }
    }

    private void collectEngine(BydVehicleData.Builder b) {
        if (engineDevice == null) return;
        try {
            // ==================== ENGINE SPEED ====================
            // Feature ID path first — try ENGINE_SPEED (339738642), then ENGINE_SPEED_GB (282066952)
            try {
                Object val = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_SPEED, Integer.class);
                if (val != null) {
                    int raw = BydDeviceHelper.getIntValue(val);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                        && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0 && raw <= 8000) {
                        b.engineSpeedRpm(raw);
                    }
                }
                // Try alternate signal if primary didn't populate
                if (b.engineSpeedRpm == BydVehicleData.UNAVAILABLE) {
                    Object altVal = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_SPEED_ALT, Integer.class);
                    if (altVal != null) {
                        int altRaw = BydDeviceHelper.getIntValue(altVal);
                        if (altRaw != BydFeatureIds.BMS_UNAVAILABLE && altRaw != BydFeatureIds.INVALID_VALUE
                            && altRaw != BydFeatureIds.INVALID_VALUE_2 && altRaw >= 0 && altRaw <= 8000) {
                            b.engineSpeedRpm(altRaw);
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("collectEngine engineSpeed feature ID error: " + e.getMessage());
            }
            // Fallback to typed getter if feature ID didn't populate
            if (b.engineSpeedRpm == BydVehicleData.UNAVAILABLE) {
                Object rpm = BydDeviceHelper.callGetter(engineDevice, "getEngineSpeed");
                if (rpm instanceof Number) {
                    int rpmVal = ((Number) rpm).intValue();
                    if (rpmVal >= 0 && rpmVal <= 8000) b.engineSpeedRpm(rpmVal);
                }
            }

            // ==================== ENGINE POWER ====================
            // Net HV-bus power: positive = motor draw, negative = into battery (regen
            // when driving, plug-in charging when parked).
            //
            // Feature ID path returns a Double in mixed units across firmware:
            //   - On most models: kW (range roughly -200..400)
            //   - On some models: deciwatts × 10 (raw > 100 → scale ×0.1)
            // Range-check excludes sentinels (BMS_UNAVAILABLE etc.) and bogus values.
            //
            // IMPORTANT: the builder is seeded from the PREVIOUS snapshot
            // (toBuilder()), so b.enginePowerKw already carries last cycle's
            // value and is almost never NaN. The typed-getter fallback below must
            // therefore NOT gate on isNaN — that would lock the getter out forever
            // once any read succeeds, and the value would freeze ("correct but
            // stuck") on every cycle the flaky feature-ID read returns null.
            // Track whether THIS cycle wrote a live value instead.
            boolean powerWritten = false;
            String powerSource = "carry-forward";
            double powerRaw = Double.NaN;
            try {
                Object val = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_POWER, Double.class);
                if (val != null) {
                    double raw = BydDeviceHelper.getDoubleValue(val);
                    if (!Double.isNaN(raw) && raw >= -200.0 && raw <= 400.0) {
                        double kw = (Math.abs(raw) > 100.0) ? raw * 0.1 : raw;
                        b.enginePowerKw(kw);
                        powerWritten = true;
                        powerSource = "featureId";
                        powerRaw = raw;
                    }
                }
            } catch (Exception e) {
                logger.debug("collectEngine enginePower feature ID error: " + e.getMessage());
            }
            // Fallback to typed getter whenever the feature-ID read did NOT write a
            // fresh value THIS cycle — a live re-read that keeps the value tracking
            // instead of freezing on the carried-forward number.
            if (!powerWritten) {
                Object power = BydDeviceHelper.callGetter(engineDevice, "getEnginePower");
                if (power instanceof Number) {
                    double kw = ((Number) power).doubleValue();
                    // ACC-OFF sign gate (mirrors the listener path): with the key
                    // removed the only plausible engine-power direction is current
                    // INTO the pack (kw < 0, plug-in charging). Positive readings
                    // are stale ECU residue — reject so this fresh-read path can't
                    // feed the ChargingDetector a spurious "engine running" value.
                    boolean accOffReject = !accIsOn && kw > -ENGINE_POWER_CHARGING_DEADBAND;
                    if (kw >= -200.0 && kw <= 400.0 && !accOffReject) {
                        b.enginePowerKw(kw);
                        powerWritten = true;
                        powerSource = "getter";
                        powerRaw = kw;
                    }
                }
            }
            // Diagnostic (throttled 1/min, INFO so it lands in default captures):
            // confirms the value is refreshing each poll and which source won. If
            // this logs source=carry-forward repeatedly while driving, BOTH live
            // reads are missing and the value is genuinely stuck at the HAL layer.
            long powerNow = System.currentTimeMillis();
            if (powerNow - lastEnginePowerLogMs > 60_000L) {
                lastEnginePowerLogMs = powerNow;
                logger.info(String.format(java.util.Locale.US,
                    "enginePower resolved=%.2fkW source=%s raw=%.1f",
                    b.enginePowerKw, powerSource, powerRaw));
            }

            // Front motor speed (negated)
            Object fms = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_FRONT_MOTOR_SPEED, Integer.class);
            if (fms != null) b.frontMotorSpeed(-BydDeviceHelper.getIntValue(fms));

            // Rear motor speed
            Object rms = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_REAR_MOTOR_SPEED, Integer.class);
            if (rms != null) b.rearMotorSpeed(BydDeviceHelper.getIntValue(rms));

            // Front motor torque (negated double)
            Object fmt = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_FRONT_MOTOR_TORQUE, Double.class);
            if (fmt != null) b.frontMotorTorque(-BydDeviceHelper.getDoubleValue(fmt));
        } catch (Exception e) {
            logger.debug("collectEngine error: " + e.getMessage());
        }
    }

    private void collectStatistic(BydVehicleData.Builder b) {
        if (statisticDevice == null) return;
        try {
            // ==================== TOTAL MILEAGE ====================
            // Named getter primary, feature ID fallback
            Object mileage = BydDeviceHelper.callGetter(statisticDevice, "getTotalMileageValue");
            if (mileage instanceof Number) {
                int raw = ((Number) mileage).intValue();
                if (raw > 0) b.totalMileageKm((int) Math.round(raw * distanceToKmFactor));
            }
            if (b.totalMileageKm == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_TOTAL_MILEAGE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                            b.totalMileageKm((int) Math.round(raw * distanceToKmFactor));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic totalMileage feature ID error: " + e.getMessage());
                }
            }

            // ==================== EV MILEAGE ====================
            // Named getter primary, feature ID fallback
            Object evMileage = BydDeviceHelper.callGetter(statisticDevice, "getEVMileageValue");
            if (evMileage instanceof Number) {
                int raw = ((Number) evMileage).intValue();
                if (raw > 0) b.evMileageKm((int) Math.round(raw * distanceToKmFactor));
            }
            if (b.evMileageKm == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_MILEAGE_EV, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                            b.evMileageKm((int) Math.round(raw * distanceToKmFactor));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic evMileage feature ID error: " + e.getMessage());
                }
            }

            // ==================== SOC (ELEC PERCENTAGE) ====================
            // Named getter primary, then feature ID fallback
            Object elecPct = BydDeviceHelper.callGetter(statisticDevice, "getElecPercentageValue");
            if (elecPct instanceof Number) {
                double soc = ((Number) elecPct).doubleValue();
                if (soc >= 0 && soc <= 100) {
                    // The on-demand getter returns a COARSE (integer on this trim) SoC,
                    // while the typed onElecPercentageChanged event carries the true
                    // decimal. Don't let an integer poll clobber a fresher decimal that
                    // rounds to the same whole number — otherwise SoC flickers
                    // integer<->decimal every poll cycle. Take the poll only when it
                    // actually moves the rounded value (or nothing has been set yet).
                    double prevSoc = b.socPercent;
                    if (Double.isNaN(prevSoc) || Math.round(prevSoc) != Math.round(soc)) {
                        b.socPercent(soc);
                    }
                }
            }
            if (Double.isNaN(b.socPercent)) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_ELEC_PERCENTAGE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0 && raw <= 100) {
                            b.socPercent((double) raw);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic socPercent feature ID error: " + e.getMessage());
                }
            }

            // ==================== WATER TEMP ====================
            Object waterTemp = BydDeviceHelper.callGetter(statisticDevice, "getWaterTemperature");
            if (waterTemp instanceof Number) b.waterTempC(((Number) waterTemp).intValue());

            // Fallback: try Engine device if Statistic didn't provide coolant temp.
            // Some firmware only exposes coolant temperature via the Engine device.
            if (b.waterTempC == BydVehicleData.UNAVAILABLE && engineDevice != null) {
                try {
                    String[] coolantGetters = {
                        "getWaterTemperature", "getCoolantTemperature",
                        "getEngineCoolantTemperature", "getEngineWaterTemperature",
                        "getEngineCoolantTemp", "getWaterTemp"
                    };
                    for (String getter : coolantGetters) {
                        Object engineCoolant = BydDeviceHelper.callGetter(engineDevice, getter);
                        if (engineCoolant instanceof Number) {
                            int tempC = ((Number) engineCoolant).intValue();
                            if (tempC >= -50 && tempC <= 200) {
                                b.waterTempC(tempC);
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // ==================== TOTAL ELEC CONSUMPTION ====================
            Object totalElec = BydDeviceHelper.callGetter(statisticDevice, "getTotalElecConValue");
            if (totalElec instanceof Number) b.totalElecCon(((Number) totalElec).doubleValue());

            // ==================== TOTAL FUEL CONSUMPTION ====================
            Object totalFuel = BydDeviceHelper.callGetter(statisticDevice, "getTotalFuelConValue");
            if (totalFuel instanceof Number) b.totalFuelCon(((Number) totalFuel).doubleValue());

            // ==================== ELECTRIC DRIVING RANGE ====================
            // Named getter primary, feature ID fallback
            Object elecRange = BydDeviceHelper.callGetter(statisticDevice, "getElecDrivingRangeValue");
            if (elecRange instanceof Number) {
                int raw = ((Number) elecRange).intValue();
                if (raw > 0) b.elecRangeKm((int) Math.round(raw * distanceToKmFactor));
            }
            if (b.elecRangeKm == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_ELEC_DRIVING_RANGE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                            b.elecRangeKm((int) Math.round(raw * distanceToKmFactor));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic elecRange feature ID error: " + e.getMessage());
                }
            }

            // ==================== FUEL PERCENTAGE & FUEL RANGE (PHEV only) ====================
            // BEVs return bogus CAN bus values for fuel (e.g. constant 62% on a Seal).
            boolean isPhev = isPhev(b);

            // ==================== FUEL DRIVING RANGE (PHEV only) ====================
            if (isPhev) {
                // Named getter primary, feature ID fallback
                Object fuelRange = BydDeviceHelper.callGetter(statisticDevice, "getFuelDrivingRangeValue");
                if (fuelRange instanceof Number) {
                    int raw = ((Number) fuelRange).intValue();
                    if (raw > 0) b.fuelRangeKm((int) Math.round(raw * distanceToKmFactor));
                }
                if (b.fuelRangeKm == BydVehicleData.UNAVAILABLE) {
                    try {
                        Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_FUEL_DRIVING_RANGE, Integer.class);
                        if (val != null) {
                            int raw = BydDeviceHelper.getIntValue(val);
                            if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                                && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                                b.fuelRangeKm((int) Math.round(raw * distanceToKmFactor));
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("collectStatistic fuelRange feature ID error: " + e.getMessage());
                    }
                }
            }

            // ==================== FUEL PERCENTAGE (PHEV only) ====================
            if (isPhev) {
                // Named getter primary
                Object fuelPct = BydDeviceHelper.callGetter(statisticDevice, "getFuelPercentageValue");
                if (fuelPct instanceof Number) {
                    int pct = ((Number) fuelPct).intValue();
                    if (pct > 0 && pct <= 100) {
                        b.fuelPercent(pct);
                    }
                }
                // Feature ID fallback
                if (Double.isNaN(b.fuelPercent)) {
                    try {
                        Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_FUEL_PERCENTAGE, Integer.class);
                        if (val != null) {
                            int raw = BydDeviceHelper.getIntValue(val);
                            if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                                && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0 && raw <= 100) {
                                b.fuelPercent(raw);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Fuel percentage feature ID failed: " + e.getMessage());
                    }
                }
            }

            // Battery temps via get() — intValue - 40 = °C
            collectStatTemp(b, BydFeatureIds.STAT_HIGHEST_BATTERY_TEMP, "high");
            collectStatTemp(b, BydFeatureIds.STAT_LOWEST_BATTERY_TEMP, "low");
            collectStatTemp(b, BydFeatureIds.STAT_AVERAGE_BATTERY_TEMP, "avg");

            // Cell voltages via get() — intValue / 1000.0 = V
            double cellHi = collectStatVoltage(b, BydFeatureIds.STAT_HIGHEST_BATTERY_VOLTAGE, "high");
            double cellLo = collectStatVoltage(b, BydFeatureIds.STAT_LOWEST_BATTERY_VOLTAGE, "low");

            // HV pack voltage, derived from the (accurate) per-cell voltage × series cell count.
            // The statistic-device event 1151336480 (formerly read as pack voltage in decivolts)
            // under-reports on some trims — e.g. on the Seal 82.5 kWh it tracks only ~149 cells'
            // worth (~494 V) while the true pack is ~570 V (verified vs an OBD2 reading of 567 V at
            // 3.294 V/cell). The per-cell voltages read correctly, so pack = avg_cell × N, where N
            // is the pack's series cell count from its nominal capacity (cellCountForCapacity, e.g.
            // 82.5 kWh → 172s) — so this stays correct across BYD models. If capacity isn't known
            // yet (cellCount == 0) we skip the override rather than publish a wrong value.
            if (!Double.isNaN(cellHi) && !Double.isNaN(cellLo)) {
                int cellCount = 0;
                try {
                    com.overdrive.app.abrp.SohEstimator soh =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                    if (soh != null) {
                        cellCount = com.overdrive.app.abrp.SohEstimator
                            .cellCountForCapacity(soh.getNominalCapacityKwh());
                    }
                } catch (Throwable ignored) {}
                if (cellCount > 0) {
                    b.hvPackVoltage(((cellHi + cellLo) / 2.0) * cellCount);
                }
            }
        } catch (Exception e) {
            logger.debug("collectStatistic error: " + e.getMessage());
        }
    }

    private void collectStatTemp(BydVehicleData.Builder b, int featureId, String which) {
        Object val = BydDeviceHelper.callGet(statisticDevice, featureId, Integer.TYPE);
        if (val == null) val = BydDeviceHelper.callGet(statisticDevice, featureId, Integer.class);
        if (val == null) return;
        int raw = BydDeviceHelper.getIntValue(val);
        if (raw == BydFeatureIds.BMS_UNAVAILABLE || raw == BydFeatureIds.INVALID_VALUE
            || raw == BydFeatureIds.INVALID_VALUE_2 || raw == Integer.MIN_VALUE) return;
        if (raw < 0 || raw > 120) return;
        double tempC = raw - 40;
        switch (which) {
            case "high": b.highCellTempC(tempC); break;
            case "low": b.lowCellTempC(tempC); break;
            case "avg": b.avgCellTempC(tempC); break;
        }
    }

    /** @return the cell voltage in V, or NaN if unavailable/out-of-range. */
    private double collectStatVoltage(BydVehicleData.Builder b, int featureId, String which) {
        Object val = BydDeviceHelper.callGet(statisticDevice, featureId, Integer.TYPE);
        if (val == null) val = BydDeviceHelper.callGet(statisticDevice, featureId, Integer.class);
        if (val == null) return Double.NaN;
        int raw = BydDeviceHelper.getIntValue(val);
        if (raw == BydFeatureIds.BMS_UNAVAILABLE || raw == BydFeatureIds.INVALID_VALUE
            || raw == BydFeatureIds.INVALID_VALUE_2 || raw == Integer.MIN_VALUE || raw <= 0) return Double.NaN;
        double volts = raw / 1000.0;
        if (volts < 1.0 || volts > 5.0) return Double.NaN;
        switch (which) {
            case "high": b.highCellVoltage(volts); break;
            case "low": b.lowCellVoltage(volts); break;
        }
        return volts;
    }

    private void collectCharging(BydVehicleData.Builder b) {
        if (chargingDevice == null) return;
        try {
            // Named getters for init read
            Object gunState = BydDeviceHelper.callGetter(chargingDevice, "getChargingGunState");
            if (gunState instanceof Number) b.chargingGunState(((Number) gunState).intValue());

            Object charger = BydDeviceHelper.callGetter(chargingDevice, "getChargerWorkState");
            if (charger instanceof Number) b.chargerWorkState(((Number) charger).intValue());

            // BYDAutoPowerDevice.isCharging() — independent ground truth from
            // the power MCU. Used by ChargingDetector as the L2 cross-check
            // that catches the PHEV "BMS stuck at 15 IDLE while charging" bug.
            // Tri-state: null when the device is unavailable or the call fails.
            Boolean powerIsCharging = null;
            if (powerDevice != null) {
                try {
                    Object pic = BydDeviceHelper.callGetter(powerDevice, "isCharging");
                    if (pic instanceof Boolean) {
                        powerIsCharging = (Boolean) pic;
                    } else if (pic instanceof Number) {
                        powerIsCharging = ((Number) pic).intValue() != 0;
                    }
                } catch (Exception e) {
                    logger.debug("collectCharging Power.isCharging error: " + e.getMessage());
                }
            }
            com.overdrive.app.monitor.ChargingDetector.getInstance()
                .updatePowerIsCharging(powerIsCharging);

            // Feature ID for battery device state, fallback to named getter
            try {
                Object val = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_BATTERY_DEVICE_STATE, Integer.class);
                if (val != null) {
                    int raw = BydDeviceHelper.getIntValue(val);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                        && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0) {
                        b.chargingState(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging batteryState feature ID error: " + e.getMessage());
            }
            if (b.chargingState == BydVehicleData.UNAVAILABLE) {
                Object battState = BydDeviceHelper.callGetter(chargingDevice, "getBatteryManagementDeviceState");
                if (battState instanceof Number) {
                    b.chargingState(((Number) battState).intValue());
                }
            }

            // Charging power from the BYD ChargingDevice SDK getter.
            // Sentinel filter: SDK reports up to ±500 kW; reject anything beyond.
            // Listener callbacks (onChargingPowerChanged) keep this fresh between polls.
            Object power = BydDeviceHelper.callGetter(chargingDevice, "getChargingPower");
            if (power instanceof Number) {
                double kw = ((Number) power).doubleValue();
                if (Math.abs(kw) > 0.01 && Math.abs(kw) < 500) {
                    b.chargingPowerKw(kw);
                }
            }

            // Targeted clear: when the BMS reports an EXPLICIT non-charging
            // terminal state (READY=0, FINISHED=2, TERMINATED=4, DISCHARG_FINISH=12),
            // we know the previous charging session is over. Clear sticky listener-
            // delivered power so the inference layer in VehicleDataMonitor can't
            // false-trigger from leftover values. We do NOT clear on IDLE (15)
            // because that's the buggy reading some PHEV firmwares give while
            // actually charging — clearing there would break detection again.
            // We do NOT clear on disconnect-only signals (gunState==1) without a
            // BMS state agreeing, because PHEVs often leave gunState UNAVAILABLE.
            if (b.chargingState == 0 || b.chargingState == 2
                    || b.chargingState == 4 || b.chargingState == 12) {
                b.chargingPowerKw(Double.NaN);
                b.externalChargingPowerKw(Double.NaN);
                // chargePowerKw is now the TOP priority in getChargingState()'s
                // cascade, so a leftover value would surface a phantom power after
                // the session ends. Clear it with its siblings. (getChargePower()
                // also returns ~359 garbage when idle, but the >0.1 && <=300 gate
                // at the read/use sites already rejects that; this kills a stale
                // in-band value, e.g. the last real rate before the gun came out.)
                b.chargePowerKw(Double.NaN);
            }

            // Charging mode — getChargingMode() raw value (AC vs DC vs wireless, model-specific).
            // Stored on the snapshot; logged once on first sight then throttled at 5min.
            Object mode = BydDeviceHelper.callGetter(chargingDevice, "getChargingMode");
            if (mode instanceof Number) {
                int rawMode = ((Number) mode).intValue();
                // Filter sentinels (BMS_UNAVAILABLE=65535, INVALID values)
                if (rawMode >= 0 && rawMode < 100) {
                    b.chargingMode(rawMode);
                    long now = System.currentTimeMillis();
                    if (now - lastChargingModeLogMs > 300_000) {
                        lastChargingModeLogMs = now;
                        logger.info("getChargingMode=" + rawMode);
                    }
                }
            }

            // SDK getChargingState() — distinct from getBatteryManagementDeviceState() above.
            // Diagnostic only for now: log to verify the value space against our existing
            // chargingState (which may come from a different source).
            Object chState = BydDeviceHelper.callGetter(chargingDevice, "getChargingState");
            if (chState instanceof Number) {
                int rawState = ((Number) chState).intValue();
                if (rawState >= 0 && rawState < 100) {
                    long now = System.currentTimeMillis();
                    if (now - lastChargingStateRawLogMs > 300_000) {
                        lastChargingStateRawLogMs = now;
                        logger.debug("getChargingState=" + rawState + " (collector chargingState=" + b.chargingState + ")");
                    }
                }
            }

            // Charging type (0=DEFAULT, 3=VTOG)
            Object type = BydDeviceHelper.callGetter(chargingDevice, "getChargingType");
            if (type instanceof Number) b.chargingType(((Number) type).intValue());

            // VTOL detection — gunState==5 OR chargingType==3
            boolean isVtol = false;
            if (b.chargingGunState == 5) isVtol = true;
            if (b.chargingType == 3) isVtol = true;
            b.vtolCharging(isVtol);

            // Charging capacity (kWh)
            Object cap = BydDeviceHelper.callGetter(chargingDevice, "getChargingCapacity");
            if (cap instanceof Number) {
                double capKwh = ((Number) cap).doubleValue();
                if (capKwh > 0) b.chargingCapacityKwh(capKwh);
            }

            // Charging percent from chargingDevice
            Object pct = BydDeviceHelper.callGetter(chargingDevice, "getChargingPercent");
            if (pct instanceof Number) {
                int chgPct = ((Number) pct).intValue();
                if (chgPct >= 0 && chgPct <= 100) b.chargingPercent(chgPct);
            }

            // Charger work state via feature ID fallback
            if (b.chargerWorkState == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_CHARGER_WORK_STATE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0) {
                            b.chargerWorkState(raw);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectCharging chargerWorkState feature ID error: " + e.getMessage());
                }
            }

            // Wireless charging states via feature IDs
            try {
                Object wlLeft = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_LEFT_STATE, Integer.class);
                if (wlLeft != null) {
                    int raw = BydDeviceHelper.getIntValue(wlLeft);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE) {
                        b.wirelessChargingLeftState(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging wirelessLeft error: " + e.getMessage());
            }
            try {
                Object wlRight = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_RIGHT_STATE, Integer.class);
                if (wlRight != null) {
                    int raw = BydDeviceHelper.getIntValue(wlRight);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE) {
                        b.wirelessChargingRightState(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging wirelessRight error: " + e.getMessage());
            }
            try {
                Object wlState = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_STATE, Integer.class);
                if (wlState != null) {
                    int raw = BydDeviceHelper.getIntValue(wlState);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE) {
                        b.wirelessChargingStatus(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging wirelessState error: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.debug("collectCharging error: " + e.getMessage());
        }
    }

    private void collectInstrument(BydVehicleData.Builder b) {
        if (instrumentDevice == null) return;
        try {
            // Named getter for outside temperature
            Object extTemp = BydDeviceHelper.callGetter(instrumentDevice, "getOutCarTemperature");
            if (extTemp instanceof Number) {
                int t = ((Number) extTemp).intValue();
                if (t >= -50 && t <= 60) b.outsideTempC(t);
            }

            // External charging power. Two scaling regimes seen across BYD firmware:
            //
            //   - Listener path (onExternalChargingPowerChanged) — SDK pre-scales
            //     to kW. This matches the OEM firmware convention, which
            //     treats the listener arg as kW directly.
            //   - Polled getter — some firmware (Seal U DM-i PHEV, build 1124xxx)
            //     returns the raw CAN value in hectowatts (centiKW). Observed:
            //     221.7 raw for a real ~1.9 kW charger (221.7/100 = 2.217 kW,
            //     which is the wall-side handshake before AC→DC conversion loss).
            //     Same firmware family is the one that has the feature ID
            //     fallback below also delivering hectowatts (189.5 raw → 1.8 kW
            //     charger, per the comment on that path).
            //
            // Heuristic: kW values are bounded by physical reality (AC charging
            // tops at ~22 kW 3-phase, PHEV onboard charger maxes at 7 kW).
            // Anything above 50 from a getter that's supposed to be kW is the
            // hectowatt scale — divide. Below 50, trust the value as-is.
            // The 104857.5 BYD sentinel falls cleanly above the 50000 cap.

            // Charge power into the pack (kW), direct from the instrument cluster.
            // getChargePower() reads the real DC charge rate (e.g. 2.9 kW on a 15 A/230 V AC
            // charge, matches the BYD app / cloud battery_power), unlike getExternalChargingPower()
            // which returns the 104857.5 sentinel on this trim. Returns 0 / sentinel when idle —
            // bound to a plausible kW range so a sentinel can't leak.
            Object chgPower = BydDeviceHelper.callGetter(instrumentDevice, "getChargePower");
            if (chgPower instanceof Number) {
                double kw = ((Number) chgPower).doubleValue();
                if (kw >= 0 && kw <= 500) b.chargePowerKw(kw);
            }

            Object extPower = BydDeviceHelper.callGetter(instrumentDevice, "getExternalChargingPower");
            if (extPower instanceof Number) {
                double raw = ((Number) extPower).doubleValue();
                double kw;
                if (raw > 50.0 && raw < 50000.0) {
                    kw = raw / 100.0; // hectowatts → kW
                } else {
                    kw = raw;         // already kW (BEV firmware default)
                }
                if (kw > 0.1 && kw <= 500) {
                    b.externalChargingPowerKw(kw);
                    if (!loggedExtChargePowerScale) {
                        loggedExtChargePowerScale = true;
                        logger.info("getExternalChargingPower: raw=" + raw + " → " + kw
                                + " kW (scale=" + (raw > 50.0 && raw < 50000.0 ? "hectowatts/100" : "kW")
                                + "). Cross-check against the cluster's charging readout to confirm.");
                    }
                }
            }

            // Feature ID fallback (842006552). Returns raw CAN value in hectowatts
            // (value/100 = kW); evidence: 1.8 kW charger reports 189.5 raw.
            // Used only when the typed getter above returned nothing useful.
            if (Double.isNaN(b.externalChargingPowerKw)
                    && (Double.isNaN(b.chargingPowerKw) || b.chargingPowerKw == 0)) {
                try {
                    Object val = BydDeviceHelper.callGet(instrumentDevice,
                            BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_POWER_DD, Double.class);
                    if (val != null) {
                        double raw = BydDeviceHelper.getDoubleValue(val);
                        if (!Double.isNaN(raw) && Math.abs(raw) > 1.0 && Math.abs(raw) < 35000) {
                            // Convert from hectowatts to kW
                            double kw = raw / 100.0;
                            b.chargingPowerKw(kw);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectInstrument chargingPower feature ID error: " + e.getMessage());
                }
            }

            // Charging percent via instrument feature ID (842006544) — read
            // unconditionally as fallback when the chargingDevice path didn't
            // populate it. Gating on a BMS-derived "may be charging" flag here
            // creates the same circular dependency we removed from the power
            // reads above; the safe-clear in collectCharging() wipes stale
            // values when the vehicle is genuinely idle (BMS not charging AND
            // gun disconnected).
            if (b.chargingPercent == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(instrumentDevice,
                            BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_PERCENT_DD, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw >= 0 && raw <= 100) {
                            b.chargingPercent(raw);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectInstrument chargingPercent feature ID error: " + e.getMessage());
                }
            }

            // Charging rest time via instrument feature IDs (primary path)
            // Fallback to chargingDevice.getChargingRestTime() is in collectChargingExtended()
            // Validates: 255 = not available, hours 0-23, minutes 0-59
            //
            // CRITICAL: reset to UNAVAILABLE FIRST so this value RE-DERIVES every poll.
            // The builder carries the previous snapshot forward via toBuilder(), and the
            // fallback in collectChargingExtended() self-guards on `== UNAVAILABLE` — so
            // without this reset the FIRST rest-time reading of a session would latch and
            // never count down (the "Time to full stuck / not updating" bug). Clearing
            // here lets both the feature-ID path (below) and the fallback re-populate a
            // FRESH value each cycle; feature-ID priority is preserved because it runs
            // first and the fallback only fills when this is still UNAVAILABLE.
            b.chargingRestTimeHours(BydVehicleData.UNAVAILABLE);
            b.chargingRestTimeMinutes(BydVehicleData.UNAVAILABLE);
            try {
                Object hourVal = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_REST_HOUR_DD, Integer.class);
                Object minVal = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_REST_MINUTE_DD, Integer.class);
                if (hourVal != null && minVal != null) {
                    int hours = BydDeviceHelper.getIntValue(hourVal);
                    int minutes = BydDeviceHelper.getIntValue(minVal);
                    if (hours != 255 && minutes != 255 && hours >= 0 && hours <= 23 && minutes >= 0 && minutes <= 59) {
                        b.chargingRestTimeHours(hours);
                        b.chargingRestTimeMinutes(minutes);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectInstrument chargingRestTime feature ID error: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.debug("collectInstrument error: " + e.getMessage());
        }
    }

    private void collectOta(BydVehicleData.Builder b) {
        if (otaDevice == null) return;
        try {
            Object voltage = BydDeviceHelper.callGetter(otaDevice, "getBatteryPowerVoltage");
            if (voltage instanceof Number) {
                double v = ((Number) voltage).doubleValue();
                if (v > 0 && v < 20) b.voltage12v(v);
            }
        } catch (Exception e) {
            logger.debug("collectOta error: " + e.getMessage());
        }
    }

    private void collectGearbox(BydVehicleData.Builder b) {
        if (gearboxDevice == null) return;
        try {
            Object gear = BydDeviceHelper.callGetter(gearboxDevice, "getGearboxAutoModeType");
            if (gear instanceof Number) b.gearMode(((Number) gear).intValue());
        } catch (Exception e) {
            logger.debug("collectGearbox error: " + e.getMessage());
        }
    }

    private void collectAc(BydVehicleData.Builder b) {
        if (acDevice == null) return;
        try {
            Object acState = BydDeviceHelper.callGetter(acDevice, "getAcStartState");
            if (acState instanceof Number) b.acStartState(((Number) acState).intValue());
            Object cycle = BydDeviceHelper.callGetter(acDevice, "getAcCycleMode");
            if (cycle instanceof Number) b.acCycleMode(((Number) cycle).intValue());
            Object wind = BydDeviceHelper.callGetter(acDevice, "getAcWindMode");
            if (wind instanceof Number) b.acWindMode(((Number) wind).intValue());
            Object fanLevel = BydDeviceHelper.callGetter(acDevice, "getAcWindLevel");
            if (fanLevel instanceof Number) {
                int level = ((Number) fanLevel).intValue();
                if (level >= 0 && level <= 7) b.acFanLevel(level);
            }
            Object unit = BydDeviceHelper.callGetter(acDevice, "getTemperatureUnit");
            if (unit instanceof Number) b.tempUnit(((Number) unit).intValue());
            // Sensed cabin temperature. Position 5 (AC_TEMP_INSIDE) is the MEASURED cabin
            // temp; positions 1/2/3 are the driver/passenger/rear climate SETPOINTS (the
            // dial, 17..33) and 4 is outside. This previously read position 1 — the driver
            // setpoint — so insideTempC tracked the target dial, not the cabin: a
            // "cabin temp > X" automation compared against the setpoint and never fired (and
            // the MQTT inside_temp / cloud / climate readouts never reflected reality).
            // Prefer the position-5 read, then fall back to the AC_TEMP_INSIDE feature-id,
            // matching the OEM firmware's getTemperatureFromDevice(5).
            Object insideTemp = BydDeviceHelper.callGetter(acDevice, "getTemprature", 5);
            int sensed = Integer.MIN_VALUE;
            if (insideTemp instanceof Number) {
                int t = ((Number) insideTemp).intValue();
                if (t != 65535 && t >= -40 && t <= 80) sensed = t;
            }
            if (sensed == Integer.MIN_VALUE) {
                int t = BydDeviceHelper.getIntValue(
                        BydDeviceHelper.callGet(acDevice, BydFeatureIds.AC_TEMP_INSIDE, Integer.class));
                if (t != Integer.MIN_VALUE && t != 65535 && t >= -40 && t <= 80) sensed = t;
            }
            if (sensed != Integer.MIN_VALUE) b.insideTempC(sensed);
        } catch (Exception e) {
            logger.debug("collectAc error: " + e.getMessage());
        }
    }

    private void collectLight(BydVehicleData.Builder b) {
        if (lightDevice == null) return;
        try {
            Object left = BydDeviceHelper.callGetter(lightDevice, "getTurnLightState", 1);
            if (left instanceof Number) b.leftTurnState(((Number) left).intValue());
            Object right = BydDeviceHelper.callGetter(lightDevice, "getTurnLightState", 2);
            if (right instanceof Number) b.rightTurnState(((Number) right).intValue());
            // Light status: 1=low, 2=high, 3=position, 6=rearFog, 7=frontFog, 8=hazard
            b.lowBeam(getLightStatus(1) == 1);
            b.highBeam(getLightStatus(2) == 1);
            b.rearFog(getLightStatus(6) == 1);
            b.frontFog(getLightStatus(7) == 1);
            b.hazard(getLightStatus(8) == 1);
            Object dayTime = BydDeviceHelper.callGetter(lightDevice, "getDayTimeLightState");
            if (dayTime instanceof Number) b.dayTimeLight(((Number) dayTime).intValue() == 1);
            // Auto-headlight (light-sensor) mode: 1=on, 0=off. The usable "it's dark"
            // proxy — no lux value exists on this platform. Named getter on the light
            // device, matching the OEM firmware's getLightAutoStatus.
            Object autoLight = BydDeviceHelper.callGetter(lightDevice, "getLightAutoStatus");
            if (autoLight instanceof Number) b.lightAutoStatus(((Number) autoLight).intValue());
        } catch (Exception e) {
            logger.debug("collectLight error: " + e.getMessage());
        }
    }

    private int getLightStatus(int position) {
        Object val = BydDeviceHelper.callGetter(lightDevice, "getLightStatus", position);
        return (val instanceof Number) ? ((Number) val).intValue() : 0;
    }

    private void collectAdas(BydVehicleData.Builder b) {
        if (adasDevice == null) return;
        try {
            // Read the SLW state id first (raw 2 = on); if this trim doesn't expose it,
            // fall back to the reference-confirmed ISLA status id (raw 1 = on). Each id
            // has its OWN on-value convention, so we compare against the right one.
            int slw = BydDeviceHelper.callGetSingle(adasDevice, BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE);
            if (slw >= 0) {
                b.speedLimitWarning(slw == 2);
            } else {
                int isla = BydDeviceHelper.callGetSingle(adasDevice, BydFeatureIds.ADAS_ISLA_SWITCH_STATUS);
                if (isla >= 0) b.speedLimitWarning(isla == 1);
            }
        } catch (Exception e) {
            logger.debug("collectAdas error: " + e.getMessage());
        }
    }

    private void collectSettings(BydVehicleData.Builder b) {
        if (settingDevice == null) return;
        try {
            int[] seatHeat = new int[2];
            int[] seatCool = new int[2];
            // SDK returns 1=off, 2=low, 3=high — normalize to 0/1/2 for the wire format.
            // On unsupported firmwares the getter returns null/throws → leave entry as 0.
            for (int i = 0; i < 2; i++) {
                Object heat = BydDeviceHelper.callGetter(settingDevice, "getSeatHeatingState", i + 1);
                if (heat instanceof Number) {
                    int v = ((Number) heat).intValue() - 1;
                    seatHeat[i] = (v >= 0 && v <= 2) ? v : 0;
                }
                Object cool = BydDeviceHelper.callGetter(settingDevice, "getSeatVentilatingState", i + 1);
                if (cool instanceof Number) {
                    int v = ((Number) cool).intValue() - 1;
                    seatCool[i] = (v >= 0 && v <= 2) ? v : 0;
                }
            }
            b.seatHeat(seatHeat).seatCool(seatCool);
            int childPresenceDetection = BydDeviceHelper.callGetSingle(settingDevice, BydFeatureIds.SETTING_CPD_SWITCH_STATUS);
            if (childPresenceDetection >= 0) {
                b.childPresenceDetection(childPresenceDetection);
            }
            // Interior ambient colour. The "all area" query does not report reliably;
            // read the FRONT (area 1) colour as the representative value. 1-based index.
            Object ambient = BydDeviceHelper.callGetter(settingDevice, "getIALColor", 1);
            if (ambient instanceof Number) {
                int c = ((Number) ambient).intValue();
                if (c >= 1 && c <= 31) b.ambientColour(c);
            }
        } catch (Exception e) {
            logger.debug("collectSettings error: " + e.getMessage());
        }
    }

    private void collectPower(BydVehicleData.Builder b) {
        if (powerDevice == null) return;
        try {
            // BYDAutoPowerDevice is a singleton that may have been initialized by another daemon
            // with a null/stale context. Force-update the internal context before calling methods.
            ensureDeviceContext(powerDevice);
            
            Object mcu = BydDeviceHelper.callGetter(powerDevice, "getMcuStatus");
            if (mcu instanceof Number) b.mcuStatus(((Number) mcu).intValue());
            // NOTE: getBatteryRemainPowerEV() intentionally NOT called here.
            // On PHEVs (Sealion 6 DM-i), the PowerDevice EV subsystem returns stale kWh
            // values when the ICE is running. We rely on Statistic/Bodywork paths for
            // remaining kWh on both BEVs and PHEVs.
        } catch (Exception e) {
            logger.debug("collectPower error: " + e.getMessage());
        }
    }
    
    /**
     * Force-update a BYD device singleton's internal context field.
     * BYD singletons store context from the first getInstance() call.
     * If another daemon initialized it first with a null/stale context, methods NPE.
     */
    /**
     * PHEV detection. getEnergyType is unreliable — observed returning 1 on
     * both BEV and PHEV firmwares, so we cannot trust it as the discriminator.
     * Primary signal: live fuel HAL values. If both getFuelPercentageValue
     * and getFuelDrivingRangeValue return BMS-unavailable sentinels, the
     * vehicle has no fuel system → BEV. Otherwise (real fuel readings, OR
     * we haven't been able to probe yet) treat as PHEV/HEV.
     *
     * Cached after first successful probe to avoid hammering reflection.
     */
    private volatile int cachedDrivetrain = 0;  // 0=unknown, 1=BEV, 2=PHEV/HEV
    private volatile long lastDrivetrainProbeMs = 0;
    private static final long DRIVETRAIN_REPROBE_MS = 60_000;

    private boolean isPhev(BydVehicleData.Builder b) {
        return computeIsPhev();
    }

    private boolean isPhev(BydVehicleData snapshot) {
        return computeIsPhev();
    }

    /**
     * Public drivetrain accessor for callers outside this class (TripDetector,
     * TripAnalyticsManager, API handlers). Reuses the cached probe with the
     * same {@link #DRIVETRAIN_REPROBE_MS} TTL so a hot path call doesn't hit
     * reflection. Returns true for PHEV/HEV, false for BEV/unknown.
     */
    public boolean isPhevPublic() {
        return computeIsPhev();
    }

    private boolean computeIsPhev() {
        long now = System.currentTimeMillis();
        if (cachedDrivetrain != 0 && (now - lastDrivetrainProbeMs) < DRIVETRAIN_REPROBE_MS) {
            return cachedDrivetrain == 2;
        }

        // ── Pre-probe: capacity-based PHEV gate ────────────────────────────
        // If the daemon has already locked in a known small (<30 kWh) nominal
        // pack — typically because the user picked a Sealion 6 / Song / Tang
        // DM-i in the model selector — that signal is far stronger than the
        // live fuel HAL probes. The fuel HAL on these PHEVs goes through a
        // warm-up period where getFuelPercentageValue / getFuelDrivingRangeValue
        // can BOTH return BMS sentinels (255/2046/etc), which the
        // sentinel-AND-sentinel branch below would incorrectly latch as BEV
        // for 60s. That regression dropped fuel-percent display on PHEVs in
        // v17. Restoring the v12-era capacity-first behaviour: small known
        // nominal → PHEV verdict, full TTL.
        //
        // Inverse risk (BEV with <30 kWh nominal) is ~zero — the smallest BYD
        // BEV is the Atto 3 at 49.9 kWh. Capacity sub-30 kWh uniquely names a
        // PHEV pack across the catalog.
        double knownNominal = 0;
        try {
            com.overdrive.app.abrp.SohEstimator sohEst =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (sohEst != null) knownNominal = sohEst.getNominalCapacityKwh();
        } catch (Exception ignored) {}
        if (knownNominal > 0 && knownNominal < 30.0) {
            cachedDrivetrain = 2;
            lastDrivetrainProbeMs = now;
            logger.info("computeIsPhev → PHEV (capacity-gate, nominal="
                + String.format("%.1f", knownNominal) + " kWh)");
            return true;
        }

        boolean fuelPctSentinel = false;
        boolean fuelRangeSentinel = false;
        boolean fuelPctReal = false;
        boolean fuelRangeReal = false;
        int fuelPctRaw = Integer.MIN_VALUE;
        int fuelRangeRaw = Integer.MIN_VALUE;
        if (statisticDevice != null) {
            try {
                Object fp = BydDeviceHelper.callGetter(statisticDevice, "getFuelPercentageValue");
                if (fp instanceof Number) {
                    int v = ((Number) fp).intValue();
                    fuelPctRaw = v;
                    if (isBevFuelSentinel(v)) fuelPctSentinel = true;
                    // 0 is intentionally NOT counted as "real" — a BEV that
                    // happens to return 0 instead of a sentinel would falsely
                    // classify as PHEV. PHEVs with truly empty tanks will be
                    // caught by fuelRangeReal once driven, or by the capacity
                    // fallback in the meantime.
                    else if (v > 0 && v <= 100) fuelPctReal = true;
                }
            } catch (Exception ignored) {}
            try {
                Object fr = BydDeviceHelper.callGetter(statisticDevice, "getFuelDrivingRangeValue");
                if (fr instanceof Number) {
                    int v = ((Number) fr).intValue();
                    fuelRangeRaw = v;
                    if (isBevFuelSentinel(v)) fuelRangeSentinel = true;
                    else if (v > 0 && v < 1500) fuelRangeReal = true;
                }
            } catch (Exception ignored) {}
        }
        // PHEV: at least one fuel signal returns a real, non-zero, non-sentinel
        // value AND the other is either real or at a sentinel (i.e. NOT a real
        // value claiming the opposite — that would indicate firmware lying).
        // BEV: both signals at sentinel.
        // Otherwise: defer to capacity heuristic, don't cache.
        if (fuelPctReal && fuelRangeReal) {
            cachedDrivetrain = 2;
            lastDrivetrainProbeMs = now;
            logger.info("computeIsPhev → PHEV (fuelPct=" + fuelPctRaw
                + ", fuelRange=" + fuelRangeRaw + ")");
            return true;
        }
        if (fuelPctSentinel && fuelRangeSentinel) {
            cachedDrivetrain = 1;
            lastDrivetrainProbeMs = now;
            logger.info("computeIsPhev → BEV (both fuel signals at sentinel: pct="
                + fuelPctRaw + ", range=" + fuelRangeRaw + ")");
            return false;
        }
        // One real + one sentinel is the "PHEV with empty tank or 0 km" case.
        // Cache as PHEV with a SHORTER TTL so a transient HAL miss self-heals
        // quickly. Without the cache, every isPhev() call re-runs both
        // reflection probes — onFuelPercentageChanged fires at HAL rate.
        if ((fuelPctReal && fuelRangeSentinel) || (fuelRangeReal && fuelPctSentinel)) {
            cachedDrivetrain = 2;
            // 5s TTL via lastDrivetrainProbeMs offset trick: pretend the probe
            // happened (DRIVETRAIN_REPROBE_MS - 5000) ms ago, so the next call
            // in >5s will re-probe.
            lastDrivetrainProbeMs = now - (DRIVETRAIN_REPROBE_MS - 5_000);
            logger.info("computeIsPhev → PHEV (mixed signals: pct=" + fuelPctRaw
                + ", range=" + fuelRangeRaw + ") — short TTL re-probe");
            return true;
        }
        // Capacity gate already ran above with knownNominal < 30. Fall through
        // here only when nominal isn't known yet OR is >=30 (BEV-sized) — both
        // resolve to BEV verdict, deliberately uncached so the next probe can
        // re-evaluate once SohEstimator picks up a real nominal.
        logger.info("computeIsPhev → BEV (no fuel signals, no small nominal: "
            + "pct=" + fuelPctRaw + ", range=" + fuelRangeRaw
            + ", nominal=" + String.format("%.1f", knownNominal) + " kWh) — uncached");
        return false;
    }

    private static boolean isBevFuelSentinel(int v) {
        return v == 255 || v == 254 || v == 511 || v == 1023
            || v == 2046 || v == 2047 || v == 4095
            || v == 65534 || v == 65535;
    }

    private void ensureDeviceContext(Object device) {
        if (device == null || context == null) return;
        try {
            // Walk up to AbsBYDAutoDevice and set mContext
            Class<?> cls = device.getClass();
            while (cls != null && cls != Object.class) {
                try {
                    java.lang.reflect.Field contextField = cls.getDeclaredField("mContext");
                    contextField.setAccessible(true);
                    Object currentCtx = contextField.get(device);
                    if (currentCtx == null) {
                        contextField.set(device, context);
                        logger.info("Fixed null context on " + device.getClass().getSimpleName());
                    }
                    return;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
        } catch (Exception e) {
            logger.debug("ensureDeviceContext failed: " + e.getMessage());
        }
    }

    // Seatbelt raw values that are NOT a real belt state — SDK failure/unavailable
    // codes. Matches the OEM firmware's FAILURE_STATUS_CODES set exactly. These are RAW
    // 32-bit negatives, so sanitizeSeatbelt tests them BEFORE the &0xFFFF mask (see the
    // ORDER MATTERS note there); a raw read equal to any of these — or masked to 2
    // (INVALID) — is dropped to "unknown" rather than mis-reported as buckled/unbuckled.
    private static final java.util.Set<Integer> SEATBELT_FAILURE_CODES = new java.util.HashSet<>(
            java.util.Arrays.asList(-2147482647, -2147482648, -2147482645, -2147482646,
                    -10011, Integer.MIN_VALUE, -1, -10013));

    // Passenger-belt de-glitch latch (mirrors the OEM firmware's per-seat
    // seatbeltEverUnlatched map): the front-passenger belt sensor reports "buckled" from
    // boot until a genuine unlatch (0) is seen at least once, so we treat its 1 as
    // unbuckled until the first real 0. Only the passenger seat needs this; the driver
    // belt reads truthfully from boot. Written/read only on the single BydDataPoll thread.
    private boolean passengerBeltEverUnlatched = false;

    /** Cached BYDAutoInstrumentDevice.getSafetyBeltStatus(int) — the dedicated named getter
     *  the WORKING telemetry-recording overlay uses (TelemetryDataCollector.probeSeatbeltApis
     *  / getSafetyBeltStatusMethod). Resolved once, off the same instrumentDevice. Absent →
     *  the feature-id fallback below is used. */
    private volatile Method seatbeltStatusMethod;
    private volatile boolean seatbeltStatusMethodResolved = false;

    private Method resolveSeatbeltStatusMethod() {
        if (seatbeltStatusMethodResolved) return seatbeltStatusMethod;
        Method m = null;
        try {
            if (instrumentDevice != null) {
                m = instrumentDevice.getClass().getMethod("getSafetyBeltStatus", int.class);
            }
        } catch (Throwable ignored) { /* absent on this trim → fall back to feature-id */ }
        seatbeltStatusMethod = m;
        seatbeltStatusMethodResolved = true;
        if (m != null) logger.info("collectSafetyBelt: using BYDAutoInstrumentDevice.getSafetyBeltStatus(int) (telemetry-overlay path)");
        return m;
    }

    /**
     * Read one seat's raw seatbelt state by AREA (1 = driver / main, 2 = passenger / deputy).
     *
     * <p>PRIMARY: the dedicated {@code BYDAutoInstrumentDevice.getSafetyBeltStatus(int area)}
     * method — the exact call the telemetry-recording overlay uses and which is confirmed to
     * return LIVE per-seat state on this firmware. The prior automation read used the generic
     * {@code get(int[],Class)} feature-id channel (INSTRUMENT_DD_*_SAFETYBELT_STATE) instead,
     * which does NOT return a live value here, so the seatbelt state never transitioned and an
     * "On Change Seatbelt" automation never fired (while the test/play button, which bypasses
     * the trigger, worked). FALLBACK: the feature-id read, for a trim where the named method is
     * absent. Returns {@link Integer#MIN_VALUE} on any miss (→ sanitizeSeatbelt → UNAVAILABLE). */
    private int readInstrumentSeatbelt(int area, int featureId) {
        Method m = resolveSeatbeltStatusMethod();
        if (m != null) {
            try {
                Object r = m.invoke(instrumentDevice, area);
                if (r instanceof Number) return ((Number) r).intValue();
            } catch (Throwable t) {
                logger.debug("getSafetyBeltStatus(area=" + area + ") failed: " + t.getMessage());
            }
        }
        // Fallback: generic feature-id read.
        return BydDeviceHelper.getIntValue(
                BydDeviceHelper.callGet(instrumentDevice, featureId, Integer.class));
    }

    /**
     * Read per-seat seatbelt buckled/unbuckled state.
     *
     * <p>Reads the belt state from the INSTRUMENT device via the dedicated
     * {@code getSafetyBeltStatus(int area)} method (area 1 = driver/main, 2 = passenger/
     * deputy) — the SAME call the telemetry-recording overlay uses (and which is confirmed
     * to return LIVE per-seat state on this firmware), with the generic feature-id read
     * (INSTRUMENT_DD_*_SAFETYBELT_STATE) as a fallback for trims lacking the method. An
     * earlier revision used ONLY the feature-id channel; that did not return a live value
     * here, so the seatbelt state never transitioned and an "On Change Seatbelt" automation
     * never fired (while the test/play button, which bypasses the trigger, still worked).
     * There are only TWO real seatbelt signals on this platform (driver + front passenger);
     * no rear-seat signals exist.
     *
     * <p>Each raw read is sanitized (mask low 16 bits, drop failure codes + INVALID(2)) to
     * a 2-slot array: index 0 = driver, index 1 = passenger, each {@code 0 = unbuckled},
     * {@code 1 = buckled}, or {@link BydVehicleData#UNAVAILABLE} when unknown/dropped — so a
     * consumer can tell "unbuckled" from "no reading".
     */
    private void collectSafetyBelt(BydVehicleData.Builder b) {
        if (instrumentDevice == null) return;
        try {
            // Read via the dedicated getSafetyBeltStatus(area) method (area 1=driver/main,
            // 2=passenger/deputy) — the SAME call the working telemetry-recording overlay
            // uses and which returns LIVE per-seat state here. Falls back to the generic
            // feature-id read only when that method is absent. (The earlier feature-id-only
            // read did not return a live value on this firmware, so the state never changed
            // and an "On Change Seatbelt" automation never fired.)
            int driver = sanitizeSeatbelt(readInstrumentSeatbelt(
                    1, BydFeatureIds.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE));
            int passenger = sanitizeSeatbelt(readInstrumentSeatbelt(
                    2, BydFeatureIds.INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE));
            // Passenger de-glitch (mirrors the OEM firmware's seatbeltEverUnlatched): the
            // front-passenger belt sensor idles at "buckled" (1) until a genuine unlatch is
            // seen at least once, so a never-fastened passenger seat would read buckled from
            // boot and a "passenger belt unbuckled" automation could never fire. Treat a 1 as
            // unbuckled until we've observed a real 0; trust the sensor's 1 thereafter.
            if (passenger == 0) {
                passengerBeltEverUnlatched = true;
            } else if (passenger == 1 && !passengerBeltEverUnlatched) {
                passenger = 0;
            }
            // Only publish the array if at least one seat gave a real reading, so a trim
            // that doesn't expose these feature-ids leaves seatbeltStatus null (unseeded)
            // rather than a pair of UNAVAILABLE sentinels.
            if (driver != BydVehicleData.UNAVAILABLE || passenger != BydVehicleData.UNAVAILABLE) {
                b.seatbeltStatus(new int[]{ driver, passenger });
            }
        } catch (Exception e) {
            logger.debug("collectSafetyBelt error: " + e.getMessage());
        }
        // Seat occupancy (someone present) — separate from belt state. The OEM firmware
        // reads getPassengerStatus(area) off the safety-belt device: NOBODY=0, SOMEBODY=1,
        // INVALID=2. Areas 1=driver, 2=front passenger. Stored as a 2-slot array
        // (index 0=driver, 1=passenger); leave null if the getter is unavailable.
        if (safetyBeltDevice != null) {
            try {
                int od = occupantOf(1), op = occupantOf(2);
                if (od != BydVehicleData.UNAVAILABLE || op != BydVehicleData.UNAVAILABLE) {
                    b.passengerDetection(new int[]{ od, op });
                }
            } catch (Exception e) {
                logger.debug("collectSafetyBelt occupancy error: " + e.getMessage());
            }
        }
    }

    /** Read one seat's occupancy via getPassengerStatus(area): 1=SOMEBODY, 0=NOBODY,
     *  else UNAVAILABLE (INVALID/unknown). */
    private int occupantOf(int area) {
        Object s = BydDeviceHelper.callGetter(safetyBeltDevice, "getPassengerStatus", area);
        if (!(s instanceof Number)) return BydVehicleData.UNAVAILABLE;
        int v = ((Number) s).intValue();
        if (v == 1) return 1;
        if (v == 0) return 0;
        return BydVehicleData.UNAVAILABLE;
    }

    /** Sanitize a raw instrument seatbelt read → 0 (unbuckled) / 1 (buckled) /
     *  {@link BydVehicleData#UNAVAILABLE} (unknown). Mirrors the OEM firmware's
     *  sanitizeSeatbeltState: drop failure codes, then mask &0xFFFF and drop INVALID(2).
     *
     *  <p>ORDER MATTERS: the failure codes are RAW 32-bit negatives (e.g.
     *  Integer.MIN_VALUE, -1, -10011), so they must be tested against the RAW value
     *  BEFORE masking. If tested after &0xFFFF, Integer.MIN_VALUE would mask to 0 and be
     *  mis-reported as "unbuckled" — a spurious belt-off trigger. */
    private static int sanitizeSeatbelt(int raw) {
        if (SEATBELT_FAILURE_CODES.contains(raw)) return BydVehicleData.UNAVAILABLE;
        int masked = raw & 0xFFFF;
        if (masked == 2) return BydVehicleData.UNAVAILABLE;  // INVALID
        if (masked == 0) return 0;   // unbuckled
        if (masked == 1) return 1;   // buckled
        return BydVehicleData.UNAVAILABLE;
    }

    private void collectTyre(BydVehicleData.Builder b) {
        if (tyreDevice == null) return;
        try {
            // Pressure value (kPa, raw int — per the OEM firmware's pressure
            // formatter: no scaling for kPa, *0.1450377 for psi,
            // /100 for bar). Areas: 1=FL, 2=FR, 3=RL, 4=RR.
            int[] pressures = new int[4];
            int[] pressureStates = new int[4];
            int[] airLeakStates = new int[4];
            int[] signalStates = new int[4];
            for (int i = 0; i < 4; i++) {
                Object p = BydDeviceHelper.callGetter(tyreDevice, "getTyrePressureValue", i + 1);
                pressures[i] = (p instanceof Number) ? ((Number) p).intValue() : -1;
                Object s = BydDeviceHelper.callGetter(tyreDevice, "getTyrePressureState", i + 1);
                pressureStates[i] = (s instanceof Number) ? ((Number) s).intValue() : -1;
                Object leak = BydDeviceHelper.callGetter(tyreDevice, "getTyreAirLeakState", i + 1);
                airLeakStates[i] = (leak instanceof Number) ? ((Number) leak).intValue() : -1;
                Object sig = BydDeviceHelper.callGetter(tyreDevice, "getTyreSignalState", i + 1);
                signalStates[i] = (sig instanceof Number) ? ((Number) sig).intValue() : -1;

                // Poll per-wheel temperature via the matching SDK getter.
                // The async onTyreBatteryValueChanged callback is dormant on
                // some firmwares (PHEV models on this fleet, confirmed by
                // log capture), but a small subset of those firmwares still
                // answer getTyreBatteryValue(area) with the same temperature
                // value the cluster reads. callGetter is null-safe so this
                // is a no-op on firmwares that don't expose the getter.
                pollPerWheelTyreTemp(i);
            }
            b.tyrePressure(pressures);
            b.tyrePressureState(pressureStates);
            b.tyreAirLeakState(airLeakStates);
            b.tyreSignalState(signalStates);
            b.tyreTemperature(snapshotTyreTemperatures());

            Object sys = BydDeviceHelper.callGetter(tyreDevice, "getTyreSystemState");
            if (sys instanceof Number) b.tyreSystemState(((Number) sys).intValue());
            Object temp = BydDeviceHelper.callGetter(tyreDevice, "getTyreTemperatureState");
            if (temp instanceof Number) b.tyreTemperatureState(((Number) temp).intValue());

            // Per-tyre temperature has three possible channels:
            //   1. Async listener: AbsBYDAutoTyreListener.onTyreBatteryValueChanged
            //      — fires on BEV firmware when registered via the two-arg
            //      registerListener(listener, int[]) overload. Dormant on some
            //      PHEV firmware with single-arg registration only.
            //   2. Polled getter: pollPerWheelTyreTemp() above tries
            //      getTyreBatteryValue / getTyreTemperatureValue /
            //      getTyreTemperature / getTyreTemperatureState.
            //   3. InstrumentDevice feature IDs: polled in
            //      collectInstrumentExtended() using the LF/RF/LB/RB
            //      tyre temperature feature IDs from BydFeatureIds.
            // If all three channels stay silent, tyre temperature is not
            // available on this firmware via any known SDK path.

            logTyreAlertsIfChanged(pressures, pressureStates, airLeakStates, signalStates,
                    sys instanceof Number ? ((Number) sys).intValue() : Integer.MIN_VALUE,
                    temp instanceof Number ? ((Number) temp).intValue() : Integer.MIN_VALUE);
        } catch (Exception e) {
            logger.debug("collectTyre error: " + e.getMessage());
        }
    }

    // Last-seen tyre alert state — change-only logging so a developing slow leak
    // surfaces at info, but a healthy car doesn't spam the log every poll.
    private volatile int[] lastTyrePressuresKpa = null;
    private volatile int[] lastTyrePressureStates = null;
    private volatile int[] lastTyreAirLeakStates = null;
    private volatile int[] lastTyreSignalStates = null;
    private volatile int lastTyreSystemState = Integer.MIN_VALUE;
    private volatile int lastTyreTemperatureState = Integer.MIN_VALUE;

    // --- Tyre alarm notification state machine (per corner FL,FR,RL,RR) ---
    // The push notification is LATCHED so a persisting alarm notifies exactly
    // ONCE and re-arms only after the corner reads normal again. This replaces
    // the old 0->non-zero edge check, which (a) never fired for a tyre already
    // low at daemon start, (b) turned a -1 signal dropout into a bogus
    // "-1 kPa Overpressure" alert, and (c) missed a real alarm that arrived
    // right after a dropout. All access is guarded by tyreAlarmLock because the
    // poll thread and the async onTyreCallback binder thread both drive it.
    //
    // The latch stores the NOTIFIED severity level (0=none, 1=WARN, 2=CRITICAL).
    // We re-notify ONLY on strict escalation (level rises above the latched
    // level, e.g. slow->fast leak, or low->deflated pressure); a same-or-lower
    // reading never re-fires. So a persisting alarm produces at most one push
    // per severity step, and re-arms (latch back to 0) after the corner reads
    // normal for TYRE_ALARM_REARM_STREAK consecutive valid polls.
    private final int[] tyrePressureLatchedLevel = new int[4];
    // Monotone-threshold debounce: consecutive confirmed reads at or above each
    // severity. warnStreak counts level>=1 reads, critStreak counts level>=2.
    // A severity fires only once ITS OWN streak meets the required count — so a
    // lone transient deflation spike bumps critStreak to 1 (no CRITICAL) while
    // warnStreak stays continuous (WARN still holds), and a tyre flapping right
    // at the CRITICAL boundary still fires WARN (level>=1 is continuous) without
    // firing a flappy CRITICAL. Escalation earns its own confirming reads
    // instead of inheriting the lower level's count.
    private final int[] tyrePressureWarnStreak = new int[4];
    private final int[] tyrePressureCritStreak = new int[4];
    private final int[] tyrePressureNormalStreak = new int[4];
    // Per corner: has the firmware pressureState enum EVER reported non-zero?
    // This tells us the enum channel is actually live on this firmware. On the
    // stuck-at-0 firmwares the kPa fallback exists to serve, this stays false
    // forever, so a bare enum==0 is NOT accepted as proof-of-normal for re-arm
    // (only a valid kPa reading is) — otherwise a kPa signal dropout, which
    // leaves enum stuck at 0, would masquerade as a normal read, re-arm the
    // latch, and let the SAME persisting low tyre notify again (duplicate).
    private final boolean[] tyrePressureEnumEverWorked = new boolean[4];
    // Per corner: consecutive reads where kPa was INVALID (getter null / sentinel)
    // while the corner was still being evaluated. kPa is the ground-truth channel,
    // so a SHORT dropout must HOLD the latch — we can't confirm the tyre recovered
    // without a real reading, and an intermittent enum lying with 0 during the
    // dropout must not re-arm us (that re-fires the same persisting low tyre =
    // duplicate). But once kPa has been dead for TYRE_KPA_DEAD_STREAK reads it is
    // treated as a dead channel, and we fall back to trusting a proven enum's 0
    // for re-arm — otherwise a firmware whose kPa dies (or a single spurious
    // startup kPa reading) would latch the corner forever and suppress every
    // future alarm on it (a missed SAFETY alarm, worse than a duplicate).
    private final int[] tyrePressureKpaInvalidStreak = new int[4];
    private final int[] tyreLeakLatchedSeverity = new int[4];
    private final int[] tyreLeakNormalStreak = new int[4];
    private final Object tyreAlarmLock = new Object();

    // kPa numeric thresholds — mirror the vehicle-control.js corner colouring
    // (PSI = kPa * 0.1450377): warn below ~34 PSI / above ~45 PSI, critical
    // below ~22 PSI (deflated). These are the SAFETY NET for firmwares whose
    // getTyrePressureState enum stays stuck at 0 even when a tyre is clearly
    // low — the exact case the old enum-only notifier missed.
    private static final int TYRE_PRESSURE_LOW_WARN_KPA = 234;   // ~34 PSI
    private static final int TYRE_PRESSURE_HIGH_WARN_KPA = 310;  // ~45 PSI
    private static final int TYRE_PRESSURE_LOW_ALERT_KPA = 152;  // ~22 PSI (deflated)
    // Consecutive-read debounce so a single transient kPa sample (hard cornering,
    // temperature spike) can't fire, and a single blip can't re-arm.
    private static final int TYRE_ALARM_FIRE_STREAK = 2;
    private static final int TYRE_ALARM_REARM_STREAK = 2;
    // How many consecutive invalid-kPa reads before kPa is considered a DEAD
    // channel (vs. a transient dropout). Above this, a proven-live enum's 0 is
    // allowed to re-arm the latch again. Kept comfortably above the dropout
    // lengths seen in practice so a brief signal gap can't re-enable enum re-arm
    // and re-fire a persisting alarm, while a genuinely dead kPa channel still
    // recovers its ability to re-arm within a few polls.
    private static final int TYRE_KPA_DEAD_STREAK = 4;

    private final int[] tyreTemperatureCache = new int[]{
            BydVehicleData.UNAVAILABLE, BydVehicleData.UNAVAILABLE,
            BydVehicleData.UNAVAILABLE, BydVehicleData.UNAVAILABLE
    };
    private volatile boolean loggedTyreSlot0 = false;
    private volatile boolean loggedInstrumentTyreTemp = false;
    // Per-wheel one-shot log. We surface the FIRST onTyreBatteryValueChanged
    // arrival for each wheel at info level so it's obvious from a single log
    // pull whether the BYD HAL is delivering temperature events at all on
    // this vehicle. Without this, a silent firmware looks identical to a
    // working firmware where we just haven't received an event yet.
    private final boolean[] loggedTyreFirstEvent = new boolean[]{false, false, false, false};
    private volatile boolean loggedTyreOutOfRange = false;

    private void logTyreAlertsIfChanged(int[] pressuresKpa, int[] pressureStates,
                                        int[] airLeakStates, int[] signalStates,
                                        int sysState, int tempState) {
        // Notification emit — delegated to a per-corner LATCHED state machine
        // (see evaluateTyreAlarms). Unlike the old 0->non-zero enum edge, this:
        //   - fires for a tyre already low at daemon start (no baseline miss),
        //   - falls back to kPa thresholds when the firmware alarm enum is
        //     stuck at 0 (the common BYD-HAL failure mode our own UI already
        //     works around), and
        //   - never emits a duplicate: one push per severity step, re-armed
        //     only after the corner reads normal again.
        // Runs BEFORE the change-detection early-return below, which only
        // throttles the diagnostic log — the state machine must observe every
        // poll so its normal-streak re-arm counter advances and a tyre that
        // stays low across the very first poll still notifies once.
        evaluateTyreAlarms(pressuresKpa, pressureStates, airLeakStates);

        // Pressures fluctuate constantly (heat, drive cycle). Only treat as
        // "changed" if any wheel moves more than 5 kPa; otherwise the log
        // would fire every poll on a moving car.
        boolean pressureChanged = lastTyrePressuresKpa == null;
        if (!pressureChanged) {
            for (int i = 0; i < pressuresKpa.length; i++) {
                if (Math.abs(pressuresKpa[i] - lastTyrePressuresKpa[i]) > 5) {
                    pressureChanged = true;
                    break;
                }
            }
        }
        boolean alertChanged =
                !java.util.Arrays.equals(pressureStates, lastTyrePressureStates)
                || !java.util.Arrays.equals(airLeakStates, lastTyreAirLeakStates)
                || !java.util.Arrays.equals(signalStates, lastTyreSignalStates)
                || sysState != lastTyreSystemState
                || tempState != lastTyreTemperatureState;
        if (!pressureChanged && !alertChanged) return;

        lastTyrePressuresKpa = pressuresKpa.clone();
        lastTyrePressureStates = pressureStates.clone();
        lastTyreAirLeakStates = airLeakStates.clone();
        lastTyreSignalStates = signalStates.clone();
        lastTyreSystemState = sysState;
        lastTyreTemperatureState = tempState;
        // Per-wheel readout: kPa, alarm-state enum (0=NORMAL/1=UNDER/2=OVER),
        // leak-state enum (0=Normal/1=Slow/2=Fast), signal-state enum (0=OK/1=Err)
        StringBuilder sb = new StringBuilder("Tyre:");
        String[] labels = {" FL", " FR", " RL", " RR"};
        for (int i = 0; i < 4; i++) {
            sb.append(labels[i]).append("=").append(pressuresKpa[i]).append("kPa");
            sb.append("/alarm=").append(pressureStates[i]);
            sb.append("/leak=").append(airLeakStates[i]);
            sb.append("/sig=").append(signalStates[i]);
        }
        sb.append(" sys=").append(sysState == Integer.MIN_VALUE ? "n/a" : sysState);
        sb.append(" temp=").append(tempState == Integer.MIN_VALUE ? "n/a" : tempState);
        logger.info(sb.toString());
    }

    private static String tyreWheelLabel(int wheel) {
        switch (wheel) {
            case 0: return Messages.get("notifications.area_front_left");
            case 1: return Messages.get("notifications.area_front_right");
            case 2: return Messages.get("notifications.area_rear_left");
            case 3: return Messages.get("notifications.area_rear_right");
            default: return Messages.get("notifications.area_door_n", wheel + 1);
        }
    }

    /**
     * Per-corner latched tyre-alarm evaluator. Called on every tyre read (poll
     * or async listener), it fires a push at most ONCE per severity step and
     * re-arms only after the corner returns to normal — so a persisting alarm
     * never spams, but a fresh or escalating one always notifies.
     *
     * <p><b>Pressure severity</b> is the max of the firmware enum and the kPa
     * fallback:
     * <ul>
     *   <li>2 (CRITICAL): kPa &le; {@link #TYRE_PRESSURE_LOW_ALERT_KPA} (deflated)</li>
     *   <li>1 (WARN): firmware pressureState != 0, OR kPa outside
     *       [{@link #TYRE_PRESSURE_LOW_WARN_KPA}, {@link #TYRE_PRESSURE_HIGH_WARN_KPA}]</li>
     *   <li>0: normal</li>
     * </ul>
     * <b>Leak severity</b> comes straight from the firmware air-leak enum
     * (1=slow/WARN, 2=fast/CRITICAL) — there is no kPa proxy for a leak.
     *
     * <p>A value of -1 (getter returned null / signal dropout) is treated as
     * "no data": it neither fires an alarm nor counts toward the normal-streak
     * re-arm, so a transient dropout can't emit a bogus "-1 kPa" push and can't
     * silently re-arm a latch mid-alarm.
     */
    private void evaluateTyreAlarms(int[] pressuresKpa, int[] pressureStates, int[] airLeakStates) {
        if (pressuresKpa == null || pressureStates == null || airLeakStates == null) return;
        synchronized (tyreAlarmLock) {
            for (int i = 0; i < 4; i++) {
                int kPa = i < pressuresKpa.length ? pressuresKpa[i] : -1;
                int pState = i < pressureStates.length ? pressureStates[i] : -1;
                int leak = i < airLeakStates.length ? airLeakStates[i] : -1;

                evaluatePressureCorner(i, kPa, pState);
                evaluateLeakCorner(i, kPa, leak);
            }
        }
    }

    /** Pressure alarm for one corner. Caller holds tyreAlarmLock. */
    private void evaluatePressureCorner(int i, int kPa, int pState) {
        // kPa is valid only when the getter answered with a real reading.
        // BydVehicleData.UNAVAILABLE (Integer.MIN_VALUE) and the -1 dropout
        // sentinel both mean "no pressure data". <=0 is never a real tyre.
        boolean kPaValid = kPa > 0 && kPa != BydVehicleData.UNAVAILABLE;
        boolean stateValid = pState >= 0; // -1 = getter returned null
        boolean enumAlarm = stateValid && pState != 0;
        if (enumAlarm) tyrePressureEnumEverWorked[i] = true;
        // Track kPa channel liveness: reset on any valid reading, count up while
        // it's invalid. A single valid read clears the "dead" state, so one
        // spurious startup blip can't poison re-arm forever.
        if (kPaValid) tyrePressureKpaInvalidStreak[i] = 0;
        else if (tyrePressureKpaInvalidStreak[i] < Integer.MAX_VALUE) tyrePressureKpaInvalidStreak[i]++;

        // No usable signal at all this poll — don't advance either streak so a
        // dropout neither fires nor re-arms; hold the latch as-is.
        if (!kPaValid && !stateValid) return;

        // Severity = max(firmware enum, kPa fallback). The kPa net is what
        // catches firmwares whose pressureState stays stuck at 0. Direction
        // (under/over) is taken from kPa when we have a real reading — the kPa
        // is ground truth; the enum's 1=UNDER/2=OVER is only a fallback for the
        // direction word when kPa is absent, and it can disagree with reality
        // on a misreporting firmware, so kPa wins when present.
        int level = 0;
        boolean under = false, over = false;
        if (enumAlarm) {
            level = 1;
            if (pState == 2) over = true; else under = true;
        }
        if (kPaValid) {
            if (kPa <= TYRE_PRESSURE_LOW_ALERT_KPA) { level = 2; under = true; over = false; }
            else if (kPa < TYRE_PRESSURE_LOW_WARN_KPA) { level = Math.max(level, 1); under = true; over = false; }
            else if (kPa > TYRE_PRESSURE_HIGH_WARN_KPA) { level = Math.max(level, 1); over = true; under = false; }
            // else: kPa is in the normal band. It does NOT clear an enum alarm
            // (level stays 1 from enumAlarm above) — the firmware enum is
            // authoritative for "there is a problem"; we just can't name a kPa
            // direction, so leave the enum's under/over.
        }

        if (level == 0) {
            // Normal reading — but re-arm ONLY on positive proof of normal, not
            // mere absence of an alarm. Channel priority mirrors firing: kPa is
            // ground truth, the enum is only trusted where kPa isn't answering.
            //   - kPa valid & in-band  -> confirmed normal.
            //   - kPa dead (invalid for >= TYRE_KPA_DEAD_STREAK reads) but a
            //     proven-live enum reads 0 -> confirmed normal (enum is all we
            //     have; refusing forever would suppress every future alarm on a
            //     firmware whose kPa channel died = missed safety alarm).
            //   - kPa merely dropped out briefly (invalid but under the dead
            //     threshold) -> NOT confirmed, even if a proven enum reads 0:
            //     without a real kPa we can't tell the tyre recovered, and an
            //     intermittent enum lying with 0 during the dropout would
            //     otherwise re-arm and re-fire the same persisting low tyre
            //     (duplicate). Hold the latch instead.
            //   - stuck-at-0 enum never proven + kPa invalid -> NOT confirmed.
            boolean kPaDead = tyrePressureKpaInvalidStreak[i] >= TYRE_KPA_DEAD_STREAK;
            boolean normalConfirmed = kPaValid
                    || (stateValid && tyrePressureEnumEverWorked[i] && kPaDead);
            if (!normalConfirmed) return; // hold latch + streaks; no re-arm, no reset

            tyrePressureWarnStreak[i] = 0;
            tyrePressureCritStreak[i] = 0;
            if (tyrePressureLatchedLevel[i] != 0) {
                if (++tyrePressureNormalStreak[i] >= TYRE_ALARM_REARM_STREAK) {
                    tyrePressureLatchedLevel[i] = 0;
                    tyrePressureNormalStreak[i] = 0;
                    logger.info("Tyre pressure re-armed (normal): " + tyreWheelLabel(i)
                            + (kPaValid ? " " + kPa + " kPa" : ""));
                }
            }
            return;
        }

        // Abnormal reading. Advance monotone per-severity streaks: any abnormal
        // read (level>=1) advances warnStreak; only a deflated read (level==2)
        // advances critStreak. A read that drops from CRITICAL back to WARN
        // resets critStreak (the deflation didn't persist) while warnStreak
        // keeps climbing — so boundary flapping still holds WARN and a lone
        // deflation spike can't fire CRITICAL. A firmware-enum alarm is already
        // debounced by the TPMS firmware, so it fires on the first read;
        // the noisier kPa-only fallback waits for a confirming read.
        tyrePressureNormalStreak[i] = 0;
        tyrePressureWarnStreak[i]++;
        if (level >= 2) tyrePressureCritStreak[i]++; else tyrePressureCritStreak[i] = 0;

        // WARN may fire on the first read when the firmware enum confirms it
        // (already firmware-debounced); a kPa-only WARN waits for a confirming
        // read. CRITICAL is ALWAYS kPa-derived (level 2 comes only from
        // kPa<=deflated threshold, never from the enum), so it ALWAYS waits for
        // a confirming read — a single transient deflation sample never fires
        // CRITICAL, even when an enum WARN alarm is simultaneously active.
        int warnRequired = enumAlarm ? 1 : TYRE_ALARM_FIRE_STREAK;
        int critRequired = TYRE_ALARM_FIRE_STREAK;

        // The severity we're allowed to fire is the HIGHEST whose own streak has
        // met its required count. Check CRITICAL first, then WARN.
        int fireLevel = 0;
        if (level >= 2 && tyrePressureCritStreak[i] >= critRequired) fireLevel = 2;
        else if (tyrePressureWarnStreak[i] >= warnRequired) fireLevel = 1;

        // Notify only on strict escalation above the latched level. A
        // same-or-lower fireLevel while already latched is the persisting alarm
        // — stay silent.
        if (fireLevel > tyrePressureLatchedLevel[i]) {
            tyrePressureLatchedLevel[i] = fireLevel;
            String kPaText = kPaValid ? (kPa + " kPa")
                    : Messages.get("notifications.tyre_no_reading");
            String title = fireLevel >= 2
                    ? Messages.get("notifications.tyre_critically_low")
                    : Messages.get(over
                            ? "notifications.tyre_overpressure"
                            : "notifications.tyre_underpressure");
            com.overdrive.app.notifications.NotificationEvent.Severity sev = fireLevel >= 2
                    ? com.overdrive.app.notifications.NotificationEvent.Severity.CRITICAL
                    : com.overdrive.app.notifications.NotificationEvent.Severity.WARN;
            try {
                org.json.JSONObject data = new org.json.JSONObject();
                data.put("wheel", i);
                if (kPaValid) data.put("kPa", kPa);
                if (stateValid) data.put("state", pState);
                data.put("level", fireLevel);
                com.overdrive.app.notifications.NotificationBus.get().publish(
                        new com.overdrive.app.notifications.NotificationEvent(
                                "vehicle.health.tyre.pressure",
                                sev,
                                title,
                                Messages.get("notifications.tyre_wheel_reading",
                                        tyreWheelLabel(i), kPaText),
                                "tyre-pressure-" + i,
                                null,
                                data));
            } catch (Throwable t) {
                // Roll the latch back so a publish failure doesn't permanently
                // suppress this corner — next abnormal read retries.
                tyrePressureLatchedLevel[i] = 0;
                logger.debug("tyre.pressure notify failed: " + t.getMessage());
            }
        }
    }

    /** Leak alarm for one corner. Caller holds tyreAlarmLock. */
    private void evaluateLeakCorner(int i, int kPa, int leak) {
        if (leak < 0) return; // getter returned null / dropout — hold latch, no re-arm

        if (leak == 0) {
            if (tyreLeakLatchedSeverity[i] != 0) {
                if (++tyreLeakNormalStreak[i] >= TYRE_ALARM_REARM_STREAK) {
                    tyreLeakLatchedSeverity[i] = 0;
                    tyreLeakNormalStreak[i] = 0;
                    logger.info("Tyre leak re-armed (normal): " + tyreWheelLabel(i));
                }
            }
            return;
        }

        // leak >= 1. Fire once on first detection and once more on escalation
        // (slow=1 -> fast=2); a same-or-lower severity while latched is silent.
        tyreLeakNormalStreak[i] = 0;
        if (leak > tyreLeakLatchedSeverity[i]) {
            tyreLeakLatchedSeverity[i] = leak;
            boolean kPaValid = kPa > 0 && kPa != BydVehicleData.UNAVAILABLE;
            com.overdrive.app.notifications.NotificationEvent.Severity sev = leak == 2
                    ? com.overdrive.app.notifications.NotificationEvent.Severity.CRITICAL
                    : com.overdrive.app.notifications.NotificationEvent.Severity.WARN;
            try {
                org.json.JSONObject data = new org.json.JSONObject();
                data.put("wheel", i);
                data.put("leakState", leak);
                if (kPaValid) data.put("kPa", kPa);
                com.overdrive.app.notifications.NotificationBus.get().publish(
                        new com.overdrive.app.notifications.NotificationEvent(
                                "vehicle.health.tyre.leak",
                                sev,
                                Messages.get(leak == 2
                                        ? "notifications.tyre_fast_leak"
                                        : "notifications.tyre_slow_leak"),
                                kPaValid
                                        ? Messages.get("notifications.tyre_wheel_pressure",
                                                tyreWheelLabel(i), kPa)
                                        : tyreWheelLabel(i),
                                "tyre-leak-" + i,
                                null,
                                data));
            } catch (Throwable t) {
                tyreLeakLatchedSeverity[i] = 0;
                logger.debug("tyre.leak notify failed: " + t.getMessage());
            }
        }
    }

    // Per-wheel temperature poll: candidate (device, method, slot-mapping)
    // tuples, in priority order. Each candidate names a getter on either
    // tyreDevice or instrumentDevice plus a per-corner slot map, because the
    // two HALs use DIFFERENT wheel-index conventions:
    //   tyreDevice.getTyreXxx(int):       1=LF, 2=RF, 3=LR, 4=RR
    //   instrumentDevice.getWheelTemperature(int): 1=RF, 2=RR, 3=LF, 4=LR
    // Cache layout is fixed at [FL=0, FR=1, RL=2, RR=3]; each candidate's
    // slotForCacheIdx[i] gives the int to pass for cache slot i.
    //
    // On the first poll we look up each via reflection; from then on we go
    // straight to the surviving method (or short-circuit if none exist on
    // this firmware). This means a sensor that wakes up later still gets a
    // chance to report — we only lock out based on method-existence, not on
    // whether a value was in range.
    private static final int DEV_TYRE = 0;
    private static final int DEV_INSTRUMENT = 1;
    private static final class TyreTempCandidate {
        final int deviceKind;
        final String methodName;
        final int[] slotForCacheIdx; // index by [FL=0, FR=1, RL=2, RR=3]
        TyreTempCandidate(int deviceKind, String methodName, int[] slotForCacheIdx) {
            this.deviceKind = deviceKind;
            this.methodName = methodName;
            this.slotForCacheIdx = slotForCacheIdx;
        }
    }
    private static final int[] TYRE_DEVICE_SLOTS       = {1, 2, 3, 4}; // identity
    private static final int[] INSTRUMENT_DEVICE_SLOTS = {3, 1, 4, 2}; // FL=slot3, FR=slot1, RL=slot4, RR=slot2
    private static final TyreTempCandidate[] TYRE_TEMP_CANDIDATES = {
            // Instrument-side first: confirmed to return real per-corner °C
            // on firmwares where every tyreDevice candidate returns null.
            new TyreTempCandidate(DEV_INSTRUMENT, "getWheelTemperature",     INSTRUMENT_DEVICE_SLOTS),
            // tyreDevice fallbacks — order preserves prior behaviour.
            new TyreTempCandidate(DEV_TYRE,       "getTyreBatteryValue",      TYRE_DEVICE_SLOTS),
            new TyreTempCandidate(DEV_TYRE,       "getTyreTemperatureValue",  TYRE_DEVICE_SLOTS),
            new TyreTempCandidate(DEV_TYRE,       "getTyreTemperature",       TYRE_DEVICE_SLOTS),
            new TyreTempCandidate(DEV_TYRE,       "getTyreTemperatureState",  TYRE_DEVICE_SLOTS),
    };
    // null = not resolved yet (first cycle still running),
    // != null and != NO_TYRE_TEMP_GETTER = the resolved Method,
    // == NO_TYRE_TEMP_GETTER sentinel = no candidate exists on this firmware.
    private static final java.lang.reflect.Method NO_TYRE_TEMP_GETTER;
    static {
        java.lang.reflect.Method m = null;
        try { m = Object.class.getDeclaredMethod("toString"); }
        catch (NoSuchMethodException e) { /* impossible */ }
        NO_TYRE_TEMP_GETTER = m;
    }
    private volatile java.lang.reflect.Method resolvedTyreTempMethod = null;
    // Index into TYRE_TEMP_CANDIDATES: which candidate is currently resolved.
    // When the resolved method consistently returns out-of-range values, we advance
    // to the next candidate. This ensures getTyreBatteryValue returning battery voltage
    // doesn't permanently block getTyreTemperatureState from being tried.
    private volatile int resolvedTyreTempCandidateIdx = 0;
    private volatile int tyreTempOutOfRangeCount = 0;
    private static final int TYRE_TEMP_OUT_OF_RANGE_THRESHOLD = 5; // after 5 bad reads, try next
    private final boolean[] loggedTyrePollHit = new boolean[]{false, false, false, false};
    // Diagnostics: surface the FIRST observation per failure mode so a single
    // log capture tells us which path the BYD HAL is taking.
    private volatile boolean loggedTyrePollNullReturn = false;
    private volatile boolean loggedTyrePollNonNumber = false;
    private volatile boolean loggedTyrePollThrew = false;

    /**
     * Try to read per-wheel temperature via a getter call. On firmwares where
     * the {@code onTyreBatteryValueChanged} async callback never fires, a
     * polled getter is the only remaining channel.
     *
     * <p>The first invocation looks up each candidate method by name; the
     * first one that exists is cached and used from then on. We do NOT
     * require a value in range to "resolve" the getter — a sensor that's
     * temporarily out of signal can still come online later.
     */
    private void pollPerWheelTyreTemp(int idx) {
        java.lang.reflect.Method method = resolvedTyreTempMethod;
        if (method == NO_TYRE_TEMP_GETTER) return;
        if (method == null) {
            method = resolveTyreTempMethod();
            resolvedTyreTempMethod = method;
            if (method == NO_TYRE_TEMP_GETTER) return;
        }
        if (resolvedTyreTempCandidateIdx >= TYRE_TEMP_CANDIDATES.length) return;

        TyreTempCandidate cand = TYRE_TEMP_CANDIDATES[resolvedTyreTempCandidateIdx];
        Object device = (cand.deviceKind == DEV_INSTRUMENT) ? instrumentDevice : tyreDevice;
        if (device == null) {
            // The candidate's device was nulled out after resolution (init
            // failure, device unavailable). Advance so the next poll picks
            // a candidate whose device is still alive.
            resolvedTyreTempCandidateIdx++;
            resolvedTyreTempMethod = null;
            return;
        }
        int wheel = cand.slotForCacheIdx[idx];

        Object raw;
        try {
            raw = method.invoke(device, wheel);
        } catch (Throwable t) {
            if (!loggedTyrePollThrew) {
                loggedTyrePollThrew = true;
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") threw " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
            }
            return; // transient failure; method stays resolved for next cycle
        }
        if (raw == null) {
            if (!loggedTyrePollNullReturn) {
                loggedTyrePollNullReturn = true;
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") returned null — getter exists but firmware has no value");
            }
            return;
        }
        if (!(raw instanceof Number)) {
            if (!loggedTyrePollNonNumber) {
                loggedTyrePollNonNumber = true;
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") returned " + raw.getClass().getSimpleName() + " = " + raw);
            }
            return;
        }
        double v = ((Number) raw).doubleValue();
        if (!(v >= -40.0 && v <= 125.0)) {
            tyreTempOutOfRangeCount++;
            if (tyreTempOutOfRangeCount == 1) {
                // Log on first occurrence
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") returned " + v + " — outside temperature range, "
                        + "this firmware reports battery voltage via this getter. "
                        + "Will try next candidate after " + TYRE_TEMP_OUT_OF_RANGE_THRESHOLD + " bad reads.");
            }
            if (tyreTempOutOfRangeCount >= TYRE_TEMP_OUT_OF_RANGE_THRESHOLD) {
                // This method consistently returns garbage — advance to next candidate
                tyreTempOutOfRangeCount = 0;
                resolvedTyreTempCandidateIdx++;
                resolvedTyreTempMethod = null; // force re-resolution from next candidate
                logger.info("Tyre temp poll: " + method.getName()
                        + " returned out-of-range " + TYRE_TEMP_OUT_OF_RANGE_THRESHOLD
                        + " times — advancing to next candidate (idx="
                        + resolvedTyreTempCandidateIdx + ")");
            }
            return;
        }
        // Valid reading — reset the out-of-range counter
        tyreTempOutOfRangeCount = 0;

        int tempC = (int) Math.round(v);
        synchronized (tyreTemperatureCache) {
            tyreTemperatureCache[idx] = tempC;
        }
        if (!loggedTyrePollHit[idx]) {
            loggedTyrePollHit[idx] = true;
            logger.info("Tyre temp poll FIRST: wheel=" + wheel
                    + " (" + new String[]{"FL","FR","RL","RR"}[idx] + ") via "
                    + method.getName() + " = " + tempC + "°C");
        }
    }

    private java.lang.reflect.Method resolveTyreTempMethod() {
        for (int i = resolvedTyreTempCandidateIdx; i < TYRE_TEMP_CANDIDATES.length; i++) {
            TyreTempCandidate cand = TYRE_TEMP_CANDIDATES[i];
            Object device = (cand.deviceKind == DEV_INSTRUMENT) ? instrumentDevice : tyreDevice;
            if (device == null) continue; // device unavailable on this firmware
            try {
                java.lang.reflect.Method m = device.getClass().getMethod(cand.methodName, int.class);
                resolvedTyreTempCandidateIdx = i;
                logger.info("Tyre temp poll: using " + cand.methodName + "(int) on "
                        + device.getClass().getSimpleName() + " (candidate idx=" + i + ")");
                return m;
            } catch (NoSuchMethodException ignored) { /* try next */ }
        }
        StringBuilder tried = new StringBuilder();
        for (int i = 0; i < TYRE_TEMP_CANDIDATES.length; i++) {
            if (i > 0) tried.append(", ");
            tried.append(TYRE_TEMP_CANDIDATES[i].deviceKind == DEV_INSTRUMENT ? "instrument." : "tyre.");
            tried.append(TYRE_TEMP_CANDIDATES[i].methodName);
        }
        logger.info("Tyre temp poll: no getter on this firmware "
                + "(tried " + tried + " starting from idx=" + resolvedTyreTempCandidateIdx + ")");
        return NO_TYRE_TEMP_GETTER;
    }

    private int[] snapshotTyreTemperatures() {
        synchronized (tyreTemperatureCache) {
            return new int[]{
                    tyreTemperatureCache[0], tyreTemperatureCache[1],
                    tyreTemperatureCache[2], tyreTemperatureCache[3]
            };
        }
    }

    // Diagnostic: log each unknown tyre feature ID at most once, capped at 32
    // unique IDs total. The cap prevents a chatty HAL (some emit a feature ID
    // every 100ms for trip metrics) from flooding the log if an unknown one
    // happens to slip through the listener filter.
    private final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> loggedUnknownTyreIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_UNKNOWN_TYRE_IDS = 32;

    private void logUnknownTyreEventOnce(int eventId, int rawInt, double rawDbl) {
        if (loggedUnknownTyreIds.size() >= MAX_UNKNOWN_TYRE_IDS) return;
        if (loggedUnknownTyreIds.putIfAbsent(eventId, Boolean.TRUE) != null) return;
        logger.info("Tyre event UNKNOWN id=" + eventId
                + " intValue=" + (rawInt == Integer.MIN_VALUE ? "n/a" : Integer.toString(rawInt))
                + " doubleValue=" + (Double.isNaN(rawDbl) ? "n/a" : Double.toString(rawDbl))
                + " — if this looks like a temperature, add it to BydFeatureIds.INSTRUMENT_*_TYRE_TEMPERATURE");
    }

    // Per-wheel out-of-range counters for the known LF/RF/LB/RB feature IDs.
    // Logged once per wheel so a sleeping TPMS sensor doesn't spam.
    private final boolean[] loggedTyreEventOutOfRange = new boolean[]{false, false, false, false};

    private void logTyreEventOutOfRangeOnce(int eventId, int wheelIdx, int rawInt, double rawDbl) {
        if (wheelIdx < 0 || wheelIdx > 3 || loggedTyreEventOutOfRange[wheelIdx]) return;
        loggedTyreEventOutOfRange[wheelIdx] = true;
        logger.info("Tyre event OUT-OF-RANGE: " + new String[]{"FL","FR","RL","RR"}[wheelIdx]
                + " (id=" + eventId + ") intValue="
                + (rawInt == Integer.MIN_VALUE ? "n/a" : Integer.toString(rawInt))
                + " doubleValue=" + (Double.isNaN(rawDbl) ? "n/a" : Double.toString(rawDbl))
                + " — sensor likely asleep; will retry silently on next event.");
    }

    private void onTyreCallback(String method, Object[] args) {
        if (args == null) return;
        try {
            // Generic feature-ID event from the 2-arg listener registration.
            // Per-wheel temperature on this firmware family arrives here keyed
            // on the LF/RF/LB/RB Instrument feature IDs. We accept either
            // intValue (some firmwares emit °C as an integer) or doubleValue
            // (others emit a fractional °C).
            if ("onDataEventChanged".equals(method) && args.length >= 2) {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int idx = -1;
                if (eventId == BydFeatureIds.INSTRUMENT_LF_TYRE_TEMPERATURE) idx = 0;
                else if (eventId == BydFeatureIds.INSTRUMENT_RF_TYRE_TEMPERATURE) idx = 1;
                else if (eventId == BydFeatureIds.INSTRUMENT_LB_TYRE_TEMPERATURE) idx = 2;
                else if (eventId == BydFeatureIds.INSTRUMENT_RB_TYRE_TEMPERATURE) idx = 3;

                if (idx < 0) {
                    // Unknown feature ID — log once per ID so the next log
                    // capture surfaces real per-wheel temperature IDs we
                    // can add to BydFeatureIds.
                    if (eventValue != null) {
                        int rawInt = BydDeviceHelper.getIntValue(eventValue);
                        double rawDbl = BydDeviceHelper.getDoubleValue(eventValue);
                        logUnknownTyreEventOnce(eventId, rawInt, rawDbl);
                    }
                    return;
                }

                // Known wheel — extract value, prefer the int slot.
                int rawInt = BydDeviceHelper.getIntValue(eventValue);
                double rawDbl = BydDeviceHelper.getDoubleValue(eventValue);
                Double tempC = null;
                if (rawInt != Integer.MIN_VALUE && rawInt >= -40 && rawInt <= 125) {
                    tempC = (double) rawInt;
                } else if (!Double.isNaN(rawDbl) && rawDbl >= -40.0 && rawDbl <= 125.0) {
                    tempC = rawDbl;
                }
                if (tempC == null) {
                    // Sentinel — TPMS hasn't reported this wheel yet, or
                    // the value lives in a slot we don't know about.
                    logTyreEventOutOfRangeOnce(eventId, idx, rawInt, rawDbl);
                    return;
                }
                int tempCi = (int) Math.round(tempC);
                synchronized (tyreTemperatureCache) {
                    tyreTemperatureCache[idx] = tempCi;
                }
                if (!loggedTyreFirstEvent[idx]) {
                    loggedTyreFirstEvent[idx] = true;
                    logger.info("Tyre event FIRST: " + new String[]{"FL","FR","RL","RR"}[idx]
                            + " (id=" + eventId + ") = " + tempCi + "°C");
                }
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    snapshot.set(current.toBuilder().tyreTemperature(snapshotTyreTemperatures()).build());
                }
                return;
            }

            if ("onTyreBatteryValueChanged".equals(method) && args.length >= 2) {
                int wheel = ((Number) args[0]).intValue();
                double value = ((Number) args[1]).doubleValue();
                if (wheel == 0) {
                    if (!loggedTyreSlot0) {
                        loggedTyreSlot0 = true;
                        logger.info("Tyre slot 0 raw event observed (value=" + value + ") — ignoring further slot 0");
                    }
                    return;
                }
                if (wheel < 1 || wheel > 4) {
                    logger.info("Tyre battery callback: unexpected wheel=" + wheel + " value=" + value);
                    return;
                }
                int idx = wheel - 1;
                if (!loggedTyreFirstEvent[idx]) {
                    loggedTyreFirstEvent[idx] = true;
                    logger.info("Tyre battery callback FIRST: wheel=" + wheel
                            + " (" + new String[]{"FL","FR","RL","RR"}[idx] + ") value=" + value);
                }
                if (!(value >= -40.0 && value <= 125.0)) {
                    if (!loggedTyreOutOfRange) {
                        loggedTyreOutOfRange = true;
                        logger.info("Tyre battery callback: value " + value
                                + " outside temperature range — likely battery voltage on this firmware");
                    }
                    return;
                }
                int tempC = (int) Math.round(value);
                synchronized (tyreTemperatureCache) {
                    tyreTemperatureCache[idx] = tempC;
                }
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    snapshot.set(current.toBuilder().tyreTemperature(snapshotTyreTemperatures()).build());
                }
                return;
            }

            if ("onTyrePressureValueChanged".equals(method)
                    || "onTyrePressureStateChanged".equals(method)
                    || "onTyreAirLeakStateChanged".equals(method)
                    || "onTyreSignalStateChanged".equals(method)
                    || "onTyreSystemStateChanged".equals(method)
                    || "onTyreTemperatureStateChanged".equals(method)
                    || "onIndirectTyreSystemStateChanged".equals(method)) {
                BydVehicleData current = snapshot.get();
                if (current == null) return;
                BydVehicleData.Builder b = current.toBuilder();
                collectTyre(b);
                snapshot.set(b.build());
            }
        } catch (Exception e) {
            logger.debug("onTyreCallback error (" + method + "): " + e.getMessage());
        }
    }

    // Last door-open state seen by the POLL fallback, per raw bodywork area (1..7).
    // Distinct from the event path: this lets the poll synthesize an edge only on a real
    // change. -1 = not yet read. Written only from the single BydDataPoll thread.
    private final java.util.Map<Integer, Integer> lastPolledDoorState = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * POLL-based door open/close fallback. The bodywork HAL delivers {@code onDoorStateChanged}
     * callbacks only while the vehicle is powered/awake — field reports show a parked car
     * stops pushing them, so a "when a door opens" automation never fired once the car was
     * off (the reported bug). The reference app reads door state on-demand via
     * {@code bodyworkDevice.getDoorState(area)} (area 1..7; 1=open, 0=closed, 255=unavailable),
     * which keeps working parked — so we poll it here every collect cycle and synthesize the
     * same {@link #notifyDoorStateListeners} edges the callback would, but only on a real
     * transition. The callback path stays primary (instant while awake); this only covers the
     * gap. Both feed {@link com.overdrive.app.automation.condition.DoorEvent}, whose
     * {@code Automations.update} is transition-gated, so an overlap can't double-fire.
     *
     * <p>Zero-cost when unused: skipped entirely unless at least one door-state listener is
     * registered (i.e. a door automation exists), so a car with no door rule pays nothing.
     */
    private void collectDoorStates(BydVehicleData.Builder b) {
        if (bodyworkDevice == null) return;
        // Only poll when something actually consumes door edges (a door automation).
        if (doorStateListeners.isEmpty()) return;
        for (int area = 1; area <= 7; area++) {
            try {
                Object v = BydDeviceHelper.callMethod(bodyworkDevice, "getDoorState", area);
                if (!(v instanceof Integer)) continue;
                int raw = (Integer) v;
                // Only 0 (closed) / 1 (open) are meaningful; 255/-1 = unavailable → skip so
                // an unreadable area never manufactures a spurious "closed" edge.
                if (raw != com.overdrive.app.byd.bodywork.BodyworkConstants.STATE_OPEN
                        && raw != com.overdrive.app.byd.bodywork.BodyworkConstants.STATE_CLOSED) continue;
                Integer prev = lastPolledDoorState.get(area);
                if (prev != null && prev == raw) continue; // no change
                lastPolledDoorState.put(area, raw);
                // Suppress the very first observed value (prev == null) ONLY when it's
                // "closed" — publishing a boot-time "closed" is a non-event and matches
                // the callback path's transition semantics; a first-seen "open" IS worth
                // delivering (a door left open while parked).
                if (prev == null && raw == com.overdrive.app.byd.bodywork.BodyworkConstants.STATE_CLOSED) continue;
                notifyDoorStateListeners(area, raw);
            } catch (Exception ignored) {
                // Getter absent on this trim → nothing to poll; leave to the callback path.
            }
        }
    }

    private void collectDoorLock(BydVehicleData.Builder b) {
        // The BYDAutoDoorLockDevice service does not expose lock state to
        // user-UID processes on most BYD firmwares — every getDoorLockStatus(area)
        // call returns INVALID(0) and onDoorLockStatusChanged never fires.
        // Field testing confirmed this on Sealion 6 / Atto 3 / others.
        //
        // Lock state is sourced exclusively from the BYD cloud REST/MQTT path
        // via BydCloudDataProvider. The vehicle-control page calls the cloud
        // API directly on load; the lock-gate uses CloudLockStateListener.
        //
        // We still publish a doorLockStatus[] array on the snapshot for
        // compatibility with downstream consumers, but with all-UNAVAILABLE
        // values — the cloud-lock fields on the JSON response carry the
        // authoritative state.
        if (b.doorLockStatus == null) {
            int[] locks = new int[7];
            for (int i = 0; i < 7; i++) locks[i] = -1;
            b.doorLockStatus(locks);
        }
    }

    private void collectSensor(BydVehicleData.Builder b) {
        if (sensorDevice == null) return;
        try {
            Object slope = BydDeviceHelper.callGetter(sensorDevice, "getSlope");
            if (slope instanceof Number) {
                int raw = ((Number) slope).intValue();
                double degrees = Math.toDegrees(Math.atan(raw / 100.0));
                if (degrees >= -60 && degrees <= 60) b.slopeDegrees(degrees);
            }
        } catch (Exception e) {
            logger.debug("collectSensor error: " + e.getMessage());
        }
    }

    private void collectEnergy(BydVehicleData.Builder b) {
        if (energyDevice == null) return;
        try {
            Object mode = BydDeviceHelper.callGetter(energyDevice, "getEnergyMode");
            if (mode instanceof Number) b.energyMode(((Number) mode).intValue());
            // Drive mode is published on the SETTING-device "drive config" axis
            // (1=normal/2=eco/3=sport/4=snow) so read and write share one axis and
            // NORMAL is representable. Prefer getDriveConfig; if that HAL getter is
            // absent, fall back to the energy-device getOperationMode (ECO=1/SPORT=2)
            // mapped UP to the config axis (1→2 eco, 2→3 sport) so eco/sport still
            // display correctly on builds without the drive-config getter.
            int driveConfig = getDriveConfigMode();
            // Only accept the valid config axis (1=normal..4=snow). Some firmwares
            // may return 0 as an "unset" sentinel; treat that (and the -1 read-fail)
            // as "no drive-config getter" and fall back to the energy getter, rather
            // than publishing a meaningless op_mode:0.
            if (driveConfig >= 1 && driveConfig <= 4) {
                b.operationMode(driveConfig);
            } else {
                Object opMode = BydDeviceHelper.callGetter(energyDevice, "getOperationMode");
                if (opMode instanceof Number) {
                    int energyAxis = ((Number) opMode).intValue();
                    // Energy axis → config axis: 0(normal)→1, 1(eco)→2, 2(sport)→3. Mapping
                    // 0→1 is what makes NORMAL readable on trims lacking getDriveConfig (the
                    // field report: eco/sport read fine via this fallback, but NORMAL never
                    // did because 0 was passed through as an out-of-band value that
                    // driveModeToString drops). Anything else passes through unchanged.
                    int mapped = energyAxis == 0 ? 1 : energyAxis == 1 ? 2 : energyAxis == 2 ? 3 : energyAxis;
                    b.operationMode(mapped);
                }
            }
            
            // SOC fallback: EnergyDevice.getElecPercentageValue() — try if statistic didn't provide SOC
            if (Double.isNaN(b.socPercent)) {
                Object elecPct = BydDeviceHelper.callGetter(energyDevice, "getElecPercentageValue");
                if (elecPct instanceof Number) {
                    double soc = ((Number) elecPct).doubleValue();
                    if (soc > 0 && soc <= 100) {
                        b.socPercent(soc);
                        logger.debug("SOC from EnergyDevice: " + soc + "%");
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("collectEnergy error: " + e.getMessage());
        }
    }

    private void collectRadar(BydVehicleData.Builder b) {
        if (radarDevice == null) return;
        try {
            Object distances = BydDeviceHelper.callGetter(radarDevice, "getAllRadarDistance");
            if (distances instanceof int[]) b.radarDistances((int[]) distances);
        } catch (Exception e) {
            logger.debug("collectRadar error: " + e.getMessage());
        }
    }

    // ==================== EXTENDED GETTERS ====================

    /**
     * Extended statistic data: OEM SOH, driving time, key battery level.
     * Called from collectAll() (core telemetry consumers need SOH).
     */
    private void collectStatisticExtended(BydVehicleData.Builder b) {
        if (statisticDevice == null) return;

        // OEM SOH: read for the b.sohPercent display fallback only. The
        // SohEstimator no longer consumes this signal — Shape B drives the
        // live SOH from the energy formula, calibration is a separate anchor.
        try {
            Integer sohValue = null;
            try {
                Object result = BydDeviceHelper.callGetter(statisticDevice, "getStatisticBatteryHealthyIndex");
                if (result instanceof Integer) {
                    sohValue = (Integer) result;
                } else if (result instanceof Double) {
                    sohValue = (int) ((Double) result).doubleValue();
                } else if (result instanceof Float) {
                    sohValue = (int) ((Float) result).floatValue();
                }
            } catch (NoSuchMethodError nsme) {
                // method missing — try feature ID below
            } catch (Exception e) {
                if (!(e.getCause() instanceof NoSuchMethodError)) {
                    logger.debug("SOH getter failed: " + e.getMessage());
                }
            }

            if (sohValue == null || sohValue < 0 || sohValue > 100) {
                try {
                    Object sohVal = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_BATTERY_HEALTHY_INDEX, Integer.class);
                    if (sohVal != null) {
                        int raw = BydDeviceHelper.getIntValue(sohVal);
                        if (raw >= 0 && raw <= 100) {
                            sohValue = raw;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("SOH feature ID failed: " + e.getMessage());
                }
            }

            if (sohValue != null && sohValue >= 0 && sohValue <= 100) {
                b.sohPercent(sohValue);
            }
        } catch (Exception e) {
            logger.debug("collectStatisticExtended SOH error: " + e.getMessage());
        }

        // Driving time
        try {
            Object drivingTime = BydDeviceHelper.callGetter(statisticDevice, "getDrivingTimeValue");
            if (drivingTime instanceof Number) {
                double hours = ((Number) drivingTime).doubleValue();
                if (hours >= 0) b.drivingTimeHours(hours);
            }
        } catch (Exception e) {
            logger.debug("collectStatisticExtended drivingTime error: " + e.getMessage());
        }

        // Key battery level
        try {
            Object keyBatt = BydDeviceHelper.callGetter(statisticDevice, "getKeyBatteryLevel");
            if (keyBatt instanceof Number) {
                b.keyBatteryLevel(((Number) keyBatt).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectStatisticExtended keyBattery error: " + e.getMessage());
        }
    }

    /**
     * Extended instrument data: cabin temp, trip data, consumption.
     * Called from collectAll() (ABRP/MQTT/trips consume these).
     */
    private void collectInstrumentExtended(BydVehicleData.Builder b) {
        // Inside cabin temperature from AC device
        try {
            if (acDevice != null) {
                Object insideTemp = BydDeviceHelper.callGet(acDevice, BydFeatureIds.AC_TEMP_INSIDE, Integer.class);
                if (insideTemp != null) {
                    int raw = BydDeviceHelper.getIntValue(insideTemp);
                    if (raw >= -40 && raw <= 60) b.insideTempCelsius(raw);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended insideTemp error: " + e.getMessage());
        }

        // Per-tyre temperature from InstrumentDevice via feature ID get() calls.
        // Slot mapping from BYDAutoFeatureIds.Instrument:
        //   LF_TYRE_TEMPERATURE, RF_TYRE_TEMPERATURE, LB_TYRE_TEMPERATURE, RB_TYRE_TEMPERATURE
        // These may return null on some firmware but are the correct channel on others.
        try {
            if (instrumentDevice != null) {
                int[] featureIds = BydFeatureIds.INSTRUMENT_TYRE_TEMP_IDS;
                // Order: LF=0, RF=1, LB(RL)=2, RB(RR)=3
                int[] tempResults = new int[4];
                boolean anyValid = false;
                for (int i = 0; i < featureIds.length; i++) {
                    Object result = BydDeviceHelper.callGet(instrumentDevice, featureIds[i], Integer.class);
                    if (result != null) {
                        int raw = BydDeviceHelper.getIntValue(result);
                        if (raw >= -40 && raw <= 125) {
                            tempResults[i] = raw;
                            anyValid = true;
                        } else {
                            tempResults[i] = Integer.MIN_VALUE;
                        }
                    } else {
                        tempResults[i] = Integer.MIN_VALUE;
                    }
                }
                if (anyValid) {
                    // Map: index 0=LF, 1=RF, 2=LB(RL), 3=RB(RR)
                    synchronized (tyreTemperatureCache) {
                        if (tempResults[0] != Integer.MIN_VALUE) tyreTemperatureCache[0] = tempResults[0];
                        if (tempResults[1] != Integer.MIN_VALUE) tyreTemperatureCache[1] = tempResults[1];
                        if (tempResults[2] != Integer.MIN_VALUE) tyreTemperatureCache[2] = tempResults[2];
                        if (tempResults[3] != Integer.MIN_VALUE) tyreTemperatureCache[3] = tempResults[3];
                    }
                    b.tyreTemperature(snapshotTyreTemperatures());
                    if (!loggedInstrumentTyreTemp) {
                        loggedInstrumentTyreTemp = true;
                        logger.info("Tyre temp from InstrumentDevice feature IDs: LF=" + tempResults[0]
                            + " RF=" + tempResults[1] + " RL=" + tempResults[2] + " RR=" + tempResults[3] + "°C");
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tyreTemp error: " + e.getMessage());
        }

        // Current trip mileage
        try {
            if (instrumentDevice != null) {
                Object tripMileage = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_MILEAGE, Double.class);
                if (tripMileage != null) {
                    double val = BydDeviceHelper.getDoubleValue(tripMileage);
                    if (!Double.isNaN(val) && val >= 0) b.currentTripMileageKm(val * distanceToKmFactor);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tripMileage error: " + e.getMessage());
        }

        // Current trip time
        try {
            if (instrumentDevice != null) {
                Object tripTime = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_TIME, Double.class);
                if (tripTime != null) {
                    double val = BydDeviceHelper.getDoubleValue(tripTime);
                    if (!Double.isNaN(val) && val >= 0) b.currentTripTimeHours(val);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tripTime error: " + e.getMessage());
        }

        // This trip electricity consumption from statistic device
        try {
            if (statisticDevice != null) {
                Object tripElec = BydDeviceHelper.callGet(statisticDevice,
                        BydFeatureIds.STAT_THIS_TRIP_ELEC_CONSUMPTION, Double.class);
                if (tripElec != null) {
                    double val = BydDeviceHelper.getDoubleValue(tripElec);
                    if (!Double.isNaN(val) && val >= 0) b.currentTripConsumptionKwh(val);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tripElecConsumption error: " + e.getMessage());
        }

        // Last 50km power consumption
        try {
            if (instrumentDevice != null) {
                Object last50km = BydDeviceHelper.callGetter(instrumentDevice, "getLast50KmPowerConsume");
                if (last50km instanceof Number) {
                    double val = ((Number) last50km).doubleValue();
                    if (val >= 0) b.last50KmConsumption(val);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended last50km error: " + e.getMessage());
        }
    }

    // ==================== KEY PROXIMITY ====================
    // Discrete key/fob proximity & authentication state probed from SettingDevice and
    // InstrumentDevice. Methods are read reflectively so missing ones (model variation)
    // don't break collection. Each value is the raw int from the SDK; UNAVAILABLE means
    // the method returned a sentinel (BMS unavailable / invalid) or wasn't present.
    //
    // Logged on every state transition and at most once per 5 minutes regardless,
    // so the log captures fob behaviour during parked / charging / approach windows.

    private volatile int lastKeyStartState = Integer.MIN_VALUE;
    private volatile int lastKeyMissingInd = Integer.MIN_VALUE;
    private volatile int lastKeyBtLowPowerMode = Integer.MIN_VALUE;
    private volatile int lastKeyPowerLowInd = Integer.MIN_VALUE;
    private volatile int lastKeyDetectionReminder = Integer.MIN_VALUE;
    private volatile int lastSmartKeyWarnState = Integer.MIN_VALUE;
    private volatile long lastKeyProbeLogMs = 0;

    private void collectKeyProximity(BydVehicleData.Builder b) {
        int startState = readKeyInt(settingDevice, "getStartKeyState");
        int missingInd = readKeyInt(settingDevice, "getMissKeyInd");
        int btLowPower = readKeyInt(settingDevice, "getIKEYBTLowPowerMode");
        int powerLow = readKeyInt(settingDevice, "getKeyPowerLowInd");
        int detectionRem = readKeyInt(instrumentDevice, "getKeyDetectionReminder");
        int warnState = readKeyInt(instrumentDevice, "getSmartKeySysWarnLightState");

        if (startState != BydVehicleData.UNAVAILABLE) b.keyStartState(startState);
        if (missingInd != BydVehicleData.UNAVAILABLE) b.keyMissingInd(missingInd);
        if (btLowPower != BydVehicleData.UNAVAILABLE) b.keyBtLowPowerMode(btLowPower);
        if (powerLow != BydVehicleData.UNAVAILABLE) b.keyPowerLowInd(powerLow);
        if (detectionRem != BydVehicleData.UNAVAILABLE) b.keyDetectionReminder(detectionRem);
        if (warnState != BydVehicleData.UNAVAILABLE) b.smartKeyWarnState(warnState);

        boolean changed =
            startState != lastKeyStartState
            || missingInd != lastKeyMissingInd
            || btLowPower != lastKeyBtLowPowerMode
            || powerLow != lastKeyPowerLowInd
            || detectionRem != lastKeyDetectionReminder
            || warnState != lastSmartKeyWarnState;

        long now = System.currentTimeMillis();
        boolean heartbeat = now - lastKeyProbeLogMs > 300_000;

        if (changed || heartbeat) {
            lastKeyStartState = startState;
            lastKeyMissingInd = missingInd;
            lastKeyBtLowPowerMode = btLowPower;
            lastKeyPowerLowInd = powerLow;
            lastKeyDetectionReminder = detectionRem;
            lastSmartKeyWarnState = warnState;
            lastKeyProbeLogMs = now;
            logger.info("KeyProbe: startState=" + fmtKeyVal(startState)
                + " missingInd=" + fmtKeyVal(missingInd)
                + " btLowPower=" + fmtKeyVal(btLowPower)
                + " powerLow=" + fmtKeyVal(powerLow)
                + " detectionReminder=" + fmtKeyVal(detectionRem)
                + " smartKeyWarn=" + fmtKeyVal(warnState)
                + " accIsOn=" + accIsOn
                + (changed ? " [CHANGE]" : " [hb]"));
        }
    }

    /**
     * Reflective single-int getter that filters BYD sentinel values
     * (BMS_UNAVAILABLE=65535, INVALID_VALUE=-10011, INVALID_VALUE_2=-10013).
     * Returns BydVehicleData.UNAVAILABLE if the method is missing, the device is
     * null, or the value is a known sentinel.
     */
    private static int readKeyInt(Object device, String methodName) {
        if (device == null) return BydVehicleData.UNAVAILABLE;
        try {
            java.lang.reflect.Method m = device.getClass().getMethod(methodName);
            Object result = m.invoke(device);
            if (!(result instanceof Number)) return BydVehicleData.UNAVAILABLE;
            int v = ((Number) result).intValue();
            if (v == 65535 || v == -10011 || v == -10013) return BydVehicleData.UNAVAILABLE;
            return v;
        } catch (NoSuchMethodException e) {
            return BydVehicleData.UNAVAILABLE;
        } catch (Exception e) {
            return BydVehicleData.UNAVAILABLE;
        }
    }

    private static String fmtKeyVal(int v) {
        return v == BydVehicleData.UNAVAILABLE ? "n/a" : Integer.toString(v);
    }

    /**
     * Extended charging data: charging rest time (time-to-full).
     * Called from collectAll() (every poll, so a live charge keeps it fresh)
     * and collectAllFull(). Acts as the fallback when the instrument feature-ID
     * read in collectInstrument() leaves rest time UNAVAILABLE.
     */
    private void collectChargingExtended(BydVehicleData.Builder b) {
        if (chargingDevice == null) return;

        // Fallback: chargingDevice.getChargingRestTime() when instrument feature IDs
        // didn't populate in collectInstrument(). Checks gun state first — if NONE, skip.
        if (b.chargingRestTimeHours == BydVehicleData.UNAVAILABLE) {
            try {
                if (b.chargingGunState != 1) {
                    Object restTime = BydDeviceHelper.callGetter(chargingDevice, "getChargingRestTime");
                    if (restTime instanceof int[]) {
                        int[] times = (int[]) restTime;
                        if (times.length >= 2) {
                            int hours = times[0];
                            int minutes = times[1];
                            if (hours != 255 && minutes != 255 && hours >= 0 && hours <= 23 && minutes >= 0 && minutes <= 59) {
                                b.chargingRestTimeHours(hours);
                                b.chargingRestTimeMinutes(minutes);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("collectChargingExtended restTime error: " + e.getMessage());
            }
        }
    }

    /**
     * Extended bodywork data: steering angle, auto system state, 12V level, sunroof, sunshade.
     * Called from collectAllFull() only (display-only, on-demand).
     */
    /**
     * Read the live steering-wheel angle into the builder. Extracted so the periodic
     * poll ({@link #collectAll}) can refresh it every cycle — {@code
     * collectBodyworkExtended} runs ONLY at init ({@code collectAllFull}), and the
     * live listener {@code handleSteeringAngleChanged} is unwired, so without this the
     * angle was seeded once at boot (wheel centered → 0) and carried forward forever:
     * the "steeringAngle" automation event never saw a transition and could never
     * fire. Guards the SDK sentinel + the same ±780° sanity range the listener uses.
     */
    private void collectSteeringAngle(BydVehicleData.Builder b) {
        if (bodyworkDevice == null) return;
        try {
            Object steering = BydDeviceHelper.callGetter(bodyworkDevice, "getSteeringWheelValue", 1);
            if (steering instanceof Number) {
                double angle = ((Number) steering).doubleValue();
                // Guard the SDK not-available sentinel and clamp to the physical range
                // (mirrors handleSteeringAngleChanged): a bogus value would false-fire
                // a steering-angle automation.
                if (angle != BydFeatureIds.SDK_NOT_AVAILABLE && angle >= -780 && angle <= 780) {
                    b.steeringAngleDegrees(angle);
                }
            }
        } catch (Exception e) {
            logger.debug("collectSteeringAngle error: " + e.getMessage());
        }
    }

    private void collectBodyworkExtended(BydVehicleData.Builder b) {
        if (bodyworkDevice == null) return;

        // Steering wheel angle (shared with the periodic poll via collectSteeringAngle).
        collectSteeringAngle(b);

        // Auto system state (0=normal, 1=set_secure, 2=start_secure)
        try {
            Object autoState = BydDeviceHelper.callGetter(bodyworkDevice, "getAutoSystemState");
            if (autoState instanceof Number) {
                b.autoSystemState(((Number) autoState).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended autoSystemState error: " + e.getMessage());
        }

        // 12V battery voltage level (LOW/NORMAL/INVALID)
        try {
            Object battLevel = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryVoltageLevel");
            if (battLevel instanceof Number) {
                b.battery12vLevel(((Number) battLevel).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended battery12vLevel error: " + e.getMessage());
        }

        // Sunroof state (if available)
        try {
            Object sunroof = BydDeviceHelper.callGetter(bodyworkDevice, "getSunroofState");
            if (sunroof instanceof Number) {
                b.sunroofState(((Number) sunroof).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended sunroofState error: " + e.getMessage());
        }

        // Front wiper state (raw). Per the OEM firmware the wiper getters live on the
        // BODYWORK device (not a dedicated wiper device). Raw int; consumers threshold
        // it (0/off vs any active level). Populates the pre-existing wiperState field.
        try {
            Object wiper = BydDeviceHelper.callGetter(bodyworkDevice, "getFrontWiperState");
            if (wiper instanceof Number) {
                b.wiperState(((Number) wiper).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended frontWiperState error: " + e.getMessage());
        }
        // Auto-wiper (rain-sensing wipe) enabled — the closest "it's raining" proxy this
        // platform exposes; there is no rain-intensity sensor. getAutoWiperState: 1=on.
        try {
            Object autoWiper = BydDeviceHelper.callGetter(bodyworkDevice, "getAutoWiperState");
            if (autoWiper instanceof Number) {
                b.autoWiperState(((Number) autoWiper).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended autoWiperState error: " + e.getMessage());
        }

        // Sunroof position (if available)
        try {
            Object sunroofPos = BydDeviceHelper.callGetter(bodyworkDevice, "getSunroofPosition");
            if (sunroofPos instanceof Number) {
                b.sunroofPosition(((Number) sunroofPos).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended sunroofPosition error: " + e.getMessage());
        }

        // Sunshade panel percent
        try {
            Object sunshade = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODY_SUNSHADE_PANEL_PERCENT, Integer.class);
            if (sunshade != null) {
                int val = BydDeviceHelper.getIntValue(sunshade);
                if (val >= 0 && val <= 100) b.sunshadePercent(val);
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended sunshade error: " + e.getMessage());
        }
    }

    /**
     * Extended engine data: coolant level, oil level, engine code.
     * Called from collectAllFull() only (display-only, on-demand).
     */
    private void collectEngineExtended(BydVehicleData.Builder b) {
        if (engineDevice == null) return;

        // Engine coolant level. BYD SDK constants: 0=NORMAL, 1=LOW.
        // Some firmwares return -1 or sentinel when the value is unavailable.
        Integer coolantRaw = null;
        try {
            Object coolant = BydDeviceHelper.callGetter(engineDevice, "getEngineCoolantLevel");
            if (coolant instanceof Number) {
                coolantRaw = ((Number) coolant).intValue();
                b.engineCoolantLevel(coolantRaw);
            }
        } catch (Exception e) {
            logger.debug("collectEngineExtended coolant error: " + e.getMessage());
        }

        // Oil level from Engine device. SDK range 0-254 (dipstick scale).
        // 0 may be a "no value" sentinel rather than empty tank — needs
        // verification against the cluster's own oil-level UI.
        Integer engineOilRaw = null;
        try {
            Object oil = BydDeviceHelper.callGetter(engineDevice, "getOilLevel");
            if (oil instanceof Number) {
                engineOilRaw = ((Number) oil).intValue();
                b.oilLevel(engineOilRaw);
            }
        } catch (Exception e) {
            logger.debug("collectEngineExtended oilLevel error: " + e.getMessage());
        }

        // Parallel reading from the Setting device (different code path).
        // Setting.getEngineOilLevel exists on most BYD firmwares — pulling it
        // alongside the Engine device version lets us cross-check which one
        // is actually populated on this car. Logged for diagnostics only;
        // not surfaced on the snapshot until we know which is canonical.
        Integer settingOilRaw = null;
        try {
            if (settingDevice != null) {
                Object oil = BydDeviceHelper.callGetter(settingDevice, "getEngineOilLevel");
                if (oil instanceof Number) settingOilRaw = ((Number) oil).intValue();
            }
        } catch (Exception ignored) {}

        // "Low oil indicator" lamp from Setting device — when this is set, the
        // dashboard is already showing the warning. Useful as a sanity check.
        Integer lowOilIndRaw = null;
        try {
            if (settingDevice != null) {
                Object ind = BydDeviceHelper.callGetter(settingDevice, "getLowOilInd");
                if (ind instanceof Number) lowOilIndRaw = ((Number) ind).intValue();
            }
        } catch (Exception ignored) {}

        logEngineFluidsIfChanged(coolantRaw, engineOilRaw, settingOilRaw, lowOilIndRaw);

        // Engine code (e.g. "BYD473QF")
        try {
            Object code = BydDeviceHelper.callGetter(engineDevice, "getEngineCode");
            if (code instanceof String) {
                b.engineCode((String) code);
            } else if (code != null) {
                String codeStr = BydDeviceHelper.getStringValue(code);
                if (codeStr != null && !codeStr.isEmpty()) b.engineCode(codeStr);
            }
        } catch (Exception e) {
            logger.debug("collectEngineExtended engineCode error: " + e.getMessage());
        }
    }

    // Last-seen engine-fluid readings — change-only logging plus a 5-min
    // heartbeat so a healthy car doesn't spam the log but transitions
    // (e.g. coolant drops to LOW after a leak develops) surface immediately.
    private volatile Integer lastCoolantRaw = null;
    private volatile Integer lastEngineOilRaw = null;
    private volatile Integer lastSettingOilRaw = null;
    private volatile Integer lastLowOilIndRaw = null;
    private volatile long lastEngineFluidsLogMs = 0;
    private volatile boolean firstEngineFluidsLog = true;

    private void logEngineFluidsIfChanged(Integer coolantRaw, Integer engineOilRaw,
                                          Integer settingOilRaw, Integer lowOilIndRaw) {
        boolean changed = firstEngineFluidsLog
                || !java.util.Objects.equals(coolantRaw, lastCoolantRaw)
                || !java.util.Objects.equals(engineOilRaw, lastEngineOilRaw)
                || !java.util.Objects.equals(settingOilRaw, lastSettingOilRaw)
                || !java.util.Objects.equals(lowOilIndRaw, lastLowOilIndRaw);
        long now = System.currentTimeMillis();
        boolean heartbeat = now - lastEngineFluidsLogMs > 300_000;
        if (!changed && !heartbeat) return;
        firstEngineFluidsLog = false;
        lastCoolantRaw = coolantRaw;
        lastEngineOilRaw = engineOilRaw;
        lastSettingOilRaw = settingOilRaw;
        lastLowOilIndRaw = lowOilIndRaw;
        lastEngineFluidsLogMs = now;
        logger.info("EngineFluids: coolant=" + fmtFluid(coolantRaw)
                + " (0=NORMAL,1=LOW)"
                + " engineOil=" + fmtFluid(engineOilRaw) + " (0-254)"
                + " settingOil=" + fmtFluid(settingOilRaw)
                + " lowOilInd=" + fmtFluid(lowOilIndRaw)
                + (changed ? " [CHANGE]" : " [hb]"));
    }

    private static String fmtFluid(Integer v) {
        return v == null ? "n/a" : v.toString();
    }

    // ==================== CLOUD DATA MERGE ====================

    /**
     * Merge cloud data as FALLBACK — only fills fields where SDK returned no value.
     * SDK is always primary (real-time 5s poll). Cloud fills gaps only.
     */
    private void mergeCloudData(BydVehicleData.Builder b) {
        try {
            com.overdrive.app.byd.cloud.BydCloudConfig config =
                    com.overdrive.app.byd.cloud.BydCloudConfig.fromUnifiedConfig();
            if (!config.cloudDataMerge) return;

            com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                    com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
            if (!provider.isTelemetryFresh()) return;

            com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = provider.getSnapshot();
            if (cs == null) return;

            // SOC — only if SDK didn't provide it
            if (Double.isNaN(b.socPercent) && cs.hasSoc()) b.socPercent(cs.socPercent);

            // EV range — only if SDK returned UNAVAILABLE
            if (b.elecRangeKm == BydVehicleData.UNAVAILABLE && cs.hasElecRange()) b.elecRangeKm(cs.elecRangeKm);

            // Fuel range / percent (PHEV) — only if SDK has nothing
            if (b.fuelRangeKm == BydVehicleData.UNAVAILABLE && cs.hasFuelRange()) b.fuelRangeKm(cs.fuelRangeKm);
            if (Double.isNaN(b.fuelPercent) && cs.hasFuelPercent()) b.fuelPercent(cs.fuelPercent);

            // Charging state — only if SDK returned UNAVAILABLE
            if (b.chargingState == BydVehicleData.UNAVAILABLE && cs.hasChargingState()) {
                int sdkState = cs.getChargingStateAsSdk();
                if (sdkState >= 0) b.chargingState(sdkState);
            }

            // Charge ETA — only if SDK has nothing
            if (b.chargingRestTimeHours == BydVehicleData.UNAVAILABLE && cs.hasRemainingHours())
                b.chargingRestTimeHours(cs.remainingHours);
            if (b.chargingRestTimeMinutes == BydVehicleData.UNAVAILABLE && cs.hasRemainingMinutes())
                b.chargingRestTimeMinutes(cs.remainingMinutes);

            // Temperatures — only if SDK returned NaN
            if (Double.isNaN(b.insideTempC) && cs.hasInsideTemp()) b.insideTempC(cs.insideTempC);
            if (Double.isNaN(b.outsideTempC) && cs.hasOutsideTemp()) b.outsideTempC(cs.outsideTempC);

            // Odometer — only if SDK returned UNAVAILABLE
            if (b.totalMileageKm == BydVehicleData.UNAVAILABLE && cs.hasTotalMileage())
                b.totalMileageKm(cs.totalMileageKm);

            // Air quality — only if SDK returned UNAVAILABLE
            if (b.pm25Inside == BydVehicleData.UNAVAILABLE && cs.hasPm25Inside())
                b.pm25Inside((int) cs.pm25Inside);
            if (b.pm25Outside == BydVehicleData.UNAVAILABLE && cs.hasPm25Outside())
                b.pm25Outside((int) cs.pm25Outside);

        } catch (Exception e) {
            logger.debug("Cloud data merge error: " + e.getMessage());
        }
    }

    // ==================== LISTENER REGISTRATION ====================

    private void registerAllListeners() {
        logger.info("Registering listeners...");
        int count = 0;

        // Bodywork: use the typed listener so onDoorStateChanged /
        // onWindowStateChanged / onWindowOpenPercentChanged actually dispatch.
        // The generic IBYDAutoListener registration succeeds but never fires
        // those device-specific callbacks.
        if (BydDeviceHelper.registerBodyworkListener(bodyworkDevice, this::onBodyworkCallback)) {
            logger.info("  Bodywork listener registered (typed)");
            count++;
        } else if (BydDeviceHelper.registerListener(bodyworkDevice, this::onBodyworkCallback)) {
            // Fallback for stub/older firmwares that only expose the generic interface.
            logger.info("  Bodywork listener registered (generic fallback — door/window callbacks may not fire)");
            count++;
        }
        if (BydDeviceHelper.registerListener(speedDevice, this::onGenericCallback)) {
            logger.info("  Speed listener registered");
            count++;
        }
        // SKIP gearbox listener — BYDAutoGearboxDevice.learningEPB() crashes with
        // "Given calling package android does not match caller's uid 2000" when running
        // as shell (UID 2000). The crash kills the BYD device manager's HandlerThread,
        // which cascades into GL thread hang → watchdog kill → daemon restart loop.
        // Gear data is collected via polling (collectAll) and GearMonitor handles gear changes.
        // if (BydDeviceHelper.registerListener(gearboxDevice, this::onGenericCallback)) {
        //     logger.info("  Gearbox listener registered");
        //     count++;
        // }
        // Charging: prefer typed registration. The generic IBYDAutoListener
        // proxy used to register here misses onBatteryManagementDeviceStateChanged
        // on some PHEV firmwares, which is the root of the inconsistent
        // charging-detection bug (BMS state would freeze at 15 IDLE while
        // charging). Typed listener guarantees AC-charging start is seen.
        if (BydDeviceHelper.registerChargingListener(chargingDevice, this::onChargingCallback)) {
            logger.info("  Charging listener registered (typed)");
            count++;
        } else if (BydDeviceHelper.registerListener(chargingDevice, this::onChargingCallback)) {
            logger.info("  Charging listener registered (generic fallback)");
            count++;
        }
        // Engine listener: typed for onEngineCoolantLevelChanged /
        // onOilLevelChanged. Without this, engine fluid status is only
        // refreshed by the one-shot collectAllFull at init.
        if (BydDeviceHelper.registerEngineListener(engineDevice, this::onEngineCallback)) {
            logger.info("  Engine listener registered (typed)");
            count++;
        } else if (BydDeviceHelper.registerListener(engineDevice, this::onEngineCallback)) {
            logger.info("  Engine listener registered (generic fallback)");
            count++;
        }
        // Instrument listener: MUST be typed. onExternalChargingPowerChanged
        // (live charging power in kW) is a concrete method on
        // AbsBYDAutoInstrumentListener, not on the IBYDAutoListener marker
        // interface — the generic Proxy path can never receive it (the HAL
        // dispatches it only to typed AbsBYDAutoInstrumentListener subscribers),
        // so charging power silently never arrived and the UI fell back to a
        // nominal estimate. Typed first; generic fallback kept for any firmware
        // that only exposes the bare 1-arg registerListener.
        if (BydDeviceHelper.registerInstrumentListener(instrumentDevice, this::onInstrumentCallback)) {
            logger.info("  Instrument listener registered (typed — external charging power)");
            count++;
        } else if (BydDeviceHelper.registerListener(instrumentDevice, this::onInstrumentCallback)) {
            logger.info("  Instrument listener registered (generic fallback)");
            count++;
        }
        // Statistic listener MUST be typed to receive onElecPercentageChanged(double)
        // — the DECIMAL display SoC. It's a concrete method on
        // AbsBYDAutoStatisticListener, not on the bare IBYDAutoListener marker
        // interface, so the generic Proxy path can never deliver it (same class of
        // bug as onExternalChargingPowerChanged above): SoC then only advanced on the
        // slow getElecPercentageValue() poll (integer on this trim). Typed first;
        // generic fallback kept for firmware exposing only the bare registerListener.
        if (BydDeviceHelper.registerStatisticListener(statisticDevice, this::onGenericCallback)) {
            logger.info("  Statistic listener registered (typed — decimal SoC)");
            count++;
        } else if (BydDeviceHelper.registerListener(statisticDevice, this::onGenericCallback)) {
            logger.info("  Statistic listener registered (generic fallback)");
            count++;
        }
        if (BydDeviceHelper.registerListener(lightDevice, this::onLightsCallback)) {
            logger.info("  Light listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(adasDevice, this::onAdasCallback)) {
            logger.info("  Adas listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(settingDevice, this::onSettingsCallback)) {
            logger.info("  Settings listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(radarDevice, this::onGenericCallback)) {
            logger.info("  Radar listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(otaDevice, this::onOtaCallback)) {
            logger.info("  OTA listener registered");
            count++;
        }

        // Display-only devices — no periodic polling, listener-driven only.
        // These update the snapshot when BYD HAL pushes CAN bus state changes.
        //
        // DoorLock requires the typed AbsBYDAutoDoorLockListener — the generic
        // IBYDAutoListener registration succeeds but never receives
        // onDoorLockStatusChanged. This was the root cause of stale lock data.
        if (BydDeviceHelper.registerDoorLockListener(doorLockDevice, this::onDoorLockCallback)) {
            logger.info("  DoorLock listener registered (typed)");
            count++;
        } else if (BydDeviceHelper.registerListener(doorLockDevice, this::onDoorLockCallback)) {
            logger.info("  DoorLock listener registered (generic fallback — lock callbacks may not fire)");
            count++;
        }
        if (BydDeviceHelper.registerTyreListener(tyreDevice, this::onTyreCallback)) {
            logger.info("  Tyre listener registered (typed)");
            count++;
        } else if (BydDeviceHelper.registerListener(tyreDevice, this::onDisplayCallback)) {
            logger.info("  Tyre listener registered (generic fallback)");
            count++;
        }
        if (BydDeviceHelper.registerListener(acDevice, this::onDisplayCallback)) {
            logger.info("  AC listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(sensorDevice, this::onDisplayCallback)) {
            logger.info("  Sensor listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(energyDevice, this::onDisplayCallback)) {
            logger.info("  Energy listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(powerDevice, this::onDisplayCallback)) {
            logger.info("  Power listener registered");
            count++;
        }

        logger.info("Listeners registered: " + count);
    }

    private void onBodyworkCallback(String method, Object[] args) {
        BydVehicleData current = snapshot.get();
        if (current == null) return;
        BydVehicleData.Builder b = current.toBuilder();
        // Bodywork events also affect window/door-open state (separate from
        // lock state) and trunk position. Refresh both the bodywork view and
        // the lock view — door open/close on the bodywork bus is often the
        // first signal of an upcoming lock event, and refreshing locks here
        // means consumers see consistent state regardless of which side fires.
        collectBodywork(b);
        collectDoorLock(b);
        BydVehicleData updated = b.build();
        snapshot.set(updated);

        // If a typed onDoorStateChanged event arrived, fan it out specifically
        // so consumers that want raw door-open events (not lock state) can
        // subscribe without polling the snapshot.
        if ("onDoorStateChanged".equals(method) && args != null && args.length >= 2) {
            int area = (args[0] instanceof Integer) ? (Integer) args[0] : -1;
            int state = (args[1] instanceof Integer) ? (Integer) args[1] : -1;
            notifyDoorStateListeners(area, state);
        }
        notifyLockSnapshotListeners(updated);
    }

    /**
     * Callback for DoorLock device — re-reads lock status on CAN bus state change.
     * Unlike other display-only devices, door lock state is critical for the
     * vehicle control page and must be updated immediately when the HAL reports
     * a change.
     *
     * The typed AbsBYDAutoDoorLockListener delivers onDoorLockStatusChanged(area,state)
     * with raw SDK semantics (UNLOCK=1, LOCK=2). We refresh the snapshot (which
     * uses inverted API contract for backwards compat) and forward the raw
     * SDK-semantic event to door-lock listeners.
     */
    private void onDoorLockCallback(String method, Object[] args) {
        BydVehicleData current = snapshot.get();
        if (current == null) return;
        BydVehicleData.Builder b = current.toBuilder();
        collectDoorLock(b);
        BydVehicleData updated = b.build();
        snapshot.set(updated);

        if ("onDoorLockStatusChanged".equals(method) && args != null && args.length >= 2) {
            int area = (args[0] instanceof Integer) ? (Integer) args[0] : -1;
            int sdkState = (args[1] instanceof Integer) ? (Integer) args[1] : -1;
            notifyDoorLockListeners(area, sdkState);
        }
        notifyLockSnapshotListeners(updated);
    }

    /**
     * Callback for display-only devices (Tyre, AC, Sensor, Energy, Power).
     * 
     * These listeners exist solely to keep the BYD device singletons' internal caches
     * fresh. We do NOT re-poll devices here — the snapshot is updated on-demand when
     * the HTTP API calls collectAllFull(), or when the bodywork listener fires.
     * 
     * This avoids the 10Hz SensorDevice postEvent from triggering expensive
     * full display sweeps (tyre×4, seatbelt×5, AC×5, light×8, radar, etc.)
     */
    private void onDisplayCallback(String method, Object[] args) {
        // No-op: listener registration keeps BYD HAL singletons' caches alive.
        // Actual data is read on-demand via collectAllFull().
    }

    private void onGenericCallback(String method, Object[] args) {
        // Typed callbacks for real-time updates
        if ("onElecPercentageChanged".equals(method) && args != null && args.length > 0) {
            try {
                double soc = ((Number) args[0]).doubleValue();
                if (soc >= 0 && soc <= 100) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().socPercent(soc).build());
                    }
                    // Fan out to the SoC voluntary-cutoff monitor (no-op when
                    // not running). Doesn't try to subclass the abstract
                    // listener separately — piggybacks on this hub.
                    try {
                        com.overdrive.app.power.SocCutoffMonitor.notifyElecPercentage(soc);
                    } catch (Throwable ignored) {}
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onFuelPercentageChanged".equals(method) && args != null && args.length > 0) {
            try {
                int fuel = ((Number) args[0]).intValue();
                if (fuel > 0 && fuel <= 100) {
                    BydVehicleData current = snapshot.get();
                    if (current != null && isPhev(current)) {
                        snapshot.set(current.toBuilder().fuelPercent(fuel).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onSpeedChanged".equals(method) && args != null && args.length > 0) {
            try {
                double speed = ((Number) args[0]).doubleValue();
                if (speed != BydFeatureIds.SDK_NOT_AVAILABLE) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().speedKmh(speed * distanceToKmFactor).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onEngineSpeedChanged".equals(method) && args != null && args.length > 0) {
            try {
                int rpm = ((Number) args[0]).intValue();
                if (rpm >= 0 && rpm <= 8000) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().engineSpeedRpm(rpm).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onBatteryPowerVoltageChanged".equals(method) && args != null && args.length > 0) {
            try {
                double voltage = ((Number) args[0]).doubleValue();
                if (voltage > 0 && voltage < 20) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().voltage12v(voltage).build());
                    }
                    // Fan out — same rationale as onOtaCallback. Some BYD
                    // trims route OTA voltage through the generic hub instead.
                    try {
                        com.overdrive.app.power.BatteryVoltageMonitorV2
                                .notifyBatteryPowerVoltage(voltage);
                    } catch (Throwable ignored) {}
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onChargingGunStateChanged".equals(method) && args != null && args.length > 0) {
            try {
                int gunState = ((Number) args[0]).intValue();
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    snapshot.set(current.toBuilder().chargingGunState(gunState).build());
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }

        // HV pack voltage DISPLAY value is no longer taken from this event — it
        // under-reports on the Seal 82.5 trim (~494 V vs true ~570 V), so the shown
        // hvPackVoltage is derived from per-cell voltage × series count in
        // collectStatistic() instead (PR-125).
        //
        // BUT we still route the raw event into CAPACITY DETECTION only. That
        // derivation needs the series cell count, which needs the capacity — a
        // chicken-and-egg that leaves capacity unknown on models with no other
        // detection source (no user model, getBatteryCapacity()=0, SOC-ratio
        // unavailable, unknown ro.product.model, BMS-fuzzy miss). This event is the
        // ONLY independent pack-level voltage on those trims, and even an
        // under-reading value is enough for autoDetectFromPackVoltage() to SNAP to
        // the nearest known BYD pack. It self-guards: no-op once capacity is known
        // or the user set a model, so it never fights PR-125's accurate display
        // value or a user override. We do NOT write hvPackVoltage here (keeping the
        // accurate cell×count value authoritative for display/SOH math).
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            try {
                int eventId = ((Number) args[0]).intValue();
                if (eventId == 1151336480) {
                    int iVal = BydDeviceHelper.getIntValue(args[1]);
                    if (iVal > 2000 && iVal < 9000) {       // decivolts: 200.0–900.0 V
                        double volts = iVal / 10.0;
                        com.overdrive.app.abrp.SohEstimator soh =
                            com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                        if (soh != null && !(soh.getNominalCapacityKwh() > 0)) {
                            soh.autoDetectFromPackVoltage(volts, snapshot.get());
                        }
                    }
                }
            } catch (Exception ignored) { /* diagnostic-only path; never disrupt collection */ }
        }
    }

    /**
     * Charging device callback — captures onChargingPowerChanged directly.
     * On many BYD models, getChargingPower() returns 0 but the callback delivers
     * the real value. We store it in the snapshot for VehicleDataMonitor to pick up.
     */
    // Throttle charging power log to once per 30 seconds
    private volatile long lastChargingPowerLogTime = 0;
    private volatile long lastChargingModeLogMs = 0;
    private volatile long lastChargingStateRawLogMs = 0;
    // One-shot: log the raw vs scaled getExternalChargingPower value the first
    // time we successfully publish a value, so the next field log capture can
    // confirm the hectowatt vs kW scaling against the cluster's own readout.
    private volatile boolean loggedExtChargePowerScale = false;

    private void onChargingCallback(String method, Object[] args) {
        // Typed callbacks for real-time charging updates
        if ("onChargingGunStateChanged".equals(method) && args != null && args.length > 0) {
            try {
                int gunState = ((Number) args[0]).intValue();
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    snapshot.set(current.toBuilder().chargingGunState(gunState).build());
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        // Real-time BMS state change — critical for detecting AC charging start/stop promptly
        if ("onBatteryManagementDeviceStateChanged".equals(method) && args != null && args.length > 0) {
            try {
                int state = ((Number) args[0]).intValue();
                if (state >= 0 && state <= 15) {
                    BydVehicleData current = snapshot.get();
                    if (current != null && current.chargingState != state) {
                        int previous = current.chargingState;
                        snapshot.set(current.toBuilder().chargingState(state).build());
                        logger.info("BMS state changed: " + state + " (" +
                                (state == 0 ? "READY" : state == 1 ? "CHARGING" : state == 2 ? "FINISHED" :
                                 state == 3 ? "DISCHARGING" : state == 15 ? "IDLE" : "OTHER") + ")");
                        notifyChargingStateListeners(previous, state);
                    }
                    // Push edge into fused detector regardless of whether the
                    // snapshot value moved (it may already match from a poll).
                    com.overdrive.app.monitor.ChargingDetector.getInstance().updateBmsState(state);
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        // Capacity event — purely diagnostic for charging session size, but the
        // act of receiving it confirms the charging HAL is alive on this firmware.
        if ("onChargingCapacityChanged".equals(method) && args != null && args.length > 0) {
            try {
                double cap = ((Number) args[0]).doubleValue();
                if (cap > 0 && cap < 200) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().chargingCapacityKwh(cap).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        // Handle the new-style BYDAutoEvent callbacks from ChargingDevice.
        // IMPORTANT: Do NOT blindly interpret onDataEventChanged values as charging power.
        // The ChargingDevice fires events for many different metrics (voltage, current,
        // capacity, temperature, etc.) and we cannot reliably distinguish power from other
        // values without knowing the specific event ID mapping.
        // The OEM firmware does NOT use onDataEventChanged for power — it only uses
        // onExternalChargingPowerChanged from InstrumentDevice (see onInstrumentCallback).
        // We skip this path entirely to avoid misinterpreting non-power values as kW.
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            // Intentionally not processing — see comment above.
            // Power comes from onExternalChargingPowerChanged (InstrumentDevice) or
            // onChargingPowerChanged (typed callback below).
            return;
        }
        if ("onChargingPowerChanged".equals(method) && args != null && args.length > 0) {
            try {
                double power = ((Number) args[0]).doubleValue();
                // Listener callback delivers kW directly. SDK docs: range -500 to 500 kW.
                if (Math.abs(power) > 0.1 && Math.abs(power) < 500) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().chargingPowerKw(power).build());
                        long now = System.currentTimeMillis();
                        if (now - lastChargingPowerLogTime > 30_000) {
                            lastChargingPowerLogTime = now;
                            logger.info("Charging power via callback: " + String.format("%.1f", power) + " kW");
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
        // Listener-driven: the specific event value was already captured above.
        // Skip full device re-collection — the 5s polling timer handles periodic refresh.
    }

    private void onOtaCallback(String method, Object[] args) {
        if ("onBatteryPowerVoltageChanged".equals(method) && args != null && args.length > 0) {
            try {
                double voltage = ((Number) args[0]).doubleValue();
                if (voltage > 0 && voltage < 20) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().voltage12v(voltage).build());
                    }
                    // Fan out to BatteryVoltageMonitorV2's MCU sleep/wake
                    // hysteresis (no-op when not running). The collector
                    // subclasses AbsBYDAutoOtaListener once and routes here;
                    // V2 piggybacks instead of trying its own registration.
                    try {
                        com.overdrive.app.power.BatteryVoltageMonitorV2
                                .notifyBatteryPowerVoltage(voltage);
                    } catch (Throwable ignored) {}
                }
            } catch (Exception e) { /* ignore */ }
        }
    }

    private void onInstrumentCallback(String method, Object[] args) {
        // Handle the new-style BYDAutoEvent callbacks
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            // NOTE: Do NOT blindly interpret all instrument events as charging power.
            // The instrument device fires events for trip odometer, nav data,
            // and dozens of other metrics. Only the typed onExternalChargingPowerChanged
            // callback (below) reliably delivers charging power.
            // Previously, events like INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_MILEAGE
            // (event 1246801948, value=18.7 km) were misinterpreted as 18.7 kW charging.
        }
        if ("onExternalChargingPowerChanged".equals(method) && args != null && args.length > 0) {
            try {
                double power = ((Number) args[0]).doubleValue();
                // Listener callback delivers kW directly (SDK converts from CAN bus internally).
                boolean accepted = (power > 0.1 && power <= 500);
                if (accepted) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().externalChargingPowerKw(power).build());
                    }
                }
                // DIAGNOSTIC: log EVERY callback arrival — this is the proof the
                // typed AbsBYDAutoInstrumentListener registration actually delivers
                // onExternalChargingPowerChanged while parked-charging (the generic
                // IBYDAutoListener-Proxy path never could). Logs the raw value AND
                // whether it passed the accept gate, so a dropped/out-of-range value
                // (e.g. 0.0 → "Charging" fallback) is visible instead of silent.
                // Throttled to once / 30s to avoid spam on a chatty firmware.
                long now = System.currentTimeMillis();
                if (now - lastChargingPowerLogTime > 30_000) {
                    lastChargingPowerLogTime = now;
                    logger.info("onExternalChargingPowerChanged fired: raw=" + power + " kW"
                        + (accepted ? " (accepted)" : " (DROPPED — outside 0.1..500 gate)"));
                }
            } catch (Exception e) {
                logger.debug("onExternalChargingPowerChanged parse error: " + e.getMessage());
            }
        }
        // Listener-driven: the specific event value was already captured above.
        // Skip full device re-collection — the 5s polling timer handles periodic refresh.
    }

    private void onLightsCallback(String method, Object[] args) {
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            try {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int iVal = BydDeviceHelper.getIntValue(eventValue);

                if (eventId == BydFeatureIds.LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE && iVal > 0 && iVal < 3) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().dayTimeLight(iVal == 1).build());
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void onAdasCallback(String method, Object[] args) {
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            try {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int iVal = BydDeviceHelper.getIntValue(eventValue);

                if (eventId == BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE && iVal > 0 && iVal < 3) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().speedLimitWarning(iVal == 2).build());
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void onSettingsCallback(String method, Object[] args) {
        if (!"onDataEventChanged".equals(method) || args == null || args.length < 2) return;
        try {
            int eventId = ((Number) args[0]).intValue();
            int iVal = BydDeviceHelper.getIntValue(args[1]);
            // SDK reports 1=on, 2=off, 3=delay for CPD
            if (eventId == BydFeatureIds.SETTING_CPD_SWITCH_STATUS && iVal > 0 && iVal < 4) {
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    snapshot.set(current.toBuilder().childPresenceDetection(iVal).build());
                }
                return;
            }

            // Interior ambient colour (1..31). The "all area" event does not fire, so
            // monitor the FRONT and BACK colour features (same numeric ids as
            // LIGHT_AMBIENT_FRONT/REAR_COLOR) and mirror the latest into the snapshot.
            // Must precede the seat 1..3 guard below (ambient values exceed that range).
            if (eventId == BydFeatureIds.LIGHT_AMBIENT_FRONT_COLOR
                    || eventId == BydFeatureIds.LIGHT_AMBIENT_REAR_COLOR) {
                if (iVal >= 1 && iVal <= 31) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().ambientColour(iVal).build());
                    }
                }
                return;
            }

            // SDK reports 1=off, 2=low, 3=high for seats. Anything else is unknown — ignore.
            if (iVal < 1 || iVal > 3) return;

            int normalized = iVal - 1;
            BydVehicleData current = snapshot.get();
            if (current == null) return;
            BydVehicleData.Builder b = current.toBuilder();
            int[] heat = (current.seatHeat == null) ? new int[2] : current.seatHeat.clone();
            int[] cool = (current.seatCool == null) ? new int[2] : current.seatCool.clone();

            if (eventId == BydFeatureIds.SET_DRIVER_SEAT_HEATING_STATE)         heat[0] = normalized;
            else if (eventId == BydFeatureIds.SET_DRIVER_SEAT_VENTILATING_STATE) cool[0] = normalized;
            else if (eventId == BydFeatureIds.SET_PASSENGER_SEAT_HEATING_STATE) heat[1] = normalized;
            else if (eventId == BydFeatureIds.SET_PASSENGER_SEAT_VENTILATING_STATE) cool[1] = normalized;
            else return;

            snapshot.set(b.seatHeat(heat).seatCool(cool).build());
        } catch (Exception ignored) {}
    }

    // ==================== EXTENDED LISTENER HANDLERS ====================
    // These handler methods exist for future use. To activate, add a registerListener() call
    // in registerAllListeners() or registerBodyworkExtendedListeners() etc.

    private void handleSteeringAngleChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                double angle = BydDeviceHelper.getDoubleValue(args[0]);
                if (!Double.isNaN(angle) && angle >= -780 && angle <= 780) {
                    snapshot.set(snapshot.get().toBuilder().steeringAngleDegrees(angle).build());
                }
            } catch (Exception e) { logger.debug("handleSteeringAngleChanged error: " + e.getMessage()); }
        }
    }

    private void handleAutoSystemStateChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int state = BydDeviceHelper.getIntValue(args[0]);
                if (state >= 0 && state <= 2) {
                    snapshot.set(snapshot.get().toBuilder().autoSystemState(state).build());
                }
            } catch (Exception e) { logger.debug("handleAutoSystemStateChanged error: " + e.getMessage()); }
        }
    }

    private void handleSunroofStateChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int state = BydDeviceHelper.getIntValue(args[0]);
                if (state >= 0 && state <= 255) {
                    snapshot.set(snapshot.get().toBuilder().sunroofState(state).build());
                }
            } catch (Exception e) { logger.debug("handleSunroofStateChanged error: " + e.getMessage()); }
        }
    }

    private void handleSunroofPositionChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int position = BydDeviceHelper.getIntValue(args[0]);
                if (position >= 0 && position <= 100) {
                    snapshot.set(snapshot.get().toBuilder().sunroofPosition(position).build());
                }
            } catch (Exception e) { logger.debug("handleSunroofPositionChanged error: " + e.getMessage()); }
        }
    }

    private void handleChargingCapacityChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                double capacity = BydDeviceHelper.getDoubleValue(args[0]);
                if (!Double.isNaN(capacity) && capacity >= 0 && capacity <= 200) {
                    snapshot.set(snapshot.get().toBuilder().remainKwh(capacity).build());
                }
            } catch (Exception e) { logger.debug("handleChargingCapacityChanged error: " + e.getMessage()); }
        }
    }

    private void handleDrivingTimeChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                double hours = BydDeviceHelper.getDoubleValue(args[0]);
                if (!Double.isNaN(hours) && hours >= 0 && hours <= 10000) {
                    snapshot.set(snapshot.get().toBuilder().drivingTimeHours(hours).build());
                }
            } catch (Exception e) { logger.debug("handleDrivingTimeChanged error: " + e.getMessage()); }
        }
    }

    private void handleKeyBatteryLevelChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int level = BydDeviceHelper.getIntValue(args[0]);
                if (level >= 0 && level <= 1) {
                    snapshot.set(snapshot.get().toBuilder().keyBatteryLevel(level).build());
                }
            } catch (Exception e) { logger.debug("handleKeyBatteryLevelChanged error: " + e.getMessage()); }
        }
    }

    /**
     * Dispatcher for the typed engine listener. Routes the three known
     * device-specific callbacks to existing handle* methods, and forwards
     * unrecognised feature-ID events to the discovery logger so we can
     * extend BydFeatureIds.Engine with whatever fluid temperature IDs this
     * firmware happens to publish.
     */
    private void onEngineCallback(String method, Object[] args) {
        if (args == null) return;
        try {
            if ("onEngineCoolantLevelChanged".equals(method)) {
                handleEngineCoolantLevelChanged(args);
                if (!loggedEngineCoolantEvent && args.length > 0) {
                    loggedEngineCoolantEvent = true;
                    int level = BydDeviceHelper.getIntValue(args[0]);
                    logger.info("Engine event FIRST: coolantLevel=" + level
                            + " (0=NORMAL,1=LOW)");
                }
                return;
            }
            if ("onOilLevelChanged".equals(method)) {
                handleOilLevelChanged(args);
                if (!loggedEngineOilEvent && args.length > 0) {
                    loggedEngineOilEvent = true;
                    int level = BydDeviceHelper.getIntValue(args[0]);
                    logger.info("Engine event FIRST: oilLevel=" + level + " (0-254)");
                }
                return;
            }
            if ("onEngineSpeedChanged".equals(method) && args.length > 0) {
                int rpm = ((Number) args[0]).intValue();
                if (rpm >= 0 && rpm <= 8000) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().engineSpeedRpm(rpm).build());
                    }
                }
                return;
            }
            if ("onDataEventChanged".equals(method) && args.length >= 2) {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int rawInt = BydDeviceHelper.getIntValue(eventValue);
                double rawDbl = BydDeviceHelper.getDoubleValue(eventValue);

                // Known engine feature IDs — these are declared in
                // BYDAutoFeatureIds.Engine but the typed callbacks
                // (onEngineSpeedChanged etc.) are dormant on PHEV firmware,
                // so consume them off the generic event channel instead.
                // Apply the same sentinel filters and scaling as collectEngine.
                if (eventId == BydFeatureIds.ENGINE_SPEED
                        || eventId == BydFeatureIds.ENGINE_SPEED_ALT) {
                    // 8191 (0x1FFF) is a PHEV "engine off" sentinel observed on this
                    // firmware family; the standard BMS_UNAVAILABLE family covers the rest.
                    if (rawInt != BydFeatureIds.BMS_UNAVAILABLE
                            && rawInt != BydFeatureIds.INVALID_VALUE
                            && rawInt != BydFeatureIds.INVALID_VALUE_2
                            && rawInt != 8191
                            && rawInt >= 0 && rawInt <= 8000) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            snapshot.set(current.toBuilder().engineSpeedRpm(rawInt).build());
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_POWER) {
                    // The event's intValue carries the raw CAN signal; doubleValue is
                    // typically 0.0 on the listener path. Same dual-scale heuristic as
                    // collectEngine: |raw| > 100 implies deciwatts (×0.1 → kW),
                    // otherwise treat as kW directly. Range-filter excludes sentinels.
                    double raw = (rawInt != Integer.MIN_VALUE) ? (double) rawInt : rawDbl;
                    if (!Double.isNaN(raw) && raw >= -200.0 && raw <= 400.0) {
                        double kw = (Math.abs(raw) > 100.0) ? raw * 0.1 : raw;
                        // After scaling, re-check the kW range so a hectowatt value
                        // like 3095 (→ 309.5) gets rejected instead of mis-stored.
                        if (kw >= -200.0 && kw <= 400.0) {
                            // ACC OFF gating: when the key is removed, the only
                            // physically plausible engine-power direction is
                            // current INTO the pack (kw < 0, plug-in charging).
                            // Positive readings while parked are stale ECU
                            // residue or sensor noise — accepting them lets the
                            // ChargingDetector's L3 inference falsely conclude
                            // "engine is running" or wash out a real charging
                            // signal. Reject them; preserve negative values so
                            // charging-while-parked detection still works.
                            if (!accIsOn && kw > -ENGINE_POWER_CHARGING_DEADBAND) {
                                return;
                            }
                            BydVehicleData current = snapshot.get();
                            if (current != null) {
                                snapshot.set(current.toBuilder().enginePowerKw(kw).build());
                            }
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_FRONT_MOTOR_SPEED) {
                    // Front motor RPM is negated to match the cluster's display
                    // convention (forward motion = positive). Filter sentinels.
                    if (rawInt != Integer.MIN_VALUE
                            && rawInt != BydFeatureIds.BMS_UNAVAILABLE
                            && rawInt != BydFeatureIds.INVALID_VALUE
                            && rawInt != BydFeatureIds.INVALID_VALUE_2
                            && Math.abs(rawInt) <= 25000) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            snapshot.set(current.toBuilder().frontMotorSpeed(-rawInt).build());
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_REAR_MOTOR_SPEED) {
                    if (rawInt != Integer.MIN_VALUE
                            && rawInt != BydFeatureIds.BMS_UNAVAILABLE
                            && rawInt != BydFeatureIds.INVALID_VALUE
                            && rawInt != BydFeatureIds.INVALID_VALUE_2
                            && Math.abs(rawInt) <= 25000) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            snapshot.set(current.toBuilder().rearMotorSpeed(rawInt).build());
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_FRONT_MOTOR_TORQUE) {
                    // Negated to match cluster convention (same as collectEngine).
                    if (!Double.isNaN(rawDbl) && Math.abs(rawDbl) <= 1000.0) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            snapshot.set(current.toBuilder().frontMotorTorque(-rawDbl).build());
                        }
                    }
                    return;
                }

                // Unknown ID — log once for discovery (capped at 32 unique IDs).
                logUnknownEngineEventOnce(eventId, rawInt, rawDbl);
            }
        } catch (Exception e) {
            logger.debug("onEngineCallback error (" + method + "): " + e.getMessage());
        }
    }

    // One-shot diagnostic flags for the typed engine callbacks.
    private volatile boolean loggedEngineCoolantEvent = false;
    private volatile boolean loggedEngineOilEvent = false;

    // Log each unknown engine feature ID once, capped at 32 unique IDs total.
    // Engine fluids on some firmware (coolant temp, oil temp on PHEVs) arrive
    // here keyed on IDs that aren't in BYDAutoFeatureIds.Engine — surface the
    // first sighting of each so we can extend the constant table empirically.
    private final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> loggedUnknownEngineIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_UNKNOWN_ENGINE_IDS = 32;

    private void logUnknownEngineEventOnce(int eventId, int rawInt, double rawDbl) {
        if (loggedUnknownEngineIds.size() >= MAX_UNKNOWN_ENGINE_IDS) return;
        if (loggedUnknownEngineIds.putIfAbsent(eventId, Boolean.TRUE) != null) return;
        logger.info("Engine event UNKNOWN id=" + eventId
                + " intValue=" + (rawInt == Integer.MIN_VALUE ? "n/a" : Integer.toString(rawInt))
                + " doubleValue=" + (Double.isNaN(rawDbl) ? "n/a" : Double.toString(rawDbl))
                + " — if this looks like a coolant/oil temp, add it to BydFeatureIds.Engine");
    }

    private void handleEngineCoolantLevelChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int level = BydDeviceHelper.getIntValue(args[0]);
                if (level >= 0 && level <= 1) {
                    snapshot.set(snapshot.get().toBuilder().engineCoolantLevel(level).build());
                }
            } catch (Exception e) { logger.debug("handleEngineCoolantLevelChanged error: " + e.getMessage()); }
        }
    }

    private void handleOilLevelChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int level = BydDeviceHelper.getIntValue(args[0]);
                if (level >= 0 && level <= 254) {
                    snapshot.set(snapshot.get().toBuilder().oilLevel(level).build());
                }
            } catch (Exception e) { logger.debug("handleOilLevelChanged error: " + e.getMessage()); }
        }
    }

    private void handleSafetyBeltStatusChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int status = BydDeviceHelper.getIntValue(args[0]);
                if (status >= 0) {
                    // Safety belt status is a bitmask — store raw value
                    // Individual seat belt states are decoded by consumers
                    snapshot.set(snapshot.get().toBuilder().build());
                }
            } catch (Exception e) { logger.debug("handleSafetyBeltStatusChanged error: " + e.getMessage()); }
        }
    }

    // ==================== VEHICLE CONTROL SETTERS ====================
    // All setters call BydDeviceHelper directly from UID 2000.
    // If a setter fails due to UID permissions, it logs the error and returns false.
    // These methods are public and always callable — no config gate needed.

    // --- Climate Control ---

    public boolean setAcPower(boolean on) {
        // Use the named start()/stop() methods on BYDAutoAcDevice — these actually
        // turn the AC system on/off. The previous implementation used AC_AUTO_MODE_SET
        // which only toggles AUTO mode (automatic climate control) without stopping the
        // AC compressor/blower. This caused "turn off" to merely disable auto mode
        // while the AC kept running in manual mode.
        //
        // Per the OEM firmware: the AC state setter calls acDevice.start(0) / acDevice.stop(0)
        // Parameter 0 = default zone (all zones).
        // Return value: 0 = success, 1 = failed, 2 = timeout, 3 = busy, 4 = invalid value
        try {
            String methodName = on ? "start" : "stop";
            Object result = BydDeviceHelper.callGetter(acDevice, methodName, 0);
            boolean success = (result instanceof Integer && ((Integer) result).intValue() == 0);
            
            if (!success && result instanceof Integer) {
                int code = ((Integer) result).intValue();
                // Retry once on BUSY (3) — AC controller may be processing a previous command
                if (code == 3) {
                    logger.info("AC " + methodName + " returned BUSY, retrying in 500ms...");
                    Thread.sleep(500);
                    result = BydDeviceHelper.callGetter(acDevice, methodName, 0);
                    success = (result instanceof Integer && ((Integer) result).intValue() == 0);
                }
                if (!success) {
                    logger.warn("AC " + methodName + " failed: result=" + result +
                        " (0=ok, 1=fail, 2=timeout, 3=busy, 4=invalid)");
                }
            }
            
            return success;
        } catch (Exception e) {
            logger.debug("setAcPower(" + on + ") via start/stop failed: " + e.getMessage());
            // Fallback: try the feature ID approach (less reliable but works on some older firmware)
            try {
                // AC_AUTO_MODE_SET with value 0 doesn't truly stop AC on most models,
                // but on some older DiLink 3.0 firmware it's the only available method.
                return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_AUTO_MODE_SET, on ? 1 : 0);
            } catch (Exception e2) {
                logger.debug("setAcPower fallback also failed: " + e2.getMessage());
                return false;
            }
        }
    }

    public boolean setAcTemperature(int zone, double tempCelsius) {
        try {
            // Temperature is sent as int (degrees × 1 for most BYD models)
            int tempInt = (int) Math.round(tempCelsius);
            if (tempInt < 17 || tempInt > 33) return false;
            // SDK method: acDevice.setAcTemperature(zone, temp, 0, 1)
            Object result = BydDeviceHelper.callMethod(acDevice, "setAcTemperature", zone, tempInt, 0, 1);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAcTemperature failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAcFanLevel(int level) {
        try {
            if (level < 1 || level > 7) return false;
            // Primary: named SDK method acDevice.setAcWindLevel(0, level).
            Object result = BydDeviceHelper.callMethod(acDevice, "setAcWindLevel", 0, level);
            if (result instanceof Integer && ((Integer) result).intValue() == 0) return true;

            // Fallback: on some DiLink 3.0 firmware setAcWindLevel is a no-op
            // (returns null / non-zero). The generic feature write
            // set(1000, AC_WIND_LEVEL_SET, level) drives the fan directly and is
            // verified to work on the Dolphin (HAL research).
            // Only reached when the named path did NOT report success, so the
            // path that already works on other firmware is left untouched.
            logger.debug("setAcWindLevel named path returned " + result + "; trying generic feature write");
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_WIND_LEVEL_SET, level);
        } catch (Exception e) {
            logger.debug("setAcFanLevel failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAcWindMode(int mode) {
        try {
            // SDK method: acDevice.setAcWindMode(0, mode)
            Object result = BydDeviceHelper.callMethod(acDevice, "setAcWindMode", 0, mode);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAcWindMode failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setFrontDefrost(boolean on) {
        return setDefrostFeature(BydFeatureIds.AC_DEFROST_FRONT_SET, on, "front");
    }

    public boolean setRearDefrost(boolean on) {
        return setDefrostFeature(BydFeatureIds.AC_DEFROST_REAR_SET, on, "rear");
    }

    /**
     * Write a defrost feature id, trying each accepted encoding until one lands — mirroring
     * the OEM firmware, which sends the preferred value first then a fallback ({@code
     * enable → 1 then 2}; {@code disable → 0 then 2}). Some trims accept only one of the
     * encodings, so a single fixed value silently missed. sendSetCommand success = code >= 0.
     */
    private boolean setDefrostFeature(int featureId, boolean on, String label) {
        int[] candidates = on ? new int[]{1, 2} : new int[]{0, 2};
        try {
            for (int v : candidates) {
                if (BydDeviceHelper.sendSetCommand(acDevice, featureId, v)) {
                    logger.info("set_" + label + "_defrost(" + on + ") accepted value=" + v);
                    return true;
                }
            }
            logger.info("set_" + label + "_defrost(" + on + ") refused all encodings");
        } catch (Exception e) {
            logger.debug("set_" + label + "_defrost failed: " + e.getMessage());
        }
        return false;
    }

    public boolean setAcCycleMode(int mode) {
        try {
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_CYCLE_MODE_SET, mode);
        } catch (Exception e) {
            logger.debug("setAcCycleMode failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * AC auto mode on/off. Feature-id path (Ac.AUTO_MODE_SET): on writes 1; off tries 0
     * then 2 (the reference tries both accepted "off" encodings, first that lands wins).
     * sendSetCommand returns true on a non-negative HAL result.
     */
    public boolean setAcAutoMode(boolean on) {
        try {
            if (on) {
                return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_AUTO_MODE_SET, 1);
            }
            // Off: try 0 first, fall back to 2 (both are "off" per the OEM enum).
            if (BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_AUTO_MODE_SET, 0)) return true;
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_AUTO_MODE_SET, 2);
        } catch (Exception e) {
            logger.debug("setAcAutoMode failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Air-intake mode on the AC cycle axis: {@code recirculate=true} → RECIRCULATION (cabin
     * air recycled), false → FRESH_AIR (outside air drawn in). Raw values match the OEM
     * enum verified against the reference SDK (FRESH_AIR=0 / RECIRCULATION=1) written via
     * the AC device's {@code AC_CYCLE_MODE_SET} feature-id — the same axis we already READ
     * as {@code getAcCycleMode} in collectAc.
     */
    public boolean setAcRecirculation(boolean recirculate) {
        try {
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_CYCLE_MODE_SET,
                    recirculate ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setAcRecirculation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fan-only (ventilation, no compressor) mode on/off. Named-method on acDevice —
     * setAcVentilationState(int); try the 1-arg form, then the 2-arg (0, state) form
     * the OEM SDK also exposes. value = enabled?1:0. Success = Integer result == 0.
     */
    public boolean setFanOnlyMode(boolean on) {
        int state = on ? 1 : 0;
        try {
            Object r = BydDeviceHelper.callMethod(acDevice, "setAcVentilationState", state);
            if (r instanceof Integer && ((Integer) r).intValue() == 0) return true;
        } catch (Exception e) {
            logger.debug("setFanOnlyMode(1-arg) failed: " + e.getMessage());
        }
        try {
            Object r = BydDeviceHelper.callMethod(acDevice, "setAcVentilationState", 0, state);
            return r instanceof Integer && ((Integer) r).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setFanOnlyMode(2-arg) failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Steering-wheel heating on/off. Named-method on settingDevice —
     * setSteeringWheelHeatingState(int state), value on=2 / off=1 (per the OEM enum).
     * Treat a null or negative/sentinel (-2147482648) result as failure.
     */
    public boolean setSteeringWheelHeating(boolean on) {
        try {
            Object r = BydDeviceHelper.callMethod(settingDevice, "setSteeringWheelHeatingState", on ? 2 : 1);
            if (r instanceof Integer) {
                int v = ((Integer) r).intValue();
                return v >= 0 && v != -2147482648;
            }
            return r != null;
        } catch (Exception e) {
            logger.debug("setSteeringWheelHeating failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Smart welcome-light on/off. Named-method on settingDevice —
     * setSmartWelcomeLightState(int), value on=1 / off=2. Success = Integer result >= 0.
     */
    public boolean setWelcomeLight(boolean on) {
        try {
            Object r = BydDeviceHelper.callMethod(settingDevice, "setSmartWelcomeLightState", on ? 1 : 2);
            if (r instanceof Integer) return ((Integer) r).intValue() >= 0;
            return r != null;
        } catch (Exception e) {
            logger.debug("setWelcomeLight failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Headlight (headlamp) level / height. Named-method on settingDevice —
     * setHeadlampLevel(int), clamped 1..11 (the reference coerces into that band).
     * Success = Integer result >= 0.
     */
    public boolean setHeadlightLevel(int level) {
        int clamped = Math.max(1, Math.min(11, level));
        try {
            Object r = BydDeviceHelper.callMethod(settingDevice, "setHeadlampLevel", clamped);
            if (r instanceof Integer) return ((Integer) r).intValue() >= 0;
            return r != null;
        } catch (Exception e) {
            logger.debug("setHeadlightLevel failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Interior cabin / reading light on/off. PARITY with the OEM firmware's
     * turnInsideLightsOn/Off, which uses a TWO-TIER ladder — we previously only had the
     * second tier, so on a trim where the bodywork feature-id write silently no-ops the
     * cabin light never changed.
     *
     * <p>Tier 1 (PREFERRED, matches the OEM firmware): the named method {@code turnOffInsideLight(int)}
     * on the SETTING device. Note the counter-intuitive name + inverted argument the OEM
     * uses: {@code turnOffInsideLight(2)} turns the light ON, {@code turnOffInsideLight(1)}
     * turns it OFF (OEM firmware: "Inside lights ON via turnOffInsideLight(state=2)").
     *
     * <p>Tier 2 (FALLBACK): the feature-id write {@code Body.INSIDE_LIGHT_STATE_SET} on the
     * BODYWORK device, value on=1 / off=2 (the original path). Reached only if the named
     * method is absent or fails.
     */
    public boolean setReadingLight(boolean on) {
        // Tier 1 — settingDevice.turnOffInsideLight(on ? 2 : 1). callMethod returns null if
        // the method is absent (→ fall through to the feature-id write); a non-negative
        // Integer / any non-null return counts as accepted (same convention as the other
        // named setting writes, e.g. setWelcomeLight).
        try {
            Object r = BydDeviceHelper.callMethod(settingDevice, "turnOffInsideLight", on ? 2 : 1);
            if (r != null) {
                boolean ok = !(r instanceof Integer) || ((Integer) r).intValue() >= 0;
                if (ok) {
                    logger.info("setReadingLight(" + on + ") via turnOffInsideLight(" + (on ? 2 : 1) + ")");
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("turnOffInsideLight failed, trying feature-id: " + e.getMessage());
        }
        // Tier 2 — feature-id write on bodyworkDevice (on=1 / off=2).
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_INSIDE_LIGHT_STATE_SET, on ? 1 : 2);
        } catch (Exception e) {
            logger.debug("setReadingLight fallback failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ambient-light music mode on/off (ambient lights pulse to audio). Feature-id path
     * (Body.ATMOSPHERE_LIGHT_MUSIC_EXECUTE) on bodyworkDevice, value on=1 / off=2.
     */
    public boolean setAmbientMusicMode(boolean on) {
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_ATMOSPHERE_LIGHT_MUSIC, on ? 1 : 2);
        } catch (Exception e) {
            logger.debug("setAmbientMusicMode failed: " + e.getMessage());
            return false;
        }
    }

    // --- Windows ---
    public boolean setSunWindowCommand(int area, int command) {
        try {
            // area: 5=Sunroof, 6=Sunshade
            if (area < 5 || area > 6) return false;
            // incoming command: 1=open, 2=close, 3=stop, 4=half, (5=breath only for sunroof)
            // Remap to these values to match windows (3 and 4 are swapped)
            // SDK command: 1=open, 2=close, 3=half, 4=stop, (5=breath only for sunroof)
            if (command == 3) {
                command = 4;
            } else if (command == 4) {
                command = 3;
            }
            // SDK method: bodyworkDevice.voiceCtlMoonRoof(cmd) or bodyworkDevice.voiceCtlSunshadePanel(cmd)
            String cmd = area == 5 ? "voiceCtlMoonRoof" : "voiceCtlSunshadePanel";
            Object result = BydDeviceHelper.callMethod(bodyworkDevice, cmd, command);
            boolean ok = result instanceof Integer && ((Integer) result).intValue() == 0;
            // Log the ACTUAL HAL return at info level so the field-reported "sunroof is
            // hit and miss" is diagnosable: distinguishes a rejected write (non-zero /
            // non-Integer result) from an accepted-but-ineffective one (returns 0 yet the
            // roof doesn't move). voiceCtl* is a momentary command with no confirmed
            // success contract on this firmware, so we surface the raw result rather than
            // guessing a retry that could double-actuate a moving roof.
            logger.info("setSunWindow " + (area == 5 ? "Sunroof" : "Sunshade") + " cmd=" + command
                    + " via " + cmd + " -> result=" + result + " (ok=" + ok + ")");
            return ok;
        } catch (Exception e) {
            logger.warn("Set " + (area == 5 ? "Sunroof" : "Sunshade") +  " failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setWindowCommand(int area, int command) {
        try {
            // area: 1=LF, 2=RF, 3=LR, 4=RR, 5=Sunroof, 6=Sunshade
            // command: 1=open, 2=close, 3=stop, 4=half, 5=breath
            // Sunshade and Sunroof have different command for set
            if (area >= 5 && area <= 6) return setSunWindowCommand(area, command);
            if (area < 1 || area > 4) return false;
            // SDK method: bodyworkDevice.setAllWindowState(lf, rf, lr, rr)
            // Only the target area gets the command, others get 0
            int lf = area == 1 ? command : 0;
            int rf = area == 2 ? command : 0;
            int lr = area == 3 ? command : 0;
            int rr = area == 4 ? command : 0;
            Object result = BydDeviceHelper.callMethod(bodyworkDevice, "setAllWindowState", lf, rf, lr, rr);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setWindowCommand failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAllWindowsCommand(int command) {
        try {
            // command: 1=open, 2=close, 3=stop
            // SDK method: bodyworkDevice.setAllWindowState(cmd, cmd, cmd, cmd)
            Object result = BydDeviceHelper.callMethod(bodyworkDevice, "setAllWindowState", command, command, command, command);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAllWindowsCommand failed: " + e.getMessage());
            return false;
        }
    }

    // Per-area executor so a new target on one window cancels its prior
    // motion without affecting the others. Lazy-init.
    private final java.util.concurrent.ExecutorService[] windowExecutors =
            new java.util.concurrent.ExecutorService[6];
    private final java.util.concurrent.Future<?>[] windowMotionTasks =
            new java.util.concurrent.Future<?>[6];

    private synchronized java.util.concurrent.ExecutorService getWindowExecutor(int areaIdx) {
        java.util.concurrent.ExecutorService ex = windowExecutors[areaIdx];
        if (ex == null) {
            ex = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "WinMove-" + (areaIdx + 1));
                t.setDaemon(true);
                return t;
            });
            windowExecutors[areaIdx] = ex;
        }
        return ex;
    }

    private int readWindowPercent(int area) {
        try {
            Object wp = BydDeviceHelper.callGetter(bodyworkDevice, "getWindowOpenPercent", area);
            if (wp instanceof Number) return ((Number) wp).intValue();
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Closed-loop window positioning: drives the window towards {@code targetPercent}
     * and stops when it reaches the target (within tolerance), the motor stalls,
     * or a safety timeout elapses. Returns immediately; motion runs on a
     * per-window background thread so a fresh target cancels the previous one.
     *
     * @param area     1=LF, 2=RF, 3=LR, 4=RR
     * @param targetPercent 0 (closed) through 100 (fully open)
     * @return true if motion was scheduled, false if inputs were invalid or
     *         the window is already at the target.
     */
    public boolean moveWindowToPercent(int area, int targetPercent) {
        if (area < 1 || area > 6) return false;
        if (targetPercent < 0 || targetPercent > 100) return false;
        int areaIdx = area - 1;

        final int target = targetPercent;
        // Set the tolerance to 0 when fully open or closed requested to prevent windows being slightly open
        final int tolerance = (targetPercent == 100 || targetPercent == 0) ? 0 : 5; // ±5 % is the realistic floor (motor coast)
        final long pollIntervalMs = 200;  // SDK getter is cheap; tight loop = clean stop
        final long maxRunMs = 12_000;     // window full-travel ≈ 4–6 s; cap at 12 s
        final long stallWindowMs = 1_200; // no progress for this long → stall / pinch

        int initial = readWindowPercent(area);
        if (initial >= 0 && Math.abs(initial - target) <= tolerance) {
            logger.debug("Window " + area + " already near target (" + initial + "% vs " + target + "%)");
            return false;
        }

        // Cancel any in-flight motion for this window.
        java.util.concurrent.Future<?> prev = windowMotionTasks[areaIdx];
        if (prev != null && !prev.isDone()) prev.cancel(true);

        Runnable task = () -> {
            try {
                int start = readWindowPercent(area);
                if (start < 0) start = 50; // unknown — assume mid; stall-detect handles oddities

                int direction = target > start ? 1 : 2; // 1=open, 2=close
                if (!setWindowCommand(area, direction)) {
                    logger.warn("Window " + area + ": initial command failed");
                    return;
                }

                long startMs = System.currentTimeMillis();
                long lastProgressMs = startMs;
                int lastSeenPercent = start;
                boolean stopped = false;

                while (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(pollIntervalMs); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    int now = readWindowPercent(area);
                    long elapsed = System.currentTimeMillis() - startMs;

                    if (now >= 0) {
                        // Stop once we've crossed the target in the direction we
                        // were moving. Crossing-based comparison avoids stopping
                        // early on a noisy reading near the boundary.
                        boolean reached = direction == 1
                                ? now >= target - tolerance
                                : now <= target + tolerance;
                        if (reached) {
                            setWindowCommand(area, 3);
                            stopped = true;
                            logger.info("Window " + area + " reached target=" + target
                                    + "% (final=" + now + "%)");
                            break;
                        }

                        if (Math.abs(now - lastSeenPercent) >= 1) {
                            lastSeenPercent = now;
                            lastProgressMs = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - lastProgressMs > stallWindowMs) {
                            setWindowCommand(area, 3);
                            stopped = true;
                            logger.warn("Window " + area + " stalled at " + now
                                    + "% (target=" + target + "%) — stopped");
                            break;
                        }
                    }

                    if (elapsed > maxRunMs) {
                        setWindowCommand(area, 3);
                        stopped = true;
                        logger.warn("Window " + area + " motion timed out at "
                                + (now >= 0 ? now : -1) + "% — stopped");
                        break;
                    }
                }

                if (!stopped) setWindowCommand(area, 3);
            } catch (Exception e) {
                logger.warn("Window " + area + " motion task error: " + e.getMessage());
                try { setWindowCommand(area, 3); } catch (Exception ignored) {}
            }
        };

        windowMotionTasks[areaIdx] = getWindowExecutor(areaIdx).submit(task);
        return true;
    }

    // --- Door lock state ---

    /** Driver's-door lock states from {@code BYDAutoOtaDevice.getLFDoorLockState}. */
    public static final int DOOR_STATE_INVALID = 0;
    public static final int DOOR_STATE_UNLOCK = 1;
    public static final int DOOR_STATE_LOCK = 2;

    /**
     * Read the driver's-door (LF) lock state via the OTA device.
     *
     * <p>This is the same local rail AccSentry/CameraDaemon use
     * ({@code BYDAutoOtaDevice.getLFDoorLockState}) — it works ACC=OFF with
     * sub-second latency on DiLink 3.0. The legacy
     * {@code BYDAutoDoorLockDevice.getDoorLockStatus(area)} path returns
     * INVALID to user-UID processes on every field firmware and is not used.
     *
     * @return {@link #DOOR_STATE_INVALID}(0), {@link #DOOR_STATE_UNLOCK}(1),
     *         or {@link #DOOR_STATE_LOCK}(2).
     */
    public int readDoorLockState() {
        if (otaDevice == null) return DOOR_STATE_INVALID;
        try {
            Object v = BydDeviceHelper.callGetter(otaDevice, "getLFDoorLockState");
            if (v instanceof Number) {
                int state = ((Number) v).intValue();
                if (state == DOOR_STATE_UNLOCK || state == DOOR_STATE_LOCK) {
                    return state;
                }
            }
        } catch (Exception e) {
            logger.debug("readDoorLockState error: " + e.getMessage());
        }
        return DOOR_STATE_INVALID;
    }

    // --- Tailgate ---

    public boolean openTailgate() {
        // Method 1: SettingDevice.voiceCtlBackDoor(1) — official BYD SDK method
        if (settingDevice != null) {
            try {
                Object result = BydDeviceHelper.callGetter(settingDevice, "voiceCtlBackDoor", 1);
                logger.info("openTailgate voiceCtlBackDoor(1) result: " + result);
                if (result == null || (result instanceof Integer && ((Integer) result).intValue() == 0)) {
                    return true;
                }
            } catch (Exception e) {
                logger.debug("openTailgate voiceCtlBackDoor failed: " + e.getMessage());
            }
        }
        // Method 2: Bodywork BACK_DOOR_TRIGGER
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_BACK_DOOR_TRIGGER, 1);
        } catch (Exception e) {
            logger.debug("openTailgate BACK_DOOR_TRIGGER failed: " + e.getMessage());
            return false;
        }
    }

    public boolean closeTailgate() {
        // SOTA FIX: the OEM firmware uses value 3 for close via SETTING_VOICE_CTRL_BACK_DOOR_SET
        // Values: 1=open, 2=stop, 3=close (confirmed from OEM firmware analysis)
        
        // Method 1: SettingDevice sendSetCommand with value 3 (close)
        if (settingDevice != null) {
            try {
                boolean result = BydDeviceHelper.sendSetCommand(settingDevice, 
                    BydFeatureIds.SETTING_VOICE_CTRL_BACK_DOOR_SET, 3);
                logger.info("closeTailgate sendSetCommand(VOICE_CTRL_BACK_DOOR, 3) result: " + result);
                if (result) return true;
            } catch (Exception e) {
                logger.debug("closeTailgate sendSetCommand failed: " + e.getMessage());
            }
            
            // Method 1b: Try voiceCtlBackDoor(3) directly
            try {
                Object result = BydDeviceHelper.callGetter(settingDevice, "voiceCtlBackDoor", 3);
                logger.info("closeTailgate voiceCtlBackDoor(3) result: " + result);
                if (result == null || (result instanceof Integer && ((Integer) result).intValue() == 0)) {
                    return true;
                }
            } catch (Exception e) {
                logger.debug("closeTailgate voiceCtlBackDoor(3) failed: " + e.getMessage());
            }
        }
        
        // Method 2: Bodywork BACK_DOOR_TRIGGER with value 3 (close)
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_BACK_DOOR_TRIGGER, 3);
        } catch (Exception e) {
            logger.debug("closeTailgate BACK_DOOR_TRIGGER(3) failed: " + e.getMessage());
            return false;
        }
    }

    public boolean stopTailgate() {
        // SOTA FIX: the OEM firmware uses value 2 for stop
        // Values: 1=open, 2=stop, 3=close
        
        // Method 1: SettingDevice sendSetCommand with value 2 (stop)
        if (settingDevice != null) {
            try {
                boolean result = BydDeviceHelper.sendSetCommand(settingDevice,
                    BydFeatureIds.SETTING_VOICE_CTRL_BACK_DOOR_SET, 2);
                logger.info("stopTailgate sendSetCommand(VOICE_CTRL_BACK_DOOR, 2) result: " + result);
                if (result) return true;
            } catch (Exception e) {
                logger.debug("stopTailgate sendSetCommand failed: " + e.getMessage());
            }
            
            // Fallback: voiceCtlBackDoor(2)
            try {
                Object result = BydDeviceHelper.callGetter(settingDevice, "voiceCtlBackDoor", 2);
                if (result == null || (result instanceof Integer && ((Integer) result).intValue() == 0)) {
                    return true;
                }
            } catch (Exception e) {
                logger.debug("stopTailgate voiceCtlBackDoor(2) failed: " + e.getMessage());
            }
        }
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_BACK_DOOR_TRIGGER, 0);
        } catch (Exception e) {
            logger.debug("stopTailgate BACK_DOOR_TRIGGER failed: " + e.getMessage());
            return false;
        }
    }

    // --- AVAS / Exterior Speaker ---

    /** Get the multimedia device (for direct access by audio test handler). */
    public Object getMultimediaDevice() {
        return multimediaDevice;
    }

    /** Get exterior speaker state: 1=enabled, 0=disabled, null=unavailable or unsupported. */
    public Integer getExteriorSpeakerState() {
        if (multimediaDevice == null) return null;
        try {
            Method m = multimediaDevice.getClass().getMethod("getExteriorSpeakerState");
            Object result = m.invoke(multimediaDevice);
            return (result instanceof Integer) ? (Integer) result : null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            logger.debug("getExteriorSpeakerState failed: " + e.getMessage());
            return null;
        }
    }

    /** Set exterior speaker state: 1=enable, 0=disable. Returns false if unsupported on this device. */
    public boolean setExteriorSpeakerState(int state) {
        if (multimediaDevice == null) return false;
        try {
            Method m = multimediaDevice.getClass().getMethod("setExteriorSpeakerState", int.class);
            m.invoke(multimediaDevice, state);
            return true;
        } catch (NoSuchMethodException e) {
            logger.warn("setExteriorSpeakerState: method not present on multimedia device — exterior speaker routing unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setExteriorSpeakerState failed: " + e.getMessage());
            return false;
        }
    }

    /** Get AVAS sound source type. Returns null if unsupported. */
    public Integer getAVASSoundSource() {
        if (multimediaDevice == null) return null;
        try {
            Method m = multimediaDevice.getClass().getMethod("getAVASSoundSource");
            Object result = m.invoke(multimediaDevice);
            return (result instanceof Integer) ? (Integer) result : null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            logger.debug("getAVASSoundSource failed: " + e.getMessage());
            return null;
        }
    }

    /** Set AVAS sound source type. Returns false if unsupported on this device. */
    public boolean setAVASSoundSource(int sourceType) {
        if (multimediaDevice == null) return false;
        try {
            Method m = multimediaDevice.getClass().getMethod("setAVASSoundSource", int.class);
            m.invoke(multimediaDevice, sourceType);
            return true;
        } catch (NoSuchMethodException e) {
            logger.warn("setAVASSoundSource: method not present on multimedia device — AVAS routing unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setAVASSoundSource failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Probe the multimedia device for any AVAS / exterior-speaker / outside-sound related methods.
     * Returns a list of method signatures (name + param types) whose name matches the regex.
     * Used by the audio test handler to discover what the OEM build actually exposes.
     */
    public java.util.List<String> probeMultimediaMethods(String regex) {
        java.util.List<String> matches = new java.util.ArrayList<>();
        if (multimediaDevice == null) return matches;
        java.util.regex.Pattern p;
        try {
            p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            return matches;
        }
        Class<?> cls = multimediaDevice.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!p.matcher(m.getName()).find()) continue;
                StringBuilder sig = new StringBuilder();
                sig.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sig.append(", ");
                    sig.append(params[i].getSimpleName());
                }
                sig.append(')');
                matches.add(sig.toString());
            }
            cls = cls.getSuperclass();
        }
        java.util.Collections.sort(matches);
        return matches;
    }

    /** Check if multimedia device is available. */
    public boolean isMultimediaAvailable() {
        return multimediaDevice != null;
    }

    // --- Charging ---
    // The smart-charging schedule lives in BYD cloud, not the HAL. The Seal HAL
    // exposes setChargeStop*/getChargeStop* methods that look like they should
    // work but: getChargeStopSupportConfig=0, getters return 0xFFFF, setters
    // silently return success-but-no-op. See feedback_byd_hal_unreliable_signals.
    // All schedule reads/writes go through BydCloudClient smart-charging
    // endpoints in VehicleControlApiHandler / VehicleCommandRouter.

    // BEV charge cap — TWO paths, tried in priority order:
    //
    //  1. PRIMARY: the SOC-target / battery-hold feature. Setting device's
    //     setSOCTarget(percent) picks the target state-of-charge and the
    //     charging device's setSocSaveSwitch(mode) turns the hold on/off.
    //     This is the path that ACTUALLY applies the cap on these BYD trims.
    //     The target is clamped exactly like the OEM: floor = 15 (or 25 when
    //     getSOCConfig()==2), ceiling = 70 (SET_DR_SOC_TARGET_MAX). Value 0/1
    //     result == success, same convention as every other SDK setter.
    //
    //  2. FALLBACK: BYDAutoChargingDevice.setChargeStopCapacityState (target %)
    //     + setChargeStopSwitchState (master on/off). On Seal trims the
    //     getChargeStopSupportConfig flag has historically returned 0, in which
    //     case the framework accepts the call but doesn't apply the cap — so we
    //     probe by writing then reading back; if the read-back doesn't match we
    //     mark unsupported and the UI hides. Only used when the SOC-target
    //     methods are absent on the firmware.
    //
    // The smart-charging SCHEDULE (distinct from the cap) lives in BYD cloud,
    // not the HAL — see BydCloudClient smart-charging endpoints.

    private static final int SOC_TARGET_MAX = 70;     // SET_DR_SOC_TARGET_MAX
    private static final int SOC_TARGET_FLOOR_DEFAULT = 15;
    private static final int SOC_TARGET_FLOOR_ALT = 25; // when getSOCConfig()==2

    private volatile boolean chargeCapProbed = false;
    private volatile boolean chargeCapSupported = false;
    /** Effective cap the last accepted write actually applied (post-clamp); -1 if none yet. */
    private volatile int lastAppliedCapPercent = -1;

    /** Last known cap %. -1 if never probed/read or the HAL returned a sentinel. */
    public int getChargeCapPercent() {
        try {
            // Primary: SOC target (the value that actually applies on these trims).
            Object v = BydDeviceHelper.callGetter(settingDevice, "getSOCTarget");
            if (v instanceof Number) {
                int iv = ((Number) v).intValue();
                // Valid target window is [15,70]; be lenient to [15,100] so a
                // fallback read isn't shadowed. Anything outside filters the HAL
                // sentinels (0xFFFF=65535, -10011, …) → treated as unavailable.
                if (iv >= 15 && iv <= 100) return iv;
            }
            // Fallback: legacy charge-stop capacity cap (50..100 on trims that
            // support it). Same sentinel filtering via the range gate.
            Object c = BydDeviceHelper.callGetter(chargingDevice, "getChargeStopCapacityState");
            if (c instanceof Number) {
                int cv = ((Number) c).intValue();
                if (cv >= 15 && cv <= 100) return cv;
            }
            // Getter unavailable/sentinel — fall back to the value we last applied
            // (avoids a racy immediate read-back returning stale after a write).
            return lastAppliedCapPercent;
        } catch (Exception e) {
            return lastAppliedCapPercent;
        }
    }

    /** Effective cap the vehicle holds after the last accepted write; -1 if none. */
    public int getLastAppliedCapPercent() {
        return lastAppliedCapPercent;
    }

    /** Last known on/off state. -1 if unsupported/read failed or a sentinel. */
    public int getChargeCapEnabled() {
        try {
            // Primary: SOC-save switch (0=off, 1/2=hold mode → on).
            Object v = BydDeviceHelper.callGetter(chargingDevice, "getSocSaveSwitch");
            if (v instanceof Number) {
                int iv = ((Number) v).intValue();
                if (iv == 0) return 0;
                if (iv == 1 || iv == 2) return 1;
            }
            // Fallback: legacy charge-stop master switch (0/1 only; a sentinel
            // such as 0xFFFF must NOT be read as "on").
            Object c = BydDeviceHelper.callGetter(chargingDevice, "getChargeStopSwitchState");
            if (c instanceof Number) {
                int cv = ((Number) c).intValue();
                if (cv == 0 || cv == 1) return cv;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Has the BEV charge-cap been observed to actually take effect on this
     * trim? null = not yet probed, true/false = result of the first write.
     * UI uses this to hide the section on no-op trims.
     */
    public Boolean isChargeCapSupported() {
        return chargeCapProbed ? Boolean.valueOf(chargeCapSupported) : null;
    }

    /**
     * Set the BEV charge cap. Tries the SOC-target path first (the path that
     * applies on these trims); if the setting device doesn't expose it, falls
     * back to the legacy charge-stop capacity cap.
     */
    public boolean setChargeCapPercent(int percent) {
        Boolean primary = trySetSocTarget(percent);
        if (primary != null) return primary.booleanValue();
        return setChargeStopCapacityFallback(percent);
    }

    /**
     * PRIMARY charge-cap write: BYDAutoSettingDevice.setSOCTarget(percent),
     * clamped to [floor, 70] where floor = getSOCConfig()==2 ? 25 : 15 (matches
     * the OEM). Returns null when the setter is absent on this firmware (so the
     * caller can fall back); TRUE/FALSE = SDK acceptance.
     */
    private Boolean trySetSocTarget(int percent) {
        if (settingDevice == null) return null;
        int floor = SOC_TARGET_FLOOR_DEFAULT;
        Object cfg = BydDeviceHelper.callGetter(settingDevice, "getSOCConfig");
        if (cfg instanceof Number && ((Number) cfg).intValue() == 2) {
            floor = SOC_TARGET_FLOOR_ALT;
        }
        int target = percent;
        if (target < floor) target = floor;
        if (target > SOC_TARGET_MAX) target = SOC_TARGET_MAX;
        // callMethod returns null both when the method is absent AND on invoke
        // failure; either way we let the caller fall back to the legacy cap.
        Object result = BydDeviceHelper.callMethod(settingDevice, "setSOCTarget", target);
        if (result == null) return null;
        boolean accepted = (result instanceof Integer) && ((Integer) result).intValue() == 0;
        // This is the proven-working path — acceptance means applied, no read-back
        // probe needed (unlike the legacy cap which returns success-but-no-op).
        chargeCapProbed = true;
        chargeCapSupported = accepted;
        if (accepted) lastAppliedCapPercent = target;
        logger.info("setSOCTarget(" + target + ") [requested=" + percent + " floor=" + floor
                + "] accepted=" + accepted);
        return Boolean.valueOf(accepted);
    }

    /**
     * FALLBACK charge-cap write: BYDAutoChargingDevice.setChargeStopCapacityState
     * (50..100%). Probes on first write via read-back; if the framework didn't
     * honor it, flip supported=false so the UI hides. Subsequent calls
     * short-circuit if already known to no-op.
     */
    private boolean setChargeStopCapacityFallback(int percent) {
        if (chargingDevice == null) {
            logger.warn("setChargeStopCapacityState: chargingDevice null");
            return false;
        }
        // Clamp to the legacy path's supported range instead of rejecting: the
        // API acceptance surface is [15,100] but this legacy cap only honors
        // [50,100], so a sub-floor request applies at 50 (matches the "clamps to
        // whichever path applies" contract; getLastAppliedCapPercent then echoes 50).
        if (percent < 50) percent = 50;
        if (percent > 100) percent = 100;
        if (chargeCapProbed && !chargeCapSupported) {
            logger.debug("setChargeStopCapacityState: known unsupported on this trim");
            return false;
        }
        try {
            Method m;
            try {
                m = chargingDevice.getClass().getMethod("setChargeStopCapacityState", int.class);
            } catch (NoSuchMethodException nsme) {
                logger.warn("setChargeStopCapacityState not present on this firmware");
                chargeCapProbed = true; chargeCapSupported = false;
                return false;
            }
            Object result = m.invoke(chargingDevice, percent);
            boolean accepted = result instanceof Integer && ((Integer) result).intValue() == 0;
            if (!accepted) {
                logger.debug("setChargeStopCapacityState(" + percent + ") returned " + result);
                return false;
            }
            // Probe: if not yet probed, read back to confirm the value stuck.
            if (!chargeCapProbed) {
                try { Thread.sleep(150L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                int readBack = getChargeCapPercent();
                chargeCapProbed = true;
                chargeCapSupported = (readBack == percent);
                logger.info("setChargeStopCapacityState probe: wrote=" + percent
                        + " readBack=" + readBack + " supported=" + chargeCapSupported);
            }
            if (chargeCapSupported) lastAppliedCapPercent = percent;
            return chargeCapSupported;
        } catch (Exception e) {
            logger.debug("setChargeStopCapacityState failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set the BEV charge-cap master switch. Tries the SOC-save switch first
     * (pairs with setSOCTarget), then the legacy charge-stop switch.
     */
    public boolean setChargeCapEnabled(boolean enabled) {
        Boolean primary = trySetSocSaveSwitch(enabled);
        if (primary != null) return primary.booleanValue();
        return setChargeStopSwitchFallback(enabled);
    }

    /**
     * PRIMARY enable: BYDAutoChargingDevice.setSocSaveSwitch(1=on / 0=off).
     * Returns null when the method is absent so the caller can fall back.
     */
    private Boolean trySetSocSaveSwitch(boolean enabled) {
        if (chargingDevice == null) return null;
        Object result = BydDeviceHelper.callMethod(chargingDevice, "setSocSaveSwitch", enabled ? 1 : 0);
        if (result == null) return null;
        boolean accepted = (result instanceof Integer) && ((Integer) result).intValue() == 0;
        logger.info("setSocSaveSwitch(" + (enabled ? 1 : 0) + ") accepted=" + accepted);
        return Boolean.valueOf(accepted);
    }

    /** FALLBACK enable: legacy BYDAutoChargingDevice.setChargeStopSwitchState (0=off, 1=on). */
    private boolean setChargeStopSwitchFallback(boolean enabled) {
        if (chargingDevice == null) {
            logger.warn("setChargeStopSwitchState: chargingDevice null");
            return false;
        }
        if (chargeCapProbed && !chargeCapSupported) {
            return false;
        }
        try {
            Method m;
            try {
                m = chargingDevice.getClass().getMethod("setChargeStopSwitchState", int.class);
            } catch (NoSuchMethodException nsme) {
                logger.warn("setChargeStopSwitchState not present on this firmware");
                return false;
            }
            int v = enabled ? 1 : 0;
            Object result = m.invoke(chargingDevice, v);
            boolean accepted = result instanceof Integer && ((Integer) result).intValue() == 0;
            if (!accepted) {
                logger.debug("setChargeStopSwitchState(" + v + ") returned " + result);
            }
            return accepted;
        } catch (Exception e) {
            logger.debug("setChargeStopSwitchState failed: " + e.getMessage());
            return false;
        }
    }

    // --- Ambient Lighting ---

    public boolean setAmbientLightEnabled(boolean on) {
        try {
            return BydDeviceHelper.sendSetCommand(lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_MAIN_SWITCH_SET, on ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setAmbientLightEnabled failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAmbientBrightness(int level) {
        try {
            if (level < 0 || level > 100) return false;
            return BydDeviceHelper.sendSetCommand(lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_CUSTOM_BRIGHTNESS_SET, level);
        } catch (Exception e) {
            logger.debug("setAmbientBrightness failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAmbientColor(int colorValue) {
        try {
            return BydDeviceHelper.sendSetCommand(lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_CUSTOM_COLOR_SET, colorValue);
        } catch (Exception e) {
            logger.debug("setAmbientColor failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set the interior ambient colour by its 1-based palette index (1-31) via the
     * Setting device's setIALColor. This is the path used by the ambient-colour
     * vehicle control and automation action (distinct from setAmbientColor above,
     * which drives the LIGHT_ATMOSPHERE_CUSTOM_COLOR raw value on the Light device).
     * Returns true when the SDK reports success (result == 0).
     */
    public boolean setAmbientLight(int colour) {
        try {
            if (colour < 1 || colour > 31) return false;
            // The SDK method is 2-arg setIALColor(int area, int color) — NOT 1-arg. The
            // 1-arg lookup found no method and silently no-op'd (returned null), which is
            // why ambient colour appeared to do nothing. Use area 1 (main/all cabin) to
            // match our own reader getIALColor(1). If the 2-arg form is somehow absent on
            // a trim, fall back to the 1-arg attempt rather than failing outright.
            Object result = BydDeviceHelper.callMethod(settingDevice, "setIALColor", 1, colour);
            if (result == null) {
                result = BydDeviceHelper.callMethod(settingDevice, "setIALColor", colour);
            }
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAmbientLight failed: " + e.getMessage());
        }
        return false;
    }

    // --- Seats ---

    public boolean setSeatHeating(int position, int level) {
        try {
            if (position < 1 || position > 4) return false;
            if (level < 0 || level > 3) return false;
            // SDK method: settingDevice.setSeatHeatingState(position, normalizedLevel)
            // Level normalization: coerceIn(level, 0, 2) + 1 → 0→1(off), 1→2(low), 2→3(high)
            int normalizedLevel = Math.min(level, 2) + 1;
            Object result = BydDeviceHelper.callMethod(settingDevice, "setSeatHeatingState", position, normalizedLevel);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setSeatHeating failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setSeatVentilation(int position, int level) {
        try {
            if (position < 1 || position > 4) return false;
            if (level < 0 || level > 3) return false;
            // Level normalization: coerceIn(level, 0, 2) + 1 → 0→1(off), 1→2(low), 2→3(high).
            // Matches the OEM firmware's seat-level normalization.
            int normalizedLevel = Math.min(level, 2) + 1;

            // Capability gate via BYDAutoSettingDevice.hasFeature(). The
            // canonical SDK exposes this for hardware detection — if it
            // returns DEVICE_NOT_HAS_THE_FEATURE we know the vehicle (e.g.
            // Atto 3 base trim) doesn't have ventilated seats wired and we
            // shouldn't pretend the SDK accepting the call means anything.
            // Probed once per session and cached.
            if (!seatVentFeatureProbed) {
                seatVentFeatureProbed = true;
                seatVentFeatureSupported = probeHasFeature(settingDevice, "SEAT_VENTILATING");
                if (!seatVentFeatureSupported) {
                    logger.warn("Seat ventilation: hasFeature(\"SEAT_VENTILATING\") returned 0. "
                        + "Vehicle hardware lacks ventilated seats. UI should grey out the control.");
                }
            }

            // Use the canonical SDK method directly. This matches the OEM
            // firmware's seat-ventilation setter, and the BYD stub SDK at
            // android/hardware/bydauto/setting/BYDAutoSettingDevice.java only
            // defines this name. The previous "fallback chain" of
            // setSeatBlowingState / setSeatCoolingState / etc. was guesswork
            // — none of those exist in the OEM firmware or the
            // stub SDK. Removed.
            Method m;
            try {
                m = settingDevice.getClass().getMethod("setSeatVentilatingState", int.class, int.class);
            } catch (NoSuchMethodException nsme) {
                logger.warn("Seat ventilation: setSeatVentilatingState not present on this firmware "
                    + "(framework-side gap, not hardware) — cannot control ventilation.");
                return false;
            }
            Object result = m.invoke(settingDevice, position, normalizedLevel);
            boolean accepted = result instanceof Integer && ((Integer) result).intValue() == 0;
            if (!accepted) {
                logger.debug("setSeatVentilatingState(" + position + ", " + normalizedLevel
                    + ") returned " + result);
                return false;
            }
            // Honest result: only return true when the hardware actually
            // exists. Otherwise the SDK accepts the call but nothing happens
            // physically and the UI would mislead the user with a green
            // toast.
            return seatVentFeatureSupported;
        } catch (Exception e) {
            logger.debug("setSeatVentilation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Recall a stored driver-side seat memory position (slot 1 or 2) — moves the
     * seat to whatever was previously saved into that slot.
     *
     * <p>Uses the "WAKE" feature id ({@code SET_LF_MEMORY_LOCATION_WAKE_SET}): on
     * this platform the memory subsystem has two distinct ids per seat — a plain
     * "SET" id that <em>stores</em> the current physical position into a slot, and
     * a "WAKE" id that <em>recalls</em> (activates) a stored slot. This is the
     * recall half; {@link #setSeatMemorySave} is the store half. SDK feature lives
     * on settingDevice — Adas.* IDs do not accept this set.
     */
    public boolean setSeatMemoryPosition(int position) {
        try {
            if (position < 1 || position > 2) return false;
            int result = BydDeviceHelper.callSetSingle(settingDevice, BydFeatureIds.SETTING_LF_MEMORY_LOCATION_WAKE_SET, position);
            // Success = HAL accepted. callSetSingle returns the raw SDK code on
            // success (SETTING_COMMAND_SUCCESS, which is NOT guaranteed 0 on this
            // platform — sibling families prove non-zero SUCCESS, e.g. CHARGING=2)
            // and -1 on sigperm/exception; documented HAL failure is -2147482648.
            // So test >= 0 (the proven convention used by sendSetCommand), NOT == 0.
            return result >= 0;
        } catch (Exception e) {
            logger.debug("setSeatMemoryPosition failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Save (store) the driver seat's <em>current</em> physical position into a
     * memory slot (1 or 2) — the mirror of {@link #setSeatMemoryPosition}. This
     * writes to the same slots as the physical door memory buttons, so a save here
     * overwrites what those buttons would recall.
     *
     * <p>Uses the "SET" feature id ({@code SET_LF_MEMORY_LOCATION_SET}), not the
     * "WAKE" recall id. Same signature-permission caveat as every other
     * settingDevice write (CPD, seat heat/vent): the SDK method resolves and rides
     * the identical {@link BydDeviceHelper#callSetSingle} path, but the HAL may
     * reject the write from our UID on a firmware that server-side-gates it
     * (HAL failure = -1 / -2147482648). Returns true when the HAL accepts.
     */
    public boolean setSeatMemorySave(int position) {
        try {
            if (position < 1 || position > 2) return false;
            int result = BydDeviceHelper.callSetSingle(settingDevice, BydFeatureIds.SETTING_LF_MEMORY_LOCATION_SET, position);
            // See setSeatMemoryPosition: >= 0 is the correct success test (SDK
            // SUCCESS code is not guaranteed 0; -1/-2147482648 are the failures).
            return result >= 0;
        } catch (Exception e) {
            logger.debug("setSeatMemorySave failed: " + e.getMessage());
        }
        return false;
    }

    public boolean setChildPresenceDetection(int value) {
        try {
            if (value < 1 || value > 3) return false;
            // 1 is for on, 2 is for off and 3 is for delay
            // Route through sendSetCommand (success = code >= 0) on the setting device — the
            // same convention the sibling setItacState uses on this device and the
            // feature-id ADAS setters use. The old callSetSingle(...) == 0 used the fragile
            // 3-int set() overload with an exact-zero test, so a benign non-zero-positive HAL
            // return was misread as failure.
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_CPD_SWITCH_STATUS_SET, value);
        } catch (Exception e) {
            logger.debug("setChildPresenceDetection failed: " + e.getMessage());
        }
        return false;
    }

    /** Cached BYDAutoSettingDevice.hasFeature("SEAT_VENTILATING") result; probed once. */
    private volatile boolean seatVentFeatureProbed = false;
    private volatile boolean seatVentFeatureSupported = false;

    /**
     * Probe (and cache) whether the trim has ventilated seats. Used by the
     * vehicle-control UI to grey out the cool buttons on cars without the
     * hardware (e.g. base-trim Atto 3, Seal without comfort package).
     */
    public boolean isSeatVentilationSupported() {
        if (!seatVentFeatureProbed) {
            seatVentFeatureProbed = true;
            seatVentFeatureSupported = probeHasFeature(settingDevice, "SEAT_VENTILATING");
        }
        return seatVentFeatureSupported;
    }

    /**
     * Capability probe via BYDAutoSettingDevice.hasFeature(String).
     * Returns DEVICE_HAS_THE_FEATURE (1) on supported vehicles per the
     * canonical SDK. Treat any result == 1 as supported.
     */
    private static boolean probeHasFeature(Object settingDevice, String feature) {
        if (settingDevice == null || feature == null) return false;
        try {
            Method m = settingDevice.getClass().getMethod("hasFeature", String.class);
            Object result = m.invoke(settingDevice, feature);
            if (result instanceof Number) {
                return ((Number) result).intValue() == 1;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // --- Lights ---

    public boolean setDayTimeLight(boolean enable) {
        try {
            Object result = BydDeviceHelper.callMethod(lightDevice, "setDayTimeLightState", enable ? 1 : 2);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setDayTimeLight failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Set hazard (double-flash) lights on/off. Writes the double-flash COMMAND feature
     * via the generic set path (on=1, off=2 — the {@code setDayTimeLightState}
     * convention). The feature id is {@link HazardLightProbe#LIGHT_CMD_DOUBLE_FLASH}
     * (resolve-by-name → 0x39400033 fallback); it is the single source of truth so the
     * on-device probe ({@code GET /api/debug/light/fire?candidate=A}) and this SET agree.
     *
     * <p><b>Unconfirmed on this platform.</b> Hazard SET has no reference-app precedent
     * (mature OEM apps only READ hazard state) and the writable feature id is inferred,
     * not a documented SDK constant. If the HAL rejects it (uid/package gate or a
     * standstill interlock), this returns false and the action reports failure rather
     * than pretending to work. Validate with the probe first; if a different candidate
     * lands, update {@link HazardLightProbe#LIGHT_CMD_DOUBLE_FLASH} (or add a dedicated
     * winning id) and this method follows automatically. The hazard READBACK
     * ({@code getLightStatus(8)} → {@code snap.hazard}) is independent and already works.
     */
    public boolean setHazardLights(boolean enable) {
        if (lightDevice == null) return false;
        try {
            int code = BydDeviceHelper.sendSetCommandRaw(
                    lightDevice, HazardLightProbe.LIGHT_CMD_DOUBLE_FLASH, enable ? 1 : 2);
            // sendSetCommandRaw returns the raw SDK code: 0 = accepted. A negative code
            // (e.g. -2147482648 FAILED) means the HAL refused the write.
            return code == 0;
        } catch (Exception e) {
            logger.debug("setHazardLights failed: " + e.getMessage());
        }
        return false;
    }

    // --- ADAS ---

    /**
     * Speed-limit warning on/off. Value convention on=2/off=1 (matches the reference
     * SDK). Two feature ids exist for this on different trims: our original
     * {@code ADAS_SLW_FUNC_SWITCH_STATE_SET} (name absent from the reference SDK — a
     * trim-specific id), and the reference's own {@code ADAS_ISLA_SWITCH_SET} (ISLA,
     * the id its speed-limit-alert control uses). Rather than bet on one, try the SLW id
     * first and, if the HAL refuses it, fall back to the ISLA id — so the control works
     * whichever id this trim honours. Both are writes on adasDevice via callSetSingle
     * (0 = accepted).
     */
    public boolean setSpeedLimitWarning(boolean enable) {
        int v = enable ? 2 : 1;
        try {
            // Route through setAdasFeature → sendSetCommand (success = code >= 0), the SAME
            // path the OEM firmware's setSpeedLimitAlert uses for the ISLA id and the
            // convention every other feature-id ADAS setter here already uses. The old
            // callSetSingle(...) == 0 used the fragile 3-int set() overload AND an exact-zero
            // test, so a benign non-zero-positive HAL return was read as failure (the
            // reported "speed-limit warning doesn't work"). A refused primary id still
            // returns the large-negative FAILED code, so the ISLA fallback is still tried.
            if (setAdasFeature(BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE_SET, v)) {
                return true;
            }
            // Primary id refused → fall back to the reference-confirmed ISLA id.
            logger.info("setSpeedLimitWarning: SLW id refused, trying ISLA id");
            return setAdasFeature(BydFeatureIds.ADAS_ISLA_SWITCH_SET, v);
        } catch (Exception e) {
            logger.debug("setSpeedLimitWarning failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Lane-assist mode (Lane Departure Warning / Prevention) via the OEM firmware's
     * dedicated {@code BYDAutoADASDevice.setLKSMode(int)} method — NOT the generic
     * {@code set(featureId,value)} path, and NOT the ELKA (emergency) switch the old
     * impl mistakenly wrote to on the wrong (setting) device.
     *
     * <p>Takes an APP-LEVEL mode and maps it to the MCU value the HAL expects, per the
     * OEM firmware: {@code 0→0 (Off), 1→1 (LDW), 2→4 (LDP), 3→3 (LDW+LDP)}. Note the
     * non-contiguous 2→4 — LDP is MCU value 4, so a naive pass-through would set the
     * wrong mode. Probed by name so an SDK rename surfaces at WARN rather than silently
     * writing to a dead device.
     */
    public boolean setLaneAssistMode(int mode) {
        if (ensureAdasDevice() == null) {
            logger.warn("setLaneAssistMode: adasDevice unavailable");
            return false;
        }
        int mcuValue;
        switch (mode) {
            case 1:  mcuValue = 1; break; // LDW
            case 2:  mcuValue = 4; break; // LDP (MCU 4, not 2)
            case 3:  mcuValue = 3; break; // LDW + LDP
            case 0:
            default: mcuValue = 0; break; // Off
        }
        Method m;
        try {
            m = adasDevice.getClass().getMethod("setLKSMode", int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn("setLKSMode: method not present on "
                + adasDevice.getClass().getSimpleName()
                + " — lane-assist control unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setLKSMode lookup failed: " + e.getMessage());
            return false;
        }
        try {
            // Accept-on-no-throw (matches the OEM firmware's setLaneAssistMode and
            // our setAdasReflection/mirror-fold/brightness contract) — do NOT gate on the
            // SDK return, which can be a benign non-zero/negative code read as failure.
            m.invoke(adasDevice, mcuValue);
            logger.info("setLKSMode(mode=" + mode + " mcu=" + mcuValue + ") invoked");
            return true;
        } catch (Exception e) {
            logger.warn("setLKSMode(mode=" + mode + " mcu=" + mcuValue + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Current lane-assist mode as app-level {@code 0=Off / 1=LDW / 2=LDP / 3=LDW+LDP},
     * or -1 if unavailable. Reads {@code BYDAutoADASDevice.getLKSMode()} and undoes the
     * setter's app→MCU mapping (MCU {@code 0→0, 1→1, 4→2, 3→3}). Used for toggle/cycle
     * readback and UI state.
     */
    public int getLaneAssistMode() {
        if (ensureAdasDevice() == null) return -1;
        try {
            Method m = adasDevice.getClass().getMethod("getLKSMode");
            Object r = m.invoke(adasDevice);
            if (!(r instanceof Number)) return -1;
            int mcu = ((Number) r).intValue();
            if (mcu == BydFeatureIds.SDK_NOT_AVAILABLE) return -1;
            switch (mcu) {
                case 0: return 0; // Off
                case 1: return 1; // LDW
                case 4: return 2; // LDP
                case 3: return 3; // LDW + LDP
                default: return -1;
            }
        } catch (NoSuchMethodException nsme) {
            return -1;
        } catch (Exception e) {
            logger.debug("getLaneAssistMode failed: " + e.getMessage());
            return -1;
        }
    }

    // ── ADAS feature writes ──────────────────────────────────────────────────
    // All ADAS feature-id writes target the ADAS device (BYDAutoADASDevice), NOT the
    // setting device. The orphaned setters below originally wrote to settingDevice,
    // which is why they were silent no-ops. A generic helper keeps them consistent and
    // guards the null device. Feature ids + on/off values are per the OEM SDK; verify
    // on-car via GET /api/vehicle/adas before trusting on any given trim.

    /** Fully-qualified ADAS device class — shared by init and the on-demand re-acquire. */
    private static final String ADAS_DEVICE_CLASS = "android.hardware.bydauto.adas.BYDAutoADASDevice";

    /**
     * Return the ADAS device, re-acquiring it on demand if it is currently null.
     *
     * <p>{@code adasDevice} is normally bound once during {@link #init(Context)}. But if the
     * ADAS HAL binder isn't ready at daemon startup (a boot race), that single bind returns
     * null and — with no re-acquire path — EVERY ADAS control (cross-traffic brake/alert,
     * TLA/DOW/RCW, ISLC/ELKA/FCW, BSD/AEB/TSR, ESP, lane-assist) stays permanently dead for
     * the collector's lifetime. This mirrors the OEM SDK reference's own ensure-device pattern:
     * if already bound, reuse it; otherwise attempt a fresh bind now so a transient startup
     * null self-heals on the next control call. A genuinely absent class on an unsupported
     * trim still returns null, same as before.
     */
    private Object ensureAdasDevice() {
        if (adasDevice != null) return adasDevice;
        if (context == null) {
            // init() hasn't run yet — nothing to bind against.
            return null;
        }
        Object device = BydDeviceHelper.getDevice(ADAS_DEVICE_CLASS, context);
        if (device != null) {
            adasDevice = device;
            logger.info("ensureAdasDevice: ADAS device re-acquired on demand (was null at init)");
        } else {
            logger.warn("ensureAdasDevice: ADAS device still unavailable on re-acquire");
        }
        return adasDevice;
    }

    /** Generic ADAS feature-id write on the ADAS device. False if device unavailable. */
    private boolean setAdasFeature(int featureId, int value) {
        if (ensureAdasDevice() == null) {
            logger.warn("setAdasFeature: adasDevice unavailable (id=" + featureId + ")");
            return false;
        }
        try {
            return BydDeviceHelper.sendSetCommand(adasDevice, featureId, value);
        } catch (Exception e) {
            logger.debug("setAdasFeature(id=" + featureId + ",v=" + value + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Generic ADAS reflection write for functions with a dedicated method and no feature
     * id (BSD/AEB/SLA/…), mirroring {@link #setLaneAssistMode}'s setLKSMode path. False
     * if the device or method is absent on this OEM build.
     *
     * <p>SUCCESS SEMANTICS: accept-on-no-throw — the SAME contract the OEM firmware
     * app uses for EVERY ADAS reflection setter (it invokes {@code setBSDState/setSLAState/
     * setAEBState/setLKSMode/…} and unconditionally returns true, never inspecting the SDK
     * return). Routing these through {@link #isSdkWriteSuccess} was the reported "most ADAS
     * controls don't work" bug: that helper reports failure when the SDK method returns
     * Boolean {@code false} or a benign NEGATIVE int, so a physically-successful write was
     * misreported as failed and the automation/keymap said it didn't work. This mirrors the
     * fix already applied to {@link #setMirrorsFolded} and {@link #setBrightnessViaMethodOn}.
     * The invoke NOT throwing is the success signal; only a missing method or a thrown
     * invoke is a real failure.
     */
    private boolean setAdasReflection(String method, int value) {
        if (ensureAdasDevice() == null) {
            logger.warn(method + ": adasDevice unavailable");
            return false;
        }
        try {
            Method m = adasDevice.getClass().getMethod(method, int.class);
            m.invoke(adasDevice, value);
            logger.info(method + "(" + value + ") invoked");
            return true;
        } catch (NoSuchMethodException nsme) {
            logger.warn(method + ": not present on " + adasDevice.getClass().getSimpleName()
                    + " — unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn(method + "(" + value + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** Blind-Spot Detection on/off — reflection setBSDState (on=1/off=0). */
    public boolean setBlindSpotDetection(boolean enabled) {
        return setAdasReflection("setBSDState", enabled ? 1 : 0);
    }

    /**
     * Automatic Emergency Braking. SAFETY-CRITICAL and ENABLE-ONLY — enforced HERE, at
     * the single chokepoint, not just in the automation action. A disable request from
     * ANY entry point (automation, key mapping, MQTT/Home Assistant, cloud) is refused,
     * so no path can silently switch off collision braking. The automation action only
     * offers "on"; but the keymap catalog + MQTT switch could pass false, so we reject
     * it defensively here. (The HAL/ECU also re-arms AEB each ignition regardless.)
     * Returns true only on a successful ENABLE; false for a refused disable or a failed
     * write. Reflection setAEBState, on=1.
     */
    public boolean setEmergencyBraking(boolean enabled) {
        if (!enabled) {
            logger.warn("setEmergencyBraking: refusing to DISABLE AEB — enable-only safety control");
            return false;
        }
        return setAdasReflection("setAEBState", 1);
    }

    /** Traffic Sign Recognition on/off — reflection setSLAState (on=1/off=0). */
    public boolean setTrafficSignRecognition(boolean enabled) {
        return setAdasReflection("setSLAState", enabled ? 1 : 0);
    }

    /** Rear Cross Traffic Alert on/off (RCTA id, on=1/off=0). */
    public boolean setRearCrossTrafficAlert(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_RCTA_STATE_SET, enabled ? 1 : 0);
    }

    /** Rear Cross Traffic BRAKE on/off (ECTB id, on=1/off=0). SAFETY (auto-brake). */
    public boolean setRearCrossTrafficBraking(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_ECTB_STATE_SET, enabled ? 1 : 0);
    }

    /** Front Cross Traffic Alert on/off (FCTA id, on=1/off=0). */
    public boolean setFrontCrossTrafficAlert(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_FCTA_SWITCH_SET, enabled ? 1 : 0);
    }

    /** Front Cross Traffic BRAKE on/off (FCTB id, on=1/off=0). SAFETY (auto-brake). */
    public boolean setFrontCrossTrafficBraking(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_FCTB_SWITCH_SET, enabled ? 1 : 0);
    }

    /** Traffic Light Attention on/off (TLA id, on=1/off=0). */
    public boolean setTrafficLightAttention(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_TLA_SWITCH_SET, enabled ? 1 : 0);
    }

    /** Open-Door Warning on/off (DOW id, on=1/off=0). */
    public boolean setOpenDoorWarning(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_DOW_STATE_SET, enabled ? 1 : 0);
    }

    /** Rear Collision Warning on/off (RCW id, on=1/off=0). */
    public boolean setRearCollisionWarning(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_RCW_STATE_SET, enabled ? 1 : 0);
    }

    /** Speed Limit Control (ISLC) on/off (on=2/off=1, per OEM convention). */
    public boolean setSpeedLimitControl(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_ISLC_SWITCH_SET, enabled ? 2 : 1);
    }

    /** Emergency / Urgent Lane Keeping (ELKA) on/off (on=2/off=1). SAFETY (auto-steer). */
    public boolean setEmergencyLaneKeeping(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_ELKA_SWITCH_SET, enabled ? 2 : 1);
    }

    /**
     * Forward Collision Warning sensitivity LEVEL (not on/off): 0=off, 1=low, 2=med,
     * 3=high mapped to the OEM's 1/2/3/4. SAFETY — lowering the level delays warnings.
     */
    public boolean setFcwLevel(int level) {
        int mcu;
        switch (level) {
            case 1:  mcu = 2; break; // low
            case 2:  mcu = 3; break; // medium
            case 3:  mcu = 4; break; // high
            case 0:
            default: mcu = 1; break; // off
        }
        return setAdasFeature(BydFeatureIds.ADAS_FCW_LEVEL_SET, mcu);
    }

    /**
     * Set Electronic Stability Program (ESP/ESC) on/off. SAFETY-CRITICAL, so this does
     * NOT trust a hard-coded polarity: the OEM SDK's {@code setESPState(int)} uses an
     * INVERTED convention (0=ON / 1=OFF) on {@code adasDevice}, but that is unverified
     * on every trim, and getting it backwards would DISABLE stability control when the
     * user asked to enable it. So we write the desired state, then READ IT BACK via
     * {@link #getEspState()} and, if the car reports the opposite of what was asked,
     * retry with the flipped value. The method returns true only when a readback
     * confirms the requested state actually took (or, if no readback is available on
     * this trim, when the primary write's SDK code was success — best effort).
     *
     * <p>Uses the dedicated {@code setESPState} reflection method on {@code adasDevice}
     * (the same BYD SDK surface family as our working {@code setLKSMode}), NOT the old
     * mis-wired {@code ADAS_ESP_STATE_SET} feature-id write on {@code settingDevice}
     * (wrong device + guessed id + wrong polarity — it was effectively inert/inverted).
     */
    public boolean setEspState(boolean enabled) {
        if (ensureAdasDevice() == null) {
            logger.warn("setEspState: adasDevice unavailable");
            return false;
        }
        Method m;
        try {
            m = adasDevice.getClass().getMethod("setESPState", int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn("setESPState: method not present on "
                    + adasDevice.getClass().getSimpleName() + " — ESP control unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setESPState lookup failed: " + e.getMessage());
            return false;
        }
        // Primary polarity per the OEM SDK: ESP ON = 0, OFF = 1 (INVERTED). Try it,
        // then verify by readback; flip on mismatch so a wrong-polarity trim self-heals.
        int primary = enabled ? 0 : 1;
        int flipped = enabled ? 1 : 0;
        boolean wrote = invokeEspWrite(m, primary);
        Boolean now = readEspOn();
        if (now != null) {
            if (now == enabled) return true;                  // confirmed correct
            logger.warn("setEspState: readback shows " + now + " after writing " + primary
                    + " for enabled=" + enabled + " — retrying flipped value " + flipped);
            invokeEspWrite(m, flipped);
            Boolean after = readEspOn();
            // Confirmed after flip, or (no second readback) assume the flip took.
            return after == null ? true : after == enabled;
        }
        // No readback on this trim — can't verify; report the primary write's result.
        return wrote;
    }

    /** Invoke setESPState(value) on adasDevice; true on no-throw. The caller
     *  ({@link #setEspState}) prefers a getESPState readback to confirm; this raw result is
     *  only used on trims with no readback, so it uses the same accept-on-no-throw contract
     *  as the OEM firmware's setESPEnabled (which never inspects the SDK return) —
     *  rather than isSdkWriteSuccess, which can read a benign non-zero/negative code as a
     *  failure and make the no-readback fallback wrongly report ESP as unchanged. */
    private boolean invokeEspWrite(Method m, int value) {
        try {
            m.invoke(adasDevice, value);
            return true;
        } catch (Exception e) {
            logger.warn("setESPState(" + value + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** ESP state as a resolved boolean (true=on/false=off), or null if unreadable. */
    private Boolean readEspOn() {
        int raw = getEspState();
        if (raw == 0) return Boolean.TRUE;   // SDK inverted: 0 = ON
        if (raw == 1) return Boolean.FALSE;  // 1 = OFF
        return null;                          // -1 / unknown encoding → can't verify
    }

    /**
     * Read the raw ESP/ESC state via the {@code getESPState()} reflection method on
     * {@code adasDevice} (matching the {@code setESPState} write path). Returns the raw
     * SDK int (INVERTED convention: 0=on / 1=off), or -1 when unreadable. NOTE: raw
     * semantics differ from the old setting-HAL reader — callers use {@link #readEspOn()}
     * for the resolved boolean. Kept public for on-device verification via
     * {@code GET /api/vehicle/adas}.
     */
    public int getEspState() {
        if (ensureAdasDevice() == null) return -1;
        try {
            Method m = adasDevice.getClass().getMethod("getESPState");
            Object r = m.invoke(adasDevice);
            return (r instanceof Number) ? ((Number) r).intValue() : -1;
        } catch (Exception e) {
            logger.debug("getEspState failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * iTAC (Intelligent Torque Adaption Control) on/off via the setting HAL
     * ({@code SETTING_ITAC_STATE_SET}). Unlike ESP this is a performance/traction
     * feature, not a stability-safety interlock. Returns false if the write path
     * threw. The feature ids are decoded from the DiLink APK (see BydFeatureIds);
     * verify with {@link #getItacState()} on-car before trusting the toggle.
     *
     * <p>Value convention per the OEM firmware's
     * {@code setItacEnabled}: the HAL wants {@code on=1 / off=2} (the same 1=on/2=off
     * convention as CPD), NOT {@code off=0}. Sending 0 was ignored by the HAL, which
     * is why the toggle appeared to do nothing.
     */
    public boolean setItacState(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_ITAC_STATE_SET, enabled ? 1 : 2);
        } catch (Exception e) {
            logger.debug("setItacState failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Read the raw iTAC state via the setting HAL ({@code SETTING_ITAC_STATE}).
     * Returns the raw SDK int (typically 1=on / 0=off), or -1 when the read fails /
     * device is unavailable. Exposed for on-device verification of the iTAC feature
     * ids before the toggle is trusted (mirrors {@link #getEspState()}).
     */
    public int getItacState() {
        try {
            return BydDeviceHelper.callGetSingle(settingDevice, BydFeatureIds.SETTING_ITAC_STATE);
        } catch (Exception e) {
            logger.debug("getItacState failed: " + e.getMessage());
            return -1;
        }
    }

    // NOTE: setIslaSwitch / setIslcSwitch removed — they were dead (no callers) and
    // carried the wrong-device/wrong-value bug (settingDevice + 1:0). The LIVE speed-
    // limit paths are setSpeedLimitWarning (ISLA, adasDevice, 2:1) and
    // setSpeedLimitControl (ISLC, adasDevice, 2:1), which are correct per the OEM SDK.

    // --- Media ---

    /**
     * Send media info (artist + title) to the instrument cluster display.
     * Encodes the string as UTF-16LE bytes for the BYD instrument cluster.
     */
    public boolean sendMediaInfo(String artistAndTitle) {
        try {
            if (artistAndTitle == null) return false;
            String formatted = "  " + artistAndTitle + "  ";
            byte[] bytes = formatted.getBytes("UTF-16LE");
            byte[] finalBytes;
            if (bytes.length > 255) {
                // Truncate to 253 bytes + 2-byte null terminator
                finalBytes = new byte[255];
                System.arraycopy(bytes, 0, finalBytes, 0, 253);
                finalBytes[253] = 0;
                finalBytes[254] = 0;
            } else {
                finalBytes = bytes;
            }
            int result = BydDeviceHelper.callSetBuffer(instrumentDevice, 1140527112, finalBytes);
            return result >= 0;
        } catch (Exception e) {
            logger.debug("sendMediaInfo failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setMusicSource(int source) {
        try {
            if (source < 0 || source > 14) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_MUSIC_SOURCE_SET, source);
        } catch (Exception e) {
            logger.debug("setMusicSource failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setMusicState(int state) {
        try {
            if (state < 1 || state > 2) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_MUSIC_STATE_SET, state);
        } catch (Exception e) {
            logger.debug("setMusicState failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setMusicPlaybackProgress(int currentSeconds, int totalSeconds) {
        try {
            if (currentSeconds < 0 || totalSeconds < 0) return false;
            // Pack current and total into the feature ID call
            // Progress is sent as a single int: current seconds (the cluster calculates percentage)
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_MUSIC_PLAYBACK_PROGRESS_SET, currentSeconds);
        } catch (Exception e) {
            logger.debug("setMusicPlaybackProgress failed: " + e.getMessage());
            return false;
        }
    }

    // --- Display ---

    /**
     * Invoke a dedicated {@code BYDAutoSettingDevice.<methodName>(int)} brightness
     * method (0..100) by reflection. Per the OEM firmware:
     * screen brightness is driven through dedicated methods
     * ({@code setInfotainmentBrightness} / {@code setDriverDisplayBrightness} /
     * {@code setHUDBrightness}), NOT the generic {@code set(SET_BRIGHTNESS_GEAR_SET,
     * EventValue)} feature-id path — that feature id is dead code in the SDK and
     * writing to it does nothing. Probed by name so an SDK rename surfaces at WARN.
     *
     * <p>SUCCESS SEMANTICS: the OEM firmware's own brightness setters INVOKE and then
     * return their own {@code true} on no-exception — they DO NOT inspect the SDK
     * method's return value. We match that: {@code isSdkWriteSuccess} was
     * mis-rejecting physically-successful writes for these void/int-returning setters
     * (a non-zero-but-benign return read as failure), which is why the control
     * reported failure even when it worked. So here: if the invoke does not throw, the
     * write was accepted → return {@code true}. Only a missing method or a thrown
     * invoke is a real failure.
     */
    private boolean setBrightnessViaMethod(String methodName, int level) {
        return setBrightnessViaMethodOn(settingDevice, methodName, level);
    }

    /** Invoke a {@code setXBrightness(int)} on a SPECIFIC setting-device handle. Factored
     *  out of setBrightnessViaMethod so the cluster path can also target the system-context
     *  handle. A null device is a quiet no-op (returns false) — used when the system-context
     *  fallback device couldn't be obtained. */
    private boolean setBrightnessViaMethodOn(Object dev, String methodName, int level) {
        if (dev == null) return false;
        if (level < 0 || level > 100) return false;
        Method m;
        try {
            m = dev.getClass().getMethod(methodName, int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn(methodName + ": method not present on "
                + dev.getClass().getSimpleName()
                + " — brightness control unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn(methodName + " lookup failed: " + e.getMessage());
            return false;
        }
        try {
            m.invoke(dev, level);
            // Accept on no-exception (mirrors the OEM firmware's own setter contract);
            // do NOT gate on the return value — these setters return a non-success-coded
            // int/void that isSdkWriteSuccess wrongly treated as failure.
            logger.info(methodName + "(" + level + ") invoked");
            return true;
        } catch (Exception e) {
            logger.warn(methodName + "(" + level + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Infotainment (centre) screen brightness. The centre screen is the MAIN Android
     * display, so besides the BYD SDK setter we ALSO drive the Android
     * {@code Settings.System.SCREEN_BRIGHTNESS} via the shell (the daemon runs as UID
     * 2000 / shell, which holds WRITE_SETTINGS). This is the lever that actually moves
     * the panel when the SDK setter is a no-op on a trim, and it also disables
     * auto-brightness first (otherwise the ambient-light sensor immediately overrides
     * a manual set — the observed "brightness does nothing" symptom). The 0..100
     * percentage maps onto the Android 0..255 range. Best-effort: succeeds if EITHER
     * path is accepted.
     */
    public boolean setInfotainmentBrightness(int level) {
        if (level < 0 || level > 100) return false;
        boolean sdkOk = setBrightnessViaMethod("setInfotainmentBrightness", level);
        boolean shellOk = setAndroidScreenBrightness(level);
        return sdkOk || shellOk;
    }

    // A second BYDAutoSettingDevice handle obtained with the REAL system context
    // (ActivityThread.getSystemContext), NOT the daemon's synthetic PermissionBypassContext.
    // Some setting-HAL writes — driver-cluster brightness among them — bind to the calling
    // package/context and SILENTLY NO-OP when invoked on a device created from the synthetic
    // context (the invoke throws nothing, so it looks like it "worked"). The reference app
    // runs in a normal app process with a real context, which is why the same SDK call moves
    // the cluster there. Built lazily + cached, off the shared `settingDevice` so the many
    // setters that already work on it are never disturbed. null if the system context or
    // device can't be obtained (we then just keep the primary result).
    private volatile Object systemCtxSettingDevice;
    private volatile boolean systemCtxSettingResolved = false;

    private Object getSystemContextSettingDevice() {
        if (systemCtxSettingResolved) return systemCtxSettingDevice;
        synchronized (this) {
            if (systemCtxSettingResolved) return systemCtxSettingDevice;
            Object dev = null;
            try {
                // Same proven "real system context" acquisition used by the multimedia
                // device fallback, guarded by a timeout thread since getSystemContext can
                // deadlock on some framework states.
                final Object[] result = new Object[1];
                Thread t = new Thread(() -> {
                    try {
                        Class<?> atClass = Class.forName("android.app.ActivityThread");
                        Object at = atClass.getMethod("currentActivityThread").invoke(null);
                        if (at != null) {
                            android.content.Context sysCtx = (android.content.Context)
                                    atClass.getMethod("getSystemContext").invoke(at);
                            if (sysCtx != null) {
                                result[0] = BydDeviceHelper.getDevice(
                                        "android.hardware.bydauto.setting.BYDAutoSettingDevice", sysCtx);
                            }
                        }
                    } catch (Throwable inner) {
                        logger.debug("systemCtx setting device inner: " + inner.getMessage());
                    }
                }, "SettingDevice-SysCtx");
                t.setDaemon(true);
                t.start();
                t.join(3000);
                if (t.isAlive()) { logger.warn("systemCtx setting device timed out"); t.interrupt(); }
                else dev = result[0];
            } catch (Throwable e) {
                logger.debug("getSystemContextSettingDevice failed: " + e.getMessage());
            }
            systemCtxSettingDevice = dev;
            systemCtxSettingResolved = true;
            if (dev != null) logger.info("System-context BYDAutoSettingDevice resolved (cluster-brightness fallback)");
            return dev;
        }
    }

    /**
     * Driver-cluster brightness. Three complementary write paths, best-effort (succeeds if
     * any lands):
     *  1) {@code setDriverDisplayBrightness(0..100)} on the setting HAL (primary handle) —
     *     matches the OEM DiLink3/4 path.
     *  2) the same setter on a real system-context handle — the synthetic daemon context can
     *     silently no-op this particular write (see {@link #getSystemContextSettingDevice}).
     *  3) {@code BYDAutoInstrumentDevice.setBacklightBrightness(1..12)} — the driver INSTRUMENT
     *     cluster is served by the instrument device on some trims (DiLink5 / where the
     *     setting-HAL write no-ops). the reference implementation drives the cluster this way (C4178d
     *     setBacklightBrightness on a 1-12 gear scale, wrapping values >11). We map the
     *     incoming 0-100 percent onto 1..12 so a single action reaches whichever HAL this
     *     trim honours.
     */
    public boolean setDriverDisplayBrightness(int level) {
        boolean primary = setBrightnessViaMethod("setDriverDisplayBrightness", level);
        boolean sysCtx = setBrightnessViaMethodOn(getSystemContextSettingDevice(), "setDriverDisplayBrightness", level);
        boolean instrument = setInstrumentBacklightBrightness(level);
        return primary || sysCtx || instrument;
    }

    /**
     * Fallback driver-cluster path: {@code BYDAutoInstrumentDevice.setBacklightBrightness(int)}
     * on a 1..12 gear scale (reference implementation). Maps the incoming 0..100 percent to 1..12. A
     * missing method / device is a quiet no-op (returns false). Accept-on-no-throw, matching
     * the other reflection setters.
     */
    private boolean setInstrumentBacklightBrightness(int level) {
        if (instrumentDevice == null) return false;
        if (level < 0 || level > 100) return false;
        int gear = Math.max(1, Math.min(12, Math.round(1 + (level / 100f) * 11f)));
        try {
            Method m = instrumentDevice.getClass().getMethod("setBacklightBrightness", int.class);
            m.invoke(instrumentDevice, gear);
            logger.info("setBacklightBrightness(" + gear + ") invoked (from " + level + "%)");
            return true;
        } catch (NoSuchMethodException nsme) {
            logger.debug("setBacklightBrightness absent on instrument device — skipping cluster fallback");
            return false;
        } catch (Exception e) {
            logger.debug("setBacklightBrightness(" + gear + ") failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setHudBrightness(int level) {
        // The HUD is a driver-facing instrument display served by the same BYDAutoSettingDevice
        // HAL as the driver cluster, so it is subject to the identical synthetic-context
        // silent-no-op (the invoke throws nothing but the panel never moves). Mirror
        // setDriverDisplayBrightness: also invoke on the real system-context handle and
        // succeed if either lands. Without this, the primary handle "succeeds" (no throw)
        // yet the HUD never changes, so both the HUD-brightness and HUD-on/off actions
        // appeared to do nothing.
        boolean primary = setBrightnessViaMethod("setHUDBrightness", level);
        boolean sysCtx = setBrightnessViaMethodOn(getSystemContextSettingDevice(), "setHUDBrightness", level);
        return primary || sysCtx;
    }

    /**
     * Drive the main Android display brightness via {@code settings put system}. The
     * daemon is UID 2000 (shell), so {@code settings put} writes System settings
     * directly — the same mechanism used elsewhere for Secure settings. Turns
     * auto-brightness OFF first ({@code screen_brightness_mode 0}) so a manual level
     * isn't instantly overridden by the ambient sensor, then sets the 0..255 value
     * scaled from the 0..100 input. Bounded exec so a stuck {@code settings} can never
     * park the caller. Returns true when the write command completes without error.
     */
    private boolean setAndroidScreenBrightness(int percent) {
        int v255 = Math.max(0, Math.min(255, Math.round(percent / 100f * 255f)));
        String script = "settings put system screen_brightness_mode 0; "
                + "settings put system screen_brightness " + v255;
        try {
            Process p = new ProcessBuilder("sh", "-c", script)
                    .redirectErrorStream(true).start();
            // Drain + bound so the child can't wedge on a full pipe or outlive the call.
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[512];
                try { while (is.read(buf) != -1) { /* discard */ } } catch (Throwable ignored) {}
            }, "screen-brightness-drain");
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); logger.warn("setAndroidScreenBrightness: settings put timed out"); return false; }
            try { is.close(); } catch (Throwable ignored) {}
            boolean ok = p.exitValue() == 0;
            logger.info("setAndroidScreenBrightness: " + percent + "% (=" + v255 + "/255) shell ok=" + ok);
            return ok;
        } catch (Throwable t) {
            logger.warn("setAndroidScreenBrightness failed: " + t.getMessage());
            return false;
        }
    }

    // --- Screen power (backlight on/off) ---
    //
    // Turn the infotainment (centre) screen fully on/off. This is the SAME
    // proven mechanism the ACC-sentry daemon uses to blank the panel while
    // keeping CPU/radio alive — deliberately NOT PowerManager.goToSleep()
    // (which the car's ACC-on keep-awake logic fights, causing the panel to
    // flick back on). The probe order, verified working on this hardware, is:
    //   1. PowerManager.turnBacklightOn/turnBacklightOff(long)   — lowercase
    //   2. PowerManager.TurnBacklightOn/TurnBacklightOff(long)   — PascalCase
    //   3. BYDAutoSettingDevice.turnBacklightOn/turnBacklightOff()
    //   4. shell: settings put system screen_brightness + input keyevent
    //      224 (WAKEUP) / 223 (SLEEP)
    // First success short-circuits. Every tier is independently try/caught so
    // a missing method just falls through to the next. Returns true when any
    // tier is accepted.
    private volatile Method screenBacklightLowerOn;
    private volatile Method screenBacklightLowerOff;
    private volatile boolean screenBacklightLowerProbed;
    private volatile Method screenBacklightPascalOn;
    private volatile Method screenBacklightPascalOff;
    private volatile boolean screenBacklightPascalProbed;

    public boolean setScreenPower(boolean on) {
        logger.info("setScreenPower: " + (on ? "ON" : "OFF"));
        // Tier 1 + 2: PowerManager backlight reflection (the primary path).
        Context ctx = context;
        if (ctx != null) {
            try {
                android.os.PowerManager pm =
                        (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    Class<?> pmClass = pm.getClass();
                    long now = android.os.SystemClock.uptimeMillis();
                    // Tier 1 — lowercase turnBacklightOn/Off(long).
                    if (!screenBacklightLowerProbed) {
                        try { screenBacklightLowerOn = pmClass.getMethod("turnBacklightOn", long.class); } catch (Throwable ignored) {}
                        try { screenBacklightLowerOff = pmClass.getMethod("turnBacklightOff", long.class); } catch (Throwable ignored) {}
                        screenBacklightLowerProbed = true;
                    }
                    Method lower = on ? screenBacklightLowerOn : screenBacklightLowerOff;
                    if (lower != null) {
                        try {
                            lower.invoke(pm, now);
                            logger.info("setScreenPower: PowerManager." + (on ? "turnBacklightOn" : "turnBacklightOff") + " OK");
                            return true;
                        } catch (Throwable t) {
                            logger.debug("PowerManager backlight (lower) invoke failed: " + t.getMessage());
                        }
                    }
                    // Tier 2 — PascalCase TurnBacklightOn/Off(long).
                    if (!screenBacklightPascalProbed) {
                        try { screenBacklightPascalOn = pmClass.getMethod("TurnBacklightOn", long.class); } catch (Throwable ignored) {}
                        try { screenBacklightPascalOff = pmClass.getMethod("TurnBacklightOff", long.class); } catch (Throwable ignored) {}
                        screenBacklightPascalProbed = true;
                    }
                    Method pascal = on ? screenBacklightPascalOn : screenBacklightPascalOff;
                    if (pascal != null) {
                        try {
                            pascal.invoke(pm, now);
                            logger.info("setScreenPower: PowerManager." + (on ? "TurnBacklightOn" : "TurnBacklightOff") + " OK");
                            return true;
                        } catch (Throwable t) {
                            logger.debug("PowerManager backlight (pascal) invoke failed: " + t.getMessage());
                        }
                    }
                }
            } catch (Throwable t) {
                logger.debug("setScreenPower PowerManager path failed: " + t.getMessage());
            }
        }
        // Tier 3 — BYDAutoSettingDevice.turnBacklightOn/turnBacklightOff() (no-arg).
        if (settingDevice != null) {
            try {
                Method m = settingDevice.getClass().getMethod(on ? "turnBacklightOn" : "turnBacklightOff");
                m.invoke(settingDevice);
                logger.info("setScreenPower: BYDAutoSettingDevice." + (on ? "turnBacklightOn" : "turnBacklightOff") + " OK");
                return true;
            } catch (NoSuchMethodException nsme) {
                logger.debug("setScreenPower: BYDAutoSettingDevice backlight method absent");
            } catch (Throwable t) {
                logger.debug("setScreenPower BYD path failed: " + t.getMessage());
            }
        }
        // Tier 4 — shell fallback: set brightness then WAKEUP/SLEEP keyevent.
        return setScreenPowerViaShell(on);
    }

    /**
     * Shell fallback for screen power (daemon is UID 2000 / shell). Mirrors the
     * ACC-sentry daemon's fallback: nudge brightness (128 on / 0 off) then inject
     * KEYCODE_WAKEUP (224) / KEYCODE_SLEEP (223). Bounded exec so a stuck child
     * never parks the caller. Returns true when the command completes cleanly.
     */
    private boolean setScreenPowerViaShell(boolean on) {
        int brightness = on ? 128 : 0;
        int keyevent = on ? 224 : 223; // 224=WAKEUP, 223=SLEEP
        String script = "settings put system screen_brightness " + brightness + "; "
                + "input keyevent " + keyevent;
        try {
            Process p = new ProcessBuilder("sh", "-c", script)
                    .redirectErrorStream(true).start();
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[512];
                try { while (is.read(buf) != -1) { /* discard */ } } catch (Throwable ignored) {}
            }, "screen-power-drain");
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); logger.warn("setScreenPowerViaShell: timed out"); return false; }
            try { is.close(); } catch (Throwable ignored) {}
            boolean ok = p.exitValue() == 0;
            logger.info("setScreenPowerViaShell: " + (on ? "ON" : "OFF") + " (kev " + keyevent + ") shell ok=" + ok);
            return ok;
        } catch (Throwable t) {
            logger.warn("setScreenPowerViaShell failed: " + t.getMessage());
            return false;
        }
    }

    // --- Miscellaneous ---

    /**
     * Fold/unfold the exterior mirrors. Per the OEM SDK this is the dedicated
     * {@code setMirrorFoldState(int)} reflection method on the bodywork device (1=fold/
     * 0=unfold) — preferred over the generic {@code MIRROR_REARVIEW_SET} feature-id
     * write (the id is real, so the generic set() may also route, but the named method
     * is the confirmed path). Falls back to the feature-id write if the method is absent.
     */
    public boolean setMirrorsFolded(boolean folded) {
        int val = folded ? 1 : 0;
        if (bodyworkDevice != null) {
            try {
                Method m = bodyworkDevice.getClass().getMethod("setMirrorFoldState", int.class);
                m.invoke(bodyworkDevice, val);
                // PARITY with the OEM firmware: it invokes setMirrorFoldState and
                // returns true on any non-throwing call — it does NOT inspect the return
                // value. This method returns void on this platform's bodywork device, so
                // routing the result through isSdkWriteSuccess (which special-cases an
                // Integer status) was fragile: a firmware that returns a nonzero-but-OK
                // status code would be misread as failure and the automation/keymap would
                // report "didn't work" even though the mirrors moved. The invoke not
                // throwing is the success signal, exactly as the reference app treats it.
                logger.info("setMirrorFoldState(" + val + ") invoked (fold=" + folded + ")");
                return true;
            } catch (NoSuchMethodException nsme) {
                logger.info("setMirrorFoldState absent — falling back to feature-id write");
            } catch (Exception e) {
                logger.debug("setMirrorFoldState failed: " + e.getMessage());
                return false;
            }
        }
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.MIRROR_REARVIEW_SET, val);
        } catch (Exception e) {
            logger.debug("setMirrorsFolded fallback failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Child lock on/off for one rear door. Per the OEM SDK this is a dedicated
     * {@code setChildLockState(int area, int enable)} reflection method on the BODYWORK
     * device (area = left?1:2, enable = 1/0) — NOT the feature-id write to a doorLock
     * device the old impl used (the doorlock HAL didn't accept it → silent no-op). Falls
     * back to the old feature-id path only if the named method is absent on this trim.
     */
    public boolean setChildLock(boolean left, boolean enable) {
        int area = left ? 1 : 2;
        int val = enable ? 1 : 0;
        if (bodyworkDevice != null) {
            try {
                Method m = bodyworkDevice.getClass().getMethod("setChildLockState", int.class, int.class);
                Object r = m.invoke(bodyworkDevice, area, val);
                return isSdkWriteSuccess(bodyworkDevice, r, "setChildLockState");
            } catch (NoSuchMethodException nsme) {
                logger.info("setChildLockState absent — falling back to doorlock feature-id write");
            } catch (Exception e) {
                logger.debug("setChildLockState failed: " + e.getMessage());
                return false;
            }
        }
        // Legacy fallback (older SDK): feature-id write on the doorlock device.
        try {
            int featureId = left ? BydFeatureIds.DOORLOCK_CHILDLOCK_LEFT_SET : BydFeatureIds.DOORLOCK_CHILDLOCK_RIGHT_SET;
            return BydDeviceHelper.sendSetCommand(doorLockDevice, featureId, val);
        } catch (Exception e) {
            logger.debug("setChildLock fallback failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Turn the phone wireless charging pad on/off. The feature id matches the OEM SDK,
     * but the OFF value convention is NOT trustworthy across trims: several sibling BYD
     * toggles (iTAC, CPD) documented that the HAL IGNORES {@code 0} and the real "off"
     * is {@code 2}, so a plain {@code enabled?1:0} write can leave the pad ON when the
     * user asked OFF (the reported "on/off doesn't work"). So we write {@code 1/0},
     * READ BACK via {@link #getWirelessChargingState()}, and if the car didn't reach the
     * requested state, retry with the {@code 1/2} convention. Returns true once a
     * readback confirms the requested state (or, if no readback is available on this
     * trim, when the primary write's SDK code was success — best effort).
     */
    public boolean setWirelessCharging(boolean enabled) {
        boolean wrote = writeWirelessCharging(enabled ? 1 : 0);
        Boolean now = readWirelessOn();
        if (now != null) {
            if (now == enabled) return true;               // confirmed
            // Off wasn't honoured by the 0 convention (or on didn't take) — retry with
            // the 1/2 convention that iTAC/CPD proved is the real one on some trims.
            logger.warn("setWirelessCharging: readback=" + now + " after 1/0 write for enabled="
                    + enabled + " — retrying 1/2 convention");
            writeWirelessCharging(enabled ? 1 : 2);
            Boolean after = readWirelessOn();
            return after == null ? true : after == enabled;
        }
        return wrote; // no readback on this trim — report the primary write
    }

    /** One wireless-charging switch write; true iff the SDK reports success. */
    private boolean writeWirelessCharging(int value) {
        try {
            return BydDeviceHelper.sendSetCommand(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_SWITCH_SET, value);
        } catch (Exception e) {
            logger.debug("writeWirelessCharging(" + value + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** Wireless pad state as a boolean (true=on/false=off), or null if unreadable. */
    private Boolean readWirelessOn() {
        int raw = getWirelessChargingState();
        // Combined status: 1 = on/charging, 0 = off. (2 also appears as "off/idle" on
        // some trims — treat anything non-1 as off.) -1/sentinel → can't verify.
        if (raw < 0) return null;
        return raw == 1;
    }

    /** Raw combined wireless-charging state (CHARGING_WIRELESS_STATE), or -1 if unreadable. */
    public int getWirelessChargingState() {
        if (chargingDevice == null) return -1;
        try {
            Object v = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_STATE, Integer.class);
            if (v == null) return -1;
            int raw = BydDeviceHelper.getIntValue(v);
            if (raw == BydFeatureIds.BMS_UNAVAILABLE || raw == BydFeatureIds.INVALID_VALUE) return -1;
            return raw;
        } catch (Exception e) {
            logger.debug("getWirelessChargingState failed: " + e.getMessage());
            return -1;
        }
    }

    public boolean wakeUpMcu() {
        try {
            Object result = BydDeviceHelper.callGetter(powerDevice, "wakeUpMcu");
            return result instanceof Number && ((Number) result).intValue() >= 0;
        } catch (Exception e) {
            logger.debug("wakeUpMcu failed: " + e.getMessage());
            return false;
        }
    }

    public boolean rotatePad() {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_PAD_ROTATION_SET, 1);
        } catch (Exception e) {
            logger.debug("rotatePad failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Drift mode on/off. Per the OEM SDK this is a dedicated {@code setDriftModeState(int)}
     * reflection method on the SETTING device (on=1/off=0) — NOT the engine-device
     * feature-id write the old dead impl used (wrong device AND wrong mechanism). Probed
     * by name so an SDK rename surfaces at WARN rather than writing to a dead path.
     * (Currently no caller wires this; kept correct so a future drive-mode action can.)
     */
    public boolean setDriftMode(boolean enabled) {
        if (settingDevice == null) {
            logger.warn("setDriftMode: settingDevice unavailable");
            return false;
        }
        try {
            Method m = settingDevice.getClass().getMethod("setDriftModeState", int.class);
            Object r = m.invoke(settingDevice, enabled ? 1 : 0);
            return isSdkWriteSuccess(settingDevice, r, "setDriftModeState");
        } catch (NoSuchMethodException nsme) {
            logger.warn("setDriftModeState: not present on this OEM build");
            return false;
        } catch (Exception e) {
            logger.debug("setDriftMode failed: " + e.getMessage());
            return false;
        }
    }

    // --- Drive / energy modes ---
    // energy-feedback / steer-assist are written via BYDAutoSettingDevice SDK
    // setters (HAL device methods, not the CarSettings ContentProvider; SDK
    // convention: setter returns 0 on success). BYDAutoEnergyDevice exposes both
    // get{Operation,Energy}Mode (see collectEnergy) AND the matching setters
    // set{Operation,Energy}Mode(int) — confirmed present in the OEM implementation
    // and invoked below via invokeOptionalModeSetter, which surfaces a genuinely
    // absent method at WARN rather than a silent false. The writes are still gated
    // by the BYD signature-permission wall (the HAL may reject from our UID), so a
    // non-zero result is treated as failure by isSdkWriteSuccess.

    /**
     * Drive/operation mode. Values are the SDK OperationMode enum from the docs
     * (doc/android/hardware/bydauto/energy): ENERGY_OPERATION_ECONOMY = 1,
     * ENERGY_OPERATION_SPORT = 2 — the ONLY two operation modes. There is no
     * NORMAL and no SNOW on this axis (SNOW is a separate road-surface value,
     * ENERGY_ROAD_SURFACE_SNOW = 2, on a different setter). Callers map the words
     * to these ints in VehicleControlCatalog.driveModeValue(); mirrors the value
     * read back as operationMode.
     *
     * <p>{@code BYDAutoEnergyDevice.setOperationMode(int)} is a real SDK method —
     * confirmed present in the OEM implementation. Like every SDK write on this
     * platform it is still gated by the BYD signature-permission wall, so the HAL
     * may reject the write from our UID (returns non-zero) even though the method
     * resolves. We invoke via {@link #invokeOptionalModeSetter} so that if a
     * firmware variant ever drops/renames the method we surface it at WARN rather
     * than a silent false.
     */
    public boolean setOperationMode(int mode) {
        return invokeOptionalModeSetter(energyDevice, "setOperationMode", mode,
                "operation mode (ECO/SPORT)");
    }

    /**
     * Drive mode on the SETTING-device "drive config" axis, which — unlike the
     * energy-device {@link #setOperationMode} (ECO=1/SPORT=2 only) — supports
     * NORMAL. Value convention per the OEM firmware's setOperationMode
     * chain. The value is on the drive-config axis:
     * <pre>NORMAL = 1, ECO = 2, SPORT = 3, SNOW = 4</pre>
     * (NOTE: distinct from the energy-device numbering — do not mix them.)
     *
     * <p>Fallback chain (first that sticks wins), per the OEM firmware:
     * <ol>
     *   <li>{@code settingDevice.setDriveConfig(int)} — the dedicated method;</li>
     *   <li>generic {@code set(SETTING_TARGET_DRIVING_MODE, value)};</li>
     *   <li>generic {@code set(SETTING_TARGET_DRIVING_MODE_ALT, value)};</li>
     *   <li>last resort for ECO/SPORT only: the energy-device
     *       {@code setOperationMode} on ITS axis (config 2/3 → energy 1/2). NORMAL
     *       and SNOW have no energy-axis equivalent, so they are not down-converted.</li>
     * </ol>
     *
     * @param configMode drive mode on the config axis (1=normal, 2=eco, 3=sport, 4=snow)
     * @return true if any path reported success
     */
    public boolean setDriveConfigMode(int configMode) {
        // 1) Dedicated setting-device method — judged with isSdkWriteSuccess (checks
        //    the resolved COMMAND_SUCCESS constant), so a real success is trustworthy.
        if (settingDevice != null) {
            Method m = null;
            try {
                m = settingDevice.getClass().getMethod("setDriveConfig", int.class);
            } catch (NoSuchMethodException ignored) {
                // fall through to the fallbacks
            } catch (Exception e) {
                logger.debug("setDriveConfig lookup failed: " + e.getMessage());
            }
            if (m != null) {
                try {
                    Object r = m.invoke(settingDevice, configMode);
                    if (isSdkWriteSuccess(settingDevice, r, "setDriveConfig")) return true;
                } catch (Exception e) {
                    logger.debug("setDriveConfig(" + configMode + ") failed: " + e.getMessage());
                }
            }
        }

        boolean ecoOrSport = (configMode == 2 || configMode == 3);

        // 2) For ECO/SPORT, prefer the KNOWN-GOOD energy-device path before the
        //    guessed target-driving-mode feature ids. The feature ids are
        //    resolveOrFallback guesses and BydDeviceHelper.sendSetCommand treats any
        //    code >= 0 as success — so a HAL that returns >= 0 for an unrecognized id
        //    would falsely "succeed" and starve the working energy fallback. eco/sport
        //    have a proven energy equivalent (config 2/3 → energy 1/2), so use it
        //    first; NORMAL/SNOW have no energy equivalent and MUST use the feature ids.
        if (ecoOrSport && setOperationMode(configMode - 1)) {
            return true;
        }

        // 3) + 4) Generic feature-id writes on the setting device. For NORMAL/SNOW this
        //    is the only path; for eco/sport it is a further fallback if the energy
        //    path above didn't stick.
        if (settingDevice != null) {
            if (BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_TARGET_DRIVING_MODE, configMode)) {
                return true;
            }
            if (BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_TARGET_DRIVING_MODE_ALT, configMode)) {
                return true;
            }
        }
        // 5) NORMAL last-resort: the energy device's own axis. ECO/SPORT map config 2/3 →
        //    energy 1/2 (handled above); by symmetry the energy axis' NORMAL is 0. On a trim
        //    where setDriveConfig + the target-mode feature-ids are all absent (the field
        //    report: eco/sport work via the energy path, NORMAL never applies), this is the
        //    only remaining lever. Best-effort — setOperationMode returns false if the getter
        //    is absent or the HAL rejects 0, so it never falsely "succeeds".
        if (configMode == 1 && setOperationMode(0)) {
            logger.info("setDriveConfigMode(NORMAL): applied via energy-device setOperationMode(0)");
            return true;
        }
        logger.warn("setDriveConfigMode(" + configMode + "): no working drive-config path on this build "
                + "(setDriveConfig absent + target-mode feature-ids rejected"
                + (configMode == 1 ? " + energy setOperationMode(0) rejected" : "") + ")");
        return false;
    }

    /**
     * Read the drive mode on the setting-device drive-config axis
     * (1=normal/2=eco/3=sport/4=snow). Returns the raw int, or -1 when the getter
     * is unavailable / read failed. Used as the primary source for the published
     * drive-mode telemetry so read and write share one axis.
     */
    public int getDriveConfigMode() {
        if (settingDevice == null) return -1;
        try {
            Object r = BydDeviceHelper.callGetter(settingDevice, "getDriveConfig");
            if (r instanceof Number) {
                int v = ((Number) r).intValue();
                // Diagnostic (throttled 1/min): so an on-device logcat reveals whether the
                // setting-device drive-config axis actually works on this trim — the field
                // report is NORMAL never reads/applies while ECO/SPORT do, which happens iff
                // getDriveConfig is absent and only the energy axis (eco/sport, no normal)
                // answers. If this logs a valid 1..4 here, NORMAL is readable and the bug is
                // elsewhere; if it never logs (getter absent) the energy fallback is in play.
                long now = System.currentTimeMillis();
                if (now - lastDriveConfigLogMs > 60000) {
                    lastDriveConfigLogMs = now;
                    logger.info("getDriveConfig raw=" + v + " (1=normal/2=eco/3=sport/4=snow)");
                }
                return v;
            }
        } catch (Exception e) {
            logger.debug("getDriveConfig failed (getter likely absent on this trim): " + e.getMessage());
        }
        return -1;
    }

    /** Throttle for the getDriveConfig diagnostic (1/min). */
    private long lastDriveConfigLogMs = 0;

    /**
     * Energy/powertrain mode: EV vs HEV (BYD SDK EnergyMode enum, matches the
     * value read back as energyMode). Only meaningful on DM/PHEV vehicles.
     *
     * <p>{@code BYDAutoEnergyDevice.setEnergyMode(int)} is a real SDK method
     * (confirmed present in the OEM implementation). Same signature-permission
     * caveat as {@link #setOperationMode}: resolves, but the HAL may reject the
     * write from our UID. Invoked defensively via {@link #invokeOptionalModeSetter}.
     *
     * <p><b>Value guard:</b> the ONLY valid values are {@code 1 = EV} and
     * {@code 3 = HEV} — the OEM firmware hard-rejects anything else. In particular
     * {@code 0 = STOP} (NOT EV): an out-of-range or 0 value reaching the HAL would
     * either be ignored or, worse, command STOP. The catalog word→int mapping only
     * ever produces 1 or 3, but this method is public, so guard here too — a caller
     * that bypasses the mapping can never drive the powertrain to an unintended mode.
     */
    public boolean setEnergyMode(int mode) {
        if (mode != 1 && mode != 3) {
            logger.warn("setEnergyMode: refusing invalid mode " + mode
                + " (only 1=EV / 3=HEV are valid; 0=STOP)");
            return false;
        }
        return invokeOptionalModeSetter(energyDevice, "setEnergyMode", mode,
                "energy/powertrain mode (EV/HEV)");
    }

    /**
     * Invoke an SDK setter by name, tolerating a firmware that dropped or renamed
     * it. {@code set{Operation,Energy}Mode} ARE real BYDAutoEnergyDevice methods
     * (confirmed in the OEM implementation), but reflecting by name lets a variant
     * that lacks them fail loudly instead of silently. Probes for the method and:
     * <ul>
     *   <li>if present — invokes it and honors the SDK 0=success convention
     *       (a non-zero return is the HAL rejecting the write, e.g. sigperm);</li>
     *   <li>if absent — logs at WARN (so an SDK name/shape mismatch surfaces
     *       loudly in production instead of a silent {@code false}) and returns
     *       false, letting the command router treat the SDK leg as no-path.</li>
     * </ul>
     */
    private boolean invokeOptionalModeSetter(Object device, String methodName, int value, String label) {
        if (device == null) {
            logger.warn(methodName + ": device unavailable — " + label + " cannot be set");
            return false;
        }
        Method m;
        try {
            m = device.getClass().getMethod(methodName, int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn(methodName + ": method not present on " + device.getClass().getSimpleName()
                + " — " + label + " has no local SDK write path on this build; route via cloud/CAN instead");
            return false;
        } catch (Exception e) {
            logger.warn(methodName + ": lookup failed: " + e.getMessage());
            return false;
        }
        try {
            Object r = m.invoke(device, value);
            return isSdkWriteSuccess(device, r, methodName);
        } catch (Exception e) {
            logger.warn(methodName + "(" + value + ") failed: " + e.getMessage());
            return false;
        }
    }

    // Per-device cache of the resolved <FAMILY>_COMMAND_SUCCESS constant value, so
    // we reflect it once per device class rather than on every write.
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Integer> COMMAND_SUCCESS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int COMMAND_SUCCESS_UNKNOWN = Integer.MIN_VALUE;

    /**
     * Judge whether a BYD SDK named-setter result means success.
     *
     * <p>The SDK's setter methods return an int result code documented ONLY by
     * name — {@code <FAMILY>_COMMAND_SUCCESS} (e.g. {@code ENERGY_COMMAND_SUCCESS},
     * {@code SETTING_COMMAND_SUCCESS}) — whose NUMERIC value is NOT published in the
     * SDK docs and is NOT guaranteed to be 0. Sibling constants prove non-zero
     * success values exist on this platform (e.g. {@code CHARGING_SUCCESS=2},
     * {@code MALFUNCTION_OK=19}). So the old hardcoded {@code r == 0} test could
     * report FAILURE on a genuine success if the HAL's SUCCESS constant isn't 0 —
     * which, combined with the wrong enum values, is why drive/energy-mode writes
     * logged FAILED.
     *
     * <p>Resolution order (first that applies):
     * <ol>
     *   <li>Reflect the device class's {@code <FAMILY>_COMMAND_SUCCESS} field and
     *       compare the result to it — correct-by-construction, immune to whatever
     *       value BYD chose. The family prefix is derived from the device's simple
     *       class name (BYDAutoEnergyDevice → ENERGY, BYDAutoSettingDevice →
     *       SETTING).</li>
     *   <li>If that constant can't be resolved, fall back to {@code code >= 0} —
     *       the SAME non-inverting convention the proven-working generic write path
     *       ({@link BydDeviceHelper#sendSetCommand}) uses, whose documented failure
     *       code is a large negative ({@code -2147482648}). This never inverts a
     *       real success the way {@code == 0} can.</li>
     *   <li>A {@code Boolean} result maps true→success. A null/void result is
     *       treated as success (the call returned without throwing) — matching
     *       {@code sendSetCommandRaw}'s "non-null result, assume success".</li>
     * </ol>
     */
    private boolean isSdkWriteSuccess(Object device, Object result, String methodName) {
        if (result instanceof Boolean) return (Boolean) result;
        if (!(result instanceof Integer)) {
            // void / null / unexpected type: the invoke didn't throw, so treat as
            // accepted (mirrors BydDeviceHelper.sendSetCommandRaw's assume-success).
            return true;
        }
        int code = (Integer) result;
        int success = resolveCommandSuccess(device.getClass());
        if (success != COMMAND_SUCCESS_UNKNOWN) {
            return code == success;
        }
        // No resolvable SUCCESS constant → use the working generic-path convention.
        return code >= 0;
    }

    /** Resolve and cache {@code <FAMILY>_COMMAND_SUCCESS} for a BYD device class,
     *  or {@link #COMMAND_SUCCESS_UNKNOWN} if none is exposed. */
    private int resolveCommandSuccess(Class<?> deviceClass) {
        Integer cached = COMMAND_SUCCESS_CACHE.get(deviceClass);
        if (cached != null) return cached;
        int resolved = COMMAND_SUCCESS_UNKNOWN;
        try {
            // BYDAutoEnergyDevice → "ENERGY"; BYDAutoSettingDevice → "SETTING".
            String simple = deviceClass.getSimpleName(); // e.g. BYDAutoEnergyDevice
            String family = simple.replaceFirst("^BYDAuto", "").replaceFirst("Device$", "").toUpperCase();
            java.lang.reflect.Field f = deviceClass.getField(family + "_COMMAND_SUCCESS");
            Object v = f.get(null);
            if (v instanceof Integer) resolved = (Integer) v;
        } catch (Throwable ignored) {
            // No such constant on this build — fall back to >= 0 (handled by caller).
        }
        COMMAND_SUCCESS_CACHE.put(deviceClass, resolved);
        if (resolved != COMMAND_SUCCESS_UNKNOWN) {
            logger.info("Resolved " + deviceClass.getSimpleName() + " COMMAND_SUCCESS=" + resolved);
        }
        return resolved;
    }

    /**
     * Energy recuperation / regen-braking strength (BYDAutoSettingDevice).
     *
     * <p>Takes a normalized user level 0..2 (0 = standard/low, 1 = high/large,
     * 2 = max) and converts it to the raw MCU value the HAL expects. Per the OEM
     * firmware's regen-level mapping: the SDK's
     * {@code setEnergyFeedback} wants MCU values {@code 2/3/4}, NOT {@code 0/1/2}.
     * The earlier mapping sent {@code standard=1} (below the valid MCU range — a
     * silent no-op) and {@code high=2} (which is the HAL's *standard* MCU value), so
     * "standard did nothing" and "high set standard" — exactly the observed bug.
     */
    public boolean setEnergyFeedback(int level) {
        if (settingDevice == null) {
            logger.warn("setEnergyFeedback: settingDevice unavailable");
            return false;
        }
        // Normalize the user level (0..2) then map to the MCU value: 0->2, 1->3, 2->4.
        int normalized = Math.max(0, Math.min(2, level));
        int mcuValue = normalized + 2;
        // Probe by name (like setSteerAssist) so an SDK rename of
        // "setEnergyFeedback" surfaces at WARN instead of silently returning
        // NOT_SUPPORTED for every regen_level command.
        Method m;
        try {
            m = settingDevice.getClass().getMethod("setEnergyFeedback", int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn("setEnergyFeedback: method not present on "
                + settingDevice.getClass().getSimpleName()
                + " — regen/energy-recuperation strength unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setEnergyFeedback lookup failed: " + e.getMessage());
            return false;
        }
        try {
            Object r = m.invoke(settingDevice, mcuValue);
            return isSdkWriteSuccess(settingDevice, r, "setEnergyFeedback");
        } catch (Exception e) {
            logger.warn("setEnergyFeedback(level=" + level + " mcu=" + mcuValue + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** Steering-assist weighting: comfort vs sport (BYDAutoSettingDevice). */
    public boolean setSteerAssist(int mode) {
        if (settingDevice == null) return false;
        // Method name must match the SDK exactly — the earlier target string
        // "setSteerAssist" (with the trailing 't') resolved to nothing, so every
        // steering-mode command silently failed as NOT_SUPPORTED. The real
        // BYDAutoSettingDevice method is `public int setSteerAssis(int value)`
        // (no trailing 't'). Probe the correct name first and fall back to the
        // with-'t' spelling in case a future SDK settles on the other stem, so a
        // genuine rename surfaces at WARN instead of returning false.
        Method m = null;
        try {
            m = settingDevice.getClass().getMethod("setSteerAssis", int.class);
        } catch (NoSuchMethodException nsme) {
            try {
                m = settingDevice.getClass().getMethod("setSteerAssist", int.class);
            } catch (NoSuchMethodException nsme2) {
                logger.warn("setSteerAssis: method not present on "
                    + settingDevice.getClass().getSimpleName()
                    + " — steering-assist weighting unsupported on this OEM build");
                return false;
            } catch (Exception e) {
                logger.warn("setSteerAssist lookup failed: " + e.getMessage());
                return false;
            }
        } catch (Exception e) {
            logger.warn("setSteerAssis lookup failed: " + e.getMessage());
            return false;
        }
        try {
            Object r = m.invoke(settingDevice, mode);
            return isSdkWriteSuccess(settingDevice, r, "setSteerAssis");
        } catch (Exception e) {
            logger.warn("setSteerAssis(" + mode + ") failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setNavigationActive(boolean active) {
        try {
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_NAVIGATION_ACTIVATED_SET, active ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setNavigationActive failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Brake-pedal feel ("comfort" vs "sport"/"strong") via {@code
     * BYDAutoADASDevice.setBrakeFootSenseState(int)}. Value convention per the OEM
     * firmware: the app-level choice {@code 0 = comfort / 1 = sport} maps to HAL
     * values {@code comfort → 2}, {@code sport → 0}. This method takes the app-level
     * 0/1 and applies that mapping, so callers use the same 0=comfort/1=sport
     * convention the UI shows.
     *
     * <p>Probed by name on {@code adasDevice} (same device the SLW/ESP controls use)
     * so an SDK rename surfaces at WARN instead of silently no-opping. Returns false
     * when the ADAS device is unavailable or the write path threw.
     */
    public boolean setBrakeFootSense(int appLevel) {
        if (adasDevice == null) {
            logger.warn("setBrakeFootSense: adasDevice unavailable");
            return false;
        }
        // App-level 0=comfort/1=sport → HAL 2=comfort/0=sport (OEM firmware mapping).
        int mcuValue = (appLevel == 0) ? 2 : 0;
        Method m;
        try {
            m = adasDevice.getClass().getMethod("setBrakeFootSenseState", int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn("setBrakeFootSenseState: method not present on "
                + adasDevice.getClass().getSimpleName()
                + " — brake-feel control unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setBrakeFootSenseState lookup failed: " + e.getMessage());
            return false;
        }
        try {
            // Accept-on-no-throw (matches the OEM firmware's setBrakeAssistMode and
            // our ADAS-reflection contract) — do NOT gate on the SDK return, which can be a
            // benign non-zero/negative code the isSdkWriteSuccess helper reads as failure.
            m.invoke(adasDevice, mcuValue);
            logger.info("setBrakeFootSenseState(app=" + appLevel + " mcu=" + mcuValue + ") invoked");
            return true;
        } catch (Exception e) {
            logger.warn("setBrakeFootSenseState(app=" + appLevel + " mcu=" + mcuValue + ") failed: " + e.getMessage());
            return false;
        }
    }

    // ── Drive-mode readbacks (for toggle/cycle + UI state) ────────────────────
    // These mirror the OEM firmware's own getters so a "toggle" flips the ACTUAL
    // current mode (including one set from the car's own menu), not a stale cache.
    // Each returns the APP-LEVEL value (matching the corresponding setter's input),
    // or a negative sentinel when unavailable so the caller can fall back gracefully.

    /**
     * Current brake-pedal feel as app-level {@code 0=comfort / 1=sport}, or -1 if
     * unavailable. Reads {@code BYDAutoADASDevice.getBrakeFootSenseState()} and applies
     * the OEM firmware's own mapping: HAL {@code 2 → comfort(0)}, anything else {@code
     * → sport(1)} (matching setBrakeFootSense's inverse comfort→2/sport→0).
     */
    public int getBrakeFootSense() {
        if (adasDevice == null) return -1;
        try {
            Method m = adasDevice.getClass().getMethod("getBrakeFootSenseState");
            Object r = m.invoke(adasDevice);
            if (!(r instanceof Number)) return -1;
            int hal = ((Number) r).intValue();
            if (hal == BydFeatureIds.SDK_NOT_AVAILABLE) return -1;
            // HAL 2 = comfort → app 0; anything else → sport (app 1). Mirrors the OEM.
            return (hal == 2) ? 0 : 1;
        } catch (NoSuchMethodException nsme) {
            return -1; // getter absent on this trim
        } catch (Exception e) {
            logger.debug("getBrakeFootSense failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Current energy-recuperation (regen) level as app-level {@code 0=standard / 1=high
     * / 2=max}, or -1 if unavailable. Reads {@code BYDAutoSettingDevice.getEnergyFeedback()}
     * (raw HAL MCU value 2/3/4) and undoes setEnergyFeedback's {@code app+2} mapping.
     */
    public int getEnergyFeedback() {
        if (settingDevice == null) return -1;
        try {
            Method m = settingDevice.getClass().getMethod("getEnergyFeedback");
            Object r = m.invoke(settingDevice);
            if (!(r instanceof Number)) return -1;
            int mcu = ((Number) r).intValue();
            if (mcu == BydFeatureIds.SDK_NOT_AVAILABLE) return -1;
            int app = mcu - 2; // inverse of setEnergyFeedback's normalized+2
            return (app >= 0 && app <= 2) ? app : -1;
        } catch (NoSuchMethodException nsme) {
            return -1;
        } catch (Exception e) {
            logger.debug("getEnergyFeedback failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Current steering-assist weighting as app-level {@code 0=comfort / 1=sport}, or -1
     * if unavailable. Reads {@code BYDAutoSettingDevice.getSteerAssis()} and applies the
     * OEM firmware's own mapping: HAL {@code 1 → comfort(0)}, HAL {@code 2 → sport(1)}.
     */
    public int getSteerAssist() {
        if (settingDevice == null) return -1;
        try {
            Method m;
            try {
                m = settingDevice.getClass().getMethod("getSteerAssis");
            } catch (NoSuchMethodException nsme) {
                m = settingDevice.getClass().getMethod("getSteerAssist"); // with-'t' fallback
            }
            Object r = m.invoke(settingDevice);
            if (!(r instanceof Number)) return -1;
            int hal = ((Number) r).intValue();
            if (hal == 1) return 0;      // comfort
            if (hal == 2) return 1;      // sport
            return -1;                    // unknown / unavailable
        } catch (NoSuchMethodException nsme) {
            return -1;
        } catch (Exception e) {
            logger.debug("getSteerAssist failed: " + e.getMessage());
            return -1;
        }
    }

    public boolean setNavigationETA(int minutes) {
        try {
            if (minutes < 0) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_NAVI_ESTIMATED_TIME_SET, minutes);
        } catch (Exception e) {
            logger.debug("setNavigationETA failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setNavigationDistance(int meters) {
        try {
            if (meters < 0) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_NAVI_ESTIMATED_MILEAGE_SET, meters);
        } catch (Exception e) {
            logger.debug("setNavigationDistance failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Log a summary of all current values (for debugging).
     */
    public void logSummary() {
        BydVehicleData d = snapshot.get();
        if (d == null) {
            logger.info("No data collected yet");
            return;
        }
        logger.info("=== BYD Vehicle Data Summary ===");
        if (d.vin != null) logger.info("  VIN: " + d.vin);
        if (!Double.isNaN(d.socPercent)) logger.info("  SOC: " + d.socPercent + "%");
        else logger.warn("  SOC: UNAVAILABLE (statistic/energy devices returned blank)");
        if (!Double.isNaN(d.voltage12v)) logger.info("  12V: " + d.voltage12v + "V");
        if (!Double.isNaN(d.remainKwh)) logger.info("  Remaining: " + d.remainKwh + " kWh");
        if (!Double.isNaN(d.speedKmh)) logger.info("  Speed: " + d.speedKmh + " km/h");
        if (d.gearMode != BydVehicleData.UNAVAILABLE) logger.info("  Gear: " + d.gearMode);
        if (d.totalMileageKm != BydVehicleData.UNAVAILABLE) logger.info("  Odometer: " + d.totalMileageKm + " km");
        if (d.elecRangeKm != BydVehicleData.UNAVAILABLE) logger.info("  EV Range: " + d.elecRangeKm + " km");
        if (!Double.isNaN(d.highCellTempC)) logger.info("  Cell Temp: " + d.highCellTempC + "/" + d.lowCellTempC + "/" + d.avgCellTempC + "°C");
        if (!Double.isNaN(d.highCellVoltage)) logger.info("  Cell Voltage: " + d.highCellVoltage + "/" + d.lowCellVoltage + "V");
        if (!Double.isNaN(d.outsideTempC)) logger.info("  Outside: " + d.outsideTempC + "°C");
        if (d.tyrePressure != null) logger.info("  Tyres: FL=" + d.tyrePressure[0] + " FR=" + d.tyrePressure[1] + " RL=" + d.tyrePressure[2] + " RR=" + d.tyrePressure[3]);
        if (d.powerLevel != BydVehicleData.UNAVAILABLE) logger.info("  Power Level: " + d.powerLevel);
        logger.info("  Devices: " + d.availableDevices.length + " available");
        logger.info("================================");
    }
}
