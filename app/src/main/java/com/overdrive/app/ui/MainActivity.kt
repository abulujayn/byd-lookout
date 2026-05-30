package com.overdrive.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupWithNavController
import com.overdrive.app.logging.LogLevel
import com.overdrive.app.logging.LogManager
// import com.overdrive.app.shell.PrivilegedShellSetup
import com.overdrive.app.storage.StorageSetup
import com.overdrive.app.ui.daemon.DaemonStartupManager
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.LogsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.google.android.material.appbar.MaterialToolbar
import android.widget.ImageView
import android.widget.LinearLayout
import com.overdrive.app.BuildConfig
import com.overdrive.app.R
import com.overdrive.app.util.BydDataCacheWhitelist

/**
 * Main activity hosting the M3 navigation-rail shell.
 *
 * Top-level destinations are wired via the rail in setupNavigation(). The
 * old drawer-action handlers (check-update, reset, battery-health,
 * camera-probe, traffic-monitor) are kept private but exposed via
 * `invoke*Action` thin wrappers that the new SettingsFragment / Diagnostics
 * fragment call.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    private val mainViewModel: MainViewModel by viewModels()
    private val daemonsViewModel: DaemonsViewModel by viewModels()
    private val logsViewModel: LogsViewModel by viewModels()
    private var appUpdater: com.overdrive.app.updater.AppUpdater? = null

    // Daemon startup manager
    private lateinit var daemonStartupManager: DaemonStartupManager

    // Handler + runnable owned by the activity so they can be cancelled in
    // onDestroy() — prevents the periodic update check from leaking the
    // activity instance after recreate.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var updateCheckRunnable: Runnable? = null

    // UI elements
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navigationRail: LinearLayout
    private lateinit var tvCurrentUrl: TextView
    private lateinit var urlBar: View
    private lateinit var statusIndicator: View
    private lateinit var urlStatusDot: View
    private lateinit var btnCopyUrl: ImageButton
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_new)

        // Storage setup is posted off the onCreate critical path so a failure
        // (e.g. ROM lacking the All-Files-Access Settings activity on BYD SL7)
        // cannot abort activity launch. See setupStorageDirectories().
        window.decorView.post {
            try {
                setupStorageDirectories()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Deferred storage setup failed: ${e.message}", e)
            }
        }

        // Initialize DeviceIdGenerator with ADB executor for file sync
        val adbExecutor = com.overdrive.app.launcher.AdbShellExecutor(this)
        com.overdrive.app.util.DeviceIdGenerator.init(adbExecutor)
        
        // Generate device ID early - this syncs to file for daemon compatibility
        // Must happen BEFORE any daemon starts
        val deviceId = com.overdrive.app.util.DeviceIdGenerator.generateDeviceId(this)
        android.util.Log.i("MainActivity", "Device ID initialized: $deviceId")
        
        // Apply BYD whitelist (ACC + data cache) to prevent background killing
        // CRITICAL: Run on background thread to avoid blocking UI on boot
        // ActivityThread.systemMain() can block for 1+ minute waiting for system services
        Thread {
            try {
                BydDataCacheWhitelist.applyAll(this)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "BYD whitelist error: ${e.message}")
            }
        }.start()
        
        initViews()
        setupNavigation(savedInstanceState)
        setupCopyButton()
        setupLogListener()
        observeViewModels()
        
        // Initialize daemon startup manager
        daemonStartupManager = DaemonStartupManager(this, daemonsViewModel)
        daemonsViewModel.setStartupManager(daemonStartupManager)
        
        // Setup ADB auth callback to re-initialize when auth is granted
        setupAdbAuthCallback()
        
        // Log app start
        logsViewModel.info("App", "OverDrive started")

        // Seed out-of-process revival watchdog so the process gets resurrected
        // if it ever gets force-stopped or OOM-killed without an external event.
        try {
            com.overdrive.app.receiver.ProcessRevivalReceiver.schedule(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "ProcessRevivalReceiver.schedule failed: ${e.message}")
        }
        
        // Setup privileged shell (UID 1000) - required for daemon management
        // setupPrivilegedShell()
        
        // Start daemons and services
        // Device ID is already synced above via generateDeviceId() which writes to file async
        // The daemon will reload from file when getState() is called
        
        // Start Location Sidecar service (establishes ADB connection)
        startLocationSidecarService()
        
        // Initialize daemons after a short delay to allow ADB connection.
        // If this is a post-update launch, run UpdateLifecycle.hardResetDaemons
        // FIRST so any zombie daemons / watchdogs from the previous install are
        // dead before the new daemon launcher starts. See UpdateLifecycle for
        // the sentinel handshake details.
        runDaemonStartup(intent, fromOnCreate = true)
        
        // Handle Location start intent (from SentryDaemon restart)
        handleLocationStartIntent(intent)
        
        // Check traffic monitor status early so drawer shows correct state
        checkTrafficMonitorStatus()
        
        // Check for app updates (delayed to not block startup)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Clean up any leftover update APK from previous install. Use the
            // shared daemonStartupManager.adbLauncher — allocating a fresh
            // AdbDaemonLauncher here would leak its non-daemon executor + a
            // tunnel-poll scheduler thread on every postDelayed firing.
            daemonStartupManager.adbLauncher.executeShellCommand("rm -f /data/local/tmp/overdrive_update.apk", object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            })

            // Surface failed-install errors first (consumeJustUpdatedVersion
            // returns null when a failure marker is present, so the success
            // toast never fires on a failed install).
            val installError = com.overdrive.app.updater.AppUpdater.consumeFailedUpdateError(this)
            if (installError != null) {
                Toast.makeText(this, getString(R.string.toast_update_install_failed, installError), Toast.LENGTH_LONG).show()
                logsViewModel.warn("Update", "Install failed: $installError")
            }

            // Show post-update message if app was just updated
            val updatedVersion = com.overdrive.app.updater.AppUpdater.consumeJustUpdatedVersion(this)
            if (updatedVersion != null) {
                Toast.makeText(this, getString(R.string.toast_updated_to, updatedVersion), Toast.LENGTH_LONG).show()
                logsViewModel.info("Update", "App updated to $updatedVersion")
            }

            checkForAppUpdate()
        }, 10000) // 10 seconds after launch
        
        // Schedule periodic update checks (every 6 hours)
        schedulePeriodicUpdateCheck()
        
        // Status overlay: start immediately if permission granted, show guide if not
        startStatusOverlay()
        
        // If launched from boot receiver with minimize flag, move to back immediately.
        // This keeps the process alive (important for daemon stability) without
        // showing the app UI over the BYD home screen.
        if (intent?.getBooleanExtra("minimize_on_start", false) == true) {
            android.util.Log.i("MainActivity", "Boot launch — minimizing to background")
            moveTaskToBack(true)
        }
    }
    
    /**
     * Start the status overlay service if overlay permission is granted, and
     * show the setup guide whenever the install/update marker has advanced.
     * The guide must reappear on every install/replace because BYD wipes the
     * autostart whitelist on each install.
     */
    private fun startStatusOverlay() {
        val hasPermission = com.overdrive.app.overlay.StatusOverlayService.hasOverlayPermission(this)
        android.util.Log.i("MainActivity", "Overlay permission: $hasPermission")
        logsViewModel.info("Overlay", "Overlay permission: $hasPermission")

        if (hasPermission) {
            com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(this)
            logsViewModel.info("Overlay", "Status overlay service started")
        }

        // showIfNeeded is no-op when the seen install-time matches the current
        // PackageInfo.lastUpdateTime, so it's safe to call on every launch.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            com.overdrive.app.overlay.SetupGuideDialog.showIfNeeded(this)
        }, 2000)
    }
    
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            handleLocationStartIntent(it)
            // Critical: when MainActivity is already running and the install
            // script's `am start --ez post_update true` re-delivers the
            // intent (singleTop launchMode → onNewIntent, not onCreate), the
            // post-update hard-reset would otherwise be skipped, leaving
            // zombie daemons from the old install alive. Re-run the same
            // flow onCreate uses; runDaemonStartup is idempotent (guarded
            // by daemonStartupRequested + by the sentinel/intent-extra
            // checks inside isPostUpdateLaunch — once the sentinels are
            // consumed, subsequent calls become no-ops).
            runDaemonStartup(it, fromOnCreate = false)
        }
    }

    /**
     * Single source of truth for "MainActivity wants daemons running."
     * Idempotent across onCreate / onNewIntent / ADB-auth-granted callbacks:
     *   - daemonStartupCoordinator guards against duplicate concurrent runs
     *   - the underlying UpdateLifecycle sentinels are one-shot so a second
     *     call after the post-update reset becomes a normal startup
     *
     * The 1-second postDelayed gives ADB / device-id sync time to settle.
     */
    private fun runDaemonStartup(intent: android.content.Intent?, fromOnCreate: Boolean) {
        val isPostUpdate = com.overdrive.app.updater.UpdateLifecycle
            .isPostUpdateLaunch(this, intent)
        // Skip the postDelayed boilerplate when called from onNewIntent for
        // a non-post-update intent — daemons are already up from onCreate
        // and re-running initializeOnAppLaunch() in that case just churns.
        if (!fromOnCreate && !isPostUpdate) {
            android.util.Log.d("MainActivity",
                "runDaemonStartup: onNewIntent without post-update marker — no-op")
            return
        }

        synchronized (daemonStartupCoordinator) {
            if (daemonStartupCoordinator.inFlight) {
                android.util.Log.i("MainActivity",
                    "runDaemonStartup: another startup pass is in flight — skipping")
                return
            }
            daemonStartupCoordinator.inFlight = true
        }

        // Defensive sentinel cleanup. Gated by the inFlight guard above so
        // duplicate onNewIntent calls don't fire redundant ADB rms while
        // another startup pass is already running. Routes through
        // daemonStartupManager's shared AdbDaemonLauncher — see
        // DaemonStartupManager.clearStaleSentinels for the contract.
        daemonStartupManager.clearStaleSentinels()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread {
                try {
                    try {
                        val synced = com.overdrive.app.util.DeviceIdGenerator
                            .syncDeviceIdToFileSync(this)
                        android.util.Log.i("MainActivity", "Device ID sync result: $synced")
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Device ID sync error: ${e.message}")
                    }

                    val startDaemons = Runnable {
                        runOnUiThread {
                            daemonStartupManager.initializeOnAppLaunch()
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                daemonStartupManager.checkAllDaemonStatuses()
                            }, 3000)
                        }
                    }

                    if (isPostUpdate) {
                        logsViewModel.info("Update",
                            "Post-update launch — hard-resetting daemons before startup")
                        // Watchdog: if hardResetDaemons silently drops its
                        // callback (synchronous throw inside the launcher,
                        // unexpected shell exec failure mode), the inFlight
                        // flag would otherwise stay true forever and block
                        // every subsequent runDaemonStartup call from
                        // onNewIntent. 30s is well past hardResetDaemons'
                        // observed worst-case (~5-8s for the shell sweep
                        // plus the 1s settle) so this only fires on a
                        // real silent-drop bug, not on slow happy paths.
                        //
                        // The watchdog Runnable is captured into
                        // daemonStartupCoordinator.pendingWatchdog so
                        // onDestroy can cancel it — otherwise the lambda
                        // captures `this@MainActivity` and leaks the
                        // activity for up to 30s after a backout.
                        val callbackFired = java.util.concurrent.atomic.AtomicBoolean(false)
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        val watchdog = Runnable {
                            if (isFinishing || isDestroyed) {
                                if (callbackFired.compareAndSet(false, true)) {
                                    synchronized (daemonStartupCoordinator) {
                                        daemonStartupCoordinator.inFlight = false
                                        daemonStartupCoordinator.pendingWatchdog = null
                                    }
                                }
                                return@Runnable
                            }
                            if (callbackFired.compareAndSet(false, true)) {
                                android.util.Log.w("MainActivity",
                                    "runDaemonStartup: hardResetDaemons callback never fired " +
                                    "within 30s — proceeding with daemon startup anyway")
                                try { showPostUpdateToasts() } catch (_: Exception) {}
                                startDaemons.run()
                                synchronized (daemonStartupCoordinator) {
                                    daemonStartupCoordinator.inFlight = false
                                    daemonStartupCoordinator.pendingWatchdog = null
                                }
                            }
                        }
                        synchronized (daemonStartupCoordinator) {
                            daemonStartupCoordinator.pendingWatchdog = watchdog
                            daemonStartupCoordinator.watchdogHandler = mainHandler
                        }
                        mainHandler.postDelayed(watchdog, 30_000)
                        com.overdrive.app.updater.UpdateLifecycle.hardResetDaemons(this) {
                            if (callbackFired.compareAndSet(false, true)) {
                                synchronized (daemonStartupCoordinator) {
                                    daemonStartupCoordinator.pendingWatchdog?.let {
                                        daemonStartupCoordinator.watchdogHandler
                                            ?.removeCallbacks(it)
                                    }
                                    daemonStartupCoordinator.pendingWatchdog = null
                                }
                                if (isFinishing || isDestroyed) {
                                    synchronized (daemonStartupCoordinator) {
                                        daemonStartupCoordinator.inFlight = false
                                    }
                                    return@hardResetDaemons
                                }
                                try {
                                    showPostUpdateToasts()
                                } finally {
                                    startDaemons.run()
                                    synchronized (daemonStartupCoordinator) {
                                        daemonStartupCoordinator.inFlight = false
                                    }
                                }
                            }
                        }
                    } else {
                        startDaemons.run()
                        synchronized (daemonStartupCoordinator) {
                            daemonStartupCoordinator.inFlight = false
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity",
                        "runDaemonStartup error: ${e.message}", e)
                    synchronized (daemonStartupCoordinator) {
                        daemonStartupCoordinator.inFlight = false
                    }
                }
            }.start()
        }, 1000)
    }

    /**
     * Show "updated to vX" / "install failed" toasts after a post-update
     * hard-reset. Plants the Telegram hint file when an update succeeded
     * so the next tunnel-URL message includes the new version.
     *
     * Both consume* methods clear their backing markers, so calling this
     * twice in one process lifetime fires toasts only once.
     */
    private fun showPostUpdateToasts() {
        val installError = com.overdrive.app.updater.AppUpdater
            .consumeFailedUpdateError(this)
        if (installError != null) {
            runOnUiThread {
                Toast.makeText(this,
                    getString(R.string.toast_update_install_failed, installError),
                    Toast.LENGTH_LONG).show()
                logsViewModel.warn("Update", "Install failed: $installError")
            }
        }
        val updatedVersion = com.overdrive.app.updater.AppUpdater
            .consumeJustUpdatedVersion(this)
        if (updatedVersion != null) {
            runOnUiThread {
                Toast.makeText(this,
                    getString(R.string.toast_updated_to, updatedVersion),
                    Toast.LENGTH_LONG).show()
                logsViewModel.info("Update", "App updated to $updatedVersion")
            }
            try {
                // Reuse the shared adbLauncher; see comment at the rm site
                // for why allocating a fresh one here leaks resources.
                val hintFile = com.overdrive.app.updater
                    .UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE
                daemonStartupManager.adbLauncher.executeShellCommand(
                    "echo '$updatedVersion' > $hintFile",
                    object : com.overdrive.app.launcher
                        .AdbDaemonLauncher.LaunchCallback {
                        override fun onLog(message: String) {}
                        override fun onLaunched() {}
                        override fun onError(error: String) {}
                    }
                )
            } catch (e: Exception) {
                android.util.Log.w("MainActivity",
                    "Failed to plant Telegram post-update hint: ${e.message}")
            }
        }
    }

    /** Coordinator object shared by onCreate / onNewIntent so concurrent
     *  startup attempts collapse into one. inFlight is reset in every
     *  terminal branch (success, hard-reset done, error path).
     *  pendingWatchdog/watchdogHandler are tracked so onDestroy can
     *  cancel an in-flight 30s watchdog and avoid leaking the Activity. */
    private val daemonStartupCoordinator = DaemonStartupCoordinator()
    private class DaemonStartupCoordinator {
        @Volatile var inFlight: Boolean = false
        var pendingWatchdog: Runnable? = null
        var watchdogHandler: android.os.Handler? = null
    }

    
    override fun onResume() {
        super.onResume()
        // Try to start overlay if permission was just granted (user returned from settings)
        com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(this)
    }
    
    /**
     * Setup ADB auth callback to re-initialize daemons when auth is granted.
     * This handles the case where user grants ADB auth after the initial connection attempt failed.
     */
    private fun setupAdbAuthCallback() {
        com.overdrive.app.launcher.AdbShellExecutor.setAuthCallback(object : com.overdrive.app.launcher.AdbShellExecutor.AdbAuthCallback {
            override fun onAuthPending() {
                runOnUiThread {
                    logsViewModel.info("ADB", "⏳ Waiting for ADB authorization...")
                    logsViewModel.info("ADB", "Please accept the USB debugging prompt")
                }
            }
            
            override fun onAuthGranted() {
                runOnUiThread {
                    logsViewModel.info("ADB", "ADB authorization granted")
                    logsViewModel.info("ADB", "Re-initializing daemons...")
                    
                    // Re-run daemon initialization now that ADB is authorized
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        daemonStartupManager.initializeOnAppLaunch()
                        
                        // Check daemon statuses after startup
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            daemonStartupManager.checkAllDaemonStatuses()
                        }, 3000)
                    }, 500)
                    
                    // Re-check traffic monitor now that ADB is available
                    checkTrafficMonitorStatus()
                }
            }
            
            override fun onAuthFailed(error: String) {
                runOnUiThread {
                    logsViewModel.error("ADB", "ADB connection failed: $error")
                }
            }
        })
    }
    
    /**
     * Check GitHub for app updates and show dialog if available.
     */
    private fun checkForAppUpdate() {
        logsViewModel.info("Update", "Checking for updates (channel: ${BuildConfig.UPDATE_CHANNEL})...")
        val updater = com.overdrive.app.updater.AppUpdater(this)
        appUpdater = updater
        updater.checkForUpdate(object : com.overdrive.app.updater.AppUpdater.UpdateCallback {
            override fun onUpdateAvailable(currentVersion: String, newVersion: String, releaseNotes: String) {
                // Don't close updater here — performAppUpdate will use it.
                com.overdrive.app.updater.UpdateDialog.showUpdateAvailable(
                    this@MainActivity, currentVersion, newVersion, releaseNotes,
                    { performAppUpdate(updater) },
                    { updater.close() }  // Dismiss path: release executor + scheduler.
                )
            }

            override fun onNoUpdate(currentVersion: String) {
                logsViewModel.debug("Update", "App is up to date (v$currentVersion)")
                // No further use — release per-instance executor.
                updater.close()
            }

            override fun onError(error: String) {
                logsViewModel.debug("Update", "Update check failed: $error")
                updater.close()
            }
        })
    }

    /**
     * Manual update check — shows toast if already up to date.
     */
    fun checkForAppUpdateManual() {
        Toast.makeText(this, getString(R.string.toast_checking_for_updates), Toast.LENGTH_SHORT).show()
        val updater = com.overdrive.app.updater.AppUpdater(this)
        appUpdater = updater
        updater.checkForUpdate(object : com.overdrive.app.updater.AppUpdater.UpdateCallback {
            override fun onUpdateAvailable(currentVersion: String, newVersion: String, releaseNotes: String) {
                com.overdrive.app.updater.UpdateDialog.showUpdateAvailable(
                    this@MainActivity, currentVersion, newVersion, releaseNotes,
                    { performAppUpdate(updater) },
                    { updater.close() }  // Dismiss path: release executor + scheduler.
                )
            }

            override fun onNoUpdate(currentVersion: String) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_app_up_to_date, currentVersion), Toast.LENGTH_LONG).show()
                updater.close()
            }

            override fun onError(error: String) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_update_check_failed, error), Toast.LENGTH_LONG).show()
                updater.close()
            }
        })
    }

    /**
     * Schedule periodic update checks (every 6 hours).
     */
    private fun schedulePeriodicUpdateCheck() {
        val sixHoursMs = 6 * 60 * 60 * 1000L
        // Cancel any prior runnable (e.g. across activity recreate).
        updateCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                checkForAppUpdate()
                mainHandler.postDelayed(this, sixHoursMs)
            }
        }
        updateCheckRunnable = runnable
        mainHandler.postDelayed(runnable, sixHoursMs)
    }

    private fun performAppUpdate(updater: com.overdrive.app.updater.AppUpdater) {
        val progress = com.overdrive.app.updater.UpdateDialog.showProgress(this) {
            // Cancel-only — do NOT call updater.close() here. The
            // background downloadAndInstall task is still in flight; it
            // observes the cancelled flag and calls runShell("rm -f
            // APK_PATH") which routes back into getAdbLauncher(). If we
            // null'd adbLauncher via close() before that runs, the lazy
            // reallocation strands a fresh AdbDaemonLauncher's executor +
            // tunnel-poll scheduler. The downloadAndInstall onError path
            // ("cancelled") already calls updater.close() — let it run.
            updater.cancel()
        }

        updater.downloadAndInstall(object : com.overdrive.app.updater.AppUpdater.InstallCallback {
            override fun onProgress(message: String) {
                runOnUiThread {
                    when {
                        message.contains("Downloading") -> progress.setStep("\u2B07\uFE0F Downloading update...", 15)
                        message.contains("Verifying") -> progress.setStep("\uD83D\uDD0D Verifying download...", 75)
                        message.contains("Stopping") -> progress.setStep("\u23F9\uFE0F Stopping daemons...", 80)
                        message.contains("Installing") -> progress.setStep("\uD83D\uDCE6 Installing update...", 90)
                        message.contains("installed") -> progress.setStep("\u2705 Update installed!", 100)
                        else -> progress.setStatus(message)
                    }
                }
            }

            override fun onDownloadProgress(percent: Int) {
                // Map the download's 0..100 into the bar's [15, 75) range so
                // the bar advances continuously while the APK streams in.
                // The non-download steps (Verifying / Stopping / Installing)
                // own the 75..100 range above. percent < 0 means indeterminate
                // — leave the bar at 15 (the step set by onProgress).
                // Without this mapping the bar held at 15 for the entire
                // download phase, which is what produced the "stuck at 15%"
                // user reports.
                if (percent < 0) return
                val clamped = percent.coerceIn(0, 100)
                val mapped = 15 + ((clamped * 60) / 100)
                runOnUiThread { progress.setProgress(mapped) }
            }

            override fun onSuccess() {
                runOnUiThread {
                    progress.setStep("\u2705 Restarting app...", 100)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        progress.dismiss()
                    }, 2000)
                }
            }

            override fun onError(error: String) {
                runOnUiThread { progress.showError(error) }
                // Install failed; release per-instance resources. (onSuccess
                // path doesn't need this — the app is being restarted by
                // pm install and process death tears everything down.)
                updater.close()
            }
        })
    }

    /**
     * SOTA: Setup storage directories from the App so it becomes the owner.
     * This ensures both app and daemon can read/write to the directories.
     * On Android 11+, requires MANAGE_EXTERNAL_STORAGE permission.
     * On Android 10 and below, requires WRITE_EXTERNAL_STORAGE runtime permission.
     */
    private fun setupStorageDirectories() {
        android.util.Log.i("MainActivity", "========== CHECKING STORAGE PERMISSION ==========")
        val hasPermission = StorageSetup.checkStoragePermission(this)
        android.util.Log.i("MainActivity", "checkStoragePermission() = $hasPermission")

        if (hasPermission) {
            android.util.Log.i("MainActivity", "Permission OK - calling setupDirectories()")
            val success = StorageSetup.setupDirectories()
            if (success) {
                android.util.Log.i("MainActivity", "Storage directories ready (App is owner)")
            } else {
                android.util.Log.w("MainActivity", "Some storage directories could not be created")
            }
            return
        }

        // On Android 10 and below, the only path is the standard runtime
        // permission dialog for WRITE_EXTERNAL_STORAGE. No MES, no app-ops.
        // Preserve the original behaviour exactly.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            android.util.Log.i("MainActivity", "Pre-R: requesting runtime WRITE_EXTERNAL_STORAGE")
            fallbackToSettingsRequest()
            return
        }

        // Android 11+: best-effort directory creation in legacy mode regardless
        // of MES state, so recordings still work on this launch even if MES
        // never lands. With requestLegacyExternalStorage="true" + targetSdk 25,
        // WRITE_EXTERNAL_STORAGE is enough for our own paths under
        // /storage/emulated/0/Overdrive.
        val legacySuccess = StorageSetup.setupDirectories()
        android.util.Log.i("MainActivity", "Legacy-mode setupDirectories success=$legacySuccess")

        // Try the silent app-ops path first (only viable route on BYD SL7 which
        // lacks the All-Files-Access Settings activity). Settings intent is only
        // opened if the app-ops grant fails to land.
        android.util.Log.i("MainActivity", "MES missing - attempting silent app-ops grant via ADB")
        try {
            val adb = com.overdrive.app.launcher.AdbShellExecutor(this)
            StorageSetup.tryGrantViaAppOps(this, adb) { granted ->
                runOnUiThread { onAppOpsGrantResult(granted) }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "app-ops pre-grant threw, falling back to Settings: ${e.message}")
            fallbackToSettingsRequest()
        }
    }

    private fun onAppOpsGrantResult(granted: Boolean) {
        if (granted) {
            android.util.Log.i("MainActivity", "MES granted via app-ops; refreshing directories")
            val success = StorageSetup.setupDirectories()
            android.util.Log.i("MainActivity", "Post-grant setupDirectories success=$success")
            return
        }
        android.util.Log.w("MainActivity", "app-ops grant did not take; falling back to Settings UI")
        fallbackToSettingsRequest()
    }

    private fun fallbackToSettingsRequest() {
        when (StorageSetup.requestStoragePermission(this)) {
            StorageSetup.RequestOutcome.REQUESTED_RUNTIME,
            StorageSetup.RequestOutcome.OPENED_SETTINGS -> {
                // Result delivered to onRequestPermissionsResult / onActivityResult.
            }
            StorageSetup.RequestOutcome.UNAVAILABLE -> {
                android.util.Log.w(
                    "MainActivity",
                    "All-Files-Access UI unavailable; staying in legacy storage mode"
                )
            }
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == StorageSetup.REQUEST_CODE_STORAGE_PERMISSION) {
            // Android 11+ Settings result
            if (StorageSetup.checkStoragePermission(this)) {
                android.util.Log.i("MainActivity", "Storage permission granted! Creating directories...")
                val success = StorageSetup.setupDirectories()
                if (success) {
                    logsViewModel.info("Storage", "Storage directories created (App is owner)")
                } else {
                    logsViewModel.warn("Storage", "Some directories could not be created")
                }
            } else {
                android.util.Log.e("MainActivity", "Storage permission denied by user")
                logsViewModel.error("Storage", "Storage permission denied - recordings may not work")
                Toast.makeText(this, getString(R.string.toast_storage_permission_required), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == StorageSetup.REQUEST_CODE_RUNTIME_PERMISSION) {
            // Android 10 and below runtime permission result
            val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            android.util.Log.i("MainActivity", "Runtime permission result: granted=$granted")
            
            if (granted) {
                android.util.Log.i("MainActivity", "Storage permission granted! Creating directories...")
                val success = StorageSetup.setupDirectories()
                if (success) {
                    logsViewModel.info("Storage", "Storage directories created (App is owner)")
                } else {
                    logsViewModel.warn("Storage", "Some directories could not be created")
                }
            } else {
                android.util.Log.e("MainActivity", "Storage permission denied by user")
                logsViewModel.error("Storage", "Storage permission denied - recordings may not work")
                Toast.makeText(this, getString(R.string.toast_storage_permission_required), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Auto-start Location Sidecar service for GPS tracking.
     * Uses daemonsViewModel's adbLauncher to avoid multiple ADB auth popups.
     * This runs silently in the background and is monitored by SentryDaemon.
     */
    private fun startLocationSidecarService() {
        logsViewModel.info("Location", "Auto-starting Location Sidecar service via ADB...")
        
        daemonsViewModel.startLocationSidecarService(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                logsViewModel.debug("Location", message)
            }
            
            override fun onLaunched() {
                logsViewModel.info("Location", "Location Sidecar service started successfully")
            }
            
            override fun onError(error: String) {
                logsViewModel.error("Location", "Failed to start Location Sidecar: $error")
            }
        })
    }
    
    /**
     * Handle Location start intent from SentryDaemon or boot receiver.
     * This is called when the daemon detects Location service died and launches the app to restart it.
     */
    private fun handleLocationStartIntent(intent: android.content.Intent) {
        val action = intent.action
        val startLocation = intent.getBooleanExtra("start_location", false)
        
        if (action == "com.overdrive.app.START_LOCATION_ACTIVITY" || startLocation) {
            logsViewModel.info("Location", "Received Location start intent from SentryDaemon")
            
            // Start LocationSidecarService directly
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                logsViewModel.info("Location", "Auto-starting Location service...")
                try {
                    val serviceIntent = android.content.Intent(this, com.overdrive.app.services.LocationSidecarService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    logsViewModel.info("Location", "Location service start requested")
                } catch (e: Exception) {
                    logsViewModel.error("Location", "Failed to start Location service: ${e.message}")
                }
            }, 1000)
        }
    }
    
    /**
     * Setup the privileged shell (UID 1000) for daemon management.
     * This must be done before starting any daemons that need elevated privileges.
     */
    private fun setupPrivilegedShell() {
        logsViewModel.info("Shell", "Setting up privileged shell...")
        
        // PrivilegedShellSetup disabled — all daemons now run via ADB shell (UID 2000)
        // PrivilegedShellSetup.init(this)
        // 
        // PrivilegedShellSetup.setup(object : PrivilegedShellSetup.SetupCallback {
        //     override fun onSuccess() {
        //         runOnUiThread {
        //             logsViewModel.info("Shell", "✓ Privileged shell ready (UID 1000)")
        //             daemonStartupManager.checkAllDaemonStatuses()
        //         }
        //     }
        //     
        //     override fun onFailure(reason: String) {
        //         runOnUiThread {
        //             logsViewModel.warn("Shell", "⚠ Privileged shell setup failed: $reason")
        //             logsViewModel.info("Shell", "Falling back to ADB shell for daemon management")
        //             daemonStartupManager.checkAllDaemonStatuses()
        //         }
        //     }
        //     
        //     override fun onProgress(message: String) {
        //         runOnUiThread {
        //             logsViewModel.debug("Shell", "→ $message")
        //         }
        //     }
        // })
    }
    
    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        navigationRail = findViewById(R.id.navigationRail)
        tvCurrentUrl = findViewById(R.id.tvCurrentUrl)
        urlBar = findViewById(R.id.urlBar)
        statusIndicator = findViewById(R.id.statusIndicator)
        urlStatusDot = findViewById(R.id.urlStatusDot)
        btnCopyUrl = findViewById(R.id.btnCopyUrl)
        
        // Brand version + device id used to live in the drawer header; in the
        // rail-based shell they're surfaced on the Dashboard card instead.
    }
    
    private fun setupNavigation(savedInstanceState: Bundle?) {
        setSupportActionBar(toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController

        // Top-level destinations on the rail — no back arrow on these.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.dashboardFragment,
                R.id.liveViewFragment,
                R.id.recordingsFragment,
                R.id.vehicleControlFragment,
                R.id.tripsFragment,
                R.id.integrationsFragment,
                R.id.diagnosticsFragment,
                R.id.settingsFragment,
                R.id.settingsAboutFragment
            )
        )

        toolbar.setupWithNavController(navController, appBarConfiguration)

        setupCustomRail()
    }

    /**
     * Bind the custom navigation rail (LinearLayout of @layout/item_rail_destination
     * includes). Material's NavigationRailView caps menu items at 7 in
     * collapsed mode, so we use a plain vertical list of icon+label rows
     * instead. Each row's destination, icon, and label are wired here.
     *
     * Selection sync is driven from the NavController so deep links and
     * code-driven nav also light up the right rail item.
     */
    private fun setupCustomRail() {
        // Order matches the previous rail_menu.xml so user's mental model
        // stays the same.
        val items = listOf(
            RailItem(R.id.railDestDashboard, R.id.dashboardFragment,
                R.drawable.ic_dashboard, R.string.rail_dashboard),
            RailItem(R.id.railDestLive, R.id.liveViewFragment,
                R.drawable.ic_live, R.string.rail_live),
            RailItem(R.id.railDestRecordings, R.id.recordingsFragment,
                R.drawable.ic_recording, R.string.rail_recordings),
            RailItem(R.id.railDestVehicle, R.id.vehicleControlFragment,
                R.drawable.ic_vehicle_control, R.string.rail_vehicle),
            RailItem(R.id.railDestTrips, R.id.tripsFragment,
                R.drawable.ic_trips, R.string.rail_trips),
            RailItem(R.id.railDestIntegrations, R.id.integrationsFragment,
                R.drawable.ic_integrations, R.string.rail_integrations),
            RailItem(R.id.railDestDiagnostics, R.id.diagnosticsFragment,
                R.drawable.ic_diagnostics, R.string.rail_diagnostics),
            RailItem(R.id.railDestSettings, R.id.settingsFragment,
                R.drawable.ic_settings, R.string.rail_settings),
            RailItem(R.id.railDestAbout, R.id.settingsAboutFragment,
                R.drawable.ic_update, R.string.settings_section_about)
        )

        // Bind icon + label and click handler per row.
        items.forEach { item ->
            val row = navigationRail.findViewById<View>(item.rowId) ?: return@forEach
            row.findViewById<ImageView>(R.id.railItemIcon)?.setImageResource(item.iconRes)
            row.findViewById<TextView>(R.id.railItemLabel)?.setText(item.labelRes)
            row.setOnClickListener {
                navigateToRailDestination(item.destinationId)
            }
        }

        // Selection sync — light up the row whose destinationId matches
        // the current nav destination (or any of its ancestors).
        navController.addOnDestinationChangedListener { _, destination, _ ->
            var node: androidx.navigation.NavDestination? = destination
            while (node != null) {
                val match = items.firstOrNull { it.destinationId == node!!.id }
                if (match != null) {
                    items.forEach { item ->
                        navigationRail.findViewById<View>(item.rowId)?.isSelected =
                            (item.destinationId == match.destinationId)
                    }
                    return@addOnDestinationChangedListener
                }
                node = node.parent
            }
        }

        // Language picker — moved to the toolbar end-cluster so it's
        // reachable from the top-right at every screen size. Falls back
        // to the legacy rail-header button if a downstream layout ever
        // restores it; the dialog itself is the same.
        val languageClick = View.OnClickListener {
            com.overdrive.app.ui.dialog.LanguagePickerDialog.show(this) {
                recreate()
            }
        }
        findViewById<View>(R.id.toolbarLanguageButton)?.setOnClickListener(languageClick)
        navigationRail.findViewById<View>(R.id.railLanguageButton)?.setOnClickListener(languageClick)
    }

    private data class RailItem(
        val rowId: Int,
        val destinationId: Int,
        val iconRes: Int,
        val labelRes: Int
    )

    /**
     * Navigate to a rail destination, popping any sub-pages so the tab
     * resets to its root. Uses M3 expressive fade-through (the incoming
     * destination scales up slightly while the outgoing fades) so the
     * switch reads as motion, not just a cross-fade.
     */
    private fun navigateToRailDestination(destinationId: Int) {
        val options = androidx.navigation.NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(false)
            .setPopUpTo(destinationId, /* inclusive = */ false, /* saveState = */ false)
            .setEnterAnim(R.anim.m3_fade_through_enter)
            .setExitAnim(R.anim.m3_fade_through_exit)
            .setPopEnterAnim(R.anim.m3_fade_through_enter)
            .setPopExitAnim(R.anim.m3_fade_through_exit)
            .build()
        try {
            navController.navigate(destinationId, /* args = */ null, options)
        } catch (_: IllegalArgumentException) {
            // Destination not in graph — defensive only.
        }
    }
    
    private fun setupCopyButton() {
        btnCopyUrl.setOnClickListener {
            val url = tvCurrentUrl.text.toString()
            if (url.isNotEmpty() && !url.startsWith("No tunnel") && !url.startsWith("Waiting") && !url.startsWith("Starting") && url != "Connecting...") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(getString(R.string.clip_label_url), url)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.toast_url_copied_short), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupLogListener() {
        // Wire LogManager to LogsViewModel
        LogManager.setLogListener(object : LogManager.LogListener {
            override fun onLog(tag: String, message: String, level: LogLevel) {
                // Convert LogManager.LogLevel to UI LogLevel
                val uiLevel = when (level) {
                    LogLevel.DEBUG -> com.overdrive.app.ui.model.LogLevel.DEBUG
                    LogLevel.INFO -> com.overdrive.app.ui.model.LogLevel.INFO
                    LogLevel.WARN -> com.overdrive.app.ui.model.LogLevel.WARN
                    LogLevel.ERROR -> com.overdrive.app.ui.model.LogLevel.ERROR
                }
                logsViewModel.addLog(tag, message, uiLevel)
            }
        })
    }
    
    private fun observeViewModels() {
        // Observe tunnel URL from cloudflared controller
        daemonsViewModel.cloudflaredController.tunnelUrl.observe(this) { url ->
            mainViewModel.setTunnelUrl(url)
            updateUrlDisplay()
        }
        
        // Observe tunnel URL from zrok controller
        daemonsViewModel.zrokController.tunnelUrl.observe(this) { url ->
            // Zrok URL takes precedence if available
            if (!url.isNullOrEmpty()) {
                mainViewModel.setTunnelUrl(url)
            }
            updateUrlDisplay()
        }

        // Observe tunnel URL from tailscale controller
        daemonsViewModel.tailscaleController.tunnelUrl.observe(this) { url ->
            // Tailscale is lowest priority — only adopt its URL when no higher-priority tunnel has one
            val zrokUrl = daemonsViewModel.zrokController.tunnelUrl.value
            val cloudflaredUrl = daemonsViewModel.cloudflaredController.tunnelUrl.value
            if (zrokUrl.isNullOrEmpty() && cloudflaredUrl.isNullOrEmpty() && !url.isNullOrEmpty()) {
                mainViewModel.setTunnelUrl(url)
            }
            updateUrlDisplay()
        }
        
        // Observe daemon states for tunnel status (cloudflared, zrok or tailscale)
        daemonsViewModel.daemonStates.observe(this) { states ->
            val cloudflaredState = states[DaemonType.CLOUDFLARED_TUNNEL]
            val zrokState = states[DaemonType.ZROK_TUNNEL]
            val tailscaleState = states[DaemonType.TAILSCALE_TUNNEL]
            // Show online if either tunnel is running
            val tunnelStatus = when {
                zrokState?.status == DaemonStatus.RUNNING -> DaemonStatus.RUNNING
                cloudflaredState?.status == DaemonStatus.RUNNING -> DaemonStatus.RUNNING
                tailscaleState?.status == DaemonStatus.RUNNING -> DaemonStatus.RUNNING
                zrokState?.status == DaemonStatus.STARTING -> DaemonStatus.STARTING
                cloudflaredState?.status == DaemonStatus.STARTING -> DaemonStatus.STARTING
                tailscaleState?.status == DaemonStatus.STARTING -> DaemonStatus.STARTING
                else -> DaemonStatus.STOPPED
            }
            updateStatusIndicator(tunnelStatus)
        }
    }
    
    private fun updateUrlDisplay() {
        // Check both tunnel URLs - prefer zrok if available
        val zrokUrl = daemonsViewModel.zrokController.tunnelUrl.value
        val cloudflaredUrl = daemonsViewModel.cloudflaredController.tunnelUrl.value
        val tailscaleUrl = daemonsViewModel.tailscaleController.tunnelUrl.value
        val tunnelUrl = zrokUrl?.takeIf { it.isNotEmpty() } ?: cloudflaredUrl?.takeIf { it.isNotEmpty() } ?: tailscaleUrl
        
        // Both modes now use tunnel URL
        if (tunnelUrl.isNullOrEmpty()) {
            // Show context-aware message based on tunnel state
            val states = daemonsViewModel.daemonStates.value
            val cfState = states?.get(DaemonType.CLOUDFLARED_TUNNEL)
            val zrokState = states?.get(DaemonType.ZROK_TUNNEL)
            val tailscaleState = states?.get(DaemonType.TAILSCALE_TUNNEL)
            val message = when {
                zrokState?.status == DaemonStatus.STARTING -> "Starting Zrok tunnel..."
                cfState?.status == DaemonStatus.STARTING -> "Starting Cloudflared tunnel..."
                tailscaleState?.status == DaemonStatus.STARTING -> "Starting Tailscale tunnel..."
                zrokState?.status == DaemonStatus.RUNNING -> "Waiting for tunnel URL..."
                cfState?.status == DaemonStatus.RUNNING -> "Waiting for tunnel URL..."
                tailscaleState?.status == DaemonStatus.RUNNING -> "Waiting for tailscale URL..."
                else -> "No tunnel running"
            }
            tvCurrentUrl.text = message
            urlStatusDot.setBackgroundResource(R.drawable.status_dot_offline)
            mainViewModel.setCurrentUrl(null)
        } else {
            tvCurrentUrl.text = tunnelUrl
            urlStatusDot.setBackgroundResource(R.drawable.status_dot_online)
            mainViewModel.setCurrentUrl(tunnelUrl)
        }
    }
    
    private fun updateStatusIndicator(status: DaemonStatus?) {
        // Single status pill replaced the standalone toolbar dot. Both the
        // legacy `statusIndicator` and the in-pill `urlStatusDot` IDs are
        // updated for safety: the legacy dot is now a 0×0 invisible View
        // (so updates are no-ops visually) and the pill dot is what users
        // actually see. Keeping both write paths means future layout swaps
        // don't need MainActivity edits.
        val drawableRes = when (status) {
            DaemonStatus.RUNNING -> R.drawable.status_dot_online
            DaemonStatus.STARTING, DaemonStatus.STOPPING -> R.drawable.status_dot_starting
            else -> R.drawable.status_dot_offline
        }
        statusIndicator.setBackgroundResource(drawableRes)
        urlStatusDot.setBackgroundResource(drawableRes)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
    
    @Deprecated("Default back behavior is handled by NavController + the activity")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
    
    // ==================== Camera Reconfiguration ====================
    
    /**
     * No-op since the rail shell doesn't have a drawer-side menu item to
     * retitle — Camera probe status is shown inside the dialog itself.
     */
    private fun updateCameraProbeMenuItem() { /* intentionally empty */ }


    // ==================== Camera Mapping data model ====================

    private data class CameraRoleOption(val key: String, val label: String)

    private data class CameraPreviewCandidate(
        val id: String,
        val kind: String,
        val label: String,
        val cameraId: Int?,
        val slice: String?,
        val view: String?,
        val width: Int,
        val height: Int
    ) {
        fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
            put("kind", kind)
            cameraId?.let { put("cameraId", it) }
            slice?.let { put("slice", it) }
            view?.let { put("view", it) }
        }
    }

    private data class CameraMappingState(
        val summary: String,
        val roles: List<CameraRoleOption>,
        val candidates: List<CameraPreviewCandidate>,
        val currentMappings: Map<String, String>,
        // Currently-saved manual camera ID, or null when auto / unset.
        // Drives the manual-camera-ID radio group's initial selection.
        val manualCameraId: Int?,
        val isManualOverride: Boolean
    )

    /**
     * Diagnostics → Camera mapping entry point. Pulls resolved camera state
     * from the daemon and shows the mapping dialog. Falls back to a toast on
     * fetch failure rather than opening an empty dialog.
     */
    private fun onReconfigureCameraClicked() {
        Thread {
            val state = fetchCameraMappingState()
            runOnUiThread {
                // Bail if the activity tore down during the 4-7 s fetch
                // window — without this guard the captured `this@MainActivity`
                // reference would hold the destroyed activity in memory and
                // showCameraMappingDialog could crash with
                // "Activity has been destroyed" on layoutInflater.
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (state == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_failed_to_save_short),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }
                showCameraMappingDialog(state)
            }
        }.start()
    }

    /**
     * Single GET against /api/surveillance/config — server merges the
     * resolved camera summary into the response so the dialog can render
     * roles, candidates, and current mappings without a second round-trip.
     */
    private fun fetchCameraMappingState(): CameraMappingState? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = com.overdrive.app.util.DaemonHttpClient.open(
                "/api/surveillance/config", "GET", 3000, 4000)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val config = org.json.JSONObject(body).optJSONObject("config") ?: return null

            val panoCameraId = config.optInt("panoCameraId", config.optInt("cameraId", -1))
            val panoWidth = config.optInt("panoWidth", -1)
            val panoHeight = config.optInt("panoHeight", -1)
            val summary = if (panoCameraId >= 0 && panoWidth > 0 && panoHeight > 0) {
                getString(
                    R.string.camera_mapping_summary_format,
                    panoCameraId, panoWidth, panoHeight
                )
            } else {
                getString(R.string.camera_mapping_summary_probing)
            }

            val roles = mutableListOf<CameraRoleOption>()
            val roleArray = config.optJSONArray("cameraRoleOptions") ?: org.json.JSONArray()
            for (i in 0 until roleArray.length()) {
                val item = roleArray.optJSONObject(i) ?: continue
                roles += CameraRoleOption(
                    item.optString("key", ""),
                    item.optString("label", item.optString("key", "Role"))
                )
            }

            val candidates = mutableListOf<CameraPreviewCandidate>()
            val candidateArray = config.optJSONArray("cameraPreviewCandidates") ?: org.json.JSONArray()
            for (i in 0 until candidateArray.length()) {
                val item = candidateArray.optJSONObject(i) ?: continue
                candidates += CameraPreviewCandidate(
                    id = item.optString("id", "candidate-$i"),
                    kind = item.optString("kind", "panoramicSlice"),
                    label = item.optString("label", item.optString("id", "Candidate")),
                    cameraId = if (item.has("cameraId")) item.optInt("cameraId") else null,
                    slice = if (item.has("slice")) item.optString("slice") else null,
                    view = if (item.has("view")) item.optString("view") else null,
                    width = item.optInt("previewWidth", 1280),
                    height = item.optInt("previewHeight", 720)
                )
            }

            val mappings = mutableMapOf<String, String>()
            val mappingsJson = config.optJSONObject("cameraRoleMappings")
            roles.forEach { role ->
                val source = mappingsJson?.optJSONObject(role.key)
                val sourceId = if (source != null && source.has("id")) source.optString("id") else null
                if (!sourceId.isNullOrEmpty()) {
                    mappings[role.key] = sourceId
                }
            }

            // Manual override state — daemon merges these into the same
            // /api/surveillance/config response (see SurveillanceApiHandler:546).
            // cameraId == -1 + manualOverride==false is the "auto" state.
            val rawManualId = config.optInt("cameraId", -1)
            val manualOverride = config.optBoolean("cameraManualOverride", false)
            val manualCameraId = if (manualOverride && rawManualId in 0..5) rawManualId else null

            CameraMappingState(
                summary = summary,
                roles = roles,
                candidates = candidates,
                currentMappings = mappings,
                manualCameraId = manualCameraId,
                isManualOverride = manualOverride
            )
        } catch (e: Exception) {
            logsViewModel.error("Camera", "Failed to load camera mapping state: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun showCameraMappingDialog(state: CameraMappingState) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_camera_mapping, null)
        val summaryView = dialogView.findViewById<TextView>(R.id.tvCameraProfileSummary)
        val roleSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerCameraRole)
        val currentMappingView = dialogView.findViewById<TextView>(R.id.tvCurrentRoleMapping)
        val candidateLabelView = dialogView.findViewById<TextView>(R.id.tvCandidateLabel)
        val previewImageView = dialogView.findViewById<ImageView>(R.id.ivCameraCandidatePreview)
        val previewPlaceholderView = dialogView.findViewById<TextView>(R.id.tvCameraPreviewPlaceholder)
        val prevButton = dialogView.findViewById<View>(R.id.btnPrevCandidate)
        val nextButton = dialogView.findViewById<View>(R.id.btnNextCandidate)
        val saveMappingButton = dialogView.findViewById<View>(R.id.btnSaveCameraRoleMapping)
        val clearMappingButton = dialogView.findViewById<View>(R.id.btnClearCameraRoleMapping)
        val manualCameraGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgManualCameraId)
        val currentManualCameraView = dialogView.findViewById<TextView>(R.id.tvCurrentManualCamera)
        val saveManualCameraButton = dialogView.findViewById<View>(R.id.btnSaveManualCameraId)

        summaryView.text = state.summary

        val roleAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            state.roles.map { it.label }
        )
        roleSpinner.adapter = roleAdapter

        var currentRoleIndex = 0
        var currentCandidateIndex = 0
        val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var dialogClosed = false
        var activePreviewCandidateId: String? = null
        // Single in-flight preview at a time. The daemon gates direct opens
        // and panoramic-slice JPEG cache reads behind a single-flight
        // cold-start (returns 503 Retry-After=2s while warming up). Keeping
        // one outstanding request avoids stacking when the user holds Next.
        var fetchInFlight = false
        // The previously-displayed bitmap. ImageView keeps a reference via
        // its Drawable but won't recycle the old one when setImageBitmap
        // replaces it; without manual recycle a 2s polling cycle leaks
        // ~30+ ARGB_8888 1280×960 bitmaps per minute (4 MB each = 120 MB/min)
        // and OOMs the dialog on the BYD heap.
        var previousPreviewBitmap: android.graphics.Bitmap? = null

        fun mappedCandidateIndexForRole(roleKey: String): Int {
            val mappedId = state.currentMappings[roleKey] ?: return 0
            return state.candidates.indexOfFirst { it.id == mappedId }
                .let { if (it >= 0) it else 0 }
        }

        fun updateCurrentMappingText() {
            val role = state.roles.getOrNull(currentRoleIndex)
            val mappedId = role?.let { state.currentMappings[it.key] }
            val mappedLabel = state.candidates.firstOrNull { it.id == mappedId }?.label
            currentMappingView.text = if (mappedLabel.isNullOrEmpty()) {
                getString(R.string.camera_mapping_current_none)
            } else {
                getString(R.string.camera_mapping_current_format, mappedLabel)
            }
        }

        fun refreshPreview(scheduleNext: Boolean) {
            if (dialogClosed || state.candidates.isEmpty()) return
            if (fetchInFlight) {
                // A previous fetch hasn't completed. Arm a single retry on the
                // poll cadence so we don't permanently lose the loop when the
                // user navigates Prev/Next mid-fetch (the in-flight result
                // discards itself when activePreviewCandidateId differs and
                // can't re-arm for the new candidate).
                if (scheduleNext) {
                    previewHandler.removeCallbacksAndMessages(null)
                    previewHandler.postDelayed({ refreshPreview(true) }, 2000)
                }
                return
            }
            val candidate = state.candidates[currentCandidateIndex]
            activePreviewCandidateId = candidate.id
            candidateLabelView.text = candidate.label
            val previewPath = buildCameraPreviewPath(candidate)
            if (previewPath == null) {
                // Malformed candidate — show placeholder, skip the fetch,
                // re-arm next poll.
                previewImageView.setImageDrawable(null)
                previewPlaceholderView.visibility = View.VISIBLE
                if (scheduleNext) {
                    previewHandler.removeCallbacksAndMessages(null)
                    previewHandler.postDelayed({ refreshPreview(true) }, 2000)
                }
                return
            }
            fetchInFlight = true
            Thread {
                // Network + JPEG decode both happen on this worker thread —
                // never block the UI thread on Bitmap.decodeByteArray (5-15 ms
                // per tick at 2 s cadence is enough to cause visible jank on
                // the BYD head unit).
                var bitmap: android.graphics.Bitmap? = null
                try {
                    // 4 s read budget — daemon does AVMCamera open + ≤800 ms
                    // frame-wait + teardown for direct kind, ~50 ms volatile
                    // read + JPEG decode/crop for slice/virtual kinds. Both
                    // are well inside this window.
                    val conn = com.overdrive.app.util.DaemonHttpClient.open(
                        previewPath,
                        "GET",
                        2500,
                        4000
                    )
                    val bytes = if (conn.responseCode == 200) {
                        conn.inputStream.use { it.readBytes() }
                    } else null
                    conn.disconnect()
                    if (bytes != null && bytes.isNotEmpty()) {
                        bitmap = android.graphics.BitmapFactory
                            .decodeByteArray(bytes, 0, bytes.size)
                    }
                } catch (_: Exception) {
                    bitmap = null
                }

                val finalBitmap = bitmap
                runOnUiThread {
                    fetchInFlight = false
                    if (dialogClosed || activePreviewCandidateId != candidate.id) {
                        // Result for an old candidate or the dialog closed.
                        // The bitmap is no longer needed; recycle.
                        finalBitmap?.recycle()
                        // The new candidate may have re-armed via
                        // selectCandidate → refreshPreview but bailed at
                        // fetchInFlight; now that the in-flight is done, kick
                        // a fresh fetch immediately so the user doesn't see
                        // a 2s blank period after Prev/Next.
                        if (!dialogClosed && scheduleNext) {
                            previewHandler.removeCallbacksAndMessages(null)
                            refreshPreview(true)
                        }
                        return@runOnUiThread
                    }
                    if (finalBitmap != null) {
                        // Recycle the previous frame before swapping in the
                        // new one — ImageView holds via Drawable and won't
                        // recycle the replaced bitmap on its own.
                        val oldBitmap = previousPreviewBitmap
                        previewImageView.setImageBitmap(finalBitmap)
                        previewPlaceholderView.visibility = View.GONE
                        previousPreviewBitmap = finalBitmap
                        oldBitmap?.recycle()
                    } else {
                        previewImageView.setImageDrawable(null)
                        previewPlaceholderView.visibility = View.VISIBLE
                        previousPreviewBitmap?.recycle()
                        previousPreviewBitmap = null
                    }
                    if (scheduleNext) {
                        // Cancel any pending poll callback first so we don't
                        // double-arm when the in-flight result coincides
                        // with selectCandidate() arming its own poll.
                        previewHandler.removeCallbacksAndMessages(null)
                        previewHandler.postDelayed({ refreshPreview(true) }, 2000)
                    }
                }
            }.start()
        }

        fun selectCandidate(index: Int) {
            if (state.candidates.isEmpty()) {
                candidateLabelView.text = getString(R.string.camera_preview_unavailable)
                previewImageView.setImageDrawable(null)
                previewPlaceholderView.visibility = View.VISIBLE
                activePreviewCandidateId = null
                return
            }
            currentCandidateIndex = when {
                index < 0 -> 0
                index >= state.candidates.size -> state.candidates.lastIndex
                else -> index
            }
            val newCandidate = state.candidates[currentCandidateIndex]
            candidateLabelView.text = newCandidate.label
            // Eagerly update the active id so an in-flight fetch result for
            // a previous candidate can no longer slip through and render
            // its image under the new candidate's label. The fetch path
            // sees activePreviewCandidateId != candidate.id and discards.
            activePreviewCandidateId = newCandidate.id
            // Cancel any pending poll for the previous candidate, then
            // re-arm with the new one.
            previewHandler.removeCallbacksAndMessages(null)
            refreshPreview(true)
        }

        // Spinner.setOnItemSelectedListener fires synchronously on the next
        // layout pass for position 0 even when the user hasn't interacted —
        // an Android quirk. Suppress the first auto-fire so we don't kick a
        // duplicate fetch that races dialog.show() and the explicit
        // selectCandidate() call below.
        var spinnerInitialFireSuppressed = false
        roleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!spinnerInitialFireSuppressed) {
                    spinnerInitialFireSuppressed = true
                    return
                }
                currentRoleIndex = position
                val newIdx = mappedCandidateIndexForRole(state.roles[position].key)
                updateCurrentMappingText()
                selectCandidate(newIdx)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        prevButton.setOnClickListener {
            if (state.candidates.isEmpty()) return@setOnClickListener
            val idx = if (currentCandidateIndex <= 0) state.candidates.lastIndex
                      else currentCandidateIndex - 1
            selectCandidate(idx)
        }

        nextButton.setOnClickListener {
            if (state.candidates.isEmpty()) return@setOnClickListener
            val idx = if (currentCandidateIndex >= state.candidates.lastIndex) 0
                      else currentCandidateIndex + 1
            selectCandidate(idx)
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.Theme_Overdrive_M3_Dialog
        )
            .setView(dialogView)
            .setNegativeButton(getString(R.string.dialog_close), null)
            .create()

        dialog.setOnDismissListener {
            dialogClosed = true
            previewHandler.removeCallbacksAndMessages(null)
            // Drop the ImageView's drawable so the bitmap is no longer
            // pinned, then recycle it — Android's GC won't reclaim the
            // pixel buffer while the ImageView still references it.
            previewImageView.setImageDrawable(null)
            previousPreviewBitmap?.recycle()
            previousPreviewBitmap = null
        }

        // Lock both Save and Clear after the first click on either. The
        // success path dismisses the dialog and SIGKILLs the daemon for a
        // 5-second relaunch; without this guard the user can spam-click,
        // each click POSTing to a dying daemon and toasting "failed to
        // save". The buttons are re-enabled only on a save *failure* (the
        // dialog stays open so the user can retry).
        fun setActionsEnabled(enabled: Boolean) {
            saveMappingButton.isEnabled = enabled
            clearMappingButton.isEnabled = enabled
        }

        saveMappingButton.setOnClickListener {
            val role = state.roles.getOrNull(currentRoleIndex) ?: return@setOnClickListener
            val candidate = state.candidates.getOrNull(currentCandidateIndex) ?: return@setOnClickListener
            val payload = org.json.JSONObject().apply {
                put("cameraRoleMapping", org.json.JSONObject().apply {
                    put("role", role.key)
                    put("source", candidate.toJson())
                })
            }.toString()
            setActionsEnabled(false)
            postSurveillanceConfig(payload) { success, message ->
                if (success) {
                    Toast.makeText(
                        this,
                        getString(R.string.camera_mapping_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                    restartCameraDaemonForCameraSettings()
                    dialog.dismiss()
                } else {
                    Toast.makeText(
                        this,
                        message ?: getString(R.string.toast_failed_to_save_short),
                        Toast.LENGTH_SHORT
                    ).show()
                    setActionsEnabled(true)
                }
            }
        }

        clearMappingButton.setOnClickListener {
            val role = state.roles.getOrNull(currentRoleIndex) ?: return@setOnClickListener
            val payload = org.json.JSONObject().apply {
                put("cameraRoleMapping", org.json.JSONObject().apply {
                    put("role", role.key)
                    put("clear", true)
                })
            }.toString()
            setActionsEnabled(false)
            postSurveillanceConfig(payload) { success, message ->
                if (success) {
                    Toast.makeText(
                        this,
                        getString(R.string.camera_mapping_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                    restartCameraDaemonForCameraSettings()
                    dialog.dismiss()
                } else {
                    Toast.makeText(
                        this,
                        message ?: getString(R.string.toast_failed_to_save_short),
                        Toast.LENGTH_SHORT
                    ).show()
                    setActionsEnabled(true)
                }
            }
        }

        // Manual camera ID picker. Independent from the role-mapping flow:
        // saves a single integer (or clears it) and skips prepare-restart so
        // it works even when the live preview pipeline is wedged. The
        // manualCameraId field is read on next pipeline init (typically the
        // next ACC OFF/ON cycle) — operators can also restart the daemon
        // manually from the Daemons screen if they want it to take effect now.
        val initialManualRadioId = when (state.manualCameraId) {
            0 -> R.id.rbManualCamera0
            1 -> R.id.rbManualCamera1
            2 -> R.id.rbManualCamera2
            3 -> R.id.rbManualCamera3
            4 -> R.id.rbManualCamera4
            5 -> R.id.rbManualCamera5
            else -> R.id.rbManualCameraAuto
        }
        manualCameraGroup.check(initialManualRadioId)
        currentManualCameraView.text = state.manualCameraId?.let {
            getString(R.string.camera_mapping_current_format, "Camera $it")
        } ?: getString(R.string.camera_current_auto)

        saveManualCameraButton.setOnClickListener {
            val selectedId = when (manualCameraGroup.checkedRadioButtonId) {
                R.id.rbManualCamera0 -> 0
                R.id.rbManualCamera1 -> 1
                R.id.rbManualCamera2 -> 2
                R.id.rbManualCamera3 -> 3
                R.id.rbManualCamera4 -> 4
                R.id.rbManualCamera5 -> 5
                else -> -1
            }
            val payload = org.json.JSONObject().apply {
                if (selectedId >= 0) {
                    put("manualCameraId", selectedId)
                } else {
                    put("clearManualCameraId", true)
                }
            }.toString()
            saveManualCameraButton.isEnabled = false
            postSurveillanceConfig(payload) { success, message ->
                saveManualCameraButton.isEnabled = true
                if (success) {
                    currentManualCameraView.text = if (selectedId >= 0) {
                        getString(R.string.camera_mapping_current_format, "Camera $selectedId")
                    } else {
                        getString(R.string.camera_current_auto)
                    }
                    Toast.makeText(
                        this,
                        getString(R.string.camera_mapping_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        message ?: getString(R.string.toast_failed_to_save_short),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        updateCurrentMappingText()
        selectCandidate(mappedCandidateIndexForRole(
            state.roles.getOrNull(currentRoleIndex)?.key ?: ""))
        dialog.show()
    }

    private fun postSurveillanceConfig(body: String, onComplete: (Boolean, String?) -> Unit) {
        Thread {
            var success = false
            var message: String? = null
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/surveillance/config", "POST", 3000, 4000)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val httpOk = conn.responseCode == 200
                if (httpOk) {
                    // Daemon's HttpResponse.sendJsonError returns HTTP 200 with
                    // a {success:false, error: "..."} body, so we can't trust
                    // the status code alone. Parse the body and check the
                    // explicit success field.
                    val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
                    try {
                        val json = org.json.JSONObject(bodyText)
                        success = json.optBoolean("success", true)
                        if (!success) {
                            message = json.optString("error",
                                getString(R.string.toast_failed_to_save_short))
                        }
                    } catch (_: Exception) {
                        // Non-JSON body — assume success (legacy endpoints).
                        success = true
                    }
                } else {
                    message = conn.errorStream?.bufferedReader()?.use { it.readText() }
                }
            } catch (e: Exception) {
                success = false
                message = e.message
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            runOnUiThread { onComplete(success, message) }
        }.start()
    }

    /**
     * Save → restart pattern: camera mapping changes propagate through
     * GpuSurveillancePipeline.init() (foveated cropper, mosaic recorder,
     * downscaler, stream scaler all read the resolved layout at init time).
     * A bare config write doesn't reapply offsets to the running pipeline,
     * so we kill the daemon and let the watchdog relaunch.
     *
     * <p>Two-phase to avoid corrupting in-flight recordings:
     * <ol>
     *   <li>POST /api/surveillance/prepare-restart — daemon stops the pipeline
     *       gracefully (writes MP4 moov atom, flushes circular buffer,
     *       closes encoder + EGL surfaces). Bounded ~1-2 s.</li>
     *   <li>SIGKILL the daemon and relaunch. The 5 s relaunch delay gives the
     *       BYD camera HAL time to release the camera before the new daemon
     *       opens it (without that, the HAL emits event 1002).</li>
     * </ol>
     */
    private fun restartCameraDaemonForCameraSettings() {
        // Guard every continuation against an Activity that's destroyed mid-
        // restart. The chain is: Thread → runOnUiThread → AdbDaemonLauncher
        // callback → runOnUiThread → 5s postDelayed → 5s postDelayed —
        // total ~10s. If the user backgrounds or recreates the activity
        // during that window, the captured `this` would otherwise be a
        // dead reference to a torn-down Activity.
        fun activityAlive(): Boolean = !isFinishing && !isDestroyed

        Toast.makeText(
            this,
            getString(R.string.toast_camera_settings_saved_restarting),
            Toast.LENGTH_LONG
        ).show()
        logsViewModel.info("Camera",
            "Camera settings changed — restarting daemon to apply them immediately")

        Thread {
            // Phase 1: graceful flush. Best-effort — failures here are
            // logged but don't block the restart, since the dialog already
            // toasted "saved" and the user expects the restart to happen.
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/surveillance/prepare-restart", "POST", 3000, 5000)
                conn.doOutput = true
                conn.outputStream.use { it.write(byteArrayOf()) }
                val code = conn.responseCode
                conn.disconnect()
                logsViewModel.debug("Camera",
                    "prepare-restart returned HTTP $code")
            } catch (e: Exception) {
                logsViewModel.warn("Camera",
                    "prepare-restart failed (continuing with kill anyway): ${e.message}")
            }

            // Phase 2: kill + relaunch on the UI thread.
            runOnUiThread {
                if (!activityAlive()) return@runOnUiThread
                // Reuse shared adbLauncher (avoids per-call leak).
                daemonStartupManager.adbLauncher.killDaemon(object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                    override fun onLog(message: String) {
                        logsViewModel.debug("Camera", message)
                    }

                    override fun onLaunched() {
                        runOnUiThread {
                            if (!activityAlive()) return@runOnUiThread
                            logsViewModel.info("Camera",
                                "Camera daemon stopped — relaunching with updated camera settings")
                            // 5 s delay: BYD camera HAL needs ~3-5 s to release
                            // the camera after process death; relaunching too
                            // early triggers event 1002 on the new daemon's open.
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (!activityAlive()) return@postDelayed
                                daemonStartupManager.initializeOnAppLaunch()
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    if (!activityAlive()) return@postDelayed
                                    daemonStartupManager.checkAllDaemonStatuses()
                                }, 5000)
                            }, 5000)
                        }
                    }

                    override fun onError(error: String) {
                        // Kill failed. The daemon is still alive AND its
                        // shutdownInProgress latch is set (prepare-restart
                        // ran successfully). Without unsticking it, every
                        // future preview returns "Preview unavailable".
                        // Fire-and-forget POST to clear the latch.
                        Thread {
                            try {
                                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                                    "/api/surveillance/abort-restart", "POST", 2000, 2000)
                                conn.doOutput = true
                                conn.outputStream.use { it.write(byteArrayOf()) }
                                conn.responseCode  // force send
                                conn.disconnect()
                            } catch (_: Exception) {
                                // Best-effort. Worst case the user must
                                // manually restart the daemon.
                            }
                        }.start()
                        runOnUiThread {
                            if (!activityAlive()) return@runOnUiThread
                            logsViewModel.error("Camera",
                                "Failed to restart daemon after camera settings save: $error")
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.toast_camera_settings_restart_failed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                })
            }
        }.start()
    }

    private fun buildCameraPreviewPath(candidate: CameraPreviewCandidate): String? {
        // Build the preview URL with strict shape matching. A malformed
        // candidate (e.g. kind=direct without cameraId, kind=panoramicSlice
        // without slice) used to silently fall through to "panoramic
        // front", which would render the front camera under whatever role
        // the user was actually mapping — misleading. Now we return null
        // and the caller treats it as "Preview unavailable".
        return when {
            candidate.kind.equals("direct", ignoreCase = true) -> {
                if (candidate.cameraId == null) null
                else "/api/surveillance/camera-preview?kind=direct" +
                    "&cameraId=${candidate.cameraId}" +
                    "&width=${candidate.width}&height=${candidate.height}"
            }
            candidate.kind.equals("panoramicSlice", ignoreCase = true) -> {
                if (candidate.slice.isNullOrEmpty()) null
                else "/api/surveillance/camera-preview?kind=panoramicSlice" +
                    "&slice=${candidate.slice}" +
                    "&width=${candidate.width}&height=${candidate.height}"
            }
            candidate.kind.equals("panoramic", ignoreCase = true)
                    || candidate.kind.equals("panoramicVirtual", ignoreCase = true) -> {
                if (candidate.view.isNullOrEmpty()) null
                else "/api/surveillance/camera-preview?kind=panoramic" +
                    "&view=${candidate.view}"
            }
            else -> null
        }
    }

    /**
     * Clear saved camera config and restart the camera daemon.
     */
    private fun performCameraReconfigure() {
        Toast.makeText(this, getString(R.string.toast_clearing_camera_config), Toast.LENGTH_SHORT).show()
        logsViewModel.info("Camera", "Clearing saved camera probe config for re-probe")
        
        Thread {
            try {
                // Clear the camera section from unified config
                val emptyCameraConfig = org.json.JSONObject()
                emptyCameraConfig.put("probedCameraId", -1)
                emptyCameraConfig.put("probedSurfaceMode", -1)
                com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", emptyCameraConfig)
                
                runOnUiThread {
                    logsViewModel.info("Camera", "Camera config cleared — restarting daemon")
                    Toast.makeText(this, getString(R.string.toast_restarting_camera_daemon), Toast.LENGTH_SHORT).show()
                }
                
                // Kill the camera daemon — DaemonLauncher's watchdog will auto-restart it.
                // Reuse shared adbLauncher (avoids per-call leak).
                daemonStartupManager.adbLauncher.killDaemon(object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                    override fun onLog(message: String) {
                        logsViewModel.debug("Camera", message)
                    }
                    
                    override fun onLaunched() {
                        runOnUiThread {
                            logsViewModel.info("Camera", "Camera daemon stopped — will auto-restart with full probe")
                            Toast.makeText(this@MainActivity,
                                getString(R.string.toast_camera_daemon_restarting), Toast.LENGTH_LONG).show()
                            
                            // Re-launch the daemon after a brief delay
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                daemonStartupManager.initializeOnAppLaunch()
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    daemonStartupManager.checkAllDaemonStatuses()
                                }, 5000)
                            }, 3000)
                        }
                    }
                    
                    override fun onError(error: String) {
                        runOnUiThread {
                            logsViewModel.error("Camera", "Failed to stop daemon: $error")
                            Toast.makeText(this@MainActivity,
                                getString(R.string.toast_camera_restart_failed),
                                Toast.LENGTH_LONG).show()
                        }
                    }
                })
                
            } catch (e: Exception) {
                runOnUiThread {
                    logsViewModel.error("Camera", "Reconfigure failed: ${e.message}")
                    Toast.makeText(this, getString(R.string.toast_failed_with_message_x, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
    
    // ==================== Battery Health (SOH) Dialog ====================

    /**
     * Shows a styled dialog with SOH status details and a reset button.
     * Reads directly from the persisted properties file (no HTTP/auth needed).
     */
    private fun showBatteryHealthDialog() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        executor.execute {
            var sohPercent = "--"
            var source = "--"
            var method = "live"
            var nominalKwh = "--"
            var samples = "--"
            var lastUpdated = "--"
            var hasEstimate = false

            // Vehicle section state. Populated from /api/performance/soh status JSON
            // when available; falls back to legacy properties-file values otherwise.
            var modelId: String? = null
            var nominalKwhValue = 0.0
            var nominalSourceVal = "unset"
            var estimatedKwhValue = 0.0
            var calibrationSoh = 0.0
            var calibrationTs = 0L

            try {
                val sohFile = java.io.File("/data/local/tmp/abrp_soh_estimate.properties")
                if (sohFile.exists()) {
                    val props = java.util.Properties()
                    java.io.FileInputStream(sohFile).use { props.load(it) }

                    val soh = props.getProperty("soh_percent")?.toDoubleOrNull()
                    if (soh != null && soh > 0 && soh <= 110) {
                        sohPercent = String.format("%.1f%%", soh)
                        hasEstimate = true
                    }

                    // Shape B: live formula + calibration anchor (separate, not blended).
                    val cal = props.getProperty("calibration_soh")?.toDoubleOrNull()
                    samples = if (cal != null && cal > 0) String.format("calib %.1f%%", cal) else "—"

                    val nominal = props.getProperty("nominal_capacity_kwh")?.toDoubleOrNull()
                    if (nominal != null && nominal > 0) {
                        nominalKwh = String.format("%.1f kWh", nominal)
                        nominalKwhValue = nominal
                    }

                    val ts = props.getProperty("last_updated")?.toLongOrNull()
                    if (ts != null && ts > 0) {
                        lastUpdated = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(ts))
                    }

                    source = props.getProperty("nominal_source") ?: "unset"
                    nominalSourceVal = source
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "SOH file read failed: ${e.message}")
            }

            // Fetch full SOH status (modelId, calibration anchor, estimated capacity) —
            // properties file alone doesn't carry modelId or live calibration shape.
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh", "GET", 2000, 3000)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    if (!json.isNull("modelId")) {
                        modelId = json.optString("modelId", "").ifEmpty { null }
                    }
                    nominalKwhValue = json.optDouble("nominalCapacityKwh", nominalKwhValue)
                    nominalSourceVal = json.optString("nominalSource", nominalSourceVal)
                    val est = json.optDouble("estimatedCapacityKwh", -1.0)
                    if (est > 0) estimatedKwhValue = est
                    val calObj = json.optJSONObject("calibration")
                    if (calObj != null) {
                        calibrationSoh = calObj.optDouble("soh", -1.0)
                        calibrationTs = calObj.optLong("timestampMs", 0L)
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) { /* keep legacy file fallback values */ }

            val finalSoh = sohPercent
            val finalSource = source
            val finalMethod = method
            val finalNominal = nominalKwh
            val finalSamples = samples
            val finalLastUpdated = lastUpdated
            val finalHasEstimate = hasEstimate
            val finalModelId = modelId
            val finalNominalKwh = nominalKwhValue
            val finalNominalSource = nominalSourceVal
            val finalEstimatedKwh = estimatedKwhValue
            val finalCalSoh = calibrationSoh
            val finalCalTs = calibrationTs

            runOnUiThread {
                val dialogView = layoutInflater.inflate(R.layout.dialog_battery_health, null)

                // Populate fields
                dialogView.findViewById<TextView>(R.id.tvSohPercent).text = finalSoh
                dialogView.findViewById<TextView>(R.id.tvSohSource).text = finalSource
                dialogView.findViewById<TextView>(R.id.tvSohMethod).text = finalMethod
                dialogView.findViewById<TextView>(R.id.tvSohCapacity).text = finalNominal
                dialogView.findViewById<TextView>(R.id.tvSohSamples).text = finalSamples
                dialogView.findViewById<TextView>(R.id.tvSohLastUpdated).text = finalLastUpdated

                // Vehicle section
                dialogView.findViewById<TextView>(R.id.tvSohModel).text =
                    if (finalModelId != null) modelDisplayName(finalModelId)
                    else getString(R.string.soh_dialog_model_not_selected)

                val packCapView = dialogView.findViewById<TextView>(R.id.tvSohPackCapacity)
                val packBadgeView = dialogView.findViewById<TextView>(R.id.tvSohPackCapacityBadge)
                if (finalNominalKwh > 0) {
                    packCapView.text = String.format("%.1f kWh", finalNominalKwh)
                    val badgeText = when (finalNominalSource) {
                        "user" -> getString(R.string.soh_dialog_source_user)
                        "auto" -> getString(R.string.soh_dialog_source_auto)
                        else -> null
                    }
                    if (badgeText != null) {
                        packBadgeView.text = "(" + badgeText + ")"
                        packBadgeView.visibility = View.VISIBLE
                    } else {
                        packBadgeView.visibility = View.GONE
                    }
                } else {
                    packCapView.text = getString(R.string.soh_dialog_capacity_not_detected)
                    packBadgeView.visibility = View.GONE
                }

                val rowEst = dialogView.findViewById<View>(R.id.rowSohEstimatedCapacity)
                if (finalEstimatedKwh > 0) {
                    dialogView.findViewById<TextView>(R.id.tvSohEstimatedCapacity).text =
                        String.format("%.1f kWh", finalEstimatedKwh)
                    rowEst.visibility = View.VISIBLE
                } else {
                    rowEst.visibility = View.GONE
                }

                val rowCal = dialogView.findViewById<View>(R.id.rowSohCalibrationAnchor)
                if (finalCalSoh > 0 && finalCalTs > 0) {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date(finalCalTs))
                    dialogView.findViewById<TextView>(R.id.tvSohCalibrationAnchor).text =
                        getString(R.string.soh_dialog_calibration_format, finalCalSoh, date)
                    rowCal.visibility = View.VISIBLE
                } else {
                    rowCal.visibility = View.GONE
                }

                // Status text
                val statusView = dialogView.findViewById<TextView>(R.id.tvSohStatus)
                if (finalHasEstimate) {
                    statusView.text = getString(R.string.soh_estimation_active)
                    statusView.setTextColor(resources.getColor(R.color.brand_primary, null))
                } else {
                    statusView.text = getString(R.string.soh_no_estimate_yet)
                    statusView.setTextColor(resources.getColor(R.color.text_muted, null))
                }

                // SOH percent color based on health
                val sohView = dialogView.findViewById<TextView>(R.id.tvSohPercent)
                if (finalHasEstimate) {
                    val sohVal = finalSoh.replace("%", "").toDoubleOrNull() ?: 0.0
                    val colorRes = when {
                        sohVal >= 85 -> R.color.brand_primary   // Good
                        sohVal >= 70 -> R.color.status_starting // Moderate
                        else -> R.color.status_error            // Degraded
                    }
                    sohView.setTextColor(resources.getColor(colorRes, null))
                }

                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.dialog_close), null)
                    .create()

                // Wire up reset button
                dialogView.findViewById<TextView>(R.id.btnResetSoh).setOnClickListener {
                    dialog.dismiss()
                    confirmSohReset()
                }

                dialog.show()
            }
        }
    }

    private fun modelDisplayName(modelId: String?): String {
        return when (modelId?.lowercase()) {
            null -> "—"
            "seal" -> "BYD Seal"
            "atto3", "atto-3" -> "BYD Atto 3"
            "atto2", "atto-2" -> "BYD Atto 2"
            "atto1", "atto-1" -> "BYD Atto 1"
            "han" -> "BYD Han"
            "tang" -> "BYD Tang"
            "song" -> "BYD Song"
            "qin" -> "BYD Qin"
            "dolphin" -> "BYD Dolphin"
            "seagull" -> "BYD Seagull"
            "sealion6" -> "BYD Sealion 6"
            "sealion7" -> "BYD Sealion 7"
            "sealu", "seal-u" -> "BYD Seal U"
            else -> modelId.replaceFirstChar { it.uppercase() }
        }
    }
    
    /**
     * Confirmation dialog before resetting SOH estimation.
     */
    private fun confirmSohReset() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_reset_soh_title))
            .setMessage(getString(R.string.dialog_reset_soh_message))
            .setPositiveButton(getString(R.string.dialog_reset)) { _, _ ->
                performSohReset()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
    
    /**
     * Perform the actual SOH reset by deleting the properties file.
     * The daemon's SohEstimator will detect the missing file and re-seed.
     */
    private fun performSohReset() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                // Use daemon API (daemon owns the file, has write permissions)
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh/reset", "POST", 3000, 3000)
                conn.doOutput = true
                conn.outputStream.use { it.write("{}".toByteArray()) }
                val responseCode = conn.responseCode
                conn.disconnect()
                
                if (responseCode == 200) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.toast_soh_reset_success), Toast.LENGTH_LONG).show()
                        logsViewModel.info("SOH", "SOH estimation reset by user")
                    }
                } else {
                    // Fallback: try direct file delete (works if app has permissions)
                    val sohFile = java.io.File("/data/local/tmp/abrp_soh_estimate.properties")
                    val deleted = if (sohFile.exists()) sohFile.delete() else true
                    runOnUiThread {
                        if (deleted) {
                            Toast.makeText(this, getString(R.string.toast_soh_reset_success), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, getString(R.string.toast_soh_reset_failed_no_daemon), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_soh_reset_failed_with_message, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ==================== Reset Data Dialog ====================

    /** Map drawer menu category id → API category name (must match server). */
    private val resetCategoryMapping = listOf(
        R.id.cbResetTrips to "trips",
        R.id.cbResetSocHistory to "socHistory",
        R.id.cbResetSoh to "soh",
        R.id.cbResetAbrpToken to "abrpToken",
        R.id.cbResetBydCloud to "bydCloud",
        R.id.cbResetMediaRecordings to "mediaRecordings",
        R.id.cbResetMediaSurveillance to "mediaSurveillance",
        R.id.cbResetMediaProximity to "mediaProximity",
        R.id.cbResetMediaTrips to "mediaTrips"
    )

    private fun showResetDataDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_reset_data, null)

        val checkboxes: List<Pair<com.google.android.material.checkbox.MaterialCheckBox, String>> =
            resetCategoryMapping.map { (id, cat) ->
                dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id) to cat
            }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.dialog_reset_selected), null)  // Wired below to allow keep-open on validate
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create()

        // Quick toggles
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResetSelectAll)
            .setOnClickListener {
                checkboxes.forEach { it.first.isChecked = true }
            }
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResetClearAll)
            .setOnClickListener {
                checkboxes.forEach { it.first.isChecked = false }
            }

        dialog.setOnShowListener {
            val ok = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            ok.setTextColor(resources.getColor(R.color.status_error, null))
            ok.setOnClickListener {
                val selected = checkboxes.filter { it.first.isChecked }.map { it.second }
                if (selected.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_select_at_least_one_category), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                confirmAndPerformReset(selected)
            }
        }

        dialog.show()
    }

    private fun confirmAndPerformReset(categories: List<String>) {
        val labels = mapOf(
            "trips" to getString(R.string.reset_label_trips),
            "socHistory" to getString(R.string.reset_label_soc_history),
            "soh" to getString(R.string.reset_label_soh),
            "abrpToken" to getString(R.string.reset_label_abrp_token),
            "bydCloud" to getString(R.string.reset_label_byd_cloud),
            "mediaRecordings" to getString(R.string.reset_label_recordings),
            "mediaSurveillance" to getString(R.string.reset_label_sentry_events),
            "mediaProximity" to getString(R.string.reset_label_proximity),
            "mediaTrips" to getString(R.string.reset_label_trip_files)
        )
        val list = categories.joinToString("\n") { "• " + (labels[it] ?: it) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_reset_following_title))
            .setMessage(getString(R.string.dialog_reset_following_message, list))
            .setPositiveButton(getString(R.string.dialog_reset)) { _, _ -> performReset(categories, labels) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun performReset(
        categories: List<String>,
        labels: Map<String, String>
    ) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val payload = org.json.JSONObject().apply {
                    put("categories", org.json.JSONArray(categories))
                }
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/reset", "POST", 5000, 15000)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                val code = conn.responseCode
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                conn.disconnect()

                val data = try { org.json.JSONObject(body) } catch (e: Exception) { null }
                runOnUiThread {
                    if (data != null && data.optBoolean("success", false)) {
                        val results = data.optJSONObject("results")
                        val lines = StringBuilder()
                        for (cat in categories) {
                            val r = results?.optJSONObject(cat)
                            val label = labels[cat] ?: cat
                            if (r != null && r.optBoolean("success", false)) {
                                val detail = when {
                                    r.has("rowsDeleted") -> " (${r.optLong("rowsDeleted")} rows)"
                                    r.has("filesDeleted") -> " (${r.optLong("filesDeleted")} files)"
                                    else -> ""
                                }
                                lines.append("• ").append(label).append(detail).append("\n")
                            } else {
                                val err = r?.optString("error", "failed") ?: "failed"
                                lines.append("• ").append(label).append(" — ").append(err).append("\n")
                            }
                        }
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
                            .setIcon(R.drawable.ic_check_circle)
                            .setTitle(getString(R.string.dialog_reset_complete_title))
                            .setMessage(lines.toString().trim())
                            .setPositiveButton(getString(R.string.dialog_ok), null)
                            .show()
                        logsViewModel.info("Reset", "Categories: ${categories.joinToString(",")}")
                    } else {
                        val err = data?.optString("error") ?: "HTTP $code"
                        Toast.makeText(this, getString(R.string.toast_reset_failed_with_error, err), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_reset_failed_with_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ==================== Traffic Monitor Management ====================

    /** Track current traffic monitor state to show correct button */
    private var trafficMonitorEnabled: Boolean? = null
    
    /**
     * Check if BYD Traffic Monitor app is currently enabled or disabled.
     * Updates the drawer menu item title accordingly.
     * 
     * Uses ADB shell — if ADB isn't connected yet, shows a "checking" state
     * and retries automatically when the drawer is opened.
     */
    private fun checkTrafficMonitorStatus() {
        // Show loading state while we check
        updateTrafficMonitorMenuItemText(getString(R.string.traffic_monitor_loading))
        
        // Reuse shared adbLauncher (avoids per-call leak).
        // Use 'grep ... || echo NOT_DISABLED' to ensure exit code 0 regardless of grep result
        daemonStartupManager.adbLauncher.executeShellCommand(
            "pm list packages -d 2>/dev/null | grep com.byd.trafficmonitor || echo NOT_DISABLED",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    val isDisabled = message.contains("com.byd.trafficmonitor") && !message.contains("NOT_DISABLED")
                    runOnUiThread {
                        trafficMonitorEnabled = !isDisabled
                        updateTrafficMonitorMenuItem(!isDisabled)
                    }
                }
                override fun onLaunched() {
                    // Command completed — if onLog wasn't called, default to enabled
                    if (trafficMonitorEnabled == null) {
                        runOnUiThread {
                            trafficMonitorEnabled = true
                            updateTrafficMonitorMenuItem(true)
                        }
                    }
                }
                override fun onError(error: String) {
                    runOnUiThread {
                        // Actual ADB connection failure
                        trafficMonitorEnabled = null
                        updateTrafficMonitorMenuItemText(getString(R.string.traffic_monitor_tap_to_check))
                    }
                }
            }
        )
    }
    
    /**
     * Drawer-era helpers — kept as no-ops because checkTrafficMonitorStatus()
     * still calls them, and we want behavior parity (the status check still
     * runs; it just doesn't have a drawer menu item to retitle anymore).
     * Settings → Diagnostics shows the traffic monitor in dialog form.
     */
    private fun updateTrafficMonitorMenuItem(enabled: Boolean) { /* no-op in rail shell */ }
    private fun updateTrafficMonitorMenuItemText(text: String) { /* no-op in rail shell */ }
    
    /**
     * Handle traffic monitor menu item click.
     * Shows an informational dialog explaining what the traffic monitor is,
     * why disabling it is recommended, and lets the user take action.
     */
    private fun onTrafficMonitorClicked() {
        val currentlyEnabled = trafficMonitorEnabled
        
        if (currentlyEnabled == null) {
            // ADB not connected — retry the check and show explanation
            checkTrafficMonitorStatus()
            
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
                .setIcon(R.drawable.ic_warning)
                .setTitle(getString(R.string.dialog_traffic_cannot_check_title))
                .setMessage(getString(R.string.dialog_traffic_cannot_check_message))
                .setPositiveButton(getString(R.string.dialog_ok), null)
                .show()
            return
        }

        if (currentlyEnabled) {
            // Currently enabled — offer to disable with full explanation
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
                .setIcon(R.drawable.ic_traffic_monitor)
                .setTitle(getString(R.string.dialog_traffic_disable_title))
                .setMessage(getString(R.string.dialog_traffic_disable_message))
                .setPositiveButton(getString(R.string.dialog_disable)) { _, _ ->
                    setTrafficMonitorEnabled(false)
                }
                .setNegativeButton(getString(R.string.dialog_keep_enabled), null)
                .show()
        } else {
            // Currently disabled — offer to re-enable
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Overdrive_M3_Dialog)
                .setIcon(R.drawable.ic_traffic_monitor)
                .setTitle(getString(R.string.dialog_traffic_enable_title))
                .setMessage(getString(R.string.dialog_traffic_enable_message))
                .setPositiveButton(getString(R.string.dialog_enable)) { _, _ ->
                    setTrafficMonitorEnabled(true)
                }
                .setNegativeButton(getString(R.string.dialog_keep_disabled), null)
                .show()
        }
    }
    
    /**
     * Enable or disable the BYD Traffic Monitor package via ADB shell.
     */
    private fun setTrafficMonitorEnabled(enable: Boolean) {
        val cmd = if (enable) {
            "pm enable com.byd.trafficmonitor 2>&1"
        } else {
            "pm disable-user --user 0 com.byd.trafficmonitor 2>&1"
        }
        
        val action = if (enable) "Enabling" else "Disabling"
        Toast.makeText(this, getString(R.string.toast_traffic_monitor_changing, action), Toast.LENGTH_SHORT).show()
        
        // Reuse shared adbLauncher (avoids per-call leak).
        daemonStartupManager.adbLauncher.executeShellCommand(cmd, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                android.util.Log.i("TrafficMonitor", "$action result: $message")
            }
            
            override fun onLaunched() {
                runOnUiThread {
                    trafficMonitorEnabled = enable
                    updateTrafficMonitorMenuItem(enable)
                    
                    val state = if (enable) "enabled" else "disabled"
                    logsViewModel.info("TrafficMonitor", "BYD Traffic Monitor $state")
                    
                    // Show reboot reminder
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity, R.style.Theme_Overdrive_M3_Dialog)
                        .setIcon(R.drawable.ic_check_circle)
                        .setTitle(getString(R.string.dialog_traffic_status_title, state.replaceFirstChar { it.uppercase() }))
                        .setMessage(getString(R.string.dialog_traffic_reboot_message))
                        .setPositiveButton(getString(R.string.dialog_ok), null)
                        .show()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_failed_with_message_x, error), Toast.LENGTH_LONG).show()
                    logsViewModel.error("TrafficMonitor", "Failed to ${if (enable) "enable" else "disable"}: $error")
                }
            }
        })
    }
    
    override fun onDestroy() {
        // Remove log listener
        LogManager.setLogListener(null)
        // Remove ADB auth callback
        com.overdrive.app.launcher.AdbShellExecutor.setAuthCallback(null)
        // Cancel the periodic update check so the Runnable doesn't leak the
        // activity reference after recreate.
        updateCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        updateCheckRunnable = null
        // Cancel any in-flight post-update watchdog. Without this the
        // Handler.postDelayed lambda holds a reference to this@MainActivity
        // for up to 30 seconds, leaking the activity if the user backs out
        // mid-update. The watchdog itself also short-circuits on
        // isFinishing/isDestroyed but we cancel here for cleanliness.
        synchronized (daemonStartupCoordinator) {
            daemonStartupCoordinator.pendingWatchdog?.let {
                daemonStartupCoordinator.watchdogHandler?.removeCallbacks(it)
            }
            daemonStartupCoordinator.pendingWatchdog = null
            daemonStartupCoordinator.watchdogHandler = null
        }
        // Note: We intentionally do NOT call cleanupAll() here
        // Daemons should persist after app closure
        super.onDestroy()
    }

    // ==========================================================
    //  Public shims invoked by SettingsFragment / DiagnosticsFragment
    //  Behaviour identical to the old drawer items — no logic change.
    // ==========================================================

    fun invokeCheckForUpdates() = checkForAppUpdateManual()
    fun invokeResetDataDialog() = showResetDataDialog()
    fun invokeBatteryHealthAction() = showBatteryHealthDialog()
    fun invokeReconfigureCameraAction() = onReconfigureCameraClicked()
    fun invokeTrafficMonitorAction() {
        // Match drawer-open behavior: refresh status before showing dialog.
        checkTrafficMonitorStatus()
        onTrafficMonitorClicked()
    }
}
