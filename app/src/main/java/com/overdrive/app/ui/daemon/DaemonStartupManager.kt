package com.overdrive.app.ui.daemon

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.launcher.AdbShellExecutor
import com.overdrive.app.launcher.ZrokLauncher
import com.overdrive.app.launcher.TailscaleLauncher
import com.overdrive.app.logging.LogManager
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.util.PreferencesManager
import com.overdrive.app.ui.viewmodel.DaemonsViewModel

class DaemonStartupManager(
    private val context: Context,
    private val daemonsViewModel: DaemonsViewModel? = null
) {
    private val log = LogManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    // Public so MainActivity / fragments / one-shot callers can route their
    // ADB-shell commands through this single shared launcher instead of
    // allocating fresh `AdbDaemonLauncher(this)` instances each call.
    // Each fresh AdbDaemonLauncher allocates a non-daemon single-thread
    // AdbShellExecutor + a tunnelLauncher.pollScheduler + nested launchers
    // that hold Activity Context refs — those leak when the caller never
    // calls closePersistentConnection().
    val adbLauncher = AdbDaemonLauncher(context)

    // Cached ZrokLauncher for the health-check tick. Allocating a fresh
    // ZrokLauncher + AdbShellExecutor + ScheduledExecutorService every 30s
    // (≈2880 instances per 24h park) burns heap with daemon-thread executors
    // that aren't promptly GC'd because the executor's worker is daemon-flagged
    // but still holds a reference to the launcher's Context. Lazy so we don't
    // pay for it when zrok isn't enabled. The companion @Volatile init flag
    // lets cleanup() decide whether shutdown is needed without forcing
    // allocation.
    @Volatile
    private var zrokLauncherInitialized = false
    @Volatile
    private var zrokAdbShellExecutor: AdbShellExecutor? = null
    private val zrokLauncherForHealthCheck: ZrokLauncher by lazy {
        val executor = AdbShellExecutor(context)
        zrokAdbShellExecutor = executor
        zrokLauncherInitialized = true
        ZrokLauncher(context, executor, log)
    }

    companion object {
        private const val TAG = "DaemonStartup"
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L  // 30 seconds

        val CORE_DAEMONS: List<DaemonType> = listOf(
            DaemonType.CAMERA_DAEMON,
            DaemonType.SENTRY_DAEMON,
            DaemonType.ACC_SENTRY_DAEMON,
        )

        val OPTIONAL_DAEMONS: List<DaemonType> = listOf(
            DaemonType.SINGBOX_PROXY,
            DaemonType.CLOUDFLARED_TUNNEL,
            DaemonType.ZROK_TUNNEL,
            DaemonType.TAILSCALE_TUNNEL,
            DaemonType.TELEGRAM_DAEMON,
        )

        // Track intentional stops so health check doesn't fight the user.
        // Mutated from controller threads (markUserStopped/clearUserStopped)
        // and read from the main looper (runHealthCheck.contains). A plain
        // mutableSetOf is a LinkedHashSet — concurrent add/iterate throws
        // ConcurrentModificationException on the main thread. Wrap with
        // ConcurrentHashMap.newKeySet for thread-safe traversal without
        // an explicit lock.
        val userStoppedDaemons: MutableSet<DaemonType> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun markUserStopped(type: DaemonType) {
            userStoppedDaemons.add(type)
        }

        fun clearUserStopped(type: DaemonType) {
            userStoppedDaemons.remove(type)
        }

        // Keep strong reference to prevent GC during delayed startup
        @Volatile
        private var bootManager: DaemonStartupManager? = null
        
        @Volatile
        private var bootStarted = false

        fun startOnBoot(context: Context) {
            if (bootStarted) return
            bootStarted = true
            userStoppedDaemons.clear()
            val manager = DaemonStartupManager(context, null)
            bootManager = manager
            manager.initializeOnBoot()
        }
    }

    fun initializeOnAppLaunch() {
        log.info(TAG, "=== Initializing daemon startup on app launch ===")
        log.info(TAG, "Waiting 45 seconds before starting daemons (system stabilization)...")

        // Hand off from any pre-existing bootManager (which was launched
        // before MainActivity attached). If we don't shut its scheduler
        // down, both managers fire 30s health checks in parallel — double
        // pkill cascades against the daemon family every tick. Treat
        // `this` as the new owner; drop the boot manager.
        val previousBootManager = bootManager
        if (previousBootManager != null && previousBootManager !== this) {
            log.info(TAG, "Handing off from bootManager → MainActivity-scoped manager")
            // Run cleanup on a background thread, NOT on the main looper.
            // cleanup() now calls adbLauncher.releasePerInstanceResources()
            // (per-instance executor + tunnel-poll scheduler shutdown only;
            // does NOT touch the process-wide shared Dadb that the new
            // manager has just started using). The shutdownNow() call
            // inside is bounded — it interrupts in-flight Runnables but
            // doesn't block on Socket I/O — so we COULD run this on the
            // main looper in principle. We still post to a background
            // thread defensively in case any future cleanup step adds
            // I/O; the cost is one short-lived daemon Thread per
            // handoff (one-time, not per-tick).
            //
            // Clear the static reference up-front so subsequent
            // initializeOnAppLaunch calls don't re-attempt the handoff,
            // and so the manager can't be re-used after we've started
            // tearing it down.
            bootManager = null
            Thread({
                try {
                    previousBootManager.cleanup()
                } catch (e: Exception) {
                    log.warn(TAG, "bootManager handoff cleanup failed: ${e.message}")
                }
            }, "bootManager-handoff").apply {
                isDaemon = true
                start()
            }
        }

        // Reset user-stopped flags on app launch (fresh start = auto-manage)
        userStoppedDaemons.clear()

        // Enable AccessibilityService keep-alive immediately (doesn't need delay)
        enableAccessibilityKeepAlive()

        // Defensive sentinel cleanup on every app-launch path. If a previous
        // process crashed mid-stop, per-daemon `.disabled` files can be
        // stranded on disk; without this rm, the about-to-be-deployed
        // watchdogs would gate-1 → exit 0 immediately on first iteration.
        // User stop-intent persists in SharedPreferences (PreferencesManager
        // .isDaemonEnabled), not in the .disabled files — so this rm only
        // undoes stale crash-debris, never user choice. Idempotent.
        // (Previously this only fired from MainActivity.onNewIntent's
        // post-update path; missing from the cold-start launch flow.)
        clearStaleSentinels()

        // Wait 45 seconds for system to fully stabilize before starting any daemons
        handler.postDelayed({ startCoreDaemons() }, 45000)
        handler.postDelayed({ startOptionalDaemonsFromPreferences() }, 60000)

        // Start periodic health check after initial daemons have had time to start
        handler.postDelayed({ startDaemonHealthCheck() }, 90000)
    }

    /**
     * Setup privileged shell (UID 1000) on app launch.
     * This enables system-level operations like granting permissions and running daemons as system user.
     */
    /*private fun setupPrivilegedShell(onComplete: () -> Unit) {
        PrivilegedShellSetup.init(context)
        
        // Check if already available
        if (PrivilegedShellSetup.isShellAvailable()) {
            log.info(TAG, "Privileged shell already available (UID 1000)")
            onComplete()
            return
        }
        
        log.info(TAG, "Setting up privileged shell...")
        PrivilegedShellSetup.setup(object : PrivilegedShellSetup.SetupCallback {
            override fun onSuccess() {
                log.info(TAG, "Privileged shell ready (UID 1000)")
                onComplete()
            }
            
            override fun onFailure(reason: String) {
                log.warn(TAG, "Privileged shell setup failed: $reason - continuing with normal startup")
                onComplete()
            }
            
            override fun onProgress(message: String) {
                log.debug(TAG, "Shell setup: $message")
            }
        })
    }*/

    private fun initializeOnBoot() {
        log.info(TAG, "=== Initializing daemon startup on boot ===")
        log.info(TAG, "Waiting 45 seconds before starting daemons (system stabilization)...")
        
        // Reset user-stopped flags on boot
        userStoppedDaemons.clear()

        // Enable AccessibilityService keep-alive immediately on boot
        enableAccessibilityKeepAlive()

        // Defensive sentinel cleanup on boot path too. A power-cycle
        // mid-stop (rare but possible — power loss while user was tapping
        // Stop) would leave the .disabled files on disk; without this rm,
        // BootReceiver-triggered daemon launches would gate-1 → exit
        // immediately. See initializeOnAppLaunch for the full rationale.
        clearStaleSentinels()

        // Wait 45 seconds for system to fully stabilize before starting any daemons
        handler.postDelayed({ startCoreDaemonsViaAdb() }, 45000)
        handler.postDelayed({ startOptionalDaemonsViaAdb() }, 60000)

        // Start periodic health check after initial daemons have had time to start
        handler.postDelayed({ startDaemonHealthCheck() }, 90000)
    }


    fun checkAllDaemonStatuses() {
        log.info(TAG, "=== Checking all daemon statuses ===")
        daemonsViewModel?.let { vm ->
            DaemonType.values().forEach { type -> vm.refreshDaemonStatus(type, logResult = true) }
            // Camera daemon defaults to private stream mode. Public exposure is opt-in
            // per-tunnel (cloudflared / zrok) via the Daemons settings, not a global mode.
            log.info(TAG, "Syncing camera daemon stream mode to: private")
            vm.cameraDaemonController.setStreamMode("private")
        }
    }

    private fun startCoreDaemons() {
        val vm = daemonsViewModel ?: run {
            log.warn(TAG, "ViewModel not available, using ADB launcher")
            startCoreDaemonsViaAdb()
            return
        }
        log.info(TAG, "Starting core daemons (Camera first, then Sentry daemons)...")
        
        // Start Camera Daemon FIRST
        log.info(TAG, "Starting Camera Daemon...")
        vm.startDaemon(DaemonType.CAMERA_DAEMON)
        
        // Start Sentry Daemon after Camera Daemon has time to initialize
        handler.postDelayed({
            log.info(TAG, "Starting Sentry Daemon...")
            vm.startDaemon(DaemonType.SENTRY_DAEMON)
        }, 5000)
        
        // Start ACC Sentry Daemon last
        handler.postDelayed({
            log.info(TAG, "Starting ACC Sentry Daemon...")
            vm.startDaemon(DaemonType.ACC_SENTRY_DAEMON)
        }, 10000)
    }

    private fun startCoreDaemonsViaAdb() {
        log.info(TAG, "Starting core daemons via ADB (Camera first, then Sentry daemons)...")

        // Start Camera Daemon FIRST. Probe the actual --nice-name (`byd_cam_daemon`)
        // not the legacy "camera_daemon" string — `ps -A` on stock Android shows
        // the nice-name, and "camera_daemon" is not a substring of "byd_cam_daemon".
        // The previous literal always reported false → one redundant launch+
        // cleanup ADB round-trip on every boot (the inner `launchDaemon` does
        // its own correct probe at DaemonLauncher.kt:328 and short-circuits, so
        // this was cosmetic, but kept boot ~1-2 s slower than necessary).
        adbLauncher.isDaemonRunning(DaemonType.CAMERA_DAEMON.processName) { running ->
            if (!running) {
                log.info(TAG, "Boot: Starting Camera Daemon...")
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
                adbLauncher.launchDaemon(outputDir, nativeLibDir, createLogCallback("CameraDaemon"))
            } else {
                log.info(TAG, "Boot: Camera Daemon already running")
            }
        }
        
        // Start Sentry Daemon after Camera Daemon has time to initialize
        handler.postDelayed({
            adbLauncher.isSentryDaemonRunning { running ->
                if (!running) {
                    log.info(TAG, "Boot: Starting Sentry Daemon...")
                    adbLauncher.launchSentryDaemon(createLogCallback("SentryDaemon"))
                } else {
                    log.info(TAG, "Boot: Sentry Daemon already running")
                }
            }
        }, 5000)
        
        // Start ACC Sentry Daemon last
        handler.postDelayed({
            adbLauncher.isDaemonRunning("acc_sentry_daemon") { running ->
                if (!running) {
                    log.info(TAG, "Boot: Starting ACC Sentry Daemon...")
                    adbLauncher.launchAccSentryDaemon(
                        onSuccess = { log.info(TAG, "Boot: ACC Sentry Daemon started") },
                        onError = { error -> log.error(TAG, "Boot: ACC Sentry error: $error") }
                    )
                } else {
                    log.info(TAG, "Boot: ACC Sentry Daemon already running")
                }
            }
        }, 10000)
    }


    private fun startOptionalDaemonsFromPreferences() {
        val vm = daemonsViewModel ?: run {
            log.warn(TAG, "ViewModel not available, using ADB launcher")
            startOptionalDaemonsViaAdb()
            return
        }
        log.info(TAG, "Starting optional daemons from preferences...")

        // Singbox starts iff the user enabled it. Tunnels are independent toggles.
        if (PreferencesManager.isDaemonEnabled(DaemonType.SINGBOX_PROXY)) {
            vm.singboxController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Singbox already running, skipping start")
                    handler.postDelayed({ startTunnelFromPreferences(vm) }, 1000)
                } else {
                    log.info(TAG, "Starting Singbox (user enabled)...")
                    handler.post { vm.startDaemon(DaemonType.SINGBOX_PROXY) }
                    handler.postDelayed({ startTunnelFromPreferences(vm) }, 5000)
                }
            }
        } else {
            startTunnelFromPreferences(vm)
        }
        
        // Start Telegram Bot daemon if user enabled it
        if (PreferencesManager.isDaemonEnabled(DaemonType.TELEGRAM_DAEMON)) {
            handler.postDelayed({
                log.info(TAG, "Starting Telegram Bot daemon (user enabled)...")
                vm.startDaemon(DaemonType.TELEGRAM_DAEMON)
            }, 15000)
        }
    }

    private fun startTunnelFromPreferences(vm: DaemonsViewModel) {
        val cloudflaredEnabled = PreferencesManager.isDaemonEnabled(DaemonType.CLOUDFLARED_TUNNEL)
        val zrokEnabled = PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)
        val tailscaleEnabled = PreferencesManager.isDaemonEnabled(DaemonType.TAILSCALE_TUNNEL)

        // Cloudflared and Zrok are mutually exclusive (both expose the dashboard publicly)
        if (cloudflaredEnabled) {
            vm.cloudflaredController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Cloudflared already running, skipping start")
                } else {
                    log.info(TAG, "Starting Cloudflared (user enabled)...")
                    handler.post { vm.startDaemon(DaemonType.CLOUDFLARED_TUNNEL) }
                }
            }
        } else if (zrokEnabled) {
            vm.zrokController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Zrok already running, skipping start")
                } else {
                    log.info(TAG, "Starting Zrok (user enabled)...")
                    handler.post { vm.startDaemon(DaemonType.ZROK_TUNNEL) }
                }
            }
        } else if (!tailscaleEnabled) {
            log.info(TAG, "No tunnel enabled by user")
        }

        // Tailscale runs independently — it's private access, not a public dashboard tunnel
        if (tailscaleEnabled) {
            vm.tailscaleController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Tailscale already running, skipping start")
                } else {
                    log.info(TAG, "Starting Tailscale (user enabled)...")
                    handler.post { vm.startDaemon(DaemonType.TAILSCALE_TUNNEL) }
                }
            }
        }
    }

    private fun startOptionalDaemonsViaAdb() {
        log.info(TAG, "Starting optional daemons via ADB...")
        try {
            // Singbox is gated only by its own user toggle now.
            if (PreferencesManager.isDaemonEnabled(DaemonType.SINGBOX_PROXY)) {
                log.info(TAG, "Boot: Starting Singbox (user enabled)...")
                adbLauncher.startSingbox(createLogCallback("Singbox"))
            }

            val tunnelDelay = 0L

            handler.postDelayed({
                // Cloudflared and Zrok are mutually exclusive
                if (PreferencesManager.isDaemonEnabled(DaemonType.CLOUDFLARED_TUNNEL)) {
                    log.info(TAG, "Boot: Starting Cloudflared...")
                    adbLauncher.launchTunnel(object : AdbDaemonLauncher.TunnelCallback {
                        override fun onLog(message: String) { log.debug(TAG, "[Cloudflared] $message") }
                        override fun onTunnelUrl(url: String) { log.info(TAG, "Boot: Cloudflared URL: $url") }
                        override fun onError(error: String) { log.error(TAG, "Boot: Cloudflared error: $error") }
                    })
                } else if (PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)) {
                    log.info(TAG, "Boot: Starting Zrok...")
                    startZrokOnBoot()
                }

                // Tailscale runs independently of cloudflared/zrok
                if (PreferencesManager.isDaemonEnabled(DaemonType.TAILSCALE_TUNNEL)) {
                    log.info(TAG, "Boot: Starting Tailscale...")
                    startTailscaleOnBoot()
                }
            }, tunnelDelay)
            
            // Start Telegram Bot daemon if user enabled it
            if (PreferencesManager.isDaemonEnabled(DaemonType.TELEGRAM_DAEMON)) {
                handler.postDelayed({
                    log.info(TAG, "Boot: Starting Telegram Bot daemon...")
                    adbLauncher.launchTelegramDaemon(createLogCallback("TelegramBot"))
                }, 15000) // Start after core daemons are up
            }
        } catch (e: Exception) {
            log.error(TAG, "Error starting optional daemons: ${e.message}")
        }
    }
    
    /**
     * Start Zrok tunnel on boot using ZrokLauncher directly.
     */
    private fun startZrokOnBoot() {
        // Reuse the cached zrokLauncherForHealthCheck instead of allocating
        // a fresh ZrokLauncher + AdbShellExecutor + ScheduledExecutorService.
        // Each fresh allocation creates daemon threads that are never
        // shutdown(), so on a 24h park (with health-check relaunches) the
        // process accumulates ~hundreds of stranded executor threads.
        zrokLauncherForHealthCheck.launchZrok(object : ZrokLauncher.ZrokCallback {
            override fun onLog(message: String) {
                log.debug(TAG, "[Zrok Boot] $message")
            }

            override fun onTunnelUrl(url: String) {
                log.info(TAG, "Boot: Zrok URL: $url")
            }

            override fun onError(error: String) {
                log.error(TAG, "Boot: Zrok error: $error")
            }
        })
    }

    /**
     * Start Tailscale tunnel on boot using TailscaleLauncher directly.
     */
    private fun startTailscaleOnBoot() {
        // Reuse the shared adbLauncher's AdbShellExecutor instead of
        // allocating a fresh one. Each fresh AdbShellExecutor allocates a
        // non-daemon single-thread Executors.newSingleThreadExecutor() that
        // we never shutdown — leaks one parked thread per call.
        val tailscaleLauncher = TailscaleLauncher(context, adbLauncher.adbShellExecutor, log)

        tailscaleLauncher.launchTailscale(object : TailscaleLauncher.TailscaleCallback {
            override fun onLog(message: String) {
                log.debug(TAG, "[Tailscale Boot] $message")
            }

            override fun onTunnelUrl(url: String?) {
                log.info(TAG, "Boot: Tailscale URL: $url")
            }

            override fun onError(error: String) {
                log.error(TAG, "Boot: Tailscale error: $error")
            }
        })
    }


    /**
     * Restart tunnel if enabled. When forceRestart=true, kills existing tunnel first
     * so it can pick up new proxy settings (e.g., after singbox toggle).
     */
    private fun restartTunnelIfEnabled(vm: DaemonsViewModel, forceRestart: Boolean = false) {
        val cloudflaredEnabled = PreferencesManager.isDaemonEnabled(DaemonType.CLOUDFLARED_TUNNEL)
        val zrokEnabled = PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)
        val tailscaleEnabled = PreferencesManager.isDaemonEnabled(DaemonType.TAILSCALE_TUNNEL)

        // Cloudflared and Zrok are mutually exclusive
        if (cloudflaredEnabled) {
            vm.cloudflaredController.isRunning { isRunning ->
                if (isRunning && forceRestart) {
                    log.info(TAG, "Restarting Cloudflared to apply new proxy settings...")
                    handler.post {
                        vm.stopDaemon(DaemonType.CLOUDFLARED_TUNNEL)
                        handler.postDelayed({
                            log.info(TAG, "Starting Cloudflared with new settings")
                            vm.startDaemon(DaemonType.CLOUDFLARED_TUNNEL)
                        }, 2000)
                    }
                } else if (!isRunning) {
                    log.info(TAG, "Starting Cloudflared (user enabled)")
                    handler.post { vm.startDaemon(DaemonType.CLOUDFLARED_TUNNEL) }
                } else {
                    log.info(TAG, "Cloudflared already running, no restart needed")
                }
            }
        } else if (zrokEnabled) {
            vm.zrokController.isRunning { isRunning ->
                if (isRunning && forceRestart) {
                    log.info(TAG, "Restarting Zrok to apply new proxy settings...")
                    handler.post {
                        vm.stopDaemon(DaemonType.ZROK_TUNNEL)
                        handler.postDelayed({
                            log.info(TAG, "Starting Zrok with new settings")
                            vm.startDaemon(DaemonType.ZROK_TUNNEL)
                        }, 2000)
                    }
                } else if (!isRunning) {
                    log.info(TAG, "Starting Zrok (user enabled)")
                    handler.post { vm.startDaemon(DaemonType.ZROK_TUNNEL) }
                } else {
                    log.info(TAG, "Zrok already running, no restart needed")
                }
            }
        }

        // Tailscale runs independently of cloudflared/zrok
        if (tailscaleEnabled) {
            vm.tailscaleController.isRunning { isRunning ->
                if (isRunning && forceRestart) {
                    log.info(TAG, "Restarting Tailscale to apply new proxy settings...")
                    handler.post {
                        vm.stopDaemon(DaemonType.TAILSCALE_TUNNEL)
                        handler.postDelayed({
                            log.info(TAG, "Starting Tailscale with new settings")
                            vm.startDaemon(DaemonType.TAILSCALE_TUNNEL)
                        }, 2000)
                    }
                } else if (!isRunning) {
                    log.info(TAG, "Starting Tailscale (user enabled)")
                    handler.post { vm.startDaemon(DaemonType.TAILSCALE_TUNNEL) }
                } else {
                    log.info(TAG, "Tailscale already running, no restart needed")
                }
            }
        }
    }
    
    private fun startTunnelIfEnabled(vm: DaemonsViewModel) {
        restartTunnelIfEnabled(vm, forceRestart = false)
    }

    fun onDaemonToggled(type: DaemonType, enabled: Boolean) {
        if (type in OPTIONAL_DAEMONS) {
            val state = if (enabled) "ON" else "OFF"
            log.info(TAG, "User toggled ${type.displayName} to $state - saving preference")
            PreferencesManager.setDaemonEnabled(type, enabled)
        }
    }

    private fun createLogCallback(name: String): AdbDaemonLauncher.LaunchCallback {
        return object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) { log.debug(TAG, "[$name] $message") }
            override fun onLaunched() { log.info(TAG, "[$name] Started successfully") }
            override fun onError(error: String) { log.error(TAG, "[$name] Error: $error") }
        }
    }

    /**
     * Enable the KeepAliveAccessibilityService via ADB settings.
     * This gives the app the highest process priority — BYD's firmware
     * will not kill an active AccessibilityService even after 24+ hours.
     */
    private fun enableAccessibilityKeepAlive() {
        // Check if already running in-process first
        if (com.overdrive.app.services.KeepAliveAccessibilityService.isRunning()) {
            log.info(TAG, "AccessibilityService already running")
            return
        }

        log.info(TAG, "Enabling AccessibilityService keep-alive via ADB...")
        // Reuse the shared adbLauncher's AdbShellExecutor — see
        // startTailscaleOnBoot for why fresh allocation leaks a thread.
        val serviceLauncher = com.overdrive.app.launcher.ServiceLauncher(
            context,
            adbLauncher.adbShellExecutor,
            log
        )
        serviceLauncher.enableAccessibilityKeepAlive(object : com.overdrive.app.launcher.ServiceLauncher.LaunchCallback {
            override fun onLog(message: String) { log.debug(TAG, "[A11y] $message") }
            override fun onLaunched() { log.info(TAG, "AccessibilityService keep-alive enabled") }
            override fun onError(error: String) { log.warn(TAG, "AccessibilityService enable failed: $error (non-fatal)") }
        })
    }

    // AtomicBoolean (not just @Volatile) because startDaemonHealthCheck does
    // a check-then-set: `if (!running) { running = true; schedule }`.
    // Plain @Volatile gives visibility but not atomicity — two callers can
    // both see false and both set true → two concurrent health-check
    // schedulers, double pkill cascades every 30s. compareAndSet collapses
    // both reads + the set into one atomic transition.
    private val healthCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Periodic health check: every 30s, verify all expected daemons are alive.
     * Core daemons are always restarted. Optional daemons only if user had them enabled.
     * Daemons intentionally stopped by the user are skipped.
     */
    private fun startDaemonHealthCheck() {
        if (!healthCheckRunning.compareAndSet(false, true)) return
        log.info(TAG, "Daemon health check started (interval=${HEALTH_CHECK_INTERVAL_MS / 1000}s)")
        scheduleNextHealthCheck()
    }

    private fun scheduleNextHealthCheck() {
        handler.postDelayed({
            if (healthCheckRunning.get()) {
                runHealthCheck()
                scheduleNextHealthCheck()
            }
        }, HEALTH_CHECK_INTERVAL_MS)
    }

    private fun runHealthCheck() {
        // Core daemons: always restart unless user explicitly stopped
        for (type in CORE_DAEMONS) {
            if (type in userStoppedDaemons) continue
            if (isDaemonStoppedViaTelegram(type)) continue
            checkAndRelaunchDaemon(type)
        }

        // Optional daemons: only restart if user had them enabled in preferences
        for (type in OPTIONAL_DAEMONS) {
            if (type in userStoppedDaemons) continue
            if (isDaemonStoppedViaTelegram(type)) continue
            if (!PreferencesManager.isDaemonEnabled(type)) continue
            checkAndRelaunchDaemon(type)
        }
    }

    /**
     * Check if a daemon was stopped via Telegram bot.
     * Reads the shared state file written by DaemonCommandHandler.
     */
    private fun isDaemonStoppedViaTelegram(type: DaemonType): Boolean {
        val telegramName = when (type) {
            DaemonType.CAMERA_DAEMON -> "camera"
            DaemonType.SENTRY_DAEMON -> "sentry"
            DaemonType.ACC_SENTRY_DAEMON -> "acc"
            DaemonType.TELEGRAM_DAEMON -> "telegram"
            DaemonType.CLOUDFLARED_TUNNEL -> "cloudflared"
            DaemonType.ZROK_TUNNEL -> "zrok"
            DaemonType.TAILSCALE_TUNNEL -> "tailscale"
            DaemonType.SINGBOX_PROXY -> "singbox"
        }
        return try {
            com.overdrive.app.daemon.telegram.DaemonCommandHandler.isDaemonStoppedViaTelegram(telegramName)
        } catch (e: Exception) {
            false
        }
    }

    private fun checkAndRelaunchDaemon(type: DaemonType) {
        // Zrok needs a more specific liveness probe than `ps -A | grep zrok`.
        // The shell watchdog (start_zrok.sh) ALSO matches that pattern, so a
        // stuck or sentinel-disabled watchdog with no share child would
        // silently pass the generic check and the user would see 502s
        // forever. Use ZrokLauncher.isTunnelRunning() — it greps for the
        // actual `zrok share` arg vector, not just any process name
        // containing "zrok".
        if (type == DaemonType.ZROK_TUNNEL) {
            // Two-layer liveness for zrok: (1) process-alive grep on
            // `'zrok share'` argv (catches dead-process), (2) HTTP probe
            // against the public URL (catches edge-session-stale = the
            // original 8–9hr 502 bug where the share process is alive
            // but zrok's edge has dropped the underlay session and
            // returns 502 to external clients).
            //
            // checkTunnelHealth combines both with a 2-strike stickiness
            // counter so a single transient blip doesn't trigger a
            // needless restart. EDGE_STALE on confirmed-stale → relaunch
            // the same way as a dead process.
            zrokLauncherForHealthCheck.checkTunnelHealth { health ->
                when (health) {
                    ZrokLauncher.TunnelHealth.PROCESS_DEAD -> {
                        log.warn(TAG, "Health check: Zrok process is DEAD — relaunching...")
                        relaunchDaemon(type)
                    }
                    ZrokLauncher.TunnelHealth.EDGE_STALE -> {
                        // Edge-stale recovery is a stop+start: the existing
                        // share process is alive, so the normal launchZrok
                        // fast path would short-circuit ("already running")
                        // and do nothing. We need to actively kill the
                        // alive-but-stale process first so the relaunch
                        // gets a fresh underlay session.
                        //
                        // Sequence the relaunch inside stopTunnel's
                        // callbacks rather than via a fixed 2s postDelayed:
                        // stopTunnel writes the disable sentinel + ps-kills
                        // the share + watchdog asynchronously, and a
                        // postDelayed only races them. With the callback
                        // form, the relaunch runs strictly after the kill
                        // script's exit (the launchZrok fast path's
                        // cleanup script then rm's the sentinel before
                        // writing a fresh watchdog).
                        log.warn(TAG, "Health check: Zrok edge session STALE — stopping alive-but-stale process, then relaunching")
                        zrokLauncherForHealthCheck.stopTunnel(object : ZrokLauncher.ZrokCallback {
                            override fun onLog(message: String) {}
                            override fun onTunnelUrl(url: String) {
                                // stopTunnel emits onTunnelUrl("") on success.
                                handler.post {
                                    log.info(TAG, "Edge-stale recovery: relaunching Zrok after stop completed")
                                    relaunchDaemon(type)
                                }
                            }
                            override fun onError(error: String) {
                                // stopTunnel onError still means the kill
                                // script ran — proceed with relaunch.
                                log.warn(TAG, "stopTunnel during edge-stale recovery returned error: $error (continuing relaunch)")
                                handler.post { relaunchDaemon(type) }
                            }
                        })
                    }
                    ZrokLauncher.TunnelHealth.HEALTHY -> {
                        // No-op
                    }
                }
            }
            return
        }
        adbLauncher.isDaemonRunning(type.processName) { isRunning ->
            if (!isRunning) {
                log.warn(TAG, "Health check: ${type.displayName} is DEAD — relaunching...")
                relaunchDaemon(type)
            }
        }
    }

    private fun relaunchDaemon(type: DaemonType) {
        val vm = daemonsViewModel
        if (vm != null) {
            handler.post { vm.startDaemon(type) }
        } else {
            // Fallback: ADB-only launch for when ViewModel is not available (boot path)
            when (type) {
                DaemonType.CAMERA_DAEMON -> {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
                    adbLauncher.launchDaemon(outputDir, nativeLibDir, createLogCallback("HealthCheck-Camera"))
                }
                DaemonType.SENTRY_DAEMON -> {
                    adbLauncher.launchSentryDaemon(createLogCallback("HealthCheck-Sentry"))
                }
                DaemonType.ACC_SENTRY_DAEMON -> {
                    adbLauncher.launchAccSentryDaemon(
                        onSuccess = { log.info(TAG, "HealthCheck: ACC Sentry restarted") },
                        onError = { e -> log.error(TAG, "HealthCheck: ACC Sentry restart failed: $e") }
                    )
                }
                DaemonType.ZROK_TUNNEL -> {
                    // Boot-path zrok recovery (no ViewModel). Without this
                    // branch, the health-check would log "no ADB fallback"
                    // and never restart zrok after a crash on the boot path.
                    log.info(TAG, "HealthCheck: relaunching Zrok tunnel via boot-path fallback")
                    zrokLauncherForHealthCheck.launchZrok(object : ZrokLauncher.ZrokCallback {
                        override fun onLog(message: String) { log.debug(TAG, "[Zrok HealthCheck] $message") }
                        override fun onTunnelUrl(url: String) { log.info(TAG, "HealthCheck: Zrok URL: $url") }
                        override fun onError(error: String) { log.error(TAG, "HealthCheck: Zrok restart failed: $error") }
                    })
                }
                else -> {
                    log.warn(TAG, "Health check: no ADB fallback for ${type.displayName}")
                }
            }
        }
    }

    /**
     * Defensive sentinel cleanup on app launch. If the previous process died
     * mid-stop (or post-update phase 3 was dropped due to an ADB hiccup),
     * per-daemon disable sentinels can be left on disk; the watchdogs would
     * then exit on their next iteration and never recover until manual rm.
     * User stop-intent is persisted in SharedPreferences (PreferencesManager
     * .isDaemonEnabled), not in the .disabled files — so clearing them here
     * only undoes stale crash-debris, never user choice. Idempotent.
     *
     * Routes through this manager's shared AdbDaemonLauncher rather than
     * letting callers allocate a fresh one — a fresh AdbDaemonLauncher
     * spawns a fresh AdbShellExecutor (single-thread non-daemon executor)
     * that's never shutdown(), so it leaks a parked thread per call.
     */
    fun clearStaleSentinels() {
        // The outer try/catch is mostly belt-and-braces — executeShellCommand
        // is async and won't throw synchronously except on
        // RejectedExecutionException (executor already shut down).
        try {
            adbLauncher.executeShellCommand(
                "rm -f /data/local/tmp/camera_daemon.disabled " +
                    "/data/local/tmp/acc_sentry_daemon.disabled " +
                    "/data/local/tmp/telegram_bot_daemon.disabled " +
                    "/data/local/tmp/zrok.disabled 2>/dev/null; echo cleared",
                object : AdbDaemonLauncher.LaunchCallback {
                    override fun onLog(message: String) {}
                    override fun onLaunched() {
                        log.info(TAG, "Cleared stale per-daemon disable sentinels (defensive)")
                    }
                    override fun onError(error: String) {
                        // Surface failures so a stuck-in-disabled state isn't
                        // invisible. The trailing `; echo cleared` makes the
                        // overall payload exit 0 even when rm fails (echo's
                        // exit code wins), so onError typically only fires
                        // on transport problems — but log just in case.
                        log.warn(TAG, "Defensive sentinel rm onError: $error")
                    }
                }
            )
        } catch (e: Exception) {
            log.warn(TAG, "Defensive sentinel rm threw: ${e.message}")
        }
    }

    fun cleanup() {
        healthCheckRunning.set(false)
        handler.removeCallbacksAndMessages(null)
        // releasePerInstanceResources — NOT closePersistentConnection.
        // closePersistentConnection nulls the process-wide shared Dadb in
        // AdbShellExecutor's companion, which would force the new
        // MainActivity-scoped manager (which is reading the same shared
        // Dadb) to reconnect + re-auth on first use. Worse, any in-flight
        // shell command on this manager's still-pending postDelayed
        // tasks would observe a closed transport and surface as spurious
        // onError. We only need to release THIS manager's per-instance
        // executor + tunnel-poll scheduler — the shared Dadb stays alive
        // for the new owner.
        adbLauncher.releasePerInstanceResources()
        // Shutdown the cached ZrokLauncher's reconcile scheduler if it was
        // ever instantiated. Without this, every Activity teardown leaves
        // a stranded daemon thread for the lifetime of the process.
        // The flag avoids forcing allocation just to check.
        if (zrokLauncherInitialized) {
            try {
                zrokLauncherForHealthCheck.shutdown()
                // The AdbShellExecutor owned by this cached launcher needs
                // its own executor thread shutdown — without it the
                // single-thread executor parks indefinitely.
                zrokAdbShellExecutor?.shutdown()
            } catch (e: Exception) {
                log.warn(TAG, "ZrokLauncher shutdown failed: ${e.message}")
            }
        }
    }
}
