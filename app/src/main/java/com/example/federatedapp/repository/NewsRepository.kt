package com.example.federatedapp.repository

import com.example.federatedapp.data.UserInteraction
import com.example.federatedapp.data.UserInteractionDao
import com.example.federatedapp.model.NewsArticle
import kotlin.math.exp
import kotlin.math.ln

class NewsRepository(private val userInteractionDao: UserInteractionDao) {

    private val allNews = listOf(
        NewsArticle("1", "Yeni Nesil İşlemciler Tanıtıldı", "Teknoloji", "Dev şirket yeni işlemci mimarisini duyurdu..."),
        NewsArticle("2", "Mars'ta Su Kalıntıları Bulundu", "Bilim", "NASA'nın yeni keşfi heyecan yarattı..."),
        NewsArticle("3", "Şampiyonlar Ligi Finali Heyecanı", "Spor", "Hafta sonu oynanacak maç için hazırlıklar tamam..."),
        NewsArticle("4", "Yapay Zeka Sanatı Nasıl Değiştiriyor?", "Teknoloji", "AI algoritmaları resim ve müzik dünyasında..."),
        NewsArticle("5", "Akdeniz'de Yeni Bir Tür Keşfedildi", "Doğa", "Deniz biyologları daha önce görülmemiş..."),
        NewsArticle("6", "Borsa İstanbul'da Rekor Artış", "Ekonomi", "Güne yükselişle başlayan piyasalar..."),
        NewsArticle("7", "Yeni Tesla Modeli Sızdırıldı", "Teknoloji", "Elon Musk'ın yeni sürprizi yollarda..."),
        NewsArticle("8", "Sürdürülebilir Tarım Teknikleri", "Doğa", "Geleceğin tarımı bugünden şekilleniyor..."),
        NewsArticle("9", "Kuantum Bilgisayarlarda Devrim", "Bilim", "Bilgi işlem hızı katlanarak artıyor..."),
        NewsArticle("10", "NBA'de Rekor Kırıldı", "Spor", "Genç yıldız bir maçta 60 sayı attı..."),
        NewsArticle("11", "Enflasyon Beklentileri Açıklandı", "Ekonomi", "Merkez Bankası yeni raporunu sundu..."),
        NewsArticle("12", "Yağmur Ormanları Tehlike Altında", "Doğa", "Ormansızlaşma hızı korkutucu seviyeye ulaştı..."),
        NewsArticle("13", "iPhone 16 Pro Özellikleri", "Teknoloji", "Yeni kamera sistemi ve işlemci detayları..."),
        NewsArticle("14", "Genetik Mühendisliğinde Yeni Adım", "Bilim", "Hastalıkların tedavisinde yeni bir umut ışığı..."),
        NewsArticle("15", "Euro 2024 Hazırlıkları Sürüyor", "Spor", "Milli takımın kamp çalışmaları başladı..."),
        NewsArticle("16", "Kripto Para Piyasasında Son Durum", "Ekonomi", "Bitcoin fiyatlarında dalgalanma devam ediyor..."),
        NewsArticle("17", "Güneş Paneli Verimliliği Artıyor", "Teknoloji", "Yenilenebilir enerjide maliyetler düşüyor..."),
        NewsArticle("18", "Okyanus Tabanında Yeni Yaşam Formu", "Bilim", "Derin deniz araştırmaları sonuç verdi..."),
        NewsArticle("19", "Tenis Turnuvasında Sürpriz Sonuç", "Spor", "Favori isim ilk turda veda etti..."),
        NewsArticle("20", "Altın Fiyatları Zirveye Yaklaştı", "Ekonomi", "Yatırımcıların güvenli liman arayışı sürüyor..."),
        NewsArticle("21", "Elektrikli Uçaklar Gökyüzünde", "Teknoloji", "Havacılık sektöründe emisyon azaltma çabaları..."),
        NewsArticle("22", "Antarktika Buzulları Eriyor", "Doğa", "Küresel ısınmanın etkileri her geçen gün artıyor..."),
        NewsArticle("23", "Dünya Kupası Elemeleri", "Spor", "Kritik maçlar öncesi son taktikler..."),
        NewsArticle("24", "Yapay Zeka ile İlaç Geliştirme", "Bilim", "Tıpta yapay zeka kullanımı hız kazanıyor..."),
        NewsArticle("25", "Karbon Ayak İzi Nasıl Azaltılır?", "Doğa", "Bireysel önlemlerle gezegeni korumak mümkün...")
    )

    fun getNews(): List<NewsArticle> = allNews

    suspend fun trackInteraction(category: String, duration: Long) {
        userInteractionDao.insertInteraction(
            UserInteraction(category = category, duration = duration)
        )
    }

    /**
     * Zamansal çürüme (temporal decay) ile kişiselleştirme ağırlıkları hesaplar.
     *
     * Her etkileşim için: skor = e^(-ln2 * yaş_ms / yarıÖmür_ms)
     * → Bugünkü tıklama = 1.0 puan, yarı ömür kadar önce = 0.5 puan
     *
     * FL bağlamında bu, cihaz üzerinde çalışan "yerel model güncellemesi"ni temsil eder.
     */
    suspend fun getPersonalizationWeights(): Map<String, Float> {
        val interactions = userInteractionDao.getAllInteractions()
        if (interactions.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        // 3 günlük yarı ömür: son etkileşimler çok daha belirleyici olur
        val halfLifeMs = 3L * 24 * 60 * 60 * 1000

        val scores = mutableMapOf<String, Float>()
        interactions.forEach { interaction ->
            val ageMs = (now - interaction.timestamp).coerceAtLeast(0L)
            val decay = exp(-ln(2.0) * ageMs.toDouble() / halfLifeMs).toFloat()
            scores[interaction.category] = (scores[interaction.category] ?: 0f) + decay
        }

        val total = scores.values.sum()
        if (total == 0f) return emptyMap()

        // Ağırlıkları normalize et ve yüksekten düşüğe sırala
        return scores
            .mapValues { it.value / total }
            .entries
            .sortedByDescending { it.value }
            .associate { it.key to it.value }
    }
}
