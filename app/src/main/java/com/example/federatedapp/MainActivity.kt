package com.example.federatedapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.federatedapp.data.AppDatabase
import com.example.federatedapp.model.NewsArticle
import com.example.federatedapp.repository.NewsRepository
import com.example.federatedapp.ui.theme.FederatedAppTheme
import com.example.federatedapp.viewmodel.NewsViewModel

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getDatabase(this)
        val repository = NewsRepository(database.userInteractionDao())
        val viewModelFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return NewsViewModel(repository) as T
            }
        }
        val viewModel = ViewModelProvider(this, viewModelFactory)[NewsViewModel::class.java]

        enableEdgeToEdge()
        setContent {
            FederatedAppTheme {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("Federated News") },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    NewsScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun NewsScreen(viewModel: NewsViewModel, modifier: Modifier = Modifier) {
    val newsList by viewModel.newsList.collectAsState()
    val weights by viewModel.personalizationWeights.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        PersonalizationPanel(weights = weights)
        LazyColumn {
            items(newsList, key = { it.id }) { article ->
                val categoryWeight = weights[article.category] ?: 0f
                NewsItem(
                    article = article,
                    categoryWeight = categoryWeight,
                    isPrimaryCategory = weights.isNotEmpty() &&
                            article.category == weights.keys.firstOrNull(),
                    onClick = { viewModel.onNewsClicked(article) }
                )
            }
        }
    }
}

/**
 * Kişiselleştirme paneli.
 *
 * weights map'i NewsRepository'den GELDİĞİNDE zaten yüksekten düşüğe sıralıdır.
 * Bu sayede ilk gösterilen kategori her zaman gerçek ana ilgi alanıdır.
 */
@Composable
fun PersonalizationPanel(weights: Map<String, Float>) {
    if (weights.isEmpty()) return

    // weights zaten sıralı gelir; yine de UI katmanında da garantiyelim
    val sortedWeights = remember(weights) {
        weights.entries.sortedByDescending { it.value }
    }
    val primaryCategory = sortedWeights.first().key
    val primaryPercent = (sortedWeights.first().value * 100).toInt()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Ana İlgi Alanı: ",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "$primaryCategory ($primaryPercent%)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "· Cihazda yerel",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sortedWeights) { (category, weight) ->
                    val isPrimary = category == primaryCategory
                    val percent = (weight * 100).toInt()
                    CategoryChip(
                        label = "$category $percent%",
                        isPrimary = isPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryChip(label: String, isPrimary: Boolean) {
    val bgColor = if (isPrimary)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (isPrimary)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = 11.sp,
        color = textColor,
        fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal
    )
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = article.category,
                    fontSize = 11.sp,
                    color = if (isPrimaryCategory)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold
                )
                if (categoryWeight > 0f) {
                    Text(
                        text = "${(categoryWeight * 100).toInt()}% eşleşme",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = article.title,
                fontSize = 17.sp,
                fontWeight = if (isPrimaryCategory) FontWeight.ExtraBold else FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = article.summary,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
    }
}
