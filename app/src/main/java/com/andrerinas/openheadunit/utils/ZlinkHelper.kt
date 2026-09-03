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

    suspend fun killZlink(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val scriptCmd = """
            pkill -9 -f zlink5.sh
            pkill -9 -f z-link
            killall -9 z-link
            pm disable com.zjinnova.zlink
        """.trimIndent()

        val (code, out) = runPrivilegedCommand(context, scriptCmd)
        AppLog.i("ZlinkHelper: killZlink executed (code=$code). out=$out")
        if (code == 0) {
            Result.success("Zlink disabled:\n$out".trim())
        } else {
            Result.failure(Exception("Failed ($code): $out"))
        }
    }

    suspend fun restartZlink(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val scriptCmd = """
            pm enable com.zjinnova.zlink
            nohup /system/bin/zlink5.sh >/dev/null 2>&1 &
        """.trimIndent()

        val (code, out) = runPrivilegedCommand(context, scriptCmd)
        AppLog.i("ZlinkHelper: restartZlink executed (code=$code). out=$out")
        if (code == 0) {
            Result.success("Zlink restarted:\n$out".trim())
        } else {
            Result.failure(Exception("Failed ($code): $out"))
        }
    }

    suspend fun disableZlink(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val scriptCmd = """
            mount -o rw,remount /system 2>/dev/null || mount -o remount,rw /system 2>/dev/null || mount -o rw,remount / 2>/dev/null
            if [ -f /system/bin/zlink5.sh ]; then
                mv /system/bin/zlink5.sh /system/bin/zlink5.sh.BAK 2>/dev/null
            fi
            mount -o ro,remount /system 2>/dev/null || mount -o remount,ro /system 2>/dev/null || mount -o ro,remount / 2>/dev/null
            pkill -9 -f zlink5.sh 2>/dev/null
            pkill -9 -f z-link 2>/dev/null
            killall -9 z-link 2>/dev/null
            pm disable com.zjinnova.zlink 2>/dev/null
        """.trimIndent()

        val (code, out) = runPrivilegedCommand(context, scriptCmd)
        val status = getStatus(context)
        AppLog.i("ZlinkHelper: disableZlink finished. Status=$status, out=$out")
        Result.success("Zlink processes killed and package disabled.")
    }

    suspend fun restoreZlink(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val scriptCmd = """
            mount -o rw,remount /system 2>/dev/null || mount -o remount,rw /system 2>/dev/null || mount -o rw,remount / 2>/dev/null
            if [ -f /system/bin/zlink5.sh.BAK ]; then
                mv /system/bin/zlink5.sh.BAK /system/bin/zlink5.sh 2>/dev/null
                chmod 755 /system/bin/zlink5.sh 2>/dev/null
            fi
            mount -o ro,remount /system 2>/dev/null || mount -o remount,ro /system 2>/dev/null || mount -o ro,remount / 2>/dev/null
            pm enable com.zjinnova.zlink 2>/dev/null
            nohup /system/bin/zlink5.sh >/dev/null 2>&1 &
        """.trimIndent()

        val (code, out) = runPrivilegedCommand(context, scriptCmd)
        val status = getStatus(context)
        AppLog.i("ZlinkHelper: restoreZlink finished. Status=$status, out=$out")
        Result.success("Zlink restored and daemon restarted.")
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
