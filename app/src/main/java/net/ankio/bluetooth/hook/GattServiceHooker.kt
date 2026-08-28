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

/**
 * 本机模拟：Hook 系统 GattService，周期性注入伪造 BLE 扫描结果。
 * 仅在 [net.ankio.bluetooth.model.SimulateMode.Self] 下生效。
 *
 * Android 17 / Mainline 蓝牙栈：先 dump 真实方法签名，再按签名注入。
 */
class GattServiceHooker : PartHooker() {

    override fun hook() {
        if (HookConfig.getString(PrefKeys.SIMULATE_MODE, "") != SimulateMode.Self.toString()) {
            HookLogManager.d(TAG, "Local BLE simulation disabled")
            return
        }

        HookLogManager.d(TAG, "Local BLE simulation started")
        val gattClass = Hooker.loader(GATT_SERVICE)

        when {
            hasMethod(gattClass, "start") -> hookStartStop(gattClass, "start", "stop")
            hasMethod(gattClass, "initMiFeature") -> hookStartStop(gattClass, "initMiFeature", "cleanup")
            else -> hookConstructorStop(gattClass)
        }
    }

    private fun hookStartStop(gattClass: Class<*>, start: String, stop: String) {
        Hooker.after(gattClass, start) { attachBroadcaster(it.thisObject) }
        Hooker.before(gattClass, stop) { detachBroadcaster(it.thisObject) }
    }

    /** Android 16+：GattService 在构造完成后就绪，停止仍走 cleanup。 */
    private fun hookConstructorStop(gattClass: Class<*>) {
        val adapterClass = Hooker.loader(ADAPTER_SERVICE)
        XposedHelpers.findAndHookConstructor(
            gattClass,
            adapterClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    attachBroadcaster(param.thisObject)
                }
            },
        )
        Hooker.before(gattClass, "cleanup") { detachBroadcaster(it.thisObject) }
    }

    private fun attachBroadcaster(gattService: Any) {
        var handler = XposedHelpers.getAdditionalInstanceField(gattService, HANDLER_KEY) as Handler?
        if (handler == null) {
            handler = Handler(Looper.getMainLooper())
        }
        XposedHelpers.setAdditionalInstanceField(gattService, HANDLER_KEY, handler)

        val broadcast = ScanBroadcaster(gattService, handler)
        XposedHelpers.setAdditionalInstanceField(gattService, RUNNABLE_KEY, broadcast)
        handler.postDelayed(broadcast, INTERVAL_MS)
    }

    private fun detachBroadcaster(gattService: Any) {
        val handler = XposedHelpers.getAdditionalInstanceField(gattService, HANDLER_KEY) as? Handler
            ?: return
        val runnable = XposedHelpers.getAdditionalInstanceField(gattService, RUNNABLE_KEY) as? Runnable
            ?: return
        handler.removeCallbacks(runnable)
    }

    private class ScanBroadcaster(
        private val gattService: Any,
        private val handler: Handler,
    ) : Runnable {

        /** 启动时可能解析失败（ScanController 尚未就绪），运行中重试。 */
        private var scanPath: ResolvedPath? = null
        private var resolveAttempts = 0

        override fun run() {
            if (scanPath == null && resolveAttempts < MAX_RESOLVE_ATTEMPTS) {
                resolveAttempts++
                scanPath = resolveScanPath(gattService)
                if (scanPath == null) {
                    HookLogManager.d(TAG, "resolveScanPath retry $resolveAttempts/$MAX_RESOLVE_ATTEMPTS")
                }
            }

            scanPath?.let { path ->
                val mac = HookConfig.getString(PrefKeys.PREF_MAC, DEFAULT_MAC)
                val rssi = HookConfig.getString(PrefKeys.PREF_RSSI, DEFAULT_RSSI).toIntOrNull()
                    ?: DEFAULT_RSSI.toInt()
                val advData = ByteUtils.hexStringToBytes(
                    HookConfig.getString(PrefKeys.PREF_DATA, DEFAULT_ADV_DATA),
                )
                try {
                    invokeScanResult(path.target, path.method, mac, rssi, advData)
                } catch (e: Throwable) {
                    HookLogManager.e(TAG, "Mock scan injection failed: ${e.message}", e)
                }
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    companion object {
        const val TAG = "BluetoothDebug"

        private const val GATT_SERVICE = "com.android.bluetooth.gatt.GattService"
        private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"
        private const val HANDLER_KEY = "handler"
        private const val RUNNABLE_KEY = "runnable"
        private const val INTERVAL_MS = 500L
        private const val MAX_RESOLVE_ATTEMPTS = 20
        private const val DEFAULT_MAC = "76:A7:8A:67:66:C9"
        private const val DEFAULT_RSSI = "-50"
        private const val DEFAULT_ADV_DATA =
            "02010403033CFE17FF0001B500024271A7B6000000C983926CB1011000000000000000000000000000000000000000000000000000000000000000000000"

        private data class ResolvedPath(
            val target: Any,
            val method: Method,
            val label: String,
        )

        private fun hasMethod(clazz: Class<*>, name: String): Boolean =
            clazz.declaredMethods.any { it.name == name }

        private fun resolveScanPath(gattService: Any): ResolvedPath? {
            val gattClass = gattService.javaClass
            HookLogManager.d(TAG, "GattService class=${gattClass.name}")

            // 1) 打印 GattService 上所有可疑方法
            dumpMethods(gattClass, "GattService")

            // 2) 收集候选目标：ScanController / TransitionalScanHelper / GattService
            val candidates = mutableListOf<Pair<String, Any>>()

            tryGet(gattService, "getScanController")?.let { sc ->
                HookLogManager.d(TAG, "getScanController -> ${sc.javaClass.name}")
                dumpMethods(sc.javaClass, "ScanController")
                candidates += "ScanController" to sc

                tryGet(sc, "getTransitionalScanHelper")?.let { h ->
                    HookLogManager.d(
                        TAG,
                        "ScanController.getTransitionalScanHelper -> ${h.javaClass.name}",
                    )
                    dumpMethods(h.javaClass, "Helper(from SC)")
                    candidates += "Helper(from SC)" to h
                }

                findFieldValue(sc, listOf("mTransitionalScanHelper", "transitionalScanHelper"))?.let { h ->
                    HookLogManager.d(TAG, "field mTransitionalScanHelper -> ${h.javaClass.name}")
                    dumpMethods(h.javaClass, "Helper(field)")
                    candidates += "Helper(field)" to h
                }
            }

            tryGet(gattService, "getTransitionalScanHelper")?.let { h ->
                HookLogManager.d(
                    TAG,
                    "GattService.getTransitionalScanHelper -> ${h.javaClass.name}",
                )
                dumpMethods(h.javaClass, "Helper(from GS)")
                candidates += "Helper(from GS)" to h
            }

            findFieldValue(
                gattService,
                listOf("mScanController", "mTransitionalScanHelper", "mScanManager"),
            )?.let { obj ->
                HookLogManager.d(TAG, "GattService field -> ${obj.javaClass.name}")
                dumpMethods(obj.javaClass, "GS-field")
                candidates += "GS-field" to obj
            }

            candidates += "GattService" to gattService

            // 3) 在每个候选上找 onScanResult / onScanResultInternal
            for ((name, target) in candidates) {
                val method = findScanMethod(target.javaClass) ?: continue
                val count = method.parameterTypes.size
                val types = method.parameterTypes.joinToString { it.simpleName }
                HookLogManager.d(TAG, "FOUND on $name: ${method.name}($count) types=[$types]")
                return ResolvedPath(target = target, method = method, label = name)
            }

            HookLogManager.e(
                TAG,
                "Unsupported device; no onScanResult* found. See dump above. " +
                    "export com.android.bluetooth and open a GitHub issue",
            )
            return null
        }

        private fun tryGet(obj: Any, methodName: String): Any? = try {
            XposedHelpers.callMethod(obj, methodName)
        } catch (_: Throwable) {
            null
        }

        private fun findFieldValue(obj: Any, names: List<String>): Any? {
            for (n in names) {
                try {
                    val f = XposedHelpers.findField(obj.javaClass, n)
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (v != null) return v
                } catch (_: Throwable) {
                }
            }
            return null
        }

        private fun findScanMethod(clazz: Class<*>): Method? {
            val methods = clazz.declaredMethods.filter {
                it.name == "onScanResult" || it.name == "onScanResultInternal"
            }
            // 优先参数多的（带 originalAddress 的 11 参）
            return methods.maxByOrNull { it.parameterTypes.size }
        }

        private fun dumpMethods(clazz: Class<*>, label: String) {
            val interesting = clazz.declaredMethods
                .filter {
                    val n = it.name.lowercase()
                    n.contains("scan") ||
                        n.contains("getscan") ||
                        n.contains("helper") ||
                        n.contains("controller")
                }
                .sortedBy { it.name }
            if (interesting.isEmpty()) {
                HookLogManager.d(TAG, "[$label] (no scan/helper/controller methods)")
                return
            }
            for (m in interesting) {
                val params = m.parameterTypes.joinToString { it.simpleName }
                HookLogManager.d(
                    TAG,
                    "[$label] ${m.name}($params) : ${m.returnType.simpleName}",
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
            val count = method.parameterTypes.size
            // AOSP 常见顺序：
            // eventType, addressType, address, primaryPhy, secondaryPhy,
            // advertisingSid, txPower, rssi, periodicAdvInt, advData [, originalAddress]
            val base = arrayOf<Any>(
                0x1b,   // eventType
                0x00,   // addressType PUBLIC；部分栈用 0x01
                mac,
                0x01,   // primaryPhy LE_1M
                0x00,   // secondaryPhy
                0xff,   // advertisingSid
                0x7f,   // txPower
                rssi,
                0x00,   // periodicAdvInt
                advData,
            )
            val args: Array<Any> = when {
                count >= 11 -> base + mac
                count <= 0 -> emptyArray()
                count < base.size -> base.copyOf(count)
                else -> base
            }

            method.isAccessible = true
            try {
                method.invoke(target, *args)
            } catch (e: IllegalArgumentException) {
                HookLogManager.e(
                    TAG,
                    "invoke arg mismatch: ${method.name} expects $count, " +
                        "types=[${method.parameterTypes.joinToString { it.simpleName }}], " +
                        "got ${args.size}",
                    e,
                )
                throw e
            }
        }
    }
}