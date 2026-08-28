package net.ankio.bluetooth.hook

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import net.ankio.bluetooth.model.SimulateMode
import net.ankio.bluetooth.utils.ByteUtils
import net.ankio.bluetooth.utils.HookLogManager
import net.ankio.bluetooth.utils.PrefKeys
import net.ankio.xposed.lib.hook.api.PartHooker
import net.ankio.xposed.lib.hook.hook.Hooker
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * 本机模拟：Android 17+ 走 le_scan.ScanController.onScanResult(11参)
 */
class GattServiceHooker : PartHooker() {

    override fun hook() {
        XposedBridge.log("BluetoothDebug: GattServiceHooker.hook() enter")

        val mode = try {
            HookConfig.getString(PrefKeys.SIMULATE_MODE, "")
        } catch (e: Throwable) {
            XposedBridge.log("BluetoothDebug: read SIMULATE_MODE failed: " + e.message)
            ""
        }
        XposedBridge.log("BluetoothDebug: SIMULATE_MODE=[" + mode + "] Self=[" + SimulateMode.Self + "]")

        // 临时强制本机模拟，验证链路；成功后再改回判断 mode
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
            XposedBridge.log("BluetoothDebug: FOUND " + m.name + " params=" + m.parameterTypes.size)
        } else {
            XposedBridge.log("BluetoothDebug: No onScanResult on ScanController")
        }
    }

    private val injectRunnable = object : Runnable {
        override fun run() {
            try {
                val target = scanControllerRef.get()
                val method = scanMethodRef.get()
                if (target != null && method != null) {
                    val mac = HookConfig.getString(PrefKeys.PREF_MAC, DEFAULT_MAC)
                    val rssi = HookConfig.getString(PrefKeys.PREF_RSSI, DEFAULT_RSSI)
                        .toIntOrNull() ?: DEFAULT_RSSI.toInt()
                    val advData = ByteUtils.hexStringToBytes(
                        HookConfig.getString(PrefKeys.PREF_DATA, DEFAULT_ADV_DATA),
                    )
                    invokeScanResult(target, method, mac, rssi, advData)
                }
            } catch (e: Throwable) {
                XposedBridge.log("BluetoothDebug: inject failed: " + e.message)
            }
            getMainHandler()?.postDelayed(this, INTERVAL_MS)
        }
    }

    companion object {
        const val TAG = "BluetoothDebug"

        private const val SCAN_CONTROLLER = "com.android.bluetooth.le_scan.ScanController"
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

        private fun findScanMethod(clazz: Class<*>): Method? {
            val methods = clazz.declaredMethods.filter {
                it.name == "onScanResult" || it.name == "onScanResultInternal"
            }
            return methods.maxByOrNull { it.parameterTypes.size }
        }

        private fun invokeScanResult(
            target: Any,
            method: Method,
            mac: String,
            rssi: Int,
            advData: ByteArray,
        ) {
            val full = listOf<Any>(
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
            val count = method.parameterTypes.size
            val args = when {
                count >= 11 -> full
                count <= 0 -> emptyList()
                else -> full.take(count)
            }.toTypedArray()
            method.isAccessible = true
            method.invoke(target, *args)
        }
    }
}