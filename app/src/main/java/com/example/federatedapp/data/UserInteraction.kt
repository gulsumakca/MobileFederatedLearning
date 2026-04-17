package com.example.federatedapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_interactions")
data class UserInteraction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val category: String,
    val timestamp: Long = System.currentTimeMillis(),
    val duration: Long // Ne kadar süre okudu (ms)
)
