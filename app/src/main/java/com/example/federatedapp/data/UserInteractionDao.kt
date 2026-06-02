package com.example.federatedapp.data

/*
 * UserInteractionDao — Veritabanı sorguları (Room DAO).
 * Tıklama ekle, tüm tıklamaları getir, kategori bazında istatistik (toplam süre + adet),
 * hepsini temizle. CategoryStat bir kategorinin özet satırını taşır.
 */

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UserInteractionDao {
    @Insert
    suspend fun insertInteraction(interaction: UserInteraction)

    @Query("SELECT * FROM user_interactions ORDER BY timestamp DESC")
    suspend fun getAllInteractions(): List<UserInteraction>

    @Query("SELECT category, SUM(duration) as totalDuration, COUNT(*) as clickCount FROM user_interactions GROUP BY category ORDER BY SUM(duration) DESC")
    suspend fun getCategoryStats(): List<CategoryStat>

    @Query("DELETE FROM user_interactions")
    suspend fun clearAll()
}

data class CategoryStat(
    val category: String,
    val totalDuration: Long,
    val clickCount: Int
)
