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
            HookLogManager.e(TAG, "ScanController class not found: ${e.message}")
            return
        }

        // 1) 构造时抓住实例
        try {
            XposedHelpers.findAndHookConstructor(
                scanControllerClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        HookLogManager.d(TAG, "ScanController constructed: ${param.thisObject}")
                        holdInstance(param.thisObject)
                    }
                },
            )
        } catch (e: Throwable) {
            HookLogManager.d(TAG, "ScanController ctor hook skip: ${e.message}")
        }

        // 2) 常见生命周期方法上再抓一次（防止构造签名变了）
        for (name in listOf("start", "onStart", "init", "setAvailable")) {
            if (!hasMethod(scanControllerClass, name)) continue
            try {
                Hooker.after(scanControllerClass, name) {
                    HookLogManager.d(TAG, "ScanController.$name -> hold instance")
                    holdInstance(it.thisObject)
                }
            } catch (_: Throwable) {
            }
        }

        // 3) 也 Hook 所有方法，第一次进到任意实例方法时抓住 this（兜底）
        try {
            for (m in scanControllerClass.declaredMethods) {
                if (m.parameterTypes.isEmpty() && m.name.startsWith("get")) continue
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
            HookLogManager.d(TAG, "broad method hook skip: ${e.message}")
        }

        // 启动定时注入
        mainHandler.post(injectRunnable)
    }

    private fun holdInstance(obj: Any?) {
        if (obj == null) return
        if (scanControllerRef.get() !== obj) {
            scanControllerRef.set(obj)
            HookLogManager.d(TAG, "Held ScanController: ${obj.javaClass.name}")
            resolveMethod(obj)
        }
    }

    private fun resolveMethod(obj: Any) {
        val m = findScanMethod(obj.javaClass)
        if (m != null) {
            scanMethodRef.set(m)
            HookLogManager.d(
                TAG,
                "FOUND \( {m.name}( \){m.parameterTypes.size}) " +
                    "types=[${m.parameterTypes.joinToString { it.simpleName }}]",
            )
        } else {
            HookLogManager.e(TAG, "No onScanResult* on ScanController")
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
                HookLogManager.e(TAG, "Mock scan injection failed: ${e.message}", e)
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
            // 优先 11 参（带 originalAddress）
            return methods.maxByOrNull { it.parameterTypes.size }
        }

        private fun dumpMethods(clazz: Class<*>, label: String) {
            for (m in clazz.declaredMethods.sortedBy { it.name }) {
                val n = m.name.lowercase()
                if (!n.contains("scan") && !n.contains("result")) continue
                HookLogManager.d(
                    TAG,
                    "[$label] \( {m.name}( \){m.parameterTypes.joinToString { it.simpleName }})",
                )
            }
        }

        private fun invokeScanResult(
            target: Any,
            method: Method,
            mac: String,
            rssi: Int,
            advData: ByteArray,
        ) {
            // (IILjava/lang/String;IIIIII[BLjava/lang/String;)V
            val args = arrayOf<Any>(
                0x1b,   // eventType
                0x00,   // addressType PUBLIC
                mac,    // address
                0x01,   // primaryPhy LE_1M
                0x00,   // secondaryPhy
                0xff,   // advertisingSid
                0x7f,   // txPower
                rssi,   // rssi
                0x00,   // periodicAdvInt
                advData,
                mac,    // originalAddress
            )
            val useArgs = if (method.parameterTypes.size >= 11) {
                args
            } else {
                args.copyOf(method.parameterTypes.size)
            }
            method.isAccessible = true
            method.invoke(target, *useArgs)
        }
    }
}
