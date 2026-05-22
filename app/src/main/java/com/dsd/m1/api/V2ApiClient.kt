package com.dsd.m1.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log


class V2ApiClient(
    private val baseUrl: String = "https://dsd2026-teamv2-production.up.railway.app"
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String) = "$baseUrl$path"

    private fun jsonBody(map: Map<String, Any?>): RequestBody {
        return gson.toJson(map).toRequestBody(JSON_MEDIA)
    }

    private fun parseResponse(response: Response): Map<String, Any?> {
        val body = response.body?.string() ?: "{}"
        if (!response.isSuccessful) {
            val err = try { gson.fromJson(body, JsonObject::class.java).get("error")?.asString } catch (_: Exception) { null }
            val message = err ?: "HTTP ${response.code}"
            Log.e("V2ApiClient", "Request failed: $message, responseBody: $body")
            throw RuntimeException(err ?: "HTTP ${response.code}")
        }
        return gson.fromJson(body, object : TypeToken<Map<String, Any?>>() {}.type)
    }

    private fun parseListResponse(response: Response): List<Map<String, Any?>> {
        val body = response.body?.string() ?: "[]"
        if (!response.isSuccessful) {
            val err = try { gson.fromJson(body, JsonObject::class.java).get("error")?.asString } catch (_: Exception) { null }
            val message = err ?: "HTTP ${response.code}"
            Log.e("V2ApiClient", "Request failed: $message, responseBody: $body")
            throw RuntimeException(err ?: "HTTP ${response.code}")
        }
        return gson.fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type)
    }

    fun register(name: String, email: String, password: String, role: String = "patient"): Map<String, Any?> {
        val req = Request.Builder().url(url("/auth/register"))
            .post(jsonBody(mapOf("name" to name, "email" to email, "password" to password, "role" to role)))
            .build()
        return parseResponse(client.newCall(req).execute())
    }

    fun login(email: String, password: String): Map<String, Any?> {
        val req = Request.Builder().url(url("/auth/login"))
            .post(jsonBody(mapOf("email" to email, "password" to password)))
            .build()
        return parseResponse(client.newCall(req).execute())
    }

    fun getMe(token: String): Map<String, Any?> {
        val req = Request.Builder().url(url("/auth/me"))
            .header("Authorization", "Bearer $token").get().build()
        return parseResponse(client.newCall(req).execute())
    }

    fun createSession(userId: Int, token: String): Map<String, Any?> {
        val req = Request.Builder().url(url("/sessions"))
            .header("Authorization", "Bearer $token")
            .post(jsonBody(mapOf("userId" to userId)))
            .build()
        return parseResponse(client.newCall(req).execute())
    }

    fun getSession(sessionId: Int, token: String): Map<String, Any?> {
        val req = Request.Builder().url(url("/sessions/$sessionId"))
            .header("Authorization", "Bearer $token").get().build()
        return parseResponse(client.newCall(req).execute())
    }

    fun endSession(sessionId: Int, token: String): Map<String, Any?> {
        val req = Request.Builder().url(url("/sessions/$sessionId/end"))
            .header("Authorization", "Bearer $token")
            .patch("".toRequestBody(JSON_MEDIA))
            .build()
        return parseResponse(client.newCall(req).execute())
    }

    fun uploadMeasurement(payload: Map<String, Any>, token: String): Map<String, Any?> {
        val req = Request.Builder().url(url("/measurements"))
            .header("Authorization", "Bearer $token")
            .post(gson.toJson(payload).toRequestBody(JSON_MEDIA))
            .build()
        return parseResponse(client.newCall(req).execute())
    }

    fun uploadMeasurementsBatch(sessionId: Int, measurements: List<Map<String, Any>>, token: String): Map<String, Any?> {
        val body = mapOf(
            "sessionId" to sessionId,
            "measurements" to measurements
        )
        val req = Request.Builder().url(url("/measurements/batch"))
            .header("Authorization", "Bearer $token")
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA))
            .build()
        return parseResponse(client.newCall(req).execute())
    }

    fun getMeasurements(sessionId: Int, token: String): List<Map<String, Any?>> {
        val req = Request.Builder().url(url("/measurements/$sessionId"))
            .header("Authorization", "Bearer $token").get().build()
        return parseListResponse(client.newCall(req).execute())
    }

    fun getSessionRecommendations(sessionId: Int, token: String): List<Map<String, Any?>> {
        val req = Request.Builder().url(url("/recommendations/session/$sessionId"))
            .header("Authorization", "Bearer $token").get().build()
        return parseListResponse(client.newCall(req).execute())
    }

    fun getEngineRecommendations(userId: Int, token: String): Map<String, Any?> {
        val req = Request.Builder().url(url("/recommendations/engine/$userId"))
            .header("Authorization", "Bearer $token").get().build()
        return parseResponse(client.newCall(req).execute())
    }

    fun getSchedule(userId: Int, token: String): List<Map<String, Any?>> {
        val req = Request.Builder().url(url("/schedule/$userId"))
            .header("Authorization", "Bearer $token").get().build()
        return parseListResponse(client.newCall(req).execute())
    }

    fun updateSchedule(scheduleId: Int, status: String, token: String): Map<String, Any?> {
        val req = Request.Builder().url(url("/schedule/$scheduleId"))
            .header("Authorization", "Bearer $token")
            .patch(jsonBody(mapOf("status" to status)))
            .build()
        return parseResponse(client.newCall(req).execute())
    }

    fun registerPushToken(userId: Int, deviceToken: String, platform: String, token: String): Map<String, Any?> {
        val req = Request.Builder().url(url("/push/register"))
            .header("Authorization", "Bearer $token")
            .post(jsonBody(mapOf("userId" to userId, "token" to deviceToken, "platform" to platform)))
            .build()
        return parseResponse(client.newCall(req).execute())
    }
}
