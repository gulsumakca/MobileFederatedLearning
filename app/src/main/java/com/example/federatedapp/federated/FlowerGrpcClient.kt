package com.example.federatedapp.federated

import android.util.Log
import com.example.federatedapp.federated.GrpcProtoCodec.decodeFitResponse
import com.example.federatedapp.federated.GrpcProtoCodec.decodeModelResponse
import com.example.federatedapp.federated.GrpcProtoCodec.encodeEmpty
import com.example.federatedapp.federated.GrpcProtoCodec.encodeFitRequest
import com.example.federatedapp.federated.GrpcProtoCodec.grpcUnwrap
import com.example.federatedapp.federated.GrpcProtoCodec.grpcWrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Federated Learning gRPC istemcisi.
 *
 * Gerçek gRPC protokolü (HTTP/2 + protobuf binary) — Gradle plugin gerektirmez.
 * Transport: OkHttp 4 + Protocol.H2_PRIOR_KNOWLEDGE (cleartext HTTP/2, h2c)
 * Kodlama: GrpcProtoCodec (minimal elle yazılmış protobuf serializer)
 *
 * Sunucu: fl_server/grpc_server.py (port 8080)
 * Android emülatörü → 10.0.2.2:8080
 * Gerçek cihaz      → <PC-IP>:8080
 */
class FlowerGrpcClient(private var serverAddress: String = "10.0.2.2:8080") {

    companion object {
        private const val TAG = "FlowerGrpcClient"
        private const val SERVICE = "flservice.FLService"
        private val GRPC_MEDIA = "application/grpc".toMediaType()
    }

    data class FitResponse(
        val globalWeights: Map<String, Float>,
        val round: Int
    )

    private var host = "10.0.2.2"
    private var port = 8080
    private var httpClient: OkHttpClient = buildClient()

    init {
        parseAndApplyAddress(serverAddress)
    }

    // ── Connection management ─────────────────────────────────────────────────

    fun connect(address: String) {
        serverAddress = address
        parseAndApplyAddress(address)
        httpClient = buildClient()
        Log.d(TAG, "gRPC bağlantısı güncellendi: $host:$port")
    }

    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    // ── FL API ────────────────────────────────────────────────────────────────

    /**
     * Yerel ağırlıkları sunucuya gönder → FedAvg → global modeli al.
     * null dönerse sunucu erişilemez (yerel modelle devam et).
     */
    // Yerel ağırlıkları protobuf'a kodlayıp sunucuya gönderir, FedAvg global modelini çözer.
    suspend fun fit(localWeights: Map<String, Float>, numSamples: Int): FitResponse? =
        withContext(Dispatchers.IO) {
            try {
                val weights = CategoryPreferenceFlowerClient.KNOWN_CATEGORIES
                    .map { localWeights[it] ?: 0f }
                val payload = grpcWrap(encodeFitRequest(weights, numSamples))

                val request = grpcRequest("Fit", payload)
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "fit() HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body?.bytes() ?: return@withContext null
                val decoded = decodeFitResponse(grpcUnwrap(body))

                val globalMap = decoded.categories.zip(decoded.globalWeights)
                    .associate { (cat, w) -> cat to w }

                Log.d(TAG, "fit() round=${decoded.round} top=${globalMap.maxByOrNull { it.value }?.key}")
                FitResponse(globalWeights = globalMap, round = decoded.round)

            } catch (e: Exception) {
                Log.w(TAG, "fit() hatası (sunucu kapalı?): ${e.message}")
                null
            }
        }

    /**
     * Mevcut global modeli çek (soğuk başlatma senkronizasyonu).
     */
    suspend fun getGlobalModel(): Map<String, Float>? = withContext(Dispatchers.IO) {
        try {
            val payload = grpcWrap(encodeEmpty())
            val request = grpcRequest("GetModel", payload)
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) return@withContext null

            val body = response.body?.bytes() ?: return@withContext null
            val decoded = decodeModelResponse(grpcUnwrap(body))

            decoded.categories.zip(decoded.weights).associate { (cat, w) -> cat to w }
                .also { Log.d(TAG, "getGlobalModel() → ${it.size} kategori") }

        } catch (e: Exception) {
            Log.w(TAG, "getGlobalModel() hatası: ${e.message}")
            null
        }
    }

    /**
     * Sunucunun ayakta olup olmadığını kontrol et.
     */
    suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = grpcWrap(encodeEmpty())
            val request = grpcRequest("GetStatus", payload)
            httpClient.newCall(request).execute().isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private fun grpcRequest(method: String, body: ByteArray): Request =
        Request.Builder()
            .url("http://$host:$port/$SERVICE/$method")
            .post(body.toRequestBody(GRPC_MEDIA))
            .header("content-type", "application/grpc")
            .header("te", "trailers")
            .build()

    private fun buildClient(): OkHttpClient =
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    private fun parseAndApplyAddress(address: String) {
        val cleaned = address.removePrefix("http://").removePrefix("https://").trim()
        val colonIdx = cleaned.lastIndexOf(':')
        if (colonIdx > 0) {
            host = cleaned.substring(0, colonIdx)
            port = cleaned.substring(colonIdx + 1).toIntOrNull() ?: 8080
        } else {
            host = cleaned
            port = 8080
        }
    }
}
