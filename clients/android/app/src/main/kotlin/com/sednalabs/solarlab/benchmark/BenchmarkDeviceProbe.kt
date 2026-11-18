package com.sednalabs.solarlab.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import com.sednalabs.solarlab.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.security.MessageDigest

class BenchmarkDeviceProbe(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private var lastHeadroomNs = Long.MIN_VALUE
    private var headroom: Any = JSONObject.NULL
    private var headroomStatus = "not_sampled"
    @Volatile private var lastEnvironment: String? = null

    fun cachedSample(): JSONObject {
        val captured = lastEnvironment ?: return sample()
        return JSONObject(captured).also {
            it.put("observation_age_ns", (SystemClock.elapsedRealtimeNanos() - it.optLong("monotonic_ns")).coerceAtLeast(0))
            it.put("observation_policy", "latest_periodic_sample; before_after_do_not_imply_two_independent_sensor_reads")
        }
    }

    fun identity(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("build_commit", BuildConfig.BUILD_COMMIT)
        .put("app_id", context.packageName)
        .put("app_version", BuildConfig.VERSION_NAME)
        .put("app_version_code", BuildConfig.VERSION_CODE)
        .put("app_build_type", BuildConfig.BUILD_TYPE)
        .put("apk_sha256", hashFile(context.applicationInfo.sourceDir))
        .put("split_apks", JSONArray(context.applicationInfo.splitSourceDirs?.map { JSONObject().put("name", File(it).name).put("sha256", hashFile(it)) }.orEmpty()))
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("product", Build.PRODUCT)
        .put("hardware", Build.HARDWARE)
        .put("soc_manufacturer", Build.SOC_MANUFACTURER)
        .put("soc_model", Build.SOC_MODEL)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        .put("os_release", Build.VERSION.RELEASE)
        .put("sdk_int", Build.VERSION.SDK_INT)
        .put("security_patch", Build.VERSION.SECURITY_PATCH)
        .put("firmware_fingerprint", Build.FINGERPRINT)
        .put("boot_elapsed_ns", SystemClock.elapsedRealtimeNanos())
        .put("clock", "elapsedRealtimeNanos includes suspend; native elapsed_ns has its separately declared clock")
        .put("source", "own_app_process; unprivileged public Android APIs and readable sysfs/procfs")
        .put("performance_hint_preferred_update_rate_ns", runCatching { context.getSystemService(android.os.PerformanceHintManager::class.java)?.preferredUpdateRateNanos }.getOrNull() ?: JSONObject.NULL)
        .put("privacy", "No serial, IMEI, account, location or automatic upload. Firmware fingerprint is device software identity.")
        .put("cpuset", selfCpuSet())
        .put("cpu_topology", topology())
        .put("displays", displays())
        .put("initial_environment", sample())

    fun thermalStatus(): Int = runCatching { power?.currentThermalStatus }.getOrNull() ?: -1

    @Synchronized
    fun sample(): JSONObject {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (lastHeadroomNs == Long.MIN_VALUE || nowNs - lastHeadroomNs >= 10_000_000_000L) {
            lastHeadroomNs = nowNs
            val value = runCatching { power?.getThermalHeadroom(0) }.getOrNull()
            headroom = value?.takeIf(Float::isFinite)?.toDouble() ?: JSONObject.NULL
            headroomStatus = if (headroom == JSONObject.NULL) "unavailable_or_rate_limited" else "available"
        }
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun integerProperty(id: Int): Any = runCatching { battery?.getIntProperty(id) }
            .getOrNull()?.takeUnless { it == Int.MIN_VALUE } ?: JSONObject.NULL
        fun longProperty(id: Int): Any = runCatching { battery?.getLongProperty(id) }
            .getOrNull()?.takeUnless { it == Long.MIN_VALUE } ?: JSONObject.NULL
        fun extra(name: String): Any = intent?.takeIf { it.hasExtra(name) }?.getIntExtra(name, -1) ?: JSONObject.NULL
        val current = integerProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val voltage = extra(BatteryManager.EXTRA_VOLTAGE)
        val estimatedPower = if (current is Int && voltage is Int && voltage > 0) {
            current.toDouble() * voltage / 1_000_000.0
        } else JSONObject.NULL
        return JSONObject()
            .put("monotonic_ns", nowNs)
            .put("wall_time_utc", Instant.now().toString())
            .put("thread_tid", Process.myTid())
            .put("thermal_status", thermalStatus())
            .put("thermal_status_unknown", -1)
            .put("thermal_headroom", headroom)
            .put("thermal_headroom_status", headroomStatus)
            .put("thermal_headroom_sample_ns", lastHeadroomNs)
            .put("battery_temperature_deci_c", extra(BatteryManager.EXTRA_TEMPERATURE))
            .put("battery_voltage_mv", voltage)
            .put("battery_current_now_ua", current)
            .put("battery_current_average_ua", integerProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))
            .put("battery_charge_counter_uah", integerProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            .put("battery_energy_counter_nwh", longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER))
            .put("battery_capacity_percent", integerProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("battery_status", extra(BatteryManager.EXTRA_STATUS))
            .put("battery_plugged", extra(BatteryManager.EXTRA_PLUGGED))
            .put("battery_signed_power_estimate_mw", estimatedPower)
            .put("battery_measurement_scope", "battery_terminal_including_display_radio_system_and_logger; not_cpu_gpu_power")
            .put("battery_current_sign", "Android nominal positive charging, negative discharging; raw driver sign preserved")
            .put("unavailable_values", "null; zero is preserved as a reported observation, not fabricated support")
            .put("power_save_mode", runCatching { power?.isPowerSaveMode }.getOrNull() ?: JSONObject.NULL)
            .put("cpu_frequency_khz", currentFrequencies())
            .put("display_refresh_hz", runCatching { context.getSystemService(DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate }.getOrNull() ?: JSONObject.NULL)
            .put("collection_duration_ns", SystemClock.elapsedRealtimeNanos() - nowNs)
            .also { lastEnvironment = it.toString() }
    }

    fun selfCpuSet(): JSONObject {
        val status = fileObservation("/proc/self/status")
        val text = status.optString("value", "")
        return JSONObject().put("scope", "self_app_process")
            .put("status", status.optString("status"))
            .put("allowed_cpus", text.lineSequence().firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim() ?: JSONObject.NULL)
            .put("allowed_memory_nodes", text.lineSequence().firstOrNull { it.startsWith("Mems_allowed_list:") }?.substringAfter(':')?.trim() ?: JSONObject.NULL)
            .put("self_cpuset", fileObservation("/proc/self/cpuset"))
            .put("self_cgroup", fileObservation("/proc/self/cgroup"))
            .put("status_read_error", status.opt("reason") ?: JSONObject.NULL)
    }

    private fun topology(): JSONObject = JSONObject()
        .put("online", fileObservation("/sys/devices/system/cpu/online"))
        .put("present", fileObservation("/sys/devices/system/cpu/present"))
        .put("kernel_page_size", runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrNull() ?: JSONObject.NULL)
        .put("cores", JSONArray(cpuDirectories().map { directory ->
            JSONObject().put("cpu", directory.name)
                .put("capacity", fileObservation("${directory.path}/cpu_capacity"))
                .put("cluster_id", fileObservation("${directory.path}/topology/cluster_id"))
                .put("core_id", fileObservation("${directory.path}/topology/core_id"))
                .put("package_id", fileObservation("${directory.path}/topology/physical_package_id"))
                .put("max_frequency_khz", fileObservation("${directory.path}/cpufreq/cpuinfo_max_freq"))
                .put("governor", fileObservation("${directory.path}/cpufreq/scaling_governor"))
                .put("caches", JSONArray(File(directory, "cache").listFiles()?.filter { it.name.matches(Regex("index[0-9]+")) }?.sortedBy(File::getName)?.map { cache ->
                    JSONObject().put("index", cache.name).also { record ->
                        listOf("level", "type", "size", "coherency_line_size", "shared_cpu_list", "ways_of_associativity", "number_of_sets").forEach {
                            record.put(it, fileObservation("${cache.path}/$it"))
                        }
                    }
                }.orEmpty()))
        }))

    private fun currentFrequencies(): JSONArray = JSONArray(cpuDirectories().map {
        JSONObject().put("cpu", it.name).put("scaling_cur_freq", fileObservation("${it.path}/cpufreq/scaling_cur_freq"))
    })

    private fun cpuDirectories(): List<File> = File("/sys/devices/system/cpu").listFiles()
        ?.filter { it.isDirectory && it.name.matches(Regex("cpu[0-9]+")) }?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() }.orEmpty()

    private fun displays(): JSONArray = JSONArray(context.getSystemService(DisplayManager::class.java)?.displays?.map { display ->
        JSONObject().put("id", display.displayId).put("refresh_hz", display.refreshRate)
            .put("mode_id", display.mode.modeId)
            .put("modes", JSONArray(display.supportedModes.map {
                JSONObject().put("id", it.modeId).put("width_px", it.physicalWidth).put("height_px", it.physicalHeight).put("refresh_hz", it.refreshRate)
            }))
    }.orEmpty())

    private fun hashFile(path: String): Any = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }.getOrElse { JSONObject().put("status", "unavailable").put("reason", it.javaClass.simpleName) }

    private fun fileObservation(path: String): JSONObject = try {
        val file = File(path)
        if (!file.isFile) JSONObject().put("status", "unavailable").put("path", path).put("reason", "missing_or_inaccessible")
        else JSONObject().put("status", "available").put("path", path).put("value", file.inputStream().bufferedReader().use { it.readText().take(32_768).trim() })
    } catch (error: Exception) {
        JSONObject().put("status", "unavailable").put("path", path).put("reason", error.javaClass.simpleName)
    }
}

/** These observations are UI vsync callbacks, deliberately not represented as Vulkan frame timings. */
class BenchmarkFrameProbe : Choreographer.FrameCallback {
    private var enabled = false
    private var previous: Long? = null
    private val intervals = mutableListOf<Long>()
    fun start() { enabled = true; previous = null; Choreographer.getInstance().postFrameCallback(this) }
    fun stop() { enabled = false; Choreographer.getInstance().removeFrameCallback(this); previous = null }
    override fun doFrame(frameTimeNanos: Long) {
        if (!enabled) return
        synchronized(intervals) {
            previous?.let { if (intervals.size < 4096) intervals += frameTimeNanos - it }
            previous = frameTimeNanos
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
    fun drain(): JSONArray = synchronized(intervals) { JSONArray(intervals.toList()).also { intervals.clear() } }
}
