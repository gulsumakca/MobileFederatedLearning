package com.example.federatedapp.federated

/*
 * CategoryPreferenceFlowerClient — Yerel öğrenme motoru (Flower istemcisi).
 * fit() veritabanındaki tüm tıklamaları okur; her tıklama için örnek ağırlığı:
 *     ağırlık = decay × (1 + okuma_bonusu)
 *   - decay        : zaman sönümü (yarı ömür 3 gün — eski tıklama değer kaybeder)
 *   - okuma_bonusu : okuma süresine bağlı (uzun okuma = güçlü sinyal)
 * TFLite varsa her tıklama için gradient adımı (trainStep), yoksa saf Kotlin normalize.
 * Sonuç: her kategori için 0–1 arası tercih ağırlığı.
 */

import com.example.federatedapp.data.CategoryStat
import com.example.federatedapp.data.UserInteractionDao
import kotlin.math.exp
import kotlin.math.ln

class CategoryPreferenceFlowerClient(
    private val dao: UserInteractionDao,
    private val tfliteModel: TFLiteNewsModel? = null
) {

    companion object {
        val KNOWN_CATEGORIES = listOf(
            "Siyaset", "Ekonomi", "Spor", "Teknoloji",
            "Bilim", "Dünya", "Doğa", "Magazin", "Yaşam", "Gündem"
        )
        private const val HALF_LIFE_MS = 3L * 24 * 60 * 60 * 1000
        private const val LEARNING_RATE = 0.15f
    }

    private var fallbackWeights = FloatArray(KNOWN_CATEGORIES.size) { 0f }

    // Tüm tıklamaları okur, her birini zaman sönümü + okuma süresiyle ağırlıklandırır
    // ve modeli eğitir; kategori başına 0–1 arası tercih ağırlığı döndürür.
    suspend fun fit(): FitResult {
        val interactions = dao.getAllInteractions()
        if (interactions.isEmpty()) return FitResult(emptyMap(), 0)

        val now = System.currentTimeMillis()

        return if (tfliteModel?.isAvailable == true) {
            // TFLite path: her tıklama için gradient step
            interactions.forEach { interaction ->
                val catIdx = KNOWN_CATEGORIES.indexOf(interaction.category)
                if (catIdx < 0) return@forEach
                val ageMs = (now - interaction.timestamp).coerceAtLeast(0L)
                val decay = exp(-ln(2.0) * ageMs.toDouble() / HALF_LIFE_MS).toFloat()
                val readingBonus = (interaction.duration / 10_000f).coerceIn(0f, 2f)
                tfliteModel.trainStep(catIdx, LEARNING_RATE, decay * (1f + readingBonus))
            }
            val probs = tfliteModel.infer()
            val weightMap = KNOWN_CATEGORIES.zip(probs.toList())
                .filter { it.second > 0f }
                .sortedByDescending { it.second }
                .associate { it.first to it.second }
            FitResult(weightMap, interactions.size)
        } else {
            // Pure Kotlin fallback
            val rawScores = mutableMapOf<String, Float>()
            interactions.forEach { interaction ->
                val ageMs = (now - interaction.timestamp).coerceAtLeast(0L)
                val decay = exp(-ln(2.0) * ageMs.toDouble() / HALF_LIFE_MS).toFloat()
                val readingBonus = (interaction.duration / 10_000f).coerceIn(0f, 2f)
                rawScores[interaction.category] =
                    (rawScores[interaction.category] ?: 0f) + decay * (1f + readingBonus)
            }
            val total = rawScores.values.sum()
            if (total == 0f) return FitResult(emptyMap(), interactions.size)
            val normalized = rawScores.mapValues { it.value / total }
            KNOWN_CATEGORIES.forEachIndexed { i, cat -> fallbackWeights[i] = normalized[cat] ?: 0f }
            FitResult(
                normalized.entries.sortedByDescending { it.value }.associate { it.key to it.value },
                interactions.size
            )
        }
    }

    // Mevcut model ağırlıklarını döndürür (sunucuya gönderilecek olan).
    fun getParameters(): FloatArray =
        if (tfliteModel?.isAvailable == true) tfliteModel.getWeights() else fallbackWeights.copyOf()

    // Sunucudan gelen global ağırlıkları yerel modele yazar (FedAvg sonucu).
    fun setParameters(params: FloatArray) {
        if (tfliteModel?.isAvailable == true) tfliteModel.setWeights(params)
        else params.copyInto(fallbackWeights, endIndex = minOf(params.size, fallbackWeights.size))
    }

    suspend fun getCategoryStats(): List<CategoryStat> = dao.getCategoryStats()

    data class FitResult(val weights: Map<String, Float>, val numSamples: Int)
}
