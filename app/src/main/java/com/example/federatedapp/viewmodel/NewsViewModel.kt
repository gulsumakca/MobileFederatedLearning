package com.example.federatedapp.viewmodel

/*
 * NewsViewModel — Arayüz ile iş mantığı arasındaki köprü (MVVM orkestratörü).
 * Ana akış (refreshData / onNewsClicked) hep aynı sırayı izler:
 *   1) yerel modeli eğit  2) ağırlıkları gRPC ile sunucuya gönder → FedAvg global model al
 *   3) global ağırlıkları yerel modele geri yaz  4) haberleri ağırlığa göre sırala.
 * Sunucuya ulaşılamazsa sessizce yerel ağırlıklarla devam eder (çevrimdışı dayanıklılık).
 * Arayüzün izlediği tüm durumları (StateFlow) burada tutar.
 */

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.federatedapp.data.CategoryStat
import com.example.federatedapp.federated.CategoryPreferenceFlowerClient
import com.example.federatedapp.federated.FlowerGrpcClient
import com.example.federatedapp.model.NewsArticle
import com.example.federatedapp.repository.NewsRepository
import com.example.federatedapp.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NewsViewModel(
    private val repository: NewsRepository,
    private val settingsRepository: SettingsRepository,
    private val flowerClient: CategoryPreferenceFlowerClient,
    private val grpcClient: FlowerGrpcClient
) : ViewModel() {

    private val _newsList = MutableStateFlow<List<NewsArticle>>(emptyList())
    val newsList: StateFlow<List<NewsArticle>> = _newsList

    private val _personalizationWeights = MutableStateFlow<Map<String, Float>>(emptyMap())
    val personalizationWeights: StateFlow<Map<String, Float>> = _personalizationWeights

    private val _categoryStats = MutableStateFlow<List<CategoryStat>>(emptyList())
    val categoryStats: StateFlow<List<CategoryStat>> = _categoryStats

    private val _totalInteractions = MutableStateFlow(0)
    val totalInteractions: StateFlow<Int> = _totalInteractions

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _sourceLimits = MutableStateFlow(settingsRepository.getAllLimits())
    val sourceLimits: StateFlow<Map<String, Int>> = _sourceLimits

    private val _isServerConnected = MutableStateFlow(false)
    val isServerConnected: StateFlow<Boolean> = _isServerConnected

    private val _flRound = MutableStateFlow(0)
    val flRound: StateFlow<Int> = _flRound

    private val _serverAddress = MutableStateFlow(settingsRepository.getServerUrl())
    val serverAddress: StateFlow<String> = _serverAddress

    init {
        grpcClient.connect(parseGrpcAddress(_serverAddress.value))
        refreshData()
    }

    // Açılış/yenileme akışı: yerel eğitim → FedAvg → global modeli yaz → haberleri sırala.
    fun refreshData() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // 1. Yerel TF Lite eğitimi
                val fitResult = flowerClient.fit()
                _totalInteractions.value = fitResult.numSamples
                _categoryStats.value = flowerClient.getCategoryStats()

                // 2. gRPC ile federated round
                val activeWeights = if (fitResult.numSamples > 0) {
                    val grpcResp = grpcClient.fit(fitResult.weights, fitResult.numSamples)
                    if (grpcResp != null) {
                        _isServerConnected.value = true
                        _flRound.value = grpcResp.round
                        // Global ağırlıkları TF Lite modeline yaz
                        val globalArray = CategoryPreferenceFlowerClient.KNOWN_CATEGORIES
                            .map { grpcResp.globalWeights[it] ?: 0f }
                            .toFloatArray()
                        flowerClient.setParameters(globalArray)
                        grpcResp.globalWeights.filter { it.value > 0f }
                    } else {
                        _isServerConnected.value = false
                        fitResult.weights
                    }
                } else {
                    // Hiç etkileşim yok: sunucudan global modeli çek
                    val globalModel = grpcClient.getGlobalModel()
                    if (globalModel != null) {
                        _isServerConnected.value = true
                        globalModel.filter { it.value > 0f }
                    } else {
                        _isServerConnected.value = false
                        emptyMap()
                    }
                }

                _personalizationWeights.value = activeWeights
                    .entries.sortedByDescending { it.value }.associate { it.key to it.value }

                // 3. Haberleri çek ve ağırlıklara göre sırala
                val allNews = repository.getNews(_sourceLimits.value)
                _newsList.value = if (activeWeights.isNotEmpty()) {
                    sortNewsByWeights(allNews, activeWeights)
                } else {
                    allNews
                }
            } catch (e: Exception) {
                _error.value = "Yükleme hatası: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun applySourceLimits(limits: Map<String, Int>) {
        settingsRepository.applyLimits(limits)
        _sourceLimits.value = settingsRepository.getAllLimits()
        refreshData()
    }

    fun updateServerAddress(address: String) {
        settingsRepository.setServerUrl(address)
        _serverAddress.value = address
        grpcClient.connect(parseGrpcAddress(address))
        refreshData()
    }

    // Habere tıklanınca: etkileşimi kaydet → modeli yeniden eğit → FL round → yeniden sırala.
    fun onNewsClicked(article: NewsArticle, readingDurationMs: Long = 5_000L) {
        viewModelScope.launch {
            repository.trackInteraction(article.category, readingDurationMs)

            val fitResult = flowerClient.fit()
            _totalInteractions.value = fitResult.numSamples
            _categoryStats.value = flowerClient.getCategoryStats()

            val activeWeights = if (fitResult.numSamples > 0) {
                val grpcResp = grpcClient.fit(fitResult.weights, fitResult.numSamples)
                if (grpcResp != null) {
                    _isServerConnected.value = true
                    _flRound.value = grpcResp.round
                    grpcResp.globalWeights.filter { it.value > 0f }
                } else {
                    _isServerConnected.value = false
                    fitResult.weights
                }
            } else fitResult.weights

            val sorted = activeWeights
                .entries.sortedByDescending { it.value }.associate { it.key to it.value }
            _personalizationWeights.value = sorted
            _newsList.value = sortNewsByWeights(_newsList.value, sorted)
        }
    }

    override fun onCleared() {
        super.onCleared()
        grpcClient.shutdown()
    }

    // Haberleri kategori ağırlığına göre büyükten küçüğe sıralar (eşitlikte id'ye göre).
    private fun sortNewsByWeights(
        news: List<NewsArticle>,
        weights: Map<String, Float>
    ): List<NewsArticle> {
        return news.sortedWith(
            compareByDescending<NewsArticle> { weights[it.category] ?: 0f }
                .thenBy { it.id.removePrefix("rss_").toIntOrNull() ?: Int.MAX_VALUE }
        )
    }

    /** "10.0.2.2:8080" veya "http://10.0.2.2:8080" → "10.0.2.2:8080" */
    private fun parseGrpcAddress(url: String): String =
        url.removePrefix("http://").removePrefix("https://")
}
