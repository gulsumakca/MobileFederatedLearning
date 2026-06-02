package com.example.federatedapp.model

data class NewsArticle(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val url: String = "",
    val imageUrl: String? = null,
    val publishedAt: String? = null,
    val source: String? = null
)
