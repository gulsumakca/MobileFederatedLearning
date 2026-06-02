package com.example.federatedapp.network

/*
 * RssFetcher — Haber çekme ve otomatik kategorilendirme.
 * 5 Türk kaynaktan (NTV, CNN Türk, Sabah, TRT Haber, Sözcü) canlı RSS indirir, XML ayrıştırır.
 * mapCategory: haberi 10 kategoriden birine atar — önce RSS etiketine bakar, yoksa
 *   başlık+özet metnini anahtar kelime sözlüğüyle tarayıp en çok eşleşen kategoriyi seçer.
 *   Hiç eşleşme yoksa güvenli varsayılan "Gündem". Modelin öğrendiği etiketler buradan üretilir.
 */

import android.util.Log
import android.util.Xml
import com.example.federatedapp.model.NewsArticle
import com.example.federatedapp.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object RssFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()
            chain.proceed(request)
        }
        .build()

    // Kaynak adı ile RSS URL eşleşmesi - SettingsRepository.SOURCES ile senkron olmalı
    val sources = listOf(
        "NTV"       to "https://www.ntv.com.tr/gundem.rss",
        "CNN Türk"  to "https://www.cnnturk.com/feed/rss/all/news",
        "Sabah"     to "https://www.sabah.com.tr/rss/anasayfa.xml",
        "TRT Haber" to "https://www.trthaber.com/sondakika_articles.rss",
        "Sözcü"     to "https://www.sozcu.com.tr/rss/"
    )

    private val idCounter = AtomicInteger(1000)

    // Tüm kaynakları sırayla gezer, her birinden limit kadar haber çekip birleştirir.
    suspend fun fetchAllNews(sourceLimits: Map<String, Int>): List<NewsArticle> = withContext(Dispatchers.IO) {
        val allArticles = mutableListOf<NewsArticle>()
        for ((name, url) in sources) {
            val limit = sourceLimits[name] ?: SettingsRepository.DEFAULT_LIMIT
            try {
                val articles = fetchFromSource(name, url, limit)
                Log.d("RssFetcher", "$name: ${articles.size} haber çekildi")
                allArticles.addAll(articles)
            } catch (e: Exception) {
                Log.e("RssFetcher", "$name başarısız: ${e.javaClass.simpleName} — ${e.message}")
            }
        }
        allArticles
    }

    private fun fetchFromSource(sourceName: String, url: String, limit: Int): List<NewsArticle> {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w("RssFetcher", "$sourceName HTTP ${response.code}")
            return emptyList()
        }
        val body = response.body ?: return emptyList()
        return body.byteStream().use { parseRss(it, sourceName) }.take(limit)
    }

    private fun parseRss(stream: InputStream, sourceName: String): List<NewsArticle> {
        val articles = mutableListOf<NewsArticle>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)

        var inItem = false
        var title = ""
        var description = ""
        var link = ""
        var category = ""
        var pubDate = ""
        var imageUrl = ""
        var guid = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name ?: ""
                    when {
                        tag == "item" || tag == "entry" -> inItem = true
                        inItem && tag == "title" -> title = safeNextText(parser)
                        inItem && tag == "description" -> description = cleanHtml(safeNextText(parser))
                        inItem && tag == "summary" -> if (description.isEmpty()) description = cleanHtml(safeNextText(parser))
                        inItem && tag == "link" -> {
                            val href = parser.getAttributeValue(null, "href")
                            link = href ?: safeNextText(parser)
                        }
                        inItem && tag == "guid" -> guid = safeNextText(parser)
                        inItem && tag == "category" -> if (category.isEmpty()) category = safeNextText(parser)
                        inItem && tag == "pubDate" -> pubDate = safeNextText(parser)
                        inItem && tag == "published" -> if (pubDate.isEmpty()) pubDate = safeNextText(parser)
                        inItem && tag == "enclosure" -> {
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            if (type.startsWith("image") && imageUrl.isEmpty()) {
                                imageUrl = parser.getAttributeValue(null, "url") ?: ""
                            }
                        }
                        inItem && (tag == "media:content" || tag == "media:thumbnail") -> {
                            if (imageUrl.isEmpty()) {
                                imageUrl = parser.getAttributeValue(null, "url") ?: ""
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    if ((tag == "item" || tag == "entry") && inItem) {
                        val resolvedLink = link.ifEmpty { guid }
                        if (title.isNotEmpty() && resolvedLink.isNotEmpty()) {
                            val mappedCategory = mapCategory(category, title, description)
                            val trimmedDesc = if (description.length > 220) description.take(220) + "…" else description
                            articles.add(
                                NewsArticle(
                                    id = "rss_${idCounter.getAndIncrement()}",
                                    title = title,
                                    category = mappedCategory,
                                    summary = trimmedDesc,
                                    url = resolvedLink,
                                    imageUrl = imageUrl.ifEmpty { null },
                                    publishedAt = pubDate.ifEmpty { null },
                                    source = sourceName
                                )
                            )
                        }
                        inItem = false
                        title = ""; description = ""; link = ""; category = ""; pubDate = ""; imageUrl = ""; guid = ""
                    }
                }
            }
            eventType = parser.next()
        }
        return articles
    }

    private fun safeNextText(parser: XmlPullParser): String {
        return try { parser.nextText().trim() } catch (_: Exception) { "" }
    }

    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ─── Kategori eşleştirme ─────────────────────────────────────────────────────

    // Haberi kategoriye atar: önce RSS etiketi, yoksa başlık+özet üzerinde anahtar
    // kelime puanlaması; en yüksek puanlı kategori kazanır, eşitlik/sıfırda "Gündem".
    private fun mapCategory(rssCategory: String, title: String, description: String): String {
        // 1. RSS etiketi varsa önce doğrudan bak
        if (rssCategory.isNotBlank()) {
            tagToCategory(rssCategory.lowercase().trim())?.let { return it }
        }

        // 2. Başlık + özet üzerinde skor sistemi:
        //    Her eşleşen anahtar kelime 1 puan; en yüksek puanlı kategori kazanır.
        //    Beraberlik veya sıfır puan → Gündem (güvenli varsayılan)
        val text = "$title $description".lowercase()
        val scores = KEYWORD_MAP.mapValues { (_, keywords) ->
            keywords.count { kw -> text.contains(kw) }
        }
        val best = scores.maxByOrNull { it.value }
        return if (best != null && best.value > 0) best.key else "Gündem"
    }

    // RSS kategori etiketi → uygulama kategorisi
    // Yalnızca tam ve belirsizlik taşımayan etiketler kabul edilir
    // RSS'in kendi kategori etiketini uygulama kategorisine eşler (belirsizse null).
    private fun tagToCategory(tag: String): String? = when {
        tag.contains("spor") || tag.contains("futbol") || tag.contains("basketbol") -> "Spor"
        tag.contains("ekonomi") || tag.contains("finans") || tag.contains("borsa") || tag.contains("piyasa") -> "Ekonomi"
        tag.contains("teknoloji") || tag.contains("bilişim") || tag.contains("dijital") -> "Teknoloji"
        tag.contains("bilim") || tag.contains("sağlık") || tag.contains("tıp") -> "Bilim"
        tag.contains("doğa") || tag.contains("çevre") || tag.contains("iklim") -> "Doğa"
        tag.contains("dünya") || tag.contains("uluslararası") -> "Dünya"
        tag.contains("magazin") || tag.contains("eğlence") || tag.contains("sinema") -> "Magazin"
        tag.contains("yaşam") || tag.contains("turizm") || tag.contains("gezi") -> "Yaşam"
        tag.contains("siyaset") || tag.contains("politika") || tag.contains("parti") -> "Siyaset"
        tag.contains("gündem") -> "Gündem"
        else -> null
    }

    // Anahtar kelime listeleri — tek ve kısa belirsiz kelimeler YOK:
    // "deniz" (isim olabilir), "altın" (soyad/isim), "para" (çok genel),
    // "araştırma" (her haberde geçer), "savaş" (deyim/mecaz), "bm" (kısaltma) gibi
    // kelimeler dışarıda bırakıldı; bunların yerine bağlamlı çok kelimeli ifadeler kullanıldı.
    private val KEYWORD_MAP: Map<String, List<String>> = mapOf(
        // Siyaset listedeki ilk kategori — isim/parti eşleşmesi kesin olduğu için
        // diğer kategorilerle beraberlik durumunda önce değerlendirilir
        "Siyaset" to listOf(
            // Partiler
            "ak parti", "akp", "chp", "mhp", "hdp", "dem parti", "iyi parti",
            "deva partisi", "gelecek partisi", "saadet partisi", "zafer partisi",
            // İsimler
            "erdoğan", "cumhurbaşkanı erdoğan",
            "ekrem imamoğlu", "imamoğlu",
            "özgür özel",
            "kemal kılıçdaroğlu", "kılıçdaroğlu",
            "devlet bahçeli", "bahçeli",
            "meral akşener", "akşener",
            "ali babacan", "ahmet davutoğlu",
            "pervin buldan", "mithat sancar",
            // Kurumlar ve terimler
            "cumhurbaşkanı", "tbmm", "meclis genel kurulu",
            "muhalefet partisi", "iktidar partisi",
            "hükümet açıkladı", "bakan açıkladı", "başbakan",
            "seçim kampanyası", "yerel seçim", "genel seçim", "referandum",
            "anayasa değişikliği", "kanun teklifi", "muhalefet lideri",
            "siyasi kriz", "koalisyon", "erken seçim"
        ),
        "Spor" to listOf(
            "fenerbahçe", "galatasaray", "beşiktaş", "trabzonspor", "başakşehir",
            "şampiyonlar ligi", "avrupa ligi", "süper lig", "tff",
            "dünya kupası", "olimpiyat oyunları", "formula 1", "motogp",
            "nba", "nfl", "euro 2024", "euro 2025", "milli takım",
            "basketbol maçı", "futbol maçı", "tenis turnuvası", "atletizm",
            "puan durumu", "maç sonucu", "gol attı", "transfer",
            "boks maçı", "güreş turnuvası", "yüzme şampiyonası", "bisiklet turu",
            "ski", "kayak yarışması", "halter", "tekvando"
        ),
        "Ekonomi" to listOf(
            "borsa istanbul", "bist", "merkez bankası",
            "enflasyon oranı", "faiz kararı", "faiz oranı",
            "döviz kuru", "dolar kuru", "euro kuru", "sterlin",
            "bütçe açığı", "cari açık", "büyüme oranı", "gsyih", "gsmh",
            "kripto para", "bitcoin", "ethereum", "blockchain",
            "ihracat rakamı", "ithalat rakamı", "ticaret dengesi",
            "ekonomik kriz", "resesyon", "işsizlik oranı",
            "hazine bonosu", "tahvil", "yatırım fonu",
            "imf", "dünya bankası", "vergi düzenlemesi", "sgk",
            "asgari ücret", "enflasyona karşı", "pahalılık"
        ),
        "Teknoloji" to listOf(
            "yapay zeka", "chatgpt", "gemini", "claude",
            "akıllı telefon", "iphone", "android",
            "apple", "samsung", "google", "microsoft",
            "siber saldırı", "veri ihlali", "fidye yazılımı",
            "sosyal medya", "tiktok", "instagram", "youtube",
            "elektrikli araç", "tesla", "otonom araç", "sürücüsüz",
            "uzay roketi", "uydu fırlatma", "drone",
            "bulut bilişim", "yazılım güncelleme", "uygulama",
            "çip üretimi", "yarı iletken", "gpu", "işlemci"
        ),
        "Bilim" to listOf(
            "bilimsel araştırma", "uzay keşfi", "nasa", "esa",
            "kanser tedavisi", "aşı geliştirme", "klinik araştırma",
            "gen terapisi", "dna analizi", "biyoteknoloji", "genomik",
            "kuantum bilgisayar", "nörobilim", "alzheimer", "parkinson",
            "tıbbi buluş", "cerrahi yöntem", "pandemi", "virüs mutasyon",
            "fizik deneyi", "kimyasal bileşik", "element keşfi",
            "karadelik", "teleskop", "mars görevi", "ay misyonu"
        ),
        "Doğa" to listOf(
            "iklim değişikliği", "küresel ısınma", "karbon emisyonu",
            "orman yangını", "doğal afet", "deprem büyüklüğü",
            "sel felaketi", "tsunami", "kasırga", "tayfun",
            "biyoçeşitlilik", "ekosistem", "nesli tükenmekte",
            "çevre kirliliği", "hava kirliliği", "plastik atık",
            "okyanus", "deniz kirliliği", "mercan resifi",
            "ozon tabakası", "yenilenebilir enerji", "rüzgar enerjisi",
            "güneş enerjisi", "karbon nötr", "sera gazı"
        ),
        "Dünya" to listOf(
            "ukrayna", "rusya saldırı", "gazze", "filistin",
            "nato zirvesi", "avrupa birliği", "birleşmiş milletler",
            "dış politika", "diplomatik ilişki", "yaptırım kararı",
            "abd başkanı", "beyaz saray", "kongre kararı",
            "ortadoğu", "suriye", "irak", "iran nükleer",
            "göç krizi", "mülteci", "insani yardım",
            "g7 zirvesi", "g20 zirvesi", "uluslararası mahkeme",
            "soykırım", "savaş suçu", "çatışma bölgesi"
        ),
        "Magazin" to listOf(
            "ünlü oyuncu", "ünlü şarkıcı", "ünlü isim",
            "evlendi", "boşandı", "nişanlandı", "hamile kaldı",
            "film prömiyeri", "netflix dizisi", "dizi yayına girdi",
            "red carpet", "ödül töreni", "oscar", "emmy", "altın küre",
            "konser bilet", "albüm çıkardı", "klip yayınlandı",
            "manken", "influencer", "takipçi rekoru"
        ),
        "Yaşam" to listOf(
            "yemek tarifi", "sağlıklı beslenme", "diyet programı",
            "tatil önerisi", "seyahat rehberi", "otel tavsiyesi",
            "ev dekorasyonu", "iç mimari", "moda trendi",
            "cilt bakımı", "güzellik ipucu", "saç bakımı",
            "evcil hayvan", "kedi", "köpek sahiplenmek",
            "yoga", "meditasyon", "stres yönetimi", "psikoloji"
        )
    )
}
