package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "market_items")
data class MarketItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val description: String,
    val quantity: String,
    val targetPrice: Double,
    val actualPrice: Double,
    val isActive: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)
