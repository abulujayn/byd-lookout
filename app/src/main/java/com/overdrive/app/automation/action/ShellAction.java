package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.server.Messages;

import java.util.List;

/**
 * Automation action that runs a shell command in the daemon (UID 2000).
 *
 * <p>This is the most powerful — and most dangerous — action, because automations
 * fire <b>autonomously</b> on vehicle events (not a deliberate button press like
 * the key-mapping shell hatch). A misfiring automation could run its command
 * repeatedly on its own. So it is gated behind a DEDICATED opt-in flag,
 * {@code automation.allowShell} (toggle on the Automations page), SEPARATE from
 * the key-mapping "Advanced actions" toggle ({@code keymap.allowAdvanced}) —
 * enabling shell for a hand-pressed key must NOT silently also arm autonomous
 * shell in automations.
 * When the flag is off, {@link #trigger} is a logged no-op; the command never
 * executes.
 *
 * <p>Runs in the daemon process, the same context {@code Automations.update}
 * evaluates in, so {@code Runtime.exec} has the daemon's privileges — the same
 * path {@code KeymapApiHandler} uses for the key-mapping shell action.
 */
public class ShellAction extends BaseAction {
    private static final String TYPE = "shell";

    private final Label label;
    private final String description;
    // Single free-text variable: the command line. 500 chars is generous for a
    // one-liner while bounding a pathological paste. Carries a warning key so the
    // form renders the same amber "runs unattended" caution as the key-mapping
    // shell field.
    private final List<Type> variables = List.of(
            new StringType(new Label("command", "automation.shell_command"), 500, "automation.shell_warning"));

    public ShellAction(Label label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getType() {
        return TYPE;
    }

    public Label getLabel() {
        return label;
    }

    public String getDescription() {
        return Messages.get(description);
    }

    public List<Type> getVariables() {
        return variables;
    }

    /**
     * Run the bound command — but only if the dedicated automation-shell gate is
     * on. The gate is re-checked here at fire time (not just in the UI), so a
     * binding saved while enabled cannot keep firing after the user turns the
     * flag back off.
     */
    public void trigger(AutomationAction automationAction) {
        Object cmdObj = automationAction.getVariables().get("command");
        String cmd = cmdObj == null ? "" : cmdObj.toString().trim();
        if (cmd.isEmpty()) {
            logger.warn("ShellAction: empty command, skipping");
            return;
        }
        if (!UnifiedConfigManager.isAutomationShellAllowed()) {
            // Gate off → never execute. Logged so a silently-inert automation is
            // debuggable ("why didn't my shell automation run?").
            logger.warn("ShellAction: automation shell is disabled (automation.allowShell=false); "
                    + "refusing to run: " + cmd);
            return;
        }
        try {
            // Mirror KeymapApiHandler.runShell: drain (and discard) combined
            // stdout+stderr on a daemon thread so the child can never wedge on a
            // full (>~64KB) pipe, bound the whole exec by a 5s ceiling and
            // force-kill a long-lived-stdout command (logcat, top, tail -f) so it
            // cannot park the AutomationQueue worker, then reap the child so
            // nothing accumulates across repeated autonomous fires.
            final Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    while (is.read(buf) != -1) { /* discard */ }
                } catch (Throwable ignored) { /* pipe closed on kill */ }
            }, "automation-shell-drain");
            drain.setDaemon(true);
            drain.start();
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
                logger.info("ShellAction fired: " + cmd);
            } catch (InterruptedException ie) {
                // The AutomationQueue worker is being torn down (e.g. disable-all).
                // Kill the child and RE-ASSERT the interrupt so the worker's blocking
                // take() throws and the thread exits as designed — swallowing it here
                // would clear the flag and turn the worker into an unkillable orphan.
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            } finally {
                // Idempotent: guarantees the child + its fds are reaped whether
                // waitFor returned, timed out, or was interrupted — no orphan on
                // any path.
                p.destroyForcibly();
                try {
                    drain.join(500);    // let the reader unwind after the pipe closes
                } catch (InterruptedException je) {
                    Thread.currentThread().interrupt();
                }
                try { is.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            logger.warn("ShellAction failed: " + t.getMessage());
        }
    }
}
