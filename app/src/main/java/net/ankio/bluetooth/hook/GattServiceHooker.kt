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
        // 一进来就打日志，确认 hooker 被调用
        HookLogManager.d(TAG, "GattServiceHooker.hook() enter")
        XposedBridge.log("BluetoothDebug: GattServiceHooker.hook() enter")

        val mode = try {
            HookConfig.getString(PrefKeys.SIMULATE_MODE, "")
        } catch (e: Throwable) {
            HookLogManager.e(TAG, "read SIMULATE_MODE failed: " + e.message)
            XposedBridge.log("BluetoothDebug: read mode failed " + e.message)
            ""
        }

        HookLogManager.d(TAG, "SIMULATE_MODE=" + mode)
        XposedBridge.log("BluetoothDebug: SIMULATE_MODE=" + mode)

        if (mode != SimulateMode.Self.toString()) {
            HookLogManager.d(TAG, "Local BLE simulation disabled")
            return
        }

        HookLogManager.d(TAG, "Local BLE simulation started (ScanController path)")

        val scanControllerClass = try {
            Hooker.loader(SCAN_CONTROLLER)
        } catch (e: Throwable) {
            HookLogManager.e(TAG, "ScanController not found: " + e.message)
            XposedBridge.log("BluetoothDebug: ScanController not found " + e.message)
            return
        }

        HookLogManager.d(TAG, "ScanController class ok: " + scanControllerClass.name)

        // 只 hook 构造函数，避免扫全方法导致崩溃
        try {
            XposedBridge.hookAllConstructors(
                scanControllerClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        HookLogManager.d(TAG, "ScanController constructed")
                        XposedBridge.log("BluetoothDebug: ScanController constructed")
                        holdInstance(param.thisObject)
                    }
                },
            )
            HookLogManager.d(TAG, "hookAllConstructors ok")
        } catch (e: Throwable) {
            HookLogManager.e(TAG, "hookAllConstructors failed: " + e.message)
            XposedBridge.log("BluetoothDebug: hookAllConstructors failed " + e.message)
        }

        // 再试几个常见名字（没有就跳过）
        for (name in listOf("start", "startLocked", "init", "onStart")) {
            try {
                if (scanControllerClass.declaredMethods.none { it.name == name }) continue
                Hooker.after(scanControllerClass, name) {
                    HookLogManager.d(TAG, "ScanController." + name)
                    holdInstance(it.thisObject)
                }
            } catch (_: Throwable) {
            }
        }

        mainHandler.post(injectRunnable)
        HookLogManager.d(TAG, "injectRunnable posted")
    }

    private fun holdInstance(obj: Any?) {
        if (obj == null) return
        if (scanControllerRef.get() !== obj) {
            scanControllerRef.set(obj)
            HookLogManager.d(TAG, "Held ScanController")
            XposedBridge.log("BluetoothDebug: Held ScanController")
            resolveMethod(obj)
        }
    }

    private fun resolveMethod(obj: Any) {
        val m = findScanMethod(obj.javaClass)
        if (m != null) {
            scanMethodRef.set(m)
            HookLogManager.d(
                TAG,
                "FOUND " + m.name + " params=" + m.parameterTypes.size,
            )
            XposedBridge.log(
                "BluetoothDebug: FOUND " + m.name + " params=" + m.parameterTypes.size,
            )
        } else {
            HookLogManager.e(TAG, "No onScanResult on ScanController")
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
                HookLogManager.e(TAG, "inject failed: " + e.message)
            }
            mainHandler.postDelayed(this, INTERVAL_MS)
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

        private val mainHandler = Handler(Looper.getMainLooper())
        private val scanControllerRef = AtomicReference<Any?>(null)
        private val scanMethodRef = AtomicReference<Method?>(null)

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