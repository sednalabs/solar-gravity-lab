package com.sednalabs.solarlab.benchmark

import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject

@Keep
object NativeBridge {
    val loadFailure: String? = load("solarlab_v2")
    external fun nativeCatalogue(): String
    external fun nativeRunCase(caseId: String, fixtureId: String, steps: Int, repeats: Int): String

    fun catalogue(): JSONObject = nativeJson(loadFailure) { nativeCatalogue() }
    fun run(caseId: String, fixtureId: String, steps: Int, repeats: Int = 1): JSONObject =
        nativeJson(loadFailure) { nativeRunCase(caseId, fixtureId, steps, repeats) }
}

@Keep
object NativeHardwareBench {
    val loadFailure: String? = load("solarlab_vulkan")
    external fun nativeCapabilities(): String
    external fun nativeRunCase(caseId: String, iterations: Int): String
    external fun nativePlacement(mode: String): String
    external fun nativeThreadInfo(): String

    fun catalogue(): JSONObject = nativeJson(loadFailure) { nativeCapabilities() }
    fun run(caseId: String, iterations: Int = 1): JSONObject = nativeJson(loadFailure) { nativeRunCase(caseId, iterations) }
    fun placement(mode: String): JSONObject = nativeJson(loadFailure) { nativePlacement(mode) }
    fun threadInfo(): JSONObject = nativeJson(loadFailure) { nativeThreadInfo() }
}

private fun load(name: String): String? = try {
    System.loadLibrary(name)
    null
} catch (error: LinkageError) {
    "${error.javaClass.simpleName}: ${error.message}"
} catch (error: SecurityException) {
    "${error.javaClass.simpleName}: ${error.message}"
}

private fun nativeJson(loadFailure: String?, call: () -> String): JSONObject {
    if (loadFailure != null) return failure("SKIP", "native_library_unavailable", loadFailure)
    return try {
        JSONObject(call())
    } catch (error: LinkageError) {
        failure("SKIP", "native_entrypoint_unavailable", error.message.orEmpty())
    } catch (error: Exception) {
        failure("FAIL", "native_response_error", "${error.javaClass.simpleName}: ${error.message}")
    }
}

internal fun failure(status: String, reason: String, detail: String): JSONObject = JSONObject()
    .put("schema_version", 1).put("status", status).put("reason", reason).put("detail", detail)

internal fun parseCases(catalogue: JSONObject, backend: String): List<BenchmarkCase> {
    val entries = catalogue.optJSONArray("cases") ?: return emptyList()
    return (0 until entries.length()).mapNotNull { index ->
        val item = entries.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val fixtures = item.optJSONArray("fixtures")?.strings().orEmpty()
            .ifEmpty { if (backend == "hardware") listOf("native-fixed") else listOf("sparse3") }
        BenchmarkCase(
            id = id,
            label = item.optString("label", id),
            family = item.optString("family", backend),
            status = if (item.has("available") && !item.optBoolean("available")) "unsupported" else item.optString("status", "not_implemented"),
            reason = item.optString("reason", ""),
            backend = backend,
            baselineId = item.optString("baseline_case_id").takeIf { it.isNotBlank() && it != "null" },
            fixtures = fixtures,
        )
    }.distinctBy(BenchmarkCase::key)
}

internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

/** Fail closed: wall time around JNI is never substituted for a missing kernel measurement. */
internal fun validatedMeasurement(result: JSONObject): Pair<String, Long?> {
    val status = result.optString("status", "FAIL").uppercase()
    if (status !in setOf("PASS", "SKIP", "FAIL")) return "FAIL" to null
    if (status != "PASS") return status to null
    val elapsed = result.optDouble("elapsed_ns", Double.NaN)
    return if (elapsed.isFinite() && elapsed >= 0.0 && elapsed < Long.MAX_VALUE.toDouble()) {
        "PASS" to elapsed.toLong()
    } else {
        "FAIL" to null
    }
}
