package com.andrerinas.openheadunit.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ApBand
import com.andrerinas.openheadunit.utils.adb.AdbManager
import com.android.dx.DexMaker
import com.android.dx.TypeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Method
import java.net.Inet4Address
import java.net.NetworkInterface
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ApInterfaceCandidate
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HotspotBandPreference
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBandPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApNetworkPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApState

/**
 * Manages WiFi Hotspot (tethering) using reflection + dexmaker.
 */
object HotspotManager {
    private const val TAG = "OPENHU_WIFI"
    private const val CALLBACK_CLASS = "android.net.ConnectivityManager\$OnStartTetheringCallback"

    /** How long to give the framework to actually bring an access point up. */
    private const val AP_STATE_TIMEOUT_MS = 6_000L

    /** How long to leave the access point down so a joined client notices it has gone. */
    private const val RESTART_SETTLE_MS = 2_000L

    /** TetheringManager.TETHERING_WIFI. */
    private const val TETHERING_WIFI = 0

    private var cachedCallbackClass: Class<*>? = null

    /**
     * Whether a start is already running. Bringing an access point up takes up to
     * [AP_STATE_TIMEOUT_MS] per band, and more than one caller asks for it: the credentials
     * provider auto-enables, and every "still waiting for credentials" refresh can ask again. Two
     * overlapping sweeps race each other's bands and end with a log claiming the access point came
     * up twice, on both of them.
     */
    @Volatile private var startInFlight = false

    /**
     * Takes the access point down and brings it straight back up.
     *
     * Removing the network is the only way this app can put a phone off it — nothing in the public
     * API disconnects a client from your own access point, and the one thing that comes close,
     * `SoftApConfiguration`'s blocked-client list, needs the same `setSoftApConfiguration()` call
     * that head units routinely refuse outright. So the network has to go; it does not have to stay
     * gone. Bringing it back here costs seconds nobody is waiting through, where leaving it down
     * charges the same seconds to the next connection, with the phone waiting.
     *
     * Putting it back is the part that is not free, and it is asked for **once** — measured rather
     * than assumed. Tearing an access point down and asking for it again is the sequence some
     * drivers handle worst: hostapd begins its channel scan before the interface is back, the scan
     * returns `ENODEV`, and it aborts instead of starting. Asking again at this layer does not beat
     * that. Across three runs, **nine** asks spaced ~12.5 s apart failed with that same signature
     * and not one recovered; the single bring-up that did succeed came from a start posted **127 ms**
     * behind a failed one — 270 ms in an earlier capture — landing in a window open for a few hundred
     * milliseconds and shut long before this layer can ask again. Where the hardware handles a
     * stop/start cleanly one ask is all that was ever needed; where it does not, the access point
     * stays down and the next connection's auto-enable brings it back, which is the cost this
     * restart exists to avoid, paid only on hardware that will not cooperate — rather than that same
     * cost plus half a minute of asking that never works.
     *
     * There is also a window this cannot close. Between the two calls the access point is genuinely
     * down, and a process killed outright in that window — `am force-stop`, or the system reclaiming
     * the app — runs none of the code below, so the hotspot stays off. Measured, and bounded rather
     * than fixed: the next connection's `SoftApCredentialsProvider` auto-enable switches it back on,
     * which is the same cost the restart exists to avoid paying, not a permanent break. Shrinking
     * [RESTART_SETTLE_MS] narrows the window; nothing removes it.
     */
    fun restart(context: Context): Boolean {
        AppLog.i("HotspotManager: Restarting the hotspot so any joined client is put off it.")
        setHotspotEnabled(context, false)
        try {
            Thread.sleep(RESTART_SETTLE_MS)
        } catch (e: InterruptedException) {
            // Interrupted with the access point already down, which is the one state this method
            // must not leave behind: switching one back on is best effort, and on a unit without
            // WRITE_SETTINGS nothing else can. Put it back before unwinding, then restore the flag
            // so whatever cancelled us still sees it.
            AppLog.w("HotspotManager: Interrupted while the hotspot was down; bringing it back before giving up.")
            val restored = setHotspotEnabled(context, true)
            Thread.currentThread().interrupt()
            return restored
        }

        if (setHotspotEnabled(context, true)) return true

        AppLog.e("HotspotManager: The hotspot was taken down to put the phone off the network and would not come back up. It is off now, and this app cannot force it: switch it on in system settings, or just connect again — the app switches it back on itself at the start of a connection.")
        return false
    }

    fun setHotspotEnabled(context: Context, enabled: Boolean): Boolean {
        AppLog.i("HotspotManager: Setting hotspot enabled=$enabled (API ${Build.VERSION.SDK_INT}, canWriteSettings=${AppPermissions.isWriteSettingsGranted(context)})")

        // Disabling has no band to choose and nothing to confirm afterwards, and never collides
        // with a start: only one caller ever asks for it. The band below is unused, since
        // SoftApConfigCompat.enableHotspot() returns before reading it when enabled is false.
        if (!enabled) return startOnBand(context, enabled = false, band = ApBand.BAND_5GHZ).attempted

        // A fresh ask against an access point that is already up would re-post every start path and
        // churn the live radio — the "auto-start turned off a hotspot that was on" case. Confirm the
        // access point first, and return without touching the radio when it is already there. This
        // check is what makes a repeated auto-enable (the credentials provider asks again on every
        // "still waiting" refresh, and once more when the AP drops) safe rather than destructive.
        if (isApUp(context)) {
            AppLog.i("HotspotManager: The hotspot is already up; not re-posting start requests (avoids churning a live access point).")
            return true
        }

        // Claimed before anything slow runs, or the WiFi-disable sleep below is long enough for a
        // second caller to walk straight past the check.
        synchronized(this) {
            if (startInFlight) {
                AppLog.i("HotspotManager: A hotspot start is already running; letting it finish rather than starting a second one.")
                return isApUp(context)
            }
            startInFlight = true
        }

        // On Android 8+, WiFi must be disabled before tethering can start. Ask, then say what
        // actually happened: setWifiEnabled() is a no-op for apps targeting API 29+ and this app
        // targets well past that, so on most devices the request is silently ignored and the
        // framework drops the station itself when it needs the radio. Announcing the attempt as if
        // it worked is how the radio state ends up being read as ours.
        try {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (wm.isWifiEnabled) {
                    @Suppress("DEPRECATION")
                    wm.isWifiEnabled = false
                    Thread.sleep(500) // Let the radio settle
                    if (wm.isWifiEnabled) {
                        AppLog.i("HotspotManager: Asked to disable WiFi and the platform ignored it (expected on modern Android); the framework will take the radio itself if it needs to.")
                    } else {
                        AppLog.i("HotspotManager: WiFi disabled before enabling hotspot.")
                    }
                }
            } catch (e: Exception) {
                AppLog.w("HotspotManager: Failed to disable WiFi: ${e.message}")
            }

            // Which bands to try, and in what order. Resolved once: every line below reports the
            // list actually being iterated, so a forced band cannot be described as "every band".
            val preference = bandPreference(context)
            val order = SoftApBandPolicy.attemptOrder(preference)
            AppLog.i("HotspotManager: Band preference is ${SoftApBandPolicy.describePreference(preference)}; trying ${order.joinToString { SoftApBandPolicy.describe(it) }}.")

            var attemptedAny = false
            for ((index, band) in order.withIndex()) {
                if (index > 0) {
                    AppLog.w("HotspotManager: no access point on ${SoftApBandPolicy.describe(order[index - 1])}; retrying on ${SoftApBandPolicy.describe(band)}. Measured on 2.4 GHz: a 1080p/60 session connects, opens the video channel and then dies having sent no frame at all, while the same access point carries 800x480/30 indefinitely. If the projection dies shortly after connecting, this line is why, and a lower resolution and frame rate is the other way out.")
                }
                val outcome = startOnBand(context, enabled = true, band = band)
                attemptedAny = attemptedAny || outcome.attempted
                if (outcome.up) {
                    AppLog.i(describeApUp(outcome.configured, band))
                    return true
                }
                if (!outcome.configured) {
                    // The band never reached the framework, so the next one would post the same
                    // request against the same stored configuration and start the same access
                    // point again. Measured on a unit that refuses setSoftApConfiguration(): three
                    // start requests, all `channels {3=0}`, two of them tearing down an access
                    // point the previous one had just brought up.
                    val nextBand = order.getOrNull(index + 1)
                    if (nextBand != null) {
                        AppLog.w("HotspotManager: This device would not take a band request, so trying ${SoftApBandPolicy.describe(nextBand)} would start the same access point again. Leaving the band to the device.")
                    } else {
                        AppLog.w("HotspotManager: This device would not take a band request, so the access point is on whatever band it already had configured, which this app cannot read.")
                    }
                    break
                }
            }

            // A request the framework accepted late still brings an access point up, after the band
            // it belonged to has been written off. Look once more before reporting failure: saying
            // no here is what makes a caller start a second, overlapping sweep.
            if (attemptedAny && awaitApUp(context)) {
                AppLog.i("HotspotManager: An access point came up after its band's window had expired; taking it. Which band it chose is not something this app can read.")
                return true
            }

            // The framework's start paths above need TETHER_PRIVILEGED, which a normal (non-system)
            // install of this app does not hold, so on many head units they are refused and the
            // hotspot stays off even though the app asked for it. Bring it up through the app's own
            // privileged shell instead. Reached only when nothing above brought an access point up
            // (a live one already returned at the top of this method), so this cannot churn an AP.
            val shellOutcome = startViaShell(context)
            if (shellOutcome.up) {
                AppLog.i("HotspotManager: Hotspot started via the privileged shell fallback (cmd wifi start-softap).")
                return true
            }

            if (attemptedAny || shellOutcome.attempted) {
                AppLog.w("HotspotManager: Every start path was tried on ${order.joinToString { SoftApBandPolicy.describe(it) }} and no access point came up within ${AP_STATE_TIMEOUT_MS / 1000}s each, including the privileged shell fallback. On a unit without a root shell this usually cannot be done from an app — switch the hotspot on in system settings instead.")
            } else {
                AppLog.w("HotspotManager: All hotspot attempts failed.")
            }
            warnIfRadioLeftDown(context)
            return false
        } finally {
            startInFlight = false
        }
    }

    /**
     * The user's band choice, or [HotspotBandPreference.AUTO] if it cannot be read.
     *
     * The catch is not decoration: `Settings` lives in credential-encrypted storage and throws
     * outright before the user has unlocked the device, and this runs from the boot and auto-start
     * paths. Falling back to the automatic sweep there is the same behaviour the app had before
     * this setting existed.
     */
    private fun bandPreference(context: Context): HotspotBandPreference = try {
        HotspotBandPreference.fromSetting(Settings(context).hotspotBand)
    } catch (e: Exception) {
        AppLog.w("HotspotManager: Could not read the band preference (${e.message}); asking for 5 GHz and falling back.")
        HotspotBandPreference.AUTO
    }

    /**
     * What one band's worth of start attempts achieved: whether anything was tried, whether an
     * access point actually came up, and whether the band we asked for ever reached the framework.
     */
    private data class BandOutcome(val attempted: Boolean, val up: Boolean, val configured: Boolean)

    /**
     * What to say about an access point that is up, given whether the band request was accepted.
     *
     * Naming a band we only *asked* for is how a log ends up contradicting the radio. Measured on a
     * unit that refuses `setSoftApConfiguration()`: this said 2.4 GHz while the access point that
     * came up 8.7 s later was on 5745 MHz, so a reader with only the log would have concluded the
     * exact opposite of what happened. The band is not readable from an ordinary app — `SoftApInfo`
     * arrives on a callback that needs NETWORK_SETTINGS — so the honest line names what was
     * requested and what became of the request, and nothing else.
     */
    private fun describeApUp(configured: Boolean, band: ApBand): String =
        if (configured) {
            "HotspotManager: Hotspot is up, and this device accepted the request for ${SoftApBandPolicy.describe(band)}."
        } else {
            "HotspotManager: Hotspot is up, but this device refused the request for ${SoftApBandPolicy.describe(band)} — the band is whatever it already had configured, which this app cannot read. If the projection dies seconds after connecting with no picture, check the hotspot's channel: on 2.4 GHz a full-resolution stream was measured to do exactly that, while a lower resolution and frame rate held."
        }

    private fun startOnBand(context: Context, enabled: Boolean, band: ApBand): BandOutcome {
        // [BUG_FIX] Must fall through, not return. enableHotspot() only calls
        // setSoftApConfiguration() — it configures an access point, it does not start one — yet
        // its `true` used to short-circuit the whole function, so on API 30+ we wrote the SSID and
        // passphrase, reported success, and ran no start path at all. The hotspot stayed off.
        val configured = SoftApConfigCompat.enableHotspot(context, enabled, band)
        if (configured) {
            AppLog.i("HotspotManager: SoftAp configuration applied; now starting the hotspot.")
        }
        // Newer API: TetheringManager (official) before ConnectivityManager fallback
        var attempted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attempted = tryTetheringManager(context, enabled) || attempted
        }
        // The remaining reflection paths (startTethering / setWifiApEnabled) require the special
        // "Modify system settings" access (WRITE_SETTINGS). Without it the framework throws a
        // SecurityException, so check first and surface a clear, actionable message instead.
        if (AppPermissions.isWriteSettingsGranted(context)) {
            attempted = tryConnectivityManager(context, enabled) || attempted
            attempted = tryLegacyWifiManager(context, enabled) || attempted
        } else {
            val verb = if (enabled) "enable" else "turn off"
            AppLog.w("HotspotManager: Cannot $verb the hotspot without the \"Modify system settings\" permission (WRITE_SETTINGS). Grant it in the setup wizard or Settings > Permissions.")
        }

        if (!enabled) return BandOutcome(attempted, up = false, configured = configured)

        // [BUG_FIX] Confirm the access point instead of trusting the call that asked for it. Every
        // start path here is reflection over an API whose real answer arrives later on a callback
        // we cannot construct, so `invoke()` returning tells us only that the request was posted:
        // on one head unit the framework refused it a millisecond later ("Tethering is already
        // active or in recovering") while this method reported success and logged nothing at all.
        // Only "an access point is up" is worth reporting as success.
        return BandOutcome(attempted, up = awaitApUp(context), configured = configured)
    }

    /**
     * The outcome of the privileged shell start: whether a `start-softap` command was actually
     * issued (so the "every path was tried" report is accurate) and whether an access point is up
     * as a result.
     */
    private data class ShellStartOutcome(val attempted: Boolean, val up: Boolean)

    /**
     * Brings the head unit's access point up through the app's own privileged shell, as a last
     * resort when the framework's start paths are unavailable — which is the normal case on a
     * head unit where this app is a user install without `TETHER_PRIVILEGED`, so
     * `TetheringManager.startTethering` and `ConnectivityManager.startTethering` are refused.
     *
     * `cmd wifi start-softap` needs no such permission when issued from a root shell, and the
     * Self-ADB `adbd` on these units runs as `u:r:su:s0`. The name, passphrase and security type it
     * is told are read from the stored configuration — the same source of truth this app hands the
     * phone (`SoftApCredentialsProvider`) — so the access point it brings up is exactly the one the
     * phone was told to join.
     *
     * Returns without issuing anything when there is no stored network to host, when no privileged
     * shell can be reached, or when the stored security type is one `start-softap` cannot host. In
     * all of those the caller falls through to its "no access point came up" report.
     */
    private fun startViaShell(context: Context): ShellStartOutcome {
        val security = HotspotConfigReader.getSystemHotspotSecurity(context)
        if (security == null || security.ssid.isEmpty()) {
            AppLog.w("HotspotManager: Shell start skipped — no stored hotspot name to host. This app can only bring up the network this device is configured to run; set the hotspot in system settings first.")
            return ShellStartOutcome(attempted = false, up = false)
        }

        // Host the access point exactly the way this device is configured to run it: the security
        // type is read off the stored SoftApConfiguration (WPA-PSK + SAE transition on this unit,
        // which maps to wpa3_transition) rather than assumed. The phone still joins it through the
        // WPA2-PSK path (its log shows it as WPA2_PERSONAL) — transition mode allows that — so no
        // client is excluded, and matching the stored type keeps the shell-started access point
        // identical to the one system settings would bring up.
        val token = securityTokenFor(security)
        if (token == null) {
            AppLog.w("HotspotManager: Shell start skipped — the stored security type (${SoftApSecurityType.fromValue(security.securityType)}) is not a personal-PSK network that `cmd wifi start-softap` can bring up.")
            return ShellStartOutcome(attempted = false, up = false)
        }

        // Honor the user's band setting: force 5 GHz or 2.4 GHz, and let the driver choose on
        // AUTO. 5 GHz is known to be the band a 1080p stream needs, so this is the default path;
        // it is also the band this class of radio is measured to refuse when hosting an access
        // point, so a failure here is attributable.
        val bandFlag = bandFlagFor(bandPreference(context))

        return runStartSoftApCommand(
            context,
            ssid = security.ssid,
            passphrase = security.passphrase,
            token = token,
            bandFlag = bandFlag
        )
    }

    /**
     * Brings the head unit's access point up through the app's own root Self-ADB, as the primary
     * start path (not a fallback) when the Blink/ZXW WPP handshake begins, so the network is up
     * by the time the phone is handed the credentials.
     *
     * Deliberately fixed to 2.4 GHz and WPA2 personal: this class of radio does not host a 5 GHz
     * access point, and a plain WPA2 network is the most broadly joinable. The name and passphrase
     * come from the manual overrides when the user set them, otherwise from the stored
     * configuration, so the network that comes up is the one this app hands the phone.
     *
     * Safe to call on the already-up path: it returns without touching the radio when an access
     * point is running. Runs only from a background (non-main) thread — it waits for the access
     * point to actually come up.
     */
    fun startViaSelfAdB(context: Context): Boolean {
        if (isApUp(context)) {
            AppLog.i("HotspotManager: The hotspot is already up; the Self-ADB start is not needed.")
            return true
        }

        // The manual overrides are the user's explicit choice of what this device hosts, so they
        // take precedence over whatever the stored configuration reports.
        val settings = Settings(context)
        val manualSsid = settings.hotspotSsid.trim()
        val ssid = if (manualSsid.isNotEmpty()) manualSsid
            else HotspotConfigReader.getSystemHotspotSecurity(context)?.ssid.orEmpty()
        if (ssid.isEmpty()) {
            AppLog.w("HotspotManager: Self-ADB start skipped — no hotspot name to host. Set 'Hotspot name (manual)' in Settings, or set the hotspot in system settings first.")
            return false
        }
        val manualPassword = settings.hotspotPassword
        val passphrase = if (manualPassword.isNotEmpty()) manualPassword
            else HotspotConfigReader.getSystemHotspotSecurity(context)?.passphrase.orEmpty()

        // 2.4 GHz only (this radio does not host 5 GHz) and WPA2 personal (open when there is no
        // passphrase). No read of the stored security type: the goal is a joinable network, and
        // the phone takes the WPA2-PSK path either way.
        return runStartSoftApCommand(context, ssid = ssid, passphrase = passphrase, token = "wpa2", bandFlag = " -b 2").up
    }

    /**
     * Issues one `cmd wifi start-softap` call over the Self-ADB shell and reports whether it was
     * actually sent and whether an access point is up as a result. Shared by the last-resort
     * auto-enable ([startViaShell]) and the WPP-handshake-triggered start ([startViaSelfAdB]),
     * which differ only in the ssid / passphrase / token / band they feed it.
     */
    private fun runStartSoftApCommand(
        context: Context,
        ssid: String,
        passphrase: String,
        token: String,
        bandFlag: String
    ): ShellStartOutcome {
        // The passphrase, if any, goes in double quotes so a value with shell-meaningful
        // characters (quotes, spaces) reaches the command intact.
        val passphraseArg = if (passphrase.isEmpty()) {
            // An open network takes no passphrase argument; the token itself says so.
            ""
        } else {
            " \"$passphrase\""
        }
        val command = "cmd wifi start-softap \"$ssid\" $token$passphraseArg$bandFlag"
        AppLog.i("HotspotManager: Starting the hotspot via the privileged shell: $command")

        // AdbManager.exec is suspend and already hops to Dispatchers.IO; the callers run on the
        // same IO thread, so runBlocking here cannot nest into a second hop and just waits for the
        // command's output.
        val (code, out) = runBlocking { AdbManager.exec(context, command) }
        if (code == -1) {
            // -1 is the channel's own failure, not the command's: adbd on 127.0.0.1:5555 could not
            // even be reached, so the privileged start was never issued. A different and, on these
            // units, more common reason than the command being refused — the radio has not been
            // touched at all, so there is nothing further to do from here.
            AppLog.e("HotspotManager: Privileged shell start not attempted — the Self-ADB channel could not reach adbd on 127.0.0.1:5555 ($out). The command was never issued, so the framework start paths above remain the only option from an app on this device.")
            return ShellStartOutcome(attempted = false, up = false)
        }

        // AdbManager.exec reports exit 0 for any command that actually reached the shell: it does
        // not read adbd's exit-status packet, so a refused `cmd wifi` call comes back 0 with the
        // error text on the stream. The exit code is therefore useless as a success signal — the
        // only one is "an access point is up" — but the output text is the only way to say *why*
        // it did not come up when it did not: a permission refusal and a radio that declined are
        // different bugs, and both are silent in the exit code.
        val up = awaitApUp(context)
        if (up) {
            AppLog.i("HotspotManager: Hotspot came up after the privileged shell start.")
        } else {
            AppLog.w("HotspotManager: The privileged shell reported success but no access point came up within ${AP_STATE_TIMEOUT_MS / 1000}s. ${refusalReason(out)}")
        }
        return ShellStartOutcome(attempted = true, up = up)
    }

    /**
     * The security token `cmd wifi start-softap` accepts, from the stored security type. Null when
     * the type is not a personal-PSK network the command can host.
     */
    private fun securityTokenFor(security: HotspotConfigReader.HotspotSecurity): String? =
        when (SoftApSecurityType.fromValue(security.securityType)) {
            SoftApSecurityType.OPEN -> "open"
            SoftApSecurityType.WPA2_PERSONAL -> "wpa2"
            SoftApSecurityType.WPA3_PERSONAL -> "wpa3"
            SoftApSecurityType.WPA3_TRANSITION -> "wpa3_transition"
            SoftApSecurityType.OWE -> "owe"
            // UNSPECIFIED (the API < 30 path, which cannot read the type) and the enterprise
            // variants: the command takes a personal passphrase only, and guessing the type would
            // host the wrong kind of network, so refuse to guess and let the caller report it.
            else -> {
                if (security.securityType == SoftApSecurityType.UNSPECIFIED.value &&
                    security.passphrase.isNotEmpty()) {
                    // Legacy WifiConfiguration says nothing about the security type, but a
                    // passphrase that is present is WPA2-personal on essentially every unit that
                    // still carries getWifiApConfiguration. This is the one place the type is
                    // inferred, and it is the only one with no better source.
                    AppLog.i("HotspotManager: Security type unreadable on this API; assuming WPA2 personal from the presence of a passphrase.")
                    "wpa2"
                } else null
            }
        }

    /**
     * What a failed `cmd wifi start-softap` run tells us, or a bare echo of the output when nothing
     * diagnostic is there.
     *
     * adbd's `exec:` stream merges the command's stderr into the same buffer as its stdout, so the
     * framework's refusal text lands here too; reading it is the only way to name the failure,
     * because the exit code is discarded upstream.
     */
    private fun refusalReason(output: String): String {
        val text = output.trim()
        if (text.isEmpty()) return "No output from the shell."
        return when {
            text.contains("SecurityException", ignoreCase = true) ||
                text.contains("Permission denied", ignoreCase = true) ||
                text.contains("START_SOFT_AP", ignoreCase = true) ->
                "the framework refused the privileged start (the shell's uid does not hold android.permission.START_SOFT_AP on this unit), which no amount of shell access overrides. Output: $text"
            text.contains("unknown command", ignoreCase = true) ||
                text.contains("usage:", ignoreCase = true) ->
                "this build of `cmd wifi` does not expose the start-softap verb, so no shell start is possible here. Output: $text"
            else -> "Output: $text"
        }
    }

    /**
     * The `-b` flag for `cmd wifi start-softap` from the user's band preference. Empty on AUTO:
     * the driver chooses, which is the safe default on a radio that may not host 5 GHz.
     */
    private fun bandFlagFor(preference: HotspotBandPreference): String = when (preference) {
        HotspotBandPreference.AUTO -> ""
        HotspotBandPreference.FORCE_5GHZ -> " -b 5"
        HotspotBandPreference.FORCE_2_4GHZ -> " -b 2"
    }

    /**
     * Says so when a failed start has left the radio with neither a station nor an access point.
     *
     * The framework drops the station to free the radio for tethering; if the attempt then backs
     * out, nothing brings it back. This app cannot: setWifiEnabled() is a no-op at its target SDK,
     * so the only way out is the user toggling WiFi. Worth one clear line, because the symptom
     * downstream is a WiFi Direct group that never forms and no obvious reason why.
     */
    private fun warnIfRadioLeftDown(context: Context) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wm.isWifiEnabled) {
                AppLog.w("HotspotManager: WiFi is off and no access point came up, so this radio is now carrying neither. Nothing here can switch WiFi back on at this target SDK — toggle WiFi on the device before trying WiFi Direct, or it will not form a group.")
            }
        } catch (e: Exception) {
            AppLog.d("HotspotManager: Could not read the WiFi state: ${e.message}")
        }
    }

    /**
     * Polls until a soft AP is up or the timeout expires.
     *
     * `getWifiApState()` is hidden but widely present; where it is blocked, fall back to looking
     * for the interface, which is what a running access point is from the outside anyway.
     */
    private fun awaitApUp(context: Context): Boolean {
        val deadline = System.currentTimeMillis() + AP_STATE_TIMEOUT_MS
        while (true) {
            if (isApUp(context)) return true
            if (System.currentTimeMillis() >= deadline) return false
            try { Thread.sleep(400) } catch (e: InterruptedException) { return false }
        }
    }

    /** Whether a soft AP is running right now, by framework state or, failing that, by interface. */
    private fun isApUp(context: Context): Boolean = when (SoftApStateReader.read(context)) {
        SoftApState.ENABLED -> true
        SoftApState.NOT_ENABLED -> false
        // Nothing was learned, so fall back to what an access point looks like from outside.
        SoftApState.UNKNOWN -> hasApInterface(context)
    }

    /**
     * Whether any interface is one this device is hosting an access point on. Uses the same policy
     * object SoftApCredentialsProvider uses to find the AP it will advertise, so the two cannot
     * disagree about whether one exists.
     */
    private fun hasApInterface(context: Context): Boolean = try {
        val stationIpv4 = NetworkAddresses.stationIpv4(context)
        NetworkInterface.getNetworkInterfaces().toList().any { nif ->
            SoftApNetworkPolicy.isApHost(
                ApInterfaceCandidate(
                    name = nif.name,
                    isLoopback = try { nif.isLoopback } catch (e: Exception) { false },
                    isUp = try { nif.isUp } catch (e: Exception) { false },
                    siteLocalIpv4 = nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { it.isSiteLocalAddress }
                        ?.hostAddress
                ),
                stationIpv4
            )
        }
    } catch (e: Exception) {
        false
    }

    // The try* paths below return "the request was posted without throwing", not "the hotspot
    // started" — the framework answers asynchronously, on a callback we cannot build. Only
    // setHotspotEnabled() decides success, and it does so by looking for the access point.
    private fun tryConnectivityManager(context: Context, enabled: Boolean): Boolean {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (!enabled) {
                val stopMethod = cm.javaClass.methods.find { it.name == "stopTethering" }
                if (stopMethod != null) {
                    stopMethod.isAccessible = true
                    stopMethod.invoke(cm, 0)
                    return true
                }
                return false
            }

            val startMethod = cm.javaClass.methods.find {
                it.name == "startTethering" && it.parameterTypes.size >= 4
            } ?: return false

            startMethod.isAccessible = true
            // Same hazard as the TetheringManager path: the framework dispatches onto this object
            // without a null check. If the shim could not be built, skip rather than crash.
            val callbackInst = createTetheringCallback(context) ?: return false
            val handler = Handler(Looper.getMainLooper())

            return when (startMethod.parameterTypes.size) {
                4 -> {
                    startMethod.invoke(cm, 0, false, callbackInst, handler)
                    true
                }
                5 -> {
                    startMethod.invoke(cm, 0, false, callbackInst, handler, context.packageName)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            AppLog.e("HotspotManager: CM path failed", e)
            return false
        }
    }

    /**
     * A do-nothing implementation of [type], or null if one cannot be made.
     *
     * `TetheringManager.StartTetheringCallback` is an interface whose methods are all default
     * no-ops, so a [java.lang.reflect.Proxy] is enough and none of DexMaker's problems apply. If
     * it ever turns out not to be an interface, returning null makes the caller skip the request
     * rather than fall back to passing null and crashing again.
     *
     * The handler answers `hashCode`/`equals`/`toString` itself: returning null from those is its
     * own NullPointerException the first time anything logs or hashes the object.
     */
    private fun noOpCallback(type: Class<*>): Any? = try {
        if (!type.isInterface) {
            AppLog.w("HotspotManager: ${type.name} is not an interface, so no callback can be made for it; skipping the request rather than passing null.")
            null
        } else {
            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "${type.simpleName}(no-op)"
                    else -> null
                }
            }
        }
    } catch (e: Exception) {
        AppLog.w("HotspotManager: Could not build a no-op ${type.simpleName}: ${e.message}")
        null
    }

    /**
     * A `TetheringRequest` for WiFi tethering, via its Builder, or null if it cannot be built.
     * Only needed on platforms that dropped the plain `(int, …)` overload of startTethering.
     */
    private fun buildTetheringRequest(): Any? = try {
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType)
            .newInstance(TETHERING_WIFI)
        builderClass.getMethod("build").invoke(builder)
    } catch (e: Exception) {
        AppLog.d("HotspotManager: Could not build a TetheringRequest: ${e.message}")
        null
    }

    @SuppressLint("NewApi")
    @Suppress("UNCHECKED_CAST")
    private fun createTetheringCallback(context: Context): Any? {
        try {
            cachedCallbackClass?.let { cls ->
                return cls.getDeclaredConstructor().newInstance()
            }

            val parentClass = Class.forName(CALLBACK_CLASS) ?: return null
            val dexMaker = DexMaker()
            val getByName: Method = TypeId::class.java.getDeclaredMethod("get", String::class.java)
            val getByClass: Method = TypeId::class.java.getDeclaredMethod("get", Class::class.java)

            val generatedType = getByName.invoke(null, "LTetheringCallback;") as TypeId<Any>
            val parentType = getByClass.invoke(null, parentClass) as TypeId<Any>

            dexMaker.declare(generatedType, "TetheringCallback.generated", java.lang.reflect.Modifier.PUBLIC, parentType)

            val constructor = generatedType.getConstructor() as com.android.dx.MethodId<Any, Void>
            val parentConstructor = parentType.getConstructor() as com.android.dx.MethodId<Any, Void>
            val code = dexMaker.declare(constructor, java.lang.reflect.Modifier.PUBLIC)
            val thisRef = code.getThis(generatedType)
            code.invokeDirect(parentConstructor, null, thisRef)
            code.returnVoid()

            val dexCache = context.codeCacheDir
            val classLoader = dexMaker.generateAndLoad(this.javaClass.classLoader, dexCache)
            val generatedClass = classLoader.loadClass("TetheringCallback")
            cachedCallbackClass = generatedClass

            return generatedClass.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            AppLog.e("HotspotManager: Dexmaker failed", e)
            return null
        }
    }

    @SuppressLint("NewApi")
    private fun tryTetheringManager(context: Context, enabled: Boolean): Boolean {
        try {
            val tm = context.getSystemService("tethering") ?: return false
            if (enabled) {
                // [BUG_FIX] Pick the overload by argument *type*, not by argument count. API 34
                // carries two 3-arg startTethering methods — (TetheringRequest, Executor,
                // StartTetheringCallback) and (int, Executor, StartTetheringCallback) — and
                // find{} returned whichever the reflection order happened to yield, so passing
                // the tethering type as an Integer threw "argument 1 has type TetheringRequest".
                val overloads = tm.javaClass.methods.filter {
                    it.name == "startTethering" && it.parameterTypes.size == 3
                }
                val byType = overloads.find { it.parameterTypes[0] == Int::class.javaPrimitiveType }
                val byRequest = overloads.find { it.parameterTypes[0].name.endsWith("TetheringRequest") }
                val method = byType ?: byRequest ?: return false

                // [BUG_FIX] Never pass null for the callback. The framework dispatches
                // onTetheringStarted()/onTetheringFailed() onto it through the executor with no
                // null check, so null crashed the app outright the moment tethering finished
                // starting. It only shows up when the attempt gets far enough to report back — a
                // request refused immediately never dispatches anything, which is why this hid
                // behind an "already active" refusal the first time round.
                val callback = noOpCallback(method.parameterTypes[2]) ?: return false

                val first: Any = if (method === byType) TETHERING_WIFI else buildTetheringRequest() ?: return false
                method.invoke(tm, first, context.mainExecutor, callback)
                return true
            } else {
                val stopMethod = tm.javaClass.methods.find { it.name == "stopTethering" }
                stopMethod?.invoke(tm, 0)
                return true
            }
        } catch (e: Exception) {
            AppLog.e("HotspotManager: TetheringManager path failed", e)
            return false
        }
    }

    private fun tryLegacyWifiManager(context: Context, enabled: Boolean): Boolean {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            return method.invoke(wm, null, enabled) as Boolean
        } catch (_: Exception) { return false }
    }
}
