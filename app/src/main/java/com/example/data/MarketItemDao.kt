package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MarketItemDao {
    @Query("SELECT * FROM market_items ORDER BY timestamp ASC")
    fun getAllItems(): Flow<List<MarketItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: MarketItem): Long

    @Update
    suspend fun updateItem(item: MarketItem)

    @Delete
    suspend fun deleteItem(item: MarketItem)

    @Query("DELETE FROM market_items WHERE id = :id")
    suspend fun deleteItemById(id: Int)
}
