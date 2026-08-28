package net.ankio.bluetooth.hook

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
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
 * 本机模拟：Android 17+ 扫描在 le_scan.ScanController，
 * 通过 onScanResult(11 参) 注入伪造 BLE 结果。
 */
class GattServiceHooker : PartHooker() {

    override fun hook() {
        if (HookConfig.getString(PrefKeys.SIMULATE_MODE, "") != SimulateMode.Self.toString()) {
            HookLogManager.d(TAG, "Local BLE simulation disabled")
            return
        }

        HookLogManager.d(TAG, "Local BLE simulation started (ScanController path)")

        val scanControllerClass = try {
            Hooker.loader(SCAN_CONTROLLER)
        } catch (e: Throwable) {
            HookLogManager.e(TAG, "ScanController class not found: " + e.message)
            return
        }

        // 1) 构造时抓住实例
        try {
            XposedHelpers.findAndHookConstructor(
                scanControllerClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        HookLogManager.d(TAG, "ScanController constructed")
                        holdInstance(param.thisObject)
                    }
                },
            )
        } catch (e: Throwable) {
            HookLogManager.d(TAG, "ScanController ctor hook skip: " + e.message)
        }

        // 2) 常见生命周期方法上再抓一次
        for (name in listOf("start", "onStart", "init", "setAvailable")) {
            if (!hasMethod(scanControllerClass, name)) continue
            try {
                Hooker.after(scanControllerClass, name) {
                    HookLogManager.d(TAG, "ScanController lifecycle hold: " + name)
                    holdInstance(it.thisObject)
                }
            } catch (_: Throwable) {
            }
        }

        // 3) 兜底：任意实例方法第一次执行时抓住 this
        try {
            for (m in scanControllerClass.declaredMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        scanControllerClass,
                        m.name,
                        *m.parameterTypes,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (scanControllerRef.get() == null) {
                                    holdInstance(param.thisObject)
                                }
                            }
                        },
                    )
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            HookLogManager.d(TAG, "broad method hook skip: " + e.message)
        }

        mainHandler.post(injectRunnable)
    }

    private fun holdInstance(obj: Any?) {
        if (obj == null) return
        if (scanControllerRef.get() !== obj) {
            scanControllerRef.set(obj)
            HookLogManager.d(TAG, "Held ScanController: " + obj.javaClass.name)
            resolveMethod(obj)
        }
    }

    private fun resolveMethod(obj: Any) {
        val m = findScanMethod(obj.javaClass)
        if (m != null) {
            scanMethodRef.set(m)
            val types = m.parameterTypes.joinToString { it.simpleName }
            HookLogManager.d(TAG, "FOUND " + m.name + "(" + m.parameterTypes.size + ") types=[" + types + "]")
        } else {
            HookLogManager.e(TAG, "No onScanResult on ScanController")
            dumpMethods(obj.javaClass, "ScanController")
        }
    }

    private val injectRunnable = object : Runnable {
        override fun run() {
            try {
                val target = scanControllerRef.get()
                val method = scanMethodRef.get()
                if (target != null && method != null) {
                    val mac = HookConfig.getString(PrefKeys.PREF_MAC, DEFAULT_MAC)
                    val rssi = HookConfig.getString(PrefKeys.PREF_RSSI, DEFAULT_RSSI).toIntOrNull()
                        ?: DEFAULT_RSSI.toInt()
                    val advData = ByteUtils.hexStringToBytes(
                        HookConfig.getString(PrefKeys.PREF_DATA, DEFAULT_ADV_DATA),
                    )
                    invokeScanResult(target, method, mac, rssi, advData)
                }
            } catch (e: Throwable) {
                HookLogManager.e(TAG, "Mock scan injection failed: " + e.message, e)
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

        private fun hasMethod(clazz: Class<*>, name: String): Boolean =
            clazz.declaredMethods.any { it.name == name }

        private fun findScanMethod(clazz: Class<*>): Method? {
            val methods = clazz.declaredMethods.filter {
                it.name == "onScanResult" || it.name == "onScanResultInternal"
            }
            return methods.maxByOrNull { it.parameterTypes.size }
        }

        private fun dumpMethods(clazz: Class<*>, label: String) {
            for (m in clazz.declaredMethods.sortedBy { it.name }) {
                val n = m.name.lowercase()
                if (!n.contains("scan") && !n.contains("result")) continue
                val params = m.parameterTypes.joinToString { it.simpleName }
                HookLogManager.d(TAG, "[" + label + "] " + m.name + "(" + params + ")")
            }
        }

        private fun invokeScanResult(
            target: Any,
            method: Method,
            mac: String,
            rssi: Int,
            advData: ByteArray,
        ) {
            // 11 params: eventType, addressType, address, primaryPhy, secondaryPhy,
            // advertisingSid, txPower, rssi, periodicAdvInt, advData, originalAddress
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
            val argsList = when {
                count >= 11 -> full
                count <= 0 -> emptyList()
                else -> full.take(count)
            }
            val args = argsList.toTypedArray()
            method.isAccessible = true
            method.invoke(target, *args)
        }
    }
}