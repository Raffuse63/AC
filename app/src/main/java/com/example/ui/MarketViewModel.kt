package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.AppDatabase
import com.example.data.MarketItem
import com.example.data.MarketItemRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MarketViewModel(private val repository: MarketItemRepository) : ViewModel() {

    var showAddItemDialog by mutableStateOf(false)

    val items: StateFlow<List<MarketItem>> = repository.allItems
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun addItem(description: String, quantity: String, targetPrice: Double) {
        viewModelScope.launch {
            val item = MarketItem(
                description = description,
                quantity = quantity,
                targetPrice = targetPrice,
                actualPrice = 0.0
            )
            repository.insert(item)
        }
    }

    fun updateActualPrice(item: MarketItem, newActualPrice: Double) {
        viewModelScope.launch {
            val updated = item.copy(actualPrice = newActualPrice)
            repository.update(updated)
        }
    }

    fun updateItemActiveStatus(item: MarketItem, isActive: Boolean) {
        viewModelScope.launch {
            val updated = item.copy(isActive = isActive)
            repository.update(updated)
        }
    }

    fun updateItem(item: MarketItem) {
        viewModelScope.launch {
            repository.update(item)
        }
    }

    fun insertItem(item: MarketItem) {
        viewModelScope.launch {
            repository.insert(item)
        }
    }

    fun deleteItem(item: MarketItem) {
        viewModelScope.launch {
            repository.delete(item)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val database = AppDatabase.getDatabase(application)
                val repository = MarketItemRepository(database.marketItemDao())
                return MarketViewModel(repository) as T
            }
        }
    }
}
