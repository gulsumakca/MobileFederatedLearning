package com.example.federatedapp

/*
 * MainActivity — Uygulamanın giriş noktası ve tüm arayüz (Jetpack Compose).
 * - onCreate: tüm bağımlılıkları kurar (Room DB, repository, TFLite, gRPC) ve
 *   NewsViewModel'e enjekte eder.
 * - UI: haber listesi, "İlgi Profilim" paneli (öğrenilen yüzdeler), haber kartları,
 *   kaynak ayarları ve WebView detay ekranı.
 * - Bir habere tıklanınca onNewsClicked() çağrılır → model eğitimini tetikler.
 */

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.example.federatedapp.data.AppDatabase
import com.example.federatedapp.data.CategoryStat
import com.example.federatedapp.federated.CategoryPreferenceFlowerClient
import com.example.federatedapp.federated.FlowerGrpcClient
import com.example.federatedapp.federated.TFLiteNewsModel
import com.example.federatedapp.model.NewsArticle
import com.example.federatedapp.repository.NewsRepository
import com.example.federatedapp.repository.SettingsRepository
import com.example.federatedapp.ui.theme.FederatedAppTheme
import com.example.federatedapp.viewmodel.NewsViewModel
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getDatabase(this)
        val dao = database.userInteractionDao()
        val newsRepository = NewsRepository(dao)
        val settingsRepository = SettingsRepository(
            getSharedPreferences("news_settings", Context.MODE_PRIVATE)
        )
        val tfliteModel = TFLiteNewsModel(this)
        val flowerClient = CategoryPreferenceFlowerClient(dao, tfliteModel)
        val grpcClient = FlowerGrpcClient(
            settingsRepository.getServerUrl()
                .removePrefix("http://").removePrefix("https://")
                .let { if (it.contains(':')) it else "$it:8080" }
        )
        val viewModelFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return NewsViewModel(newsRepository, settingsRepository, flowerClient, grpcClient) as T
            }
        }
        val viewModel = ViewModelProvider(this, viewModelFactory)[NewsViewModel::class.java]

        enableEdgeToEdge()
        setContent {
            FederatedAppTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "news_list") {
                    composable("news_list") {
                        NewsListScaffold(viewModel = viewModel, navController = navController)
                    }
                    composable("news_detail/{encodedUrl}") { backStackEntry ->
                        val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
                        val url = Uri.decode(encodedUrl)
                        NewsDetailScaffold(url = url, navController = navController)
                    }
                }
            }
        }
    }
}

// ─── News List ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsListScaffold(viewModel: NewsViewModel, navController: NavController) {
    val isLoading by viewModel.isLoading.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    val sourceLimits by viewModel.sourceLimits.collectAsState()
    val serverAddress by viewModel.serverAddress.collectAsState()

    if (showSettings) {
        SourceSettingsSheet(
            initialLimits = sourceLimits,
            initialServerUrl = serverAddress,
            onApply = { limits, url ->
                viewModel.applySourceLimits(limits)
                if (url != serverAddress) viewModel.updateServerAddress(url)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Federated News") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Kaynak Ayarları")
                    }
                    IconButton(onClick = { viewModel.refreshData() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Yenile")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            NewsScreen(viewModel = viewModel, navController = navController)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSettingsSheet(
    initialLimits: Map<String, Int>,
    initialServerUrl: String,
    onApply: (Map<String, Int>, String) -> Unit,
    onDismiss: () -> Unit
) {
    var localLimits by remember { mutableStateOf(initialLimits) }
    var localUrl by remember { mutableStateOf(initialServerUrl) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // ── Haber kaynakları ──────────────────────────────────────────
            Text(
                "Kaynak Başına Haber Sayısı",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Her kaynaktan kaç haber gösterileceğini seçin (${SettingsRepository.MIN_LIMIT}–${SettingsRepository.MAX_LIMIT})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            for (source in SettingsRepository.SOURCES) {
                val limit = localLimits[source] ?: SettingsRepository.DEFAULT_LIMIT
                SourceLimitRow(
                    source = source,
                    limit = limit,
                    onDecrease = {
                        if (limit > SettingsRepository.MIN_LIMIT)
                            localLimits = localLimits + (source to limit - 1)
                    },
                    onIncrease = {
                        if (limit < SettingsRepository.MAX_LIMIT)
                            localLimits = localLimits + (source to limit + 1)
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }

            // ── Flower sunucu URL ─────────────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Flower FL Sunucu Adresi",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Emülatör: http://10.0.2.2:5000  ·  Gerçek cihaz: http://<PC-IP>:5000",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = localUrl,
                onValueChange = { localUrl = it },
                label = { Text("Sunucu URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { onApply(localLimits, localUrl) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Uygula ve Yenile")
            }
        }
    }
}

@Composable
fun SourceLimitRow(
    source: String,
    limit: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = source,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilledTonalIconButton(
                onClick = onDecrease,
                enabled = limit > SettingsRepository.MIN_LIMIT,
                modifier = Modifier.size(36.dp)
            ) {
                Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = limit.toString(),
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            FilledTonalIconButton(
                onClick = onIncrease,
                enabled = limit < SettingsRepository.MAX_LIMIT,
                modifier = Modifier.size(36.dp)
            ) {
                Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── News Screen ──────────────────────────────────────────────────────────────

@Composable
fun NewsScreen(viewModel: NewsViewModel, navController: NavController) {
    val newsList by viewModel.newsList.collectAsState()
    val weights by viewModel.personalizationWeights.collectAsState()
    val categoryStats by viewModel.categoryStats.collectAsState()
    val totalInteractions by viewModel.totalInteractions.collectAsState()
    val isServerConnected by viewModel.isServerConnected.collectAsState()
    val flRound by viewModel.flRound.collectAsState()
    val error by viewModel.error.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 13.sp
                )
            }
            InterestPanel(
                weights = weights,
                categoryStats = categoryStats,
                totalInteractions = totalInteractions,
                isServerConnected = isServerConnected,
                flRound = flRound
            )
        }
        items(newsList, key = { it.id }) { article ->
            val categoryWeight = weights[article.category] ?: 0f
            NewsItem(
                article = article,
                categoryWeight = categoryWeight,
                isPrimaryCategory = weights.isNotEmpty() &&
                        article.category == weights.keys.firstOrNull(),
                onClick = {
                    viewModel.onNewsClicked(article)
                    if (article.url.isNotEmpty()) {
                        navController.navigate("news_detail/${Uri.encode(article.url)}")
                    }
                }
            )
        }
    }
}

@Composable
fun InterestPanel(
    weights: Map<String, Float>,
    categoryStats: List<CategoryStat>,
    totalInteractions: Int,
    isServerConnected: Boolean,
    flRound: Int
) {
    val allCategories = CategoryPreferenceFlowerClient.KNOWN_CATEGORIES
    // weights boşsa tüm kategoriler eşit gösterilir (0f)
    val displayWeights = if (weights.isEmpty())
        allCategories.associateWith { 0f }
    else
        weights + allCategories.filter { it !in weights }.associateWith { 0f }

    var expanded by remember { mutableStateOf(true) }
    val sorted = remember(displayWeights) { displayWeights.entries.sortedByDescending { it.value } }
    val topCategory = if (weights.isNotEmpty()) sorted.first().key else null
    val statsMap = remember(categoryStats) { categoryStats.associateBy { it.category } }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Başlık satırı ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "İlgi Profilim",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isServerConnected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline
                        ) {
                            Text(
                                text = if (isServerConnected) "● Flower Round $flRound"
                                       else "○ Yerel mod",
                                fontSize = 9.sp,
                                color = if (isServerConnected)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.surface,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = if (totalInteractions > 0 && topCategory != null)
                            "$totalInteractions tıklama · En ilgi: $topCategory"
                        else
                            "Haberlere tıkladıkça ilgi profiliniz oluşur",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.65f)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                                  else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                )
            }

            // ── Kategori çubukları ─────────────────────────────────────────
            if (expanded) {
                Spacer(Modifier.height(14.dp))
                for ((category, weight) in sorted) {
                    val clickCount = statsMap[category]?.clickCount ?: 0
                    CategoryProgressRow(
                        category = category,
                        weight = weight,
                        clickCount = clickCount,
                        isTop = category == topCategory
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
fun CategoryProgressRow(
    category: String,
    weight: Float,
    clickCount: Int,
    isTop: Boolean
) {
    val animatedProgress by animateFloatAsState(
        targetValue = weight,
        animationSpec = tween(durationMillis = 600),
        label = "progress_$category"
    )
    val barColor = if (isTop) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isTop) {
                    Text("★ ", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    category,
                    fontSize = 13.sp,
                    fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (clickCount > 0) {
                    Text(
                        "$clickCount tıklama",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                    )
                }
                Text(
                    "${(weight * 100).roundToInt()}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isTop) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.1f),
            strokeCap = StrokeCap.Round
        )
    }
}


@Composable
fun NewsItem(
    article: NewsArticle,
    categoryWeight: Float,
    isPrimaryCategory: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isPrimaryCategory) 6.dp else 2.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isPrimaryCategory)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                // Kategori + Kaynak + Eşleşme
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryBadge(
                            category = article.category,
                            isPrimary = isPrimaryCategory
                        )
                        article.source?.let { src ->
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                src,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                    if (categoryWeight > 0f) {
                        Text(
                            "${(categoryWeight * 100).toInt()}% eşleşme",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    article.title,
                    fontSize = 15.sp,
                    fontWeight = if (isPrimaryCategory) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (article.summary.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        article.summary,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                article.publishedAt?.let { date ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        formatDate(date),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            article.imageUrl?.let { imgUrl ->
                Spacer(modifier = Modifier.width(10.dp))
                AsyncImage(
                    model = imgUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

@Composable
fun CategoryBadge(category: String, isPrimary: Boolean) {
    val bgColor = categoryColor(category).copy(alpha = if (isPrimary) 1f else 0.15f)
    val textColor = if (isPrimary) MaterialTheme.colorScheme.onPrimary
    else categoryColor(category)

    Text(
        text = category,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = textColor
    )
}

@Composable
private fun categoryColor(category: String) = when (category) {
    "Siyaset" -> MaterialTheme.colorScheme.error
    "Spor"    -> MaterialTheme.colorScheme.tertiary
    "Ekonomi" -> MaterialTheme.colorScheme.secondary
    "Dünya"   -> MaterialTheme.colorScheme.secondary
    "Magazin" -> MaterialTheme.colorScheme.tertiary
    else      -> MaterialTheme.colorScheme.primary
}

// ─── News Detail ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsDetailScaffold(url: String, navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Haber Detayı", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { innerPadding ->
        NewsDetailScreen(url = url, modifier = Modifier.padding(innerPadding))
    }
}

@Composable
fun NewsDetailScreen(url: String, modifier: Modifier = Modifier) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        webView?.let { if (it.canGoBack()) it.goBack() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?
                    ) = false
                }
                @Suppress("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                loadUrl(url)
                webView = this
            }
        },
        update = { /* URL sabit, yeniden yükleme gerekmez */ }
    )
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun formatDate(raw: String): String {
    return try {
        // "Mon, 02 Jan 2006 15:04:05 GMT" → "Mon, 02 Jan 2006 15:04"
        raw.take(22).trimEnd().trimEnd(',')
    } catch (_: Exception) {
        raw.take(20)
    }
}
