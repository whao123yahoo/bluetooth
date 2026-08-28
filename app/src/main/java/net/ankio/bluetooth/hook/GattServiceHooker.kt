package net.ankio.bluetooth.hook

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import net.ankio.bluetooth.model.SimulateMode
import net.ankio.bluetooth.utils.ByteUtils
import net.ankio.bluetooth.utils.PrefKeys
import net.ankio.xposed.lib.hook.api.PartHooker
import net.ankio.xposed.lib.hook.hook.Hooker
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * 本机模拟：Android 17+ 走 le_scan.ScanController.onScanResultInternal(11参)
 */
class GattServiceHooker : PartHooker() {

    override fun hook() {
        XposedBridge.log("BluetoothDebug: GattServiceHooker.hook() enter")

        val mode = readPref(PrefKeys.SIMULATE_MODE, "")
        XposedBridge.log("BluetoothDebug: SIMULATE_MODE=[" + mode + "] Self=[" + SimulateMode.Self + "]")

        // 临时强制本机模拟；确认配置同步正常后可改 forceSelf = false
        val forceSelf = true
        if (!forceSelf && mode != SimulateMode.Self.toString()) {
            XposedBridge.log("BluetoothDebug: Local BLE simulation disabled")
            return
        }
        XposedBridge.log("BluetoothDebug: Local BLE simulation started (forceSelf=" + forceSelf + ")")

        val scanControllerClass = try {
            Hooker.loader(SCAN_CONTROLLER)
        } catch (e: Throwable) {
            XposedBridge.log("BluetoothDebug: ScanController not found: " + e.message)
            return
        }
        XposedBridge.log("BluetoothDebug: ScanController class ok: " + scanControllerClass.name)

        try {
            XposedBridge.hookAllConstructors(
                scanControllerClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("BluetoothDebug: ScanController constructed")
                        holdInstance(param.thisObject)
                    }
                },
            )
            XposedBridge.log("BluetoothDebug: hookAllConstructors ok")
        } catch (e: Throwable) {
            XposedBridge.log("BluetoothDebug: hookAllConstructors failed: " + e.message)
        }

        for (name in listOf("start", "startLocked", "init", "onStart")) {
            try {
                if (scanControllerClass.declaredMethods.none { it.name == name }) continue
                Hooker.after(scanControllerClass, name) {
                    XposedBridge.log("BluetoothDebug: ScanController." + name)
                    holdInstance(it.thisObject)
                }
            } catch (_: Throwable) {
            }
        }

        val handler = getMainHandler()
        if (handler != null) {
            handler.post(injectRunnable)
            XposedBridge.log("BluetoothDebug: injectRunnable posted")
        } else {
            XposedBridge.log("BluetoothDebug: MainLooper not ready")
        }
    }

    private fun holdInstance(obj: Any?) {
        if (obj == null) return
        if (scanControllerRef.get() !== obj) {
            scanControllerRef.set(obj)
            XposedBridge.log("BluetoothDebug: Held ScanController")
            resolveMethod(obj)
        }
    }

    private fun resolveMethod(obj: Any) {
        val m = findScanMethod(obj.javaClass)
        if (m != null) {
            scanMethodRef.set(m)
            XposedBridge.log(
                "BluetoothDebug: FOUND " + m.name + " params=" + m.parameterTypes.size,
            )
        } else {
            XposedBridge.log("BluetoothDebug: No onScanResult on ScanController")
        }
    }

    private val injectRunnable = object : Runnable {
        private var tick = 0

        override fun run() {
            val target = scanControllerRef.get()
            val method = scanMethodRef.get()
            if (target != null && method != null) {
                val mac = readPref(PrefKeys.PREF_MAC, DEFAULT_MAC)
                val rssiStr = readPref(PrefKeys.PREF_RSSI, DEFAULT_RSSI)
                val rssi = rssiStr.toIntOrNull() ?: DEFAULT_RSSI.toInt()
                val dataHex = readPref(PrefKeys.PREF_DATA, DEFAULT_ADV_DATA)
                val advData = try {
                    ByteUtils.hexStringToBytes(dataHex)
                } catch (_: Throwable) {
                    ByteUtils.hexStringToBytes(DEFAULT_ADV_DATA)
                }

                if (tick % 10 == 0) {
                    XposedBridge.log(
                        "BluetoothDebug: using mac=" + mac +
                            " rssi=" + rssi +
                            " dataLen=" + advData.size +
                            " dataPrefix=" + dataHex.take(32),
                    )
                }
                tick++

                invokeScanResult(target, method, mac, rssi, advData)
            }
            getMainHandler()?.postDelayed(this, INTERVAL_MS)
        }
    }

    companion object {
        const val TAG = "BluetoothDebug"

        private const val SCAN_CONTROLLER = "com.android.bluetooth.le_scan.ScanController"
        private const val MODULE_PACKAGE = "net.ankio.bluetooth"
        private const val INTERVAL_MS = 500L
        private const val DEFAULT_MAC = "76:A7:8A:67:66:C9"
        private const val DEFAULT_RSSI = "-50"
        private const val DEFAULT_ADV_DATA =
            "02010403033CFE17FF0001B500024271A7B6000000C983926CB1011000000000000000000000000000000000000000000000000000000000000000000000"

        private val scanControllerRef = AtomicReference<Any?>(null)
        private val scanMethodRef = AtomicReference<Method?>(null)

        @Volatile
        private var mainHandler: Handler? = null

        private fun getMainHandler(): Handler? {
            mainHandler?.let { return it }
            val looper = Looper.getMainLooper() ?: return null
            return Handler(looper).also { mainHandler = it }
        }

        /**
         * 跨进程读模块配置：先 HookConfig，再 XSharedPreferences.reload()
         */
        private fun readPref(key: String, default: String): String {
            try {
                val v = HookConfig.getString(key, default)
                if (v.isNotEmpty()) return v
            } catch (_: Throwable) {
            }
            try {
                val xp = XSharedPreferences(MODULE_PACKAGE)
                xp.reload()
                val v = xp.getString(key, null)
                if (!v.isNullOrEmpty()) return v
            } catch (_: Throwable) {
            }
            // 常见自定义 prefs 文件名再试一次
            for (name in listOf("config", "settings", "bluetooth", MODULE_PACKAGE + "_preferences")) {
                try {
                    val xp = XSharedPreferences(MODULE_PACKAGE, name)
                    xp.reload()
                    val v = xp.getString(key, null)
                    if (!v.isNullOrEmpty()) return v
                } catch (_: Throwable) {
                }
            }
            return default
        }

        private fun findScanMethod(clazz: Class<*>): Method? {
            val internal = clazz.declaredMethods.filter { it.name == "onScanResultInternal" }
            if (internal.isNotEmpty()) {
                return internal.maxByOrNull { it.parameterTypes.size }
            }
            val normal = clazz.declaredMethods.filter { it.name == "onScanResult" }
            return normal.maxByOrNull { it.parameterTypes.size }
        }

        private fun invokeScanResult(
            target: Any,
            method: Method,
            mac: String,
            rssi: Int,
            advData: ByteArray,
        ) {
            val count = method.parameterTypes.size
            try {
                if (count >= 11) {
                    XposedHelpers.callMethod(
                        target,
                        method.name,
                        0x1b,
                        0x00,
                        mac,
                        0x01,
                        0x00,
                        0xff,
                        0x7f,
                        rssi,
                        0x00,
                        advData,
                        mac,
                    )
                } else {
                    XposedHelpers.callMethod(
                        target,
                        method.name,
                        0x1b,
                        0x00,
                        mac,
                        0x01,
                        0x00,
                        0xff,
                        0x7f,
                        rssi,
                        0x00,
                        advData,
                    )
                }
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                XposedBridge.log(
                    "BluetoothDebug: inject failed: " +
                        cause.javaClass.name + ": " + cause.message,
                )
            }
        }
    }
}