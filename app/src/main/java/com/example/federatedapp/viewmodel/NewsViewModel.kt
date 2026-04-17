package com.example.federatedapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.federatedapp.model.NewsArticle
import com.example.federatedapp.repository.NewsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NewsViewModel(private val repository: NewsRepository) : ViewModel() {

    private val _newsList = MutableStateFlow<List<NewsArticle>>(emptyList())
    val newsList: StateFlow<List<NewsArticle>> = _newsList

    private val _personalizationWeights = MutableStateFlow<Map<String, Float>>(emptyMap())
    val personalizationWeights: StateFlow<Map<String, Float>> = _personalizationWeights

    init {
        refreshData()
    }

    private fun refreshData() {
        viewModelScope.launch {
            // Ağırlıkları hesapla (zaten yüksekten düşüğe sıralı gelir)
            val currentWeights = repository.getPersonalizationWeights()
            _personalizationWeights.value = currentWeights

            val allNews = repository.getNews()
            _newsList.value = if (currentWeights.isNotEmpty()) {
                sortNewsByWeights(allNews, currentWeights)
            } else {
                allNews
            }
        }
    }

    /**
     * Haberleri ağırlıklara göre sıralar.
     *
     * Birincil sıralama : kategori ağırlığı (yüksekten düşüğe)
     * İkincil sıralama  : haber ID'si (kararlı, tekrarlanabilir sıra)
     *
     * İkincil anahtar olmazsa aynı ağırlıktaki kategorilerdeki haberler
     * her yenilemede rastgele sıralanabilir.
     */
    private fun sortNewsByWeights(
        news: List<NewsArticle>,
        weights: Map<String, Float>
    ): List<NewsArticle> {
        return news.sortedWith(
            compareByDescending<NewsArticle> { article ->
                weights[article.category] ?: 0f
            }.thenBy { article ->
                article.id.toIntOrNull() ?: Int.MAX_VALUE
            }
        )
    }

    fun onNewsClicked(article: NewsArticle, readingDurationMs: Long = 5_000L) {
        viewModelScope.launch {
            repository.trackInteraction(article.category, readingDurationMs)
            refreshData()
        }
    }
}
