package com.overdrive.app.config

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import org.json.JSONObject

/**
 * Manages Cloudflared Paid configuration and provides a setup dialog.
 * Kept in a separate file for easy maintenance/portability.
 * Uses UnifiedConfigManager for cross-process state sync.
 */
object CloudflaredPaidConfig {

    @JvmStatic
    fun isPaidVersion(): Boolean {
        return UnifiedConfigManager.getCloudflared().optBoolean("isPaid", false)
    }

    @JvmStatic
    fun getToken(): String {
        return UnifiedConfigManager.getCloudflared().optString("token", "")
    }

    /**
     * Build the command line arguments for cloudflared based on current configuration.
     * Can be called from any process (app or daemon).
     */
    @JvmStatic
    fun getArgs(): String {
        // Ensure fresh read if in daemon process. App process also calls
        // forceReload before launch to ensure it picks up the latest settings
        // saved via IPC to the daemon.
        UnifiedConfigManager.forceReload()
        val config = UnifiedConfigManager.getCloudflared()
        val isPaid = config.optBoolean("isPaid", false)
        val token = config.optString("token", "")

        return if (isPaid && token.isNotEmpty()) {
            "tunnel run --token $token"
        } else {
            "tunnel --url http://127.0.0.1:8080 --edge-ip-version 4 --protocol http2 --no-autoupdate --retries 20 --grace-period 45s"
        }
    }

    @JvmStatic
    fun isConfigured(): Boolean {
        return !isPaidVersion() || getToken().isNotEmpty()
    }

    /**
     * Build the shell command to extract the tunnel URL from the log.
     */
    @JvmStatic
    fun getUrlExtractionCommand(logPath: String): String {
        return if (isPaidVersion() && getToken().isNotEmpty()) {
            "cat $logPath | grep --line-buffered -iE 'ingress|hostname'"
        } else {
            "cat $logPath 2>/dev/null"
        }
    }

    /**
     * Parse the tunnel URL from the log content.
     */
    @JvmStatic
    fun parseUrl(logContent: String): String? {
        return if (isPaidVersion() && getToken().isNotEmpty()) {
            val cfUrlPattern = Regex("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
            val match = cfUrlPattern.find(logContent)
            if (match != null) {
                // Return as https URL
                "https://" + match.value
            } else null
        } else {
            val cfUrlPattern = Regex("https://([a-z0-9]+-[a-z0-9-]+)\\.trycloudflare\\.com")
            cfUrlPattern.find(logContent)?.value
        }
    }

    /**
     * Build the command for grep -o (used in getTunnelUrl).
     */
    @JvmStatic
    fun getGrepUrlCommand(logPath: String): String {
        return if (isPaidVersion() && getToken().isNotEmpty()) {
            // For paid version, we might have multiple hostnames in log,
            // we look for the one in 'ingress' or 'hostname' lines.
            // This is harder to do with a single grep -o, so we might return
            // the full line and let the caller parse it, or try to refine.
            "grep -iE 'ingress|hostname' $logPath 2>/dev/null | grep -oE '[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}' | head -1"
        } else {
            "grep -o 'https://[a-z0-9-]*\\.trycloudflare\\.com' $logPath 2>/dev/null | grep -v 'api\\.' | head -1"
        }
    }

    /**
     * Show the Cloudflared settings dialog.
     */
    fun showSettingsDialog(context: Context, daemonsViewModel: DaemonsViewModel) {
        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_cloudflared_settings, null)

        val swPaid = dialogView.findViewById<SwitchMaterial>(R.id.swCloudflarePaid)
        val tilToken = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilCloudflareToken)
        val etToken = dialogView.findViewById<EditText>(R.id.etCloudflareToken)

        // Ensure we have latest data
        UnifiedConfigManager.forceReload()
        val config = UnifiedConfigManager.getCloudflared()
        val currentIsPaid = config.optBoolean("isPaid", false)
        val currentToken = config.optString("token", "")

        swPaid.isChecked = currentIsPaid
        etToken.setText(currentToken)

        // Initial state
        tilToken.isEnabled = currentIsPaid

        swPaid.setOnCheckedChangeListener { _, isChecked ->
            tilToken.isEnabled = isChecked
        }

        MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(context.getString(R.string.dialog_cloudflared_settings_title))
            .setMessage(context.getString(R.string.dialog_cloudflared_settings_message))
            .setView(dialogView)
            .setPositiveButton(context.getString(R.string.dialog_save)) { _, _ ->
                val paid = swPaid.isChecked
                val token = etToken.text?.toString()?.trim() ?: ""

                if (paid && token.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.toast_cloudflared_token_cannot_be_empty), Toast.LENGTH_SHORT).show()
                } else {
                    val newConfig = JSONObject()
                    newConfig.put("isPaid", paid)
                    newConfig.put("token", token)

                    // Save to unified config (atomic write via daemon IPC if called from app)
                    Thread {
                        UnifiedConfigManager.setCloudflared(newConfig)
                        // Allow write to settle
                        Thread.sleep(200)
                        
                        // RESTART tunnel if it was running to apply changes immediately
                        daemonsViewModel.getState(DaemonType.CLOUDFLARED_TUNNEL)?.let { state ->
                            if (state.status == com.overdrive.app.ui.model.DaemonStatus.RUNNING || 
                                state.status == com.overdrive.app.ui.model.DaemonStatus.STARTING) {
                                // Stop then start to force new URL extraction
                                daemonsViewModel.stopDaemon(DaemonType.CLOUDFLARED_TUNNEL)
                                Thread.sleep(500)
                                daemonsViewModel.startDaemon(DaemonType.CLOUDFLARED_TUNNEL)
                            } else {
                                daemonsViewModel.refreshDaemonStatus(DaemonType.CLOUDFLARED_TUNNEL)
                            }
                        } ?: daemonsViewModel.refreshDaemonStatus(DaemonType.CLOUDFLARED_TUNNEL)
                    }.start()
                    
                    Toast.makeText(context, context.getString(R.string.toast_cloudflared_saved_settings), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .show()
    }
}
