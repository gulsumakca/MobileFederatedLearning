package com.example.federatedapp.repository

/*
 * NewsRepository — Veri deposu (repository deseni); veri kaynaklarını tek noktada toplar.
 * - getNews()           : RSS'ten haber çeker (başarısızsa gömülü örnek haberlere düşer)
 * - trackInteraction()  : bir tıklamayı veritabanına kaydeder
 * - getPersonalizationWeights(): fit()'in basit versiyonu (sadece zaman sönümü, gradient yok).
 *   Ana akış bunu değil CategoryPreferenceFlowerClient'ı kullanır — bu metot yedek/eski yol.
 */

import com.example.federatedapp.data.UserInteraction
import com.example.federatedapp.data.UserInteractionDao
import com.example.federatedapp.model.NewsArticle
import com.example.federatedapp.network.RssFetcher
import kotlin.math.exp
import kotlin.math.ln

class NewsRepository(private val userInteractionDao: UserInteractionDao) {

    private val fallbackNews = listOf(
        NewsArticle("1", "Yeni Nesil İşlemciler Tanıtıldı", "Teknoloji", "Dev şirket yeni işlemci mimarisini duyurdu..."),
        NewsArticle("2", "Mars'ta Su Kalıntıları Bulundu", "Bilim", "NASA'nın yeni keşfi heyecan yarattı..."),
        NewsArticle("3", "Şampiyonlar Ligi Finali Heyecanı", "Spor", "Hafta sonu oynanacak maç için hazırlıklar tamam..."),
        NewsArticle("4", "Yapay Zeka Sanatı Nasıl Değiştiriyor?", "Teknoloji", "AI algoritmaları resim ve müzik dünyasında..."),
        NewsArticle("5", "Akdeniz'de Yeni Bir Tür Keşfedildi", "Doğa", "Deniz biyologları daha önce görülmemiş bir tür buldu..."),
        NewsArticle("6", "Borsa İstanbul'da Rekor Artış", "Ekonomi", "Güne yükselişle başlayan piyasalar kapanışta da güçlü seyretti..."),
        NewsArticle("7", "Yeni Tesla Modeli Sızdırıldı", "Teknoloji", "Elon Musk'ın yeni sürprizi yollarda görüntülendi..."),
        NewsArticle("8", "Sürdürülebilir Tarım Teknikleri", "Doğa", "Geleceğin tarımı bugünden şekilleniyor..."),
        NewsArticle("9", "Kuantum Bilgisayarlarda Devrim", "Bilim", "Bilgi işlem hızı katlanarak artıyor..."),
        NewsArticle("10", "NBA'de Rekor Kırıldı", "Spor", "Genç yıldız bir maçta 60 sayı attı...")
    )

    // RSS'ten haber çeker; boş dönerse gömülü örnek haberlere düşer (çevrimdışı yedek).
    suspend fun getNews(sourceLimits: Map<String, Int>): List<NewsArticle> {
        return try {
            val rssNews = RssFetcher.fetchAllNews(sourceLimits)
            if (rssNews.isNotEmpty()) rssNews else fallbackNews
        } catch (_: Exception) {
            fallbackNews
        }
    }

    // Bir tıklamayı (kategori + okuma süresi) veritabanına kaydeder.
    suspend fun trackInteraction(category: String, duration: Long) {
        userInteractionDao.insertInteraction(
            UserInteraction(category = category, duration = duration)
        )
    }

    suspend fun getPersonalizationWeights(): Map<String, Float> {
        val interactions = userInteractionDao.getAllInteractions()
        if (interactions.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        val halfLifeMs = 3L * 24 * 60 * 60 * 1000

        val scores = mutableMapOf<String, Float>()
        interactions.forEach { interaction ->
            val ageMs = (now - interaction.timestamp).coerceAtLeast(0L)
            val decay = exp(-ln(2.0) * ageMs.toDouble() / halfLifeMs).toFloat()
            scores[interaction.category] = (scores[interaction.category] ?: 0f) + decay
        }

        val total = scores.values.sum()
        if (total == 0f) return emptyMap()

        return scores
            .mapValues { it.value / total }
            .entries
            .sortedByDescending { it.value }
            .associate { it.key to it.value }
    }
}
