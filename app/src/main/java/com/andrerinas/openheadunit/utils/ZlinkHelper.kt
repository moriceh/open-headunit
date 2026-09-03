package com.andrerinas.openheadunit.utils

import android.content.Context
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.adb.AdbManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ZlinkHelper {

    private val TARGET_PACKAGES = listOf("com.zjinnova.zlink")

    data class ZlinkStatus(
        val isScriptActive: Boolean,
        val isScriptBakPresent: Boolean,
        val isProcessRunning: Boolean,
        val disabledPackages: List<String>,
        val enabledPackages: List<String>,
        val accessMode: String
    ) {
        val isEffectivelyDisabled: Boolean
            get() = !isProcessRunning && (disabledPackages.isNotEmpty() || !isScriptActive)

        val isInstalled: Boolean
            get() = isScriptActive || isScriptBakPresent || disabledPackages.isNotEmpty() || enabledPackages.isNotEmpty()
    }

    private suspend fun runPrivilegedCommand(context: Context, command: String): Pair<Int, String> {
        // 1. First, try Self-ADB (port 5555)
        try {
            val adbResult = AdbManager.exec(context, command)
            if (adbResult.first == 0) {
                return adbResult
            }
        } catch (e: Exception) {
            AppLog.d("ZlinkHelper: Self-ADB attempt failed (${e.message}), trying standard root...")
        }

        // 2. Fallback to libsu (com.topjohnwu.superuser.Shell)
        try {
            if (Shell.isAppGrantedRoot() == true || Shell.cmd("id").exec().isSuccess) {
                val result = Shell.cmd(command).exec()
                val out = (result.out + result.err).joinToString("\n").trim()
                return Pair(result.code, out)
            }
        } catch (e: Exception) {
            AppLog.d("ZlinkHelper: libsu attempt failed (${e.message}), trying su binary...")
        }

        // 3. Fallback to direct su process execution
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = process.inputStream.bufferedReader().readText() +
                    process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            Pair(code, out.trim())
        } catch (e: Exception) {
            AppLog.e("ZlinkHelper: All execution methods failed ($command)", e)
            Pair(-1, e.message ?: "Execution error")
        }
    }

    suspend fun getStatus(context: Context): ZlinkStatus = withContext(Dispatchers.IO) {
        val checkScript = runPrivilegedCommand(context, "test -f /system/bin/zlink5.sh && echo SCRIPT_EXISTS").second.contains("SCRIPT_EXISTS")
        val checkBak = runPrivilegedCommand(context, "test -f /system/bin/zlink5.sh.BAK && echo BAK_EXISTS").second.contains("BAK_EXISTS")
        
        val processOutput = runPrivilegedCommand(context, "ps -ef 2>/dev/null || ps 2>/dev/null").second
        val processRunning = processOutput.lines().any { line ->
            !line.contains("grep") && (line.contains("zlink5.sh") || line.contains("z-link"))
        }

        val disabledPkgsOut = runPrivilegedCommand(context, "pm list packages -d 2>/dev/null").second
        val disabledPackages = disabledPkgsOut.lines()
            .map { it.removePrefix("package:").trim() }
            .filter { pkg -> TARGET_PACKAGES.any { target -> pkg.equals(target, ignoreCase = true) } }

        val enabledPkgsOut = runPrivilegedCommand(context, "pm list packages -e 2>/dev/null").second
        val enabledPackages = enabledPkgsOut.lines()
            .map { it.removePrefix("package:").trim() }
            .filter { pkg -> TARGET_PACKAGES.any { target -> pkg.equals(target, ignoreCase = true) } }

        val whoamiRaw = runPrivilegedCommand(context, "whoami 2>/dev/null || id 2>/dev/null").second
        val accessMode = when {
            whoamiRaw.contains("root") -> "root"
            whoamiRaw.contains("shell") -> "shell (ADB)"
            whoamiRaw.isNotBlank() -> whoamiRaw.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "Unknown"
            else -> "Unknown"
        }

        ZlinkStatus(
            isScriptActive = checkScript,
            isScriptBakPresent = checkBak,
            isProcessRunning = processRunning,
            disabledPackages = disabledPackages,
            enabledPackages = enabledPackages,
            accessMode = accessMode
        )
    }

    /**
     * The user's confirmed working command set on this class of head unit: `/system` is read-only,
     * so there is no script to move — only the package (for user 0) and the two daemon processes.
     * Kept short on purpose: this same snippet is run by the boot watchdog every start, so any
     * extra mount/mv attempt just adds noise and failure surface.
     */
    private const val ZLINK_KILL_CMD = """
            pkill zlink5.sh 2>/dev/null
            pkill z-link 2>/dev/null
            pm disable --user 0 com.zjinnova.zlink 2>/dev/null
        """

    suspend fun killZlink(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val (code, out) = runPrivilegedCommand(context, ZLINK_KILL_CMD.trimIndent())
        AppLog.i("ZlinkHelper: killZlink executed (code=$code). out=$out")
        if (code == 0) {
            Result.success("Zlink killed.\n$out".trim())
        } else {
            Result.failure(Exception("Failed ($code): $out"))
        }
    }

    /**
     * `zlink5.sh` is a `while true; do ... sleep 1; done` supervisor — it never exits on its own.
     * It therefore MUST be launched detached. `setsid` puts it in a fresh session so the ADB exec
     * shell can exit immediately and close its stream; `nohup` alone does NOT work here because
     * `nohup` only redirects stdout when it is a terminal, so over the ADB pipe the daemon inherited
     * the stream and `AdbManager.exec` read it forever (the "Restore freezes the app" bug).
     */
    private const val ZLINK_DAEMON_START = "setsid /system/bin/zlink5.sh >/dev/null 2>&1 </dev/null &"

    suspend fun restartZlink(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val scriptCmd = """
            pm enable --user 0 com.zjinnova.zlink 2>/dev/null
            $ZLINK_DAEMON_START
        """.trimIndent()

        val (code, out) = runPrivilegedCommand(context, scriptCmd)
        AppLog.i("ZlinkHelper: restartZlink executed (code=$code). out=$out")
        if (code == 0) {
            Result.success("Zlink restarted:\n$out".trim())
        } else {
            Result.failure(Exception("Failed ($code): $out"))
        }
    }

    /**
     * A trailing probe appended to the disable/restore scripts so the shell's own view of the result
     * comes back in the same round-trip as the mutations. `/system` is read-only on these units so
     * there is no script to move; the two things that can actually change are the user-0 package
     * state and whether the two daemon processes are still up.
     */
    private const val ZLINK_STATE_PROBE = """
            echo "---zlink-state---"
            (pm list packages -d --user 0 2>/dev/null | grep com.zjinnova.zlink) || echo "pkg: enabled"
            (ps -ef 2>/dev/null || ps 2>/dev/null) | grep -i -E "zlink5|z-link" | grep -v grep || echo "process: none"
        """

    suspend fun disableZlink(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val scriptCmd = """
            $ZLINK_KILL_CMD
            $ZLINK_STATE_PROBE
        """.trimIndent()

        val (code, out) = runPrivilegedCommand(context, scriptCmd)
        val status = getStatus(context)
        AppLog.i("ZlinkHelper: disableZlink finished. code=$code Status=$status\n$out")

        // The process kill is the part that must actually have worked; report it honestly instead of
        // always claiming success. A still-running daemon after a kill is the one failure the user
        // would otherwise be shown a green toast for.
        if (status.isProcessRunning) {
            Result.failure(
                Exception("Zlink process still running after kill.\n$out")
            )
        } else {
            val pkgNote = if (status.disabledPackages.isNotEmpty()) "package disabled"
                          else "package still enabled"
            Result.success("Zlink stopped. $pkgNote.")
        }
    }

    suspend fun restoreZlink(context: Context): Result<String> = withContext(Dispatchers.IO) {
        // See ZLINK_DAEMON_START: the supervisor must be launched in a detached session so the ADB
        // stream can close. The probe below still tells us whether the daemon actually came up.
        val scriptCmd = """
            pm enable --user 0 com.zjinnova.zlink 2>/dev/null
            $ZLINK_DAEMON_START
            $ZLINK_STATE_PROBE
        """.trimIndent()

        val (code, out) = runPrivilegedCommand(context, scriptCmd)
        AppLog.i("ZlinkHelper: restoreZlink finished. code=$code\n$out")

        // Give the daemon a moment to come up, then check whether it is actually running.
        delay(1500)
        val after = getStatus(context)
        if (after.isProcessRunning) {
            Result.success("Zlink restored and running.")
        } else {
            Result.failure(
                Exception("Zlink did not come back up after restore.\n$out")
            )
        }
    }

    fun startBootWatchdog(context: Context, scope: CoroutineScope) {
        val settings = App.provide(context).settings
        if (!settings.autoKillZlink) return

        scope.launch(Dispatchers.IO) {
            AppLog.i("ZlinkHelper: Boot watchdog started (autoKillZlink=true). Killing Zlink processes...")
            // Run kill loop: kill immediately, then after 3s, 8s, 14s, 20s (to catch zlink5.sh's sleep 10)
            val delays = listOf(0L, 3000L, 5000L, 6000L, 6000L, 10000L)
            for (d in delays) {
                if (!settings.autoKillZlink) break
                if (d > 0) delay(d)
                killZlink(context)
            }
            AppLog.i("ZlinkHelper: Boot watchdog completed.")
        }
    }
}
