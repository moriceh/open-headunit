package com.andrerinas.openheadunit.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * The int values of `SoftApConfiguration.SoftApSecurityType`, hidden and unavailable at compile
 * time, named here to match their AOSP values so a security type read off the stored configuration
 * can be mapped to the security token a `cmd wifi start-softap` call accepts.
 */
enum class SoftApSecurityType(val value: Int) {
    UNSPECIFIED(0),
    OPEN(1),
    WPA2_PERSONAL(2),
    WPA3_PERSONAL(3),
    WPA3_TRANSITION(4),
    OWE(5);

    companion object {
        fun fromValue(value: Int): SoftApSecurityType =
            entries.firstOrNull { it.value == value } ?: UNSPECIFIED
    }
}

/**
 * Reads the SSID and passphrase of the hotspot this device is configured to run.
 *
 * Reflection throughout — neither `getSoftApConfiguration` nor `getWifiApConfiguration` is public
 * API, and both can throw on a locked-down device — so callers want a manual override ahead of it.
 * Shared by `ShareHotspotQrDialog` and the Native AA hotspot transport.
 */
object HotspotConfigReader {

    /**
     * The stored hotspot, read in one pass off the [WifiManager]. Carries the security type as
     * well as the name and passphrase because a `cmd wifi start-softap` call must be told it;
     * inferring it from "a passphrase exists" is wrong, since a WPA-PSK-only and a
     * WPA-PSK+SAE transition network look identical from the passphrase's point of view.
     */
    data class HotspotSecurity(val ssid: String, val passphrase: String, val securityType: Int)

    /** [HotspotSecurity], or null when the stored configuration cannot be read. */
    fun getSystemHotspotSecurity(context: Context): HotspotSecurity? {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // 1. Try modern getSoftApConfiguration (API 30+)
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val getSoftApConfigurationMethod = wm.javaClass.getMethod("getSoftApConfiguration")
                    val softApConfig = getSoftApConfigurationMethod.invoke(wm)
                    if (softApConfig != null) {
                        val getSsidMethod = softApConfig.javaClass.getMethod("getSsid")
                        val getPassphraseMethod = softApConfig.javaClass.getMethod("getPassphrase")
                        val ssid = getSsidMethod.invoke(softApConfig) as? String ?: ""
                        val pass = getPassphraseMethod.invoke(softApConfig) as? String ?: ""
                        if (ssid.isNotEmpty()) {
                            val securityType = try {
                                softApConfig.javaClass.getMethod("getSecurityType").invoke(softApConfig) as? Int
                                    ?: SoftApSecurityType.UNSPECIFIED.value
                            } catch (e: Exception) {
                                AppLog.d("HotspotConfigReader: could not read the security type: ${e.message}")
                                SoftApSecurityType.UNSPECIFIED.value
                            }
                            return HotspotSecurity(ssid, pass, securityType)
                        }
                    }
                } catch (e: Exception) {
                    AppLog.d("HotspotConfigReader: Failed to get soft ap config via reflection: ${e.message}")
                }
            }

            // 2. Try legacy getWifiApConfiguration (API < 30). Its WifiConfiguration carries no
            //    security-type int, so report the type as unspecified and let the caller fall back.
            try {
                val getWifiApConfigurationMethod = wm.javaClass.getMethod("getWifiApConfiguration")
                val wifiConfig = getWifiApConfigurationMethod.invoke(wm)
                if (wifiConfig != null) {
                    val ssidField = wifiConfig.javaClass.getField("SSID")
                    val preSharedKeyField = wifiConfig.javaClass.getField("preSharedKey")
                    val ssid = ssidField.get(wifiConfig) as? String ?: ""
                    val pass = preSharedKeyField.get(wifiConfig) as? String ?: ""

                    // Clean SSID quotes if present
                    val cleanSsid = if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid.substring(1, ssid.length - 1)
                    } else {
                        ssid
                    }
                    return HotspotSecurity(cleanSsid, pass, SoftApSecurityType.UNSPECIFIED.value)
                }
            } catch (e: Exception) {
                AppLog.d("HotspotConfigReader: Failed to get wifi ap config via reflection: ${e.message}")
            }
        } catch (e: Exception) {
            AppLog.e("HotspotConfigReader: Failed to access WifiManager: ${e.message}")
        }
        return null
    }

    /** The configured hotspot as (ssid, passphrase), or null if it cannot be read. */
    fun getSystemHotspotConfig(context: Context): Pair<String, String>? {
        val security = getSystemHotspotSecurity(context)
        return security?.let { Pair(it.ssid, it.passphrase) }
    }
}
