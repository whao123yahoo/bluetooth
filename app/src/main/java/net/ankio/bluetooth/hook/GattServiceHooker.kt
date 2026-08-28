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
import java.util.regex.Pattern

/**
 * 本机模拟：Android 17+ 通过 le_scan.ScanController.onScanResultInternal 注入伪造 BLE 扫描结果。
 * 优化版本：减少重复代码，提高性能和可维护性
 */
class GattServiceHooker : PartHooker() {

    override fun hook() {
        val mode = readPref(PrefKeys.SIMULATE_MODE, "")
        if (mode != SimulateMode.Self.toString()) {
            log("Local BLE simulation disabled, mode=[$mode]")
            return
        }

        log("Local BLE simulation started (ScanController)")

        val scanControllerClass = try {
            Hooker.loader(SCAN_CONTROLLER)
        } catch (e: Throwable) {
            log("ScanController not found: ${e.message}")
            return
        }

        // 钩子构造方法
        hookConstructors(scanControllerClass)
        
        // 钩子其他方法
        hookMethods(scanControllerClass)

        // 启动注入循环
        getMainHandler()?.post(injectRunnable)
    }

    /**
     * 钩子 ScanController 的所有构造方法
     */
    private fun hookConstructors(clazz: Class<*>) {
        try {
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    holdInstance(param.thisObject)
                }
            })
        } catch (e: Throwable) {
            log("hookAllConstructors failed: ${e.message}")
        }
    }

    /**
     * 钩子 ScanController 的特定方法
     */
    private fun hookMethods(clazz: Class<*>) {
        val methodNames = listOf("start", "startLocked", "init", "onStart")
        for (name in methodNames) {
            try {
                if (clazz.declaredMethods.none { it.name == name }) continue
                Hooker.after(clazz, name) { param ->
                    holdInstance(param.thisObject)
                }
            } catch (e: Throwable) {
                log("hookMethod[$name] failed: ${e.message}")
            }
        }
    }

    /**
     * 持有 ScanController 实例
     */
    private fun holdInstance(obj: Any?) {
        obj?.let {
            if (scanControllerRef.get() !== it) {
                scanControllerRef.set(it)
                log("Held ScanController instance")
                resolveMethod(it)
            }
        }
    }

    /**
     * 查找目标方法
     */
    private fun resolveMethod(obj: Any) {
        val method = findScanMethod(obj.javaClass)
        if (method != null) {
            scanMethodRef.set(method)
            log("Found ${method.name} with ${method.parameterTypes.size} parameters")
        } else {
            log("No onScanResult method found on ScanController")
        }
    }

    /**
     * 查找 ScanController 中的扫描结果方法
     */
    private fun findScanMethod(clazz: Class<*>): Method? {
        return clazz.declaredMethods
            .filter { it.name == "onScanResultInternal" || it.name == "onScanResult" }
            .maxByOrNull { it.parameterTypes.size }
    }

    /**
     * 注入 Runnable
     */
    private val injectRunnable = object : Runnable {
        private var tick = 0
        private var cachedMac = ""
        private var cachedRssi = -50
        private var cachedData = ByteArray(0)
        private var lastConfigHash = 0

        override fun run() {
            val target = scanControllerRef.get()
            val method = scanMethodRef.get()
            
            if (target == null || method == null) {
                // 如果还没有实例，稍后重试
                getMainHandler()?.postDelayed(this, INTERVAL_MS)
                return
            }

            try {
                // 读取配置
                val mac = readPref(PrefKeys.PREF_MAC, DEFAULT_MAC)
                val rssi = readPref(PrefKeys.PREF_RSSI, DEFAULT_RSSI).toIntOrNull() ?: DEFAULT_RSSI
                val data = parseAdvData()

                // 计算配置哈希，检测是否变化
                val configHash = mac.hashCode() + rssi + data.contentHashCode()
                
                // 只在配置变化时更新缓存
                if (configHash != lastConfigHash) {
                    cachedMac = mac
                    cachedRssi = rssi
                    cachedData = data
                    lastConfigHash = configHash
                    log("Config updated: mac=$mac, rssi=$rssi, dataLen=${data.size}")
                }

                // 定期日志
                if (tick % 30 == 0) {
                    log("Injecting: mac=${cachedMac}, rssi=${cachedRssi}, dataLen=${cachedData.size}, tick=$tick")
                }
                tick++

                // 执行注入
                invokeScanResult(target, method, cachedMac, cachedRssi, cachedData)
                
            } catch (e: Throwable) {
                log("Injection error: ${e.javaClass.name}: ${e.message}")
            }

            // 继续循环
            getMainHandler()?.postDelayed(this, INTERVAL_MS)
        }

        /**
         * 解析广告数据
         */
        private fun parseAdvData(): ByteArray {
            val dataHex = readPref(PrefKeys.PREF_DATA, DEFAULT_ADV_DATA)
            return try {
                ByteUtils.hexStringToBytes(dataHex)
            } catch (e: Throwable) {
                log("Failed to parse hex data: ${e.message}, using default")
                ByteUtils.hexStringToBytes(DEFAULT_ADV_DATA)
            }
        }
    }

    /**
     * 执行扫描结果注入
     */
    private fun invokeScanResult(
        target: Any,
        method: Method,
        mac: String,
        rssi: Int,
        advData: ByteArray
    ) {
        // 参数验证
        if (!isValidMac(mac)) {
            log("Invalid MAC address: $mac")
            return
        }
        if (rssi !in -100..0) {
            log("Invalid RSSI: $rssi, should be between -100 and 0")
            return
        }
        if (advData.isEmpty()) {
            log("Advertisement data is empty")
            return
        }

        val paramCount = method.parameterTypes.size
        try {
            when (paramCount) {
                // 根据参数数量调用不同的方法签名
                11 -> {
                    XposedHelpers.callMethod(
                        target,
                        method.name,
                        0x1b,  // 未知参数
                        0x00,  // 未知参数
                        mac,   // MAC地址
                        0x01,  // 地址类型
                        0x00,  // 未知参数
                        0xff,  // 未知参数
                        0x7f,  // 未知参数
                        rssi,  // RSSI
                        0x00,  // 未知参数
                        advData, // 广告数据
                        mac    // 再次传入MAC
                    )
                }
                10 -> {
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
                        advData
                    )
                }
                else -> {
                    log("Unsupported method parameter count: $paramCount")
                }
            }
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            log("Inject failed: ${cause.javaClass.name}: ${cause.message}")
        }
    }

    /**
     * 验证 MAC 地址格式
     */
    private fun isValidMac(mac: String): Boolean {
        return MAC_PATTERN.matcher(mac).matches()
    }

    /**
     * 获取主线程 Handler
     */
    private fun getMainHandler(): Handler? {
        mainHandler?.let { return it }
        val looper = Looper.getMainLooper() ?: return null
        return Handler(looper).also { mainHandler = it }
    }

    /**
     * 读取配置项，支持多种来源
     */
    private fun readPref(key: String, default: String): String {
        // 尝试从 HookConfig 读取
        try {
            HookConfig.getString(key, default)?.takeIf { it.isNotEmpty() }?.let {
                return it
            }
        } catch (_: Throwable) {}

        // 尝试从 SharedPreferences 读取
        val prefNames = listOf(
            MODULE_PACKAGE,
            "${MODULE_PACKAGE}_preferences",
            "config",
            "settings",
            "bluetooth"
        )
        
        for (name in prefNames) {
            try {
                val xp = XSharedPreferences(MODULE_PACKAGE, name)
                xp.reload()
                xp.getString(key, null)?.takeIf { it.isNotEmpty() }?.let {
                    return it
                }
            } catch (_: Throwable) {}
        }

        return default
    }

    companion object {
        private const val TAG = "BluetoothDebug"
        private const val SCAN_CONTROLLER = "com.android.bluetooth.le_scan.ScanController"
        private const val MODULE_PACKAGE = "net.ankio.bluetooth"
        private const val INTERVAL_MS = 500L
        private const val DEFAULT_MAC = "76:A7:8A:67:66:C9"
        private const val DEFAULT_RSSI = -50
        private const val DEFAULT_ADV_DATA = "02010403033CFE17FF0001B500024271A7B6000000C983926CB1011000000000000000000000000000000000000000000000000000000000000000000000"

        private val scanControllerRef = AtomicReference<Any?>(null)
        private val scanMethodRef = AtomicReference<Method?>(null)
        private val MAC_PATTERN = Pattern.compile("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

        @Volatile
        private var mainHandler: Handler? = null

        /**
         * 日志辅助方法
         */
        private fun log(message: String) {
            XposedBridge.log("$TAG: $message")
        }
    }
}