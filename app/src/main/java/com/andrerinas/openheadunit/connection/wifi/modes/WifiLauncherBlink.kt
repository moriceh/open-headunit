package com.andrerinas.openheadunit.connection.wifi.modes

import com.andrerinas.openheadunit.connection.wifi.WifiLauncher
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HotspotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Direct Wireless Android Auto launcher for Choiceway / ZXW headunits via the internal
 * Bluetooth daemon listening on TCP 127.0.0.1:3152 (/dev/BT_serial) and the native Hotspot.
 */
class WifiLauncherBlink(manager: WifiLauncherManager) : WifiLauncherNative(manager, NativeStrategy.HOTSPOT) {

    override val mode = WifiLauncherMode.BLINK

    override fun hasSameStartConfiguration(launcher: WifiLauncher) = launcher is WifiLauncherBlink

    override fun start(noInfoToasts: Boolean) {
        if (settings.autoEnableHotspotSelfAdB) {
            AppLog.i("WifiLauncherBlink: Starting hotspot via SelfADB immediately on mode start...")
            service.serviceScope.launch(Dispatchers.IO) {
                HotspotManager.startViaSelfAdB(service)
            }
        }
        super.start(noInfoToasts)
    }
}
