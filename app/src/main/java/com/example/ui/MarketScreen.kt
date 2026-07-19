package com.example.ui

import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import java.util.Locale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.MarketItem
import com.example.ui.MarketViewModel

@Composable
fun MarketApp(
    modifier: Modifier = Modifier,
    viewModel: MarketViewModel = viewModel(factory = MarketViewModel.Factory),
    financeViewModel: com.example.FinanceViewModel? = null
) {
    val items by viewModel.items.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Multi-selection states for pushing to Tracker
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItemIds by remember { mutableStateOf(setOf<Int>()) }

    // Dialog State for custom edit modal
    var editingItem by remember { mutableStateOf<MarketItem?>(null) }

    // Undo States
    var recentlyDeletedItem by remember { mutableStateOf<MarketItem?>(null) }
    var showUndoBanner by remember { mutableStateOf(false) }

    // Delete confirmation state
    var showDeleteConfirmation by remember { mutableStateOf<MarketItem?>(null) }

    // Timer to automatically clear/hide the undo banner after 5 seconds
    LaunchedEffect(showUndoBanner) {
        if (showUndoBanner) {
            kotlinx.coroutines.delay(5000)
            showUndoBanner = false
            recentlyDeletedItem = null
        }
    }

    // Sorting: unpaid/incomplete items first, completed/paid items (actualPrice > 0) go to the bottom of the list
    val sortedItems = remember(items) {
        items.sortedWith(
            compareBy<MarketItem> { it.actualPrice > 0.0 }
                .thenByDescending { it.timestamp }
        )
    }

    // Totals calculations
    val totalTarget = items.filter { it.isActive }.sumOf { it.targetPrice }
    val totalActual = items.filter { it.isActive }.sumOf { it.actualPrice }
    val difference = totalTarget - totalActual

    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFEFF6FF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Summary Card - Styled like Tracker's summary card, placed at the absolute top (fixed)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(16.dp),
                        clip = false,
                        ambientColor = Color(0xFF1E293B).copy(alpha = 0.08f),
                        spotColor = Color(0xFF1E293B).copy(alpha = 0.12f)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(
                    width = 1.2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF34D399).copy(alpha = 0.5f), // Soft Emerald
                            Color(0xFF60A5FA).copy(alpha = 0.5f), // Soft Blue
                            Color(0xFFC084FC).copy(alpha = 0.5f)  // Soft Purple
                        )
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFF0FDF4), // Emerald tint
                                    Color(0xFFEFF6FF), // Blue tint
                                    Color(0xFFFAF5FF)  // Purple tint
                                )
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                            Text("মোট টার্গেট", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                            Text(
                                text = "৳${convertToBengaliNumber(String.format(Locale.US, "%,.0f", totalTarget))}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF1E40AF), // Medium Blue Theme
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(35.dp)
                                .background(Color(0xFFE2E8F0))
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.2f)) {
                            Text("মোট খরচ", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                            Text(
                                text = "৳${convertToBengaliNumber(String.format(Locale.US, "%,.0f", totalActual))}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF16A34A), // Green Theme
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(35.dp)
                                .background(Color(0xFFE2E8F0))
                        )
                        val diffColor = if (difference >= 0) Color(0xFF1E40AF) else Color(0xFFDC2626)
                        val displayDiffSymbol = if (difference >= 0) "+" else ""
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                            Text("অবশিষ্ট", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                            Text(
                                text = "$displayDiffSymbol৳${convertToBengaliNumber(String.format(Locale.US, "%,.0f", difference))}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = diffColor,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                }
            }

            if (sortedItems.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSelectionMode) "সিলেক্ট করা হয়েছে: ${convertToBengaliNumber(selectedItemIds.size.toString())}টি" else "বাজারের তালিকা (ডাবল-ট্যাপ এ এডিট, লং-প্রেস এ ডিলিট)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            isSelectionMode = !isSelectionMode
                            if (!isSelectionMode) {
                                selectedItemIds = emptySet()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isSelectionMode) Icons.Default.Close else Icons.Default.List,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isSelectionMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isSelectionMode) "বাতিল" else "সিলেক্ট করুন",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelectionMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Main List using Column
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (sortedItems.isNotEmpty()) {
                    sortedItems.forEach { item ->
                        MarketItemRow(
                            item = item,
                            onActualChange = { newPrice ->
                                viewModel.updateActualPrice(item, newPrice)
                            },
                            onDoubleClick = {
                                editingItem = item
                            },
                            onLongClick = {
                                showDeleteConfirmation = item
                            },
                            isSelectionMode = isSelectionMode,
                            isSelected = item.id in selectedItemIds,
                            onSelectedChange = { selected ->
                                selectedItemIds = if (selected) {
                                    selectedItemIds + item.id
                                } else {
                                    selectedItemIds - item.id
                                }
                            }
                        )
                    }
                } else {
                    // Empty state
                    Spacer(modifier = Modifier.height(36.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShoppingCart,
                            contentDescription = "খালি ঝুড়ি",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "আপনার লিস্টটি এখন খালি!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "নিচের প্লাস (+) বাটনে ট্যাপ করে নতুন পণ্য যোগ করুন।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(80.dp)) // padding for FAB
            }
        }

        // Custom Floating Undo Banner
        if (showUndoBanner && recentlyDeletedItem != null) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("undo_banner"),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShoppingCart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "\"${recentlyDeletedItem?.description}\" মুছে ফেলা হয়েছে",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            recentlyDeletedItem?.let { itemToRestore ->
                                viewModel.insertItem(itemToRestore)
                                Toast.makeText(context, "তথ্য ফিরিয়ে আনা হয়েছে!", Toast.LENGTH_SHORT).show()
                            }
                            showUndoBanner = false
                            recentlyDeletedItem = null
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "ফিরিয়ে আনুন",
                            color = MaterialTheme.colorScheme.inversePrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Floating "Push to Tracker" and calculation On/Off buttons panel at the bottom center when items are selected
        if (isSelectionMode && selectedItemIds.isNotEmpty()) {
            val selectedItems = items.filter { it.id in selectedItemIds }
            val totalPushAmount = selectedItems.filter { it.isActive }.sumOf { if (it.actualPrice > 0.0) it.actualPrice else it.targetPrice }

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(16.dp),
                        clip = false,
                        ambientColor = Color(0xFF1E293B).copy(alpha = 0.15f),
                        spotColor = Color(0xFF1E293B).copy(alpha = 0.2f)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Row(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Off Button
                    Button(
                        onClick = {
                            selectedItems.forEach { item ->
                                viewModel.updateItemActiveStatus(item, isActive = false)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF64748B), // Slate/grey color for "Off"
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear, // Off symbol
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "অফ (Off)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // On Button
                    Button(
                        onClick = {
                            selectedItems.forEach { item ->
                                viewModel.updateItemActiveStatus(item, isActive = true)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981), // Emerald green color for "On"
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check, // On symbol
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "অন (On)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Push to Tracker Button
                    Button(
                        onClick = {
                            val activeSelectedItems = selectedItems.filter { it.isActive }
                            if (activeSelectedItems.isEmpty()) {
                                Toast.makeText(context, "কোনো হিসাব অন থাকা আইটেম সিলেক্ট করা নেই!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            val noteBuilder = activeSelectedItems.joinToString("") { item ->
                                val price = if (item.actualPrice > 0.0) item.actualPrice else item.targetPrice
                                "${item.description} (${item.quantity}) - ৳${if (price % 1.0 == 0.0) price.toInt() else price}\n"
                            }
                            
                            if (financeViewModel != null) {
                                try {
                                    // Prefill the tracker dialog fields in FinanceViewModel
                                    financeViewModel.cancelEditing()
                                    financeViewModel.amountInput = if (totalPushAmount % 1.0 == 0.0) totalPushAmount.toInt().toString() else totalPushAmount.toString()
                                    financeViewModel.categoryInput = "বাজার"
                                    financeViewModel.noteInput = noteBuilder
                                    financeViewModel.activeFormType = "EXPENSE"
                                    financeViewModel.selectedPersonName = "General"
                                    financeViewModel.resetTrackerFormDateTime()
                                    
                                    // Mark selected items as completed/paid in the Bazar list
                                    activeSelectedItems.forEach { item ->
                                        if (item.actualPrice == 0.0) {
                                            viewModel.updateActualPrice(item, item.targetPrice)
                                        }
                                    }
                                    
                                    // Navigate to Tracker and display the dialog
                                    financeViewModel.currentTab = "TRACKER"
                                    financeViewModel.showAddTransactionDialog = true
                                    
                                    Toast.makeText(context, "ট্রেকার ডায়লগে বাজার তালিকা যুক্ত করা হয়েছে! 🛒", Toast.LENGTH_LONG).show()
                                    
                                    isSelectionMode = false
                                    selectedItemIds = emptySet()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "ত্রুটি: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "ট্রেকার পাওয়া যায়নি!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1.8f)
                            .height(40.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(8.dp),
                                clip = false,
                                ambientColor = Color(0xFF2563EB).copy(alpha = 0.25f),
                                spotColor = Color(0xFF2563EB).copy(alpha = 0.35f)
                            ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2563EB),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "ট্রেকারে যুক্ত (৳${convertToBengaliNumber(String.format(Locale.US, "%,.0f", totalPushAmount))})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // Custom dialog edit modal for double-tap edit
    editingItem?.let { item ->
        val (initialNum, initialUnit) = remember(item.id) {
            val parts = item.quantity.split(" ")
            if (parts.size >= 2 && parts.last() in listOf("কেজি", "গ্রাম", "টি")) {
                Pair(parts.dropLast(1).joinToString(" "), parts.last())
            } else {
                Pair(item.quantity, "কেজি")
            }
        }
        var editDesc by remember(item.id) { mutableStateOf(item.description) }
        var editQtyNum by remember(item.id) { mutableStateOf(initialNum) }
        var editQtyUnit by remember(item.id) { mutableStateOf(initialUnit) }
        var editTarget by remember(item.id) { mutableStateOf(if (item.targetPrice == 0.0) "" else item.targetPrice.toString()) }
        var editActual by remember(item.id) { mutableStateOf(if (item.actualPrice == 0.0) "" else item.actualPrice.toString()) }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingItem = null },
            title = {
                Text(
                    text = "পণ্য পরিবর্তন করুন",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        label = { Text("পণ্যের বিবরণ") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = editQtyNum,
                            onValueChange = { editQtyNum = it },
                            label = { Text("পরিমাণ") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1.5f)
                        )

                        var editDropdownExpanded by remember { mutableStateOf(false) }
                        val editUnits = listOf("কেজি", "গ্রাম", "টি")

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .padding(top = 8.dp) // align with OutlinedTextField's top label baseline
                        ) {
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White.copy(alpha = 0.8f)
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { editDropdownExpanded = true }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = editQtyUnit,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "ইউনিট নির্বাচন",
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = editDropdownExpanded,
                                onDismissRequest = { editDropdownExpanded = false },
                                modifier = Modifier.background(Color.White)
                            ) {
                                editUnits.forEach { unit ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = unit,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        },
                                        onClick = {
                                            editQtyUnit = unit
                                            editDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editTarget,
                            onValueChange = { editTarget = it },
                            label = { Text("টার্গেট ৳") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editActual,
                            onValueChange = { editActual = it },
                            label = { Text("আসল খরচ ৳") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetVal = editTarget.toDoubleOrNull()
                        val actualVal = editActual.toDoubleOrNull() ?: 0.0
                        val finalQty = if (editQtyNum.trim().isNotEmpty()) "${editQtyNum.trim()} $editQtyUnit" else ""
                        if (editDesc.trim().isNotEmpty() && finalQty.isNotEmpty() && targetVal != null) {
                            val updated = item.copy(
                                description = editDesc.trim(),
                                quantity = finalQty,
                                targetPrice = targetVal,
                                actualPrice = actualVal
                            )
                            viewModel.updateItem(updated)
                            editingItem = null
                            Toast.makeText(context, "তথ্য সফলভাবে পরিবর্তন করা হয়েছে!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "সব তথ্য সঠিকভাবে দিন", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("সংরক্ষণ", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { editingItem = null }
                ) {
                    Text("বাতিল", color = MaterialTheme.colorScheme.outlineVariant)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Delete Confirmation Dialog with Undo trigger
    showDeleteConfirmation?.let { itemToDelete ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirmation = null },
            title = {
                Text(
                    text = "পণ্যটি মুছতে চান?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "\"${itemToDelete.description}\" তালিকা থেকে মুছে ফেলা হবে। আপনি কি নিশ্চিত?",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteItem(itemToDelete)
                        recentlyDeletedItem = itemToDelete
                        showUndoBanner = true
                        showDeleteConfirmation = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("মুছে ফেলুন", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDeleteConfirmation = null }
                ) {
                    Text("বাতিল", color = MaterialTheme.colorScheme.outlineVariant)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Add Item Dialog
    if (viewModel.showAddItemDialog) {
        var addDesc by remember { mutableStateOf("") }
        var addQtyNum by remember { mutableStateOf("") }
        var addQtyUnit by remember { mutableStateOf("কেজি") }
        var addTarget by remember { mutableStateOf("") }
        var addDropdownExpanded by remember { mutableStateOf(false) }
        val addUnits = listOf("কেজি", "গ্রাম", "টি")

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.showAddItemDialog = false },
            title = {
                Text(
                    text = "নতুন পণ্য যুক্ত করুন",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = addDesc,
                        onValueChange = { addDesc = it },
                        label = { Text("পণ্যের বিবরণ (যেমন: চাল, ডাল)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = addQtyNum,
                            onValueChange = { addQtyNum = it },
                            label = { Text("পরিমাণ") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            ),
                            modifier = Modifier.weight(1.1f)
                        )

                        // Dropdown for Unit
                        Box(
                            modifier = Modifier
                                .weight(0.9f)
                                .height(56.dp)
                                .padding(top = 8.dp)
                        ) {
                            Card(
                                shape = RoundedCornerShape(4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White.copy(alpha = 0.8f)
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable { addDropdownExpanded = true }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = addQtyUnit,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "ইউনিট নির্বাচন",
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = addDropdownExpanded,
                                onDismissRequest = { addDropdownExpanded = false },
                                modifier = Modifier.background(Color.White)
                            ) {
                                addUnits.forEach { unit ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = unit,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        },
                                        onClick = {
                                            addQtyUnit = unit
                                            addDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = addTarget,
                        onValueChange = { addTarget = it },
                        label = { Text("টার্গেট মূল্য ৳") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetVal = addTarget.toDoubleOrNull()
                        val descTrim = addDesc.trim()
                        val qtyTrim = addQtyNum.trim()

                        if (descTrim.isNotEmpty() && qtyTrim.isNotEmpty() && targetVal != null) {
                            val combinedQty = "$qtyTrim $addQtyUnit"
                            viewModel.addItem(descTrim, combinedQty, targetVal)
                            viewModel.showAddItemDialog = false
                        } else {
                            Toast.makeText(context, "সব তথ্য সঠিকভাবে দিন", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("সংরক্ষণ", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.showAddItemDialog = false }
                ) {
                    Text("বাতিল", color = MaterialTheme.colorScheme.outlineVariant)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarketItemRow(
    item: MarketItem,
    onActualChange: (Double) -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectedChange: (Boolean) -> Unit = {}
) {
    // Key remember block on both item.id and item.actualPrice to stay in perfect sync with edits
    var actualText by remember(item.id, item.actualPrice) {
        mutableStateOf(if (item.actualPrice == 0.0) "" else {
            if (item.actualPrice % 1.0 == 0.0) item.actualPrice.toInt().toString() else item.actualPrice.toString()
        })
    }
    var isEditingActual by remember { mutableStateOf(false) }
    var hasBeenFocused by remember(isEditingActual) { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isEditingActual) {
        if (isEditingActual) {
            focusRequester.requestFocus()
        }
    }

    // Background color of the card is always white as requested, with high-quality elevation shadow
    val isPaid = item.actualPrice > 0.0
    val isActiveItem = item.isActive
    val cardBgColor = if (isActiveItem) Color.White else Color(0xFFF1F5F9)

    val cardBorderColor = if (!isActiveItem) {
        Color(0xFFCBD5E1) // Faded grey border for inactive/disabled calculation items
    } else if (isPaid) {
        Color(0xFF10B981) // Highly visible pleasant green border for completed/paid items
    } else {
        Color(0xFF3B82F6).copy(alpha = 0.5f) // Subtle modern blue border for unpaid items
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBgColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp), // Elevated box shadow for beautiful depth
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.2.dp,
                color = cardBorderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .combinedClickable(
                onDoubleClick = {
                    if (!isSelectionMode && isActiveItem) {
                        onDoubleClick()
                    }
                },
                onLongClick = {
                    if (!isSelectionMode) {
                        onLongClick()
                    }
                },
                onClick = {
                    if (isSelectionMode) {
                        onSelectedChange(!isSelected)
                    }
                }
            )
            .testTag("market_item_row_${item.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Custom circle checkbox for multi-select mode
            if (isSelectionMode) {
                IconButton(
                    onClick = { onSelectedChange(!isSelected) },
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(32.dp)
                        .testTag("checkbox_${item.id}")
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .border(
                                width = 2.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Left side column containing description, quantity tag side-by-side, and target below
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                // Description and Quantity tag badge side-by-side
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isActiveItem) MaterialTheme.colorScheme.onSurface else Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("item_description_${item.id}")
                    )

                    // Distinct background badge/chip for quantity (পরিমাণ)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isActiveItem) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color(0xFFE2E8F0))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.quantity,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isActiveItem) MaterialTheme.colorScheme.primary else Color(0xFF64748B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("item_quantity_${item.id}")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                // Target price under description
                Text(
                    text = "টার্গেট: ৳${if (item.targetPrice % 1.0 == 0.0) item.targetPrice.toInt() else item.targetPrice}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isActiveItem) MaterialTheme.colorScheme.outlineVariant else Color(0xFF94A3B8),
                    modifier = Modifier.testTag("item_target_${item.id}")
                )
            }

            // Right side inputs and controls: larger font styling
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isEditingActual && isActiveItem) {
                    // Actual price numeric input field
                    Box(
                        modifier = Modifier
                            .width(105.dp)
                            .height(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .border(
                                width = 1.2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            BasicTextField(
                                value = actualText,
                                onValueChange = { input ->
                                    if (input.isEmpty() || input.toDoubleOrNull() != null || input == ".") {
                                        actualText = input
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        isEditingActual = false
                                        val value = actualText.toDoubleOrNull() ?: 0.0
                                        onActualChange(value)
                                        focusManager.clearFocus()
                                    }
                                ),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.End,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp // Enlarged text
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester)
                                    .testTag("item_actual_input_${item.id}")
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            hasBeenFocused = true
                                        } else if (hasBeenFocused) {
                                            isEditingActual = false
                                            val value = actualText.toDoubleOrNull() ?: 0.0
                                            onActualChange(value)
                                        }
                                    }
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "৳",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    // Plain clickable Text representing actual price, turns to input on click (Larger font style)
                    val displayActualText = if (item.actualPrice == 0.0) "খরচ লিখুন" else {
                        "৳${if (item.actualPrice % 1.0 == 0.0) item.actualPrice.toInt().toString() else item.actualPrice.toString()}"
                    }
                    val displayColor = if (!isActiveItem) {
                        Color(0xFF94A3B8)
                    } else if (item.actualPrice == 0.0) {
                        MaterialTheme.colorScheme.outlineVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isActiveItem) MaterialTheme.colorScheme.background else Color(0xFFE2E8F0))
                            .border(
                                width = 1.dp,
                                color = if (isActiveItem) MaterialTheme.colorScheme.outline.copy(alpha = 0.25f) else Color(0xFFCBD5E1),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                if (isActiveItem) {
                                    isEditingActual = true
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = displayActualText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = displayColor,
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp // Enlarged text
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f) // Distinct clear border
    val borderWidth = if (isFocused) 1.5.dp else 1.dp
    val containerColor = if (isFocused) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.8f)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        textStyle = TextStyle(
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        ),
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp) // Fixed height to prevent shrinking/resizing on text entry
                    .background(containerColor, RoundedCornerShape(10.dp))
                    .border(
                        width = borderWidth,
                        color = borderColor,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 12.dp), // Comfortable horizontal padding
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart // Ensures text is perfectly vertically centered
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    innerTextField()
                }
            }
        }
    )
}

fun convertToBengaliNumber(input: String): String {
    val englishDigits = listOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
    val bengaliDigits = listOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
    var result = input
    for (i in 0..9) {
        result = result.replace(englishDigits[i], bengaliDigits[i])
    }
    return result
}
