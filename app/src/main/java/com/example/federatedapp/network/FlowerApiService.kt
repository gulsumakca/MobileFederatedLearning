package com.example.federatedapp.network

import android.util.Log
import com.example.federatedapp.federated.CategoryPreferenceFlowerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object FlowerApiService {

    // Android emülatörü → 10.0.2.2 (host machine localhost)
    // Gerçek cihaz     → bilgisayarınızın yerel IP'si
    var serverUrl = "http://10.0.2.2:5000"

    private val JSON_MEDIA = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class FitResponse(
        val globalWeights: Map<String, Float>,
        val round: Int
    )

    /**
     * Yerel ağırlıkları sunucuya gönder → FedAvg → global modeli al.
     * Sunucu erişilemezse null döner (istemci yerel modelle devam eder).
     */
    suspend fun fit(
        localWeights: Map<String, Float>,
        numSamples: Int
    ): FitResponse? = withContext(Dispatchers.IO) {
        try {
            val weightsArray = JSONArray().apply {
                CategoryPreferenceFlowerClient.KNOWN_CATEGORIES.forEach { cat ->
                    put(localWeights[cat]?.toDouble() ?: 0.0)
                }
            }
            val body = JSONObject().apply {
                put("weights", weightsArray)
                put("num_samples", numSamples)
            }

            val request = Request.Builder()
                .url("$serverUrl/fl/fit")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("FlowerApi", "fit HTTP ${response.code}")
                return@withContext null
            }

            val json = JSONObject(response.body!!.string())
            val globalArr = json.getJSONArray("global_weights")
            val cats = json.getJSONArray("categories")
            val round = json.optInt("round", 1)

            val globalWeights = (0 until cats.length()).associate { i ->
                cats.getString(i) to globalArr.getDouble(i).toFloat()
            }

            Log.d("FlowerApi", "fit round=$round globalTop=${globalWeights.maxByOrNull { it.value }?.key}")
            FitResponse(globalWeights, round)
        } catch (e: Exception) {
            Log.w("FlowerApi", "fit failed (sunucu kapalı?): ${e.message}")
            null
        }
    }

    /** Sunucunun ayakta olup olmadığını kontrol et */
    suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$serverUrl/fl/status").get().build()
            client.newCall(request).execute().isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    /** Mevcut global modeli çek (ilk açılışta model senkronizasyonu için) */
    suspend fun getGlobalModel(): Map<String, Float>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$serverUrl/fl/model").get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body!!.string())
            val weights = json.getJSONArray("weights")
            val cats = json.getJSONArray("categories")

            (0 until cats.length()).associate { i ->
                cats.getString(i) to weights.getDouble(i).toFloat()
            }
        } catch (_: Exception) {
            null
        }
    }
}
