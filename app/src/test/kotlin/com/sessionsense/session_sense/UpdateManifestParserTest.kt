package com.sessionsense.session_sense

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestParserTest {
    private val sha = "a".repeat(64)
    private fun json(vararg overrides: Pair<String, Any?>, drop: Set<String> = emptySet()): String {
        val o = JSONObject()
            .put("versionCode", 1_002_003).put("versionName", "1.2.3")
            .put("apkUrl", "https://github.com/DevxD98/sessionsense/releases/download/v1.2.3/sessionsense-1.2.3.apk")
            .put("sha256", sha).put("sizeBytes", 12_345_678).put("minSupportedVersionCode", 1_000_000)
            .put("notes", "- Faster widgets\n- Fixes").put("publishedAt", "2026-09-27T10:00:00Z")
        overrides.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        drop.forEach { o.remove(it) }
        return o.toString()
    }
    private fun ok(body: String) = (UpdateManifestParser.parse(body) as ManifestResult.Ok).manifest
    private fun invalid(body: String) = UpdateManifestParser.parse(body) as ManifestResult.Invalid

    @Test fun valid() {
        val m = ok(json())
        assertEquals(1_002_003, m.versionCode); assertEquals("1.2.3", m.versionName)
        assertEquals(12_345_678L, m.sizeBytes); assertEquals(1_000_000, m.minSupportedVersionCode)
        assertEquals("- Faster widgets\n- Fixes", m.notes); assertEquals("2026-09-27T10:00:00Z", m.publishedAt)
    }

    @Test fun roundTripsThroughToJson() { val m = ok(json()); assertEquals(m, ok(m.toJson())) }

    @Test fun shaIsNormalisedToLowercase() = assertEquals("ab".repeat(32), ok(json("sha256" to "AB".repeat(32))).sha256)

    @Test fun optionalFieldsDefault() {
        val m = ok(json(drop = setOf("minSupportedVersionCode", "notes", "publishedAt")))
        assertEquals(0, m.minSupportedVersionCode); assertEquals("", m.notes); assertEquals("", m.publishedAt)
    }

    @Test fun missingRequiredFields() {
        listOf("versionCode", "versionName", "apkUrl", "sha256", "sizeBytes").forEach { field ->
            assertTrue(field, invalid(json(drop = setOf(field))).reason.contains(field))
        }
    }

    @Test fun wrongTypesAndValues() {
        assertTrue(invalid(json("versionCode" to "1002003")).reason.contains("versionCode"))
        assertTrue(invalid(json("versionCode" to 1.5)).reason.contains("versionCode"))
        assertTrue(invalid(json("versionCode" to 0)).reason.contains("versionCode"))
        assertTrue(invalid(json("versionName" to "1.2")).reason.contains("versionName"))
        assertTrue(invalid(json("versionName" to "v1.2.3")).reason.contains("versionName"))
        assertTrue(invalid(json("sha256" to "abc")).reason.contains("sha256"))
        assertTrue(invalid(json("sha256" to "g".repeat(64))).reason.contains("sha256"))
        assertTrue(invalid(json("sizeBytes" to 0)).reason.contains("sizeBytes"))
        assertTrue(invalid(json("minSupportedVersionCode" to -1)).reason.contains("minSupportedVersionCode"))
        assertTrue(invalid(json("minSupportedVersionCode" to null)).reason.contains("minSupportedVersionCode"))
    }

    @Test fun codeMustMatchName() = assertTrue(invalid(json("versionCode" to 1_002_004)).reason.contains("does not match"))

    @Test fun apkUrlMustBeHttpsGitHub() {
        listOf(
            "http://github.com/DevxD98/sessionsense/releases/download/v1.2.3/a.apk",
            "https://evil.example/a.apk",
            "https://github.com.evil.example/a.apk",
            "https://user@github.com/a.apk",
            "https://github.com:8443/a.apk",
            "file:///sdcard/a.apk",
            "not a url",
        ).forEach { assertTrue(it, invalid(json("apkUrl" to it)).reason.contains("apkUrl")) }
    }

    @Test fun malformed() {
        listOf("", "{", "[]", "null", "\"update\"", "<html>Not Found</html>", "{\"versionCode\":").forEach {
            assertTrue(it, UpdateManifestParser.parse(it) is ManifestResult.Invalid)
        }
    }

    @Test fun olderVersionParsesButIsNotAnUpdate() {
        val m = ok(json("versionCode" to 900_000, "versionName" to "0.900.0", "minSupportedVersionCode" to 0))
        assertEquals(UpdateStatus.UpToDate, UpdatePolicy.status(installedCode = 1_000_000, manifest = m, skippedCode = 0))
    }
}
