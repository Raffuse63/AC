package com.example

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ============================================================================
// ROOM DATABASE & ENTITIES
// ============================================================================

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val openingBalance: Double = 0.0,
    val monthlySalary: Double = 0.0,
    val isSalaryIncluded: Boolean = false
)

@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String
)

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val amount: Double,
    val type: String, // "INCOME" or "EXPENSE"
    val category: String,
    val dateTime: Long, // Timestamp
    val note: String,
    val personName: String = "General",
    val paidAmount: Double = 0.0,
    val repaymentsCsv: String = ""
)

@Entity(tableName = "notices")
data class NoticeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val timestamp: Long,
    val colorHex: String // Pastel color hex, e.g., "#FFF9C4"
)

@Dao
interface FinanceDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    fun getProfileFlow(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun getProfile(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity)

    @Query("SELECT * FROM transactions ORDER BY dateTime DESC")
    fun getAllTransactionsFlow(): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Delete
    suspend fun deleteTransaction(transaction: TransactionEntity)

    @Query("SELECT * FROM notices ORDER BY timestamp DESC")
    fun getAllNoticesFlow(): Flow<List<NoticeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotice(notice: NoticeEntity)

    @Delete
    suspend fun deleteNotice(notice: NoticeEntity)

    @Query("SELECT * FROM persons ORDER BY id ASC")
    fun getAllPersonsFlow(): Flow<List<PersonEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPerson(person: PersonEntity)

    @Delete
    suspend fun deletePerson(person: PersonEntity)

    @Query("DELETE FROM transactions")
    suspend fun clearTransactions()

    @Query("DELETE FROM persons")
    suspend fun clearPersons()

    @Query("DELETE FROM notices")
    suspend fun clearNotices()

    @Query("DELETE FROM profile")
    suspend fun clearProfile()
}

@Database(
    entities = [ProfileEntity::class, TransactionEntity::class, NoticeEntity::class, PersonEntity::class],
    version = 4,
    exportSchema = false
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

    companion object {
        @Volatile
        private var INSTANCE: FinanceDatabase? = null

        fun getDatabase(context: Context): FinanceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FinanceDatabase::class.java,
                    "finance_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ============================================================================
// VIEWMODEL FOR STATE MANAGEMENT
// ============================================================================

class FinanceViewModel(val dao: FinanceDao) : ViewModel() {
    val profile = dao.getProfileFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val transactions = dao.getAllTransactionsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val notices = dao.getAllNoticesFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val persons = dao.getAllPersonsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Active Navigation Tab: "ACCOUNT", "TRACKER", "NOTICE"
    var currentTab by mutableStateOf("ACCOUNT")

    // Account inputs (Legacy, kept to avoid compile errors, but visual account screen is removed)
    var openingBalanceInput by mutableStateOf("")
    var monthlySalaryInput by mutableStateOf("")

    // Person inputs
    var selectedPersonName by mutableStateOf("")
    var newPersonNameInput by mutableStateOf("")
    var filterPersonName by mutableStateOf("ALL")

    // Tracker inputs
    var activeFormType by mutableStateOf("EXPENSE") // "ALL", "EXPENSE" or "INCOME"
    var amountInput by mutableStateOf("")
    var categoryInput by mutableStateOf("")
    var dateTimeInput by mutableStateOf("")
    var noteInput by mutableStateOf("")

    // Edit Transaction mode
    var editingTransactionId by mutableStateOf<Int?>(null)

    // Filter values
    var filterType by mutableStateOf("ALL")   // "ALL", "INCOME", "EXPENSE"
    var filterMonth by mutableStateOf(Calendar.getInstance().get(Calendar.MONTH).toString())  // Default current month
    var filterYear by mutableStateOf(Calendar.getInstance().get(Calendar.YEAR).toString())   // Default current year
    var filterDay by mutableStateOf("ALL")    // "ALL", "1" - "31"
    var filterCategoryQuery by mutableStateOf("")

    // List toggle: Default last 5 records
    var showAllTransactions by mutableStateOf(false)

    // Notice Inputs
    var noticeTitleInput by mutableStateOf("")
    var noticeContentInput by mutableStateOf("")
    var selectedNoticeColorHex by mutableStateOf("#FFF9C4") // Default yellow
    var showAddNoticeDialog by mutableStateOf(false)
    var showAddPersonDialog by mutableStateOf(false)
    var showAddTransactionDialog by mutableStateOf(false)
    var editingNotice by mutableStateOf<NoticeEntity?>(null)

    // Dialog state for Double Tap note review
    var selectedTransactionForDetails by mutableStateOf<TransactionEntity?>(null)

    // Backup and Restore States
    var showBackupDialog by mutableStateOf(false)
    var showRestoreDialog by mutableStateOf(false)
    var backupJsonText by mutableStateOf("")
    var restoreInputText by mutableStateOf("")

    fun generateBackupJsonString(): String {
        return try {
            val rootObj = JSONObject()
            rootObj.put("version", 1)

            // Profile
            val p = profile.value
            if (p != null) {
                val pObj = JSONObject()
                pObj.put("id", p.id)
                pObj.put("openingBalance", p.openingBalance)
                pObj.put("monthlySalary", p.monthlySalary)
                pObj.put("isSalaryIncluded", p.isSalaryIncluded)
                rootObj.put("profile", pObj)
            }

            // Persons
            val personsArray = JSONArray()
            persons.value.forEach { person ->
                val personObj = JSONObject()
                personObj.put("id", person.id)
                personObj.put("name", person.name)
                personsArray.put(personObj)
            }
            rootObj.put("persons", personsArray)

            // Transactions
            val txArray = JSONArray()
            transactions.value.forEach { tx ->
                val txObj = JSONObject()
                txObj.put("id", tx.id)
                txObj.put("amount", tx.amount)
                txObj.put("type", tx.type)
                txObj.put("category", tx.category)
                txObj.put("dateTime", tx.dateTime)
                txObj.put("note", tx.note)
                txObj.put("personName", tx.personName)
                txObj.put("paidAmount", tx.paidAmount)
                txObj.put("repaymentsCsv", tx.repaymentsCsv)
                txArray.put(txObj)
            }
            rootObj.put("transactions", txArray)

            // Notices
            val noticesArray = JSONArray()
            notices.value.forEach { notice ->
                val noticeObj = JSONObject()
                noticeObj.put("id", notice.id)
                noticeObj.put("content", notice.content)
                noticeObj.put("timestamp", notice.timestamp)
                noticeObj.put("colorHex", notice.colorHex)
                noticesArray.put(noticeObj)
            }
            rootObj.put("notices", noticesArray)

            rootObj.toString(2)
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun restoreFromJsonString(jsonString: String): Boolean {
        return try {
            val rootObj = JSONObject(jsonString)
            if (!rootObj.has("transactions") && !rootObj.has("persons") && !rootObj.has("notices") && !rootObj.has("profile")) {
                return false
            }

            // Clear tables
            dao.clearTransactions()
            dao.clearPersons()
            dao.clearNotices()
            dao.clearProfile()

            // Restore Profile
            if (rootObj.has("profile")) {
                val pObj = rootObj.getJSONObject("profile")
                val profileEntity = ProfileEntity(
                    id = pObj.optInt("id", 1),
                    openingBalance = pObj.optDouble("openingBalance", 0.0),
                    monthlySalary = pObj.optDouble("monthlySalary", 0.0),
                    isSalaryIncluded = pObj.optBoolean("isSalaryIncluded", false)
                )
                dao.insertProfile(profileEntity)
            } else {
                dao.insertProfile(ProfileEntity(1, 0.0, 0.0, false))
            }

            // Restore Persons
            if (rootObj.has("persons")) {
                val pArray = rootObj.getJSONArray("persons")
                for (i in 0 until pArray.length()) {
                    val pObj = pArray.getJSONObject(i)
                    val person = PersonEntity(
                        id = pObj.getInt("id"),
                        name = pObj.getString("name")
                    )
                    dao.insertPerson(person)
                }
            }

            // Restore Transactions
            if (rootObj.has("transactions")) {
                val tArray = rootObj.getJSONArray("transactions")
                for (i in 0 until tArray.length()) {
                    val tObj = tArray.getJSONObject(i)
                    val tx = TransactionEntity(
                        id = tObj.getInt("id"),
                        amount = tObj.getDouble("amount"),
                        type = tObj.getString("type"),
                        category = tObj.getString("category"),
                        dateTime = tObj.getLong("dateTime"),
                        note = tObj.optString("note", ""),
                        personName = tObj.optString("personName", "General"),
                        paidAmount = tObj.optDouble("paidAmount", 0.0),
                        repaymentsCsv = tObj.optString("repaymentsCsv", "")
                    )
                    dao.insertTransaction(tx)
                }
            }

            // Restore Notices
            if (rootObj.has("notices")) {
                val nArray = rootObj.getJSONArray("notices")
                for (i in 0 until nArray.length()) {
                    val nObj = nArray.getJSONObject(i)
                    val notice = NoticeEntity(
                        id = nObj.getInt("id"),
                        content = nObj.getString("content"),
                        timestamp = nObj.getLong("timestamp"),
                        colorHex = nObj.optString("colorHex", "#FFF9C4")
                    )
                    dao.insertNotice(notice)
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Dialog confirmation states
    var showDeleteConfirmDialog by mutableStateOf(false)
    var pendingDeleteAction by mutableStateOf<(() -> Unit)?>(null)
    var deleteConfirmTitle by mutableStateOf("")
    var deleteConfirmMessage by mutableStateOf("")

    fun confirmDelete(title: String, message: String, action: () -> Unit) {
        deleteConfirmTitle = title
        deleteConfirmMessage = message
        pendingDeleteAction = action
        showDeleteConfirmDialog = true
    }

    // Undo states
    var lastDeletedPerson by mutableStateOf<PersonEntity?>(null)
    var lastDeletedPersonTransactions by mutableStateOf<List<TransactionEntity>>(emptyList())
    var lastDeletedTransaction by mutableStateOf<TransactionEntity?>(null)
    var lastDeletedNotice by mutableStateOf<NoticeEntity?>(null)

    fun deletePersonWithUndo(person: PersonEntity, onShowSnackbar: (String, () -> Unit) -> Unit) {
        viewModelScope.launch {
            lastDeletedPerson = person
            val allTxs = dao.getAllTransactionsFlow().first()
            lastDeletedPersonTransactions = allTxs.filter { it.personName.trim().equals(person.name.trim(), ignoreCase = true) }
            
            dao.deletePerson(person)
            lastDeletedPersonTransactions.forEach { dao.deleteTransaction(it) }

            onShowSnackbar("${person.name} has been deleted!") {
                viewModelScope.launch {
                    val p = lastDeletedPerson
                    if (p != null) {
                        dao.insertPerson(p)
                        lastDeletedPersonTransactions.forEach { dao.insertTransaction(it) }
                        lastDeletedPerson = null
                        lastDeletedPersonTransactions = emptyList()
                    }
                }
            }
        }
    }

    fun deleteTransactionWithUndo(tx: TransactionEntity, onShowSnackbar: (String, () -> Unit) -> Unit) {
        viewModelScope.launch {
            lastDeletedTransaction = tx
            dao.deleteTransaction(tx)
            
            onShowSnackbar("Transaction has been deleted!") {
                viewModelScope.launch {
                    val t = lastDeletedTransaction
                    if (t != null) {
                        dao.insertTransaction(t)
                        lastDeletedTransaction = null
                    }
                }
            }
        }
    }

    fun deleteNoticeWithUndo(notice: NoticeEntity, onShowSnackbar: (String, () -> Unit) -> Unit) {
        viewModelScope.launch {
            lastDeletedNotice = notice
            dao.deleteNotice(notice)
            
            onShowSnackbar("Note has been deleted!") {
                viewModelScope.launch {
                    val n = lastDeletedNotice
                    if (n != null) {
                        dao.insertNotice(n)
                        lastDeletedNotice = null
                    }
                }
            }
        }
    }

    fun updatePersonName(person: PersonEntity, newName: String) {
        if (newName.trim().isEmpty()) return
        viewModelScope.launch {
            val updatedPerson = person.copy(name = newName.trim())
            dao.insertPerson(updatedPerson)
            
            val txs = dao.getAllTransactionsFlow().first()
            txs.forEach { tx ->
                if (tx.personName.trim().equals(person.name.trim(), ignoreCase = true)) {
                    dao.updateTransaction(tx.copy(personName = newName.trim()))
                }
            }
        }
    }

    init {
        resetTrackerFormDateTime()
        viewModelScope.launch {
            val existing = dao.getProfile()
            if (existing == null) {
                dao.insertProfile(ProfileEntity(1, 0.0, 0.0, false))
            } else {
                openingBalanceInput = if (existing.openingBalance == 0.0) "" else existing.openingBalance.toString()
                monthlySalaryInput = if (existing.monthlySalary == 0.0) "" else existing.monthlySalary.toString()
            }
            try {
                val list = dao.getAllPersonsFlow().first()
                // Do not insert "সাধারণ" default person anymore
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun resetTrackerFormDateTime() {
        dateTimeInput = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
    }

    fun addPerson() {
        val name = newPersonNameInput.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            dao.insertPerson(PersonEntity(name = name))
            newPersonNameInput = ""
        }
    }

    fun deletePerson(person: PersonEntity) {
        viewModelScope.launch {
            dao.deletePerson(person)
        }
    }

    fun updateProfileSettings() {
        val op = openingBalanceInput.toDoubleOrNull() ?: 0.0
        val sal = monthlySalaryInput.toDoubleOrNull() ?: 0.0
        viewModelScope.launch {
            val current = dao.getProfile() ?: ProfileEntity()
            dao.insertProfile(current.copy(openingBalance = op, monthlySalary = sal))
        }
    }

    fun toggleSalaryInclusion(included: Boolean) {
        viewModelScope.launch {
            val current = dao.getProfile() ?: ProfileEntity()
            dao.insertProfile(current.copy(isSalaryIncluded = included))
        }
    }

    fun saveTransaction() {
        val amount = amountInput.toDoubleOrNull() ?: 0.0
        if (amount <= 0.0) return

        val category = categoryInput.trim().ifEmpty { "Others 🪙" }
        val parsedTime = parseDateTime(dateTimeInput)
        val person = selectedPersonName.trim().ifEmpty { "General" }

        viewModelScope.launch {
            if (editingTransactionId != null) {
                dao.updateTransaction(
                    TransactionEntity(
                        id = editingTransactionId!!,
                        amount = amount,
                        type = activeFormType,
                        category = category,
                        dateTime = parsedTime,
                        note = noteInput.trim(),
                        personName = person
                    )
                )
                editingTransactionId = null
            } else {
                dao.insertTransaction(
                    TransactionEntity(
                        amount = amount,
                        type = activeFormType,
                        category = category,
                        dateTime = parsedTime,
                        note = noteInput.trim(),
                        personName = person
                    )
                )
            }
            // Clear inputs and reset dateTime
            amountInput = ""
            categoryInput = ""
            noteInput = ""
            resetTrackerFormDateTime()
        }
    }

    fun startEditingTransaction(tx: TransactionEntity) {
        editingTransactionId = tx.id
        activeFormType = tx.type
        amountInput = tx.amount.toString()
        categoryInput = tx.category
        dateTimeInput = formatDateTime(tx.dateTime)
        noteInput = tx.note
        selectedPersonName = tx.personName
        currentTab = "TRACKER" // Navigate to tracker to edit
    }

    fun deleteTransaction(tx: TransactionEntity) {
        viewModelScope.launch {
            dao.deleteTransaction(tx)
        }
    }

    fun cancelEditing() {
        editingTransactionId = null
        amountInput = ""
        categoryInput = ""
        noteInput = ""
        resetTrackerFormDateTime()
    }

    fun saveNotice() {
        val titleInput = noticeTitleInput.trim()
        val contentInput = noticeContentInput.trim()
        if (titleInput.isEmpty() && contentInput.isEmpty()) return

        val finalTitle: String
        val finalContent: String
        if (titleInput.isNotEmpty()) {
            finalTitle = titleInput
            finalContent = contentInput
        } else {
            val lines = contentInput.lines()
            if (lines.isNotEmpty()) {
                finalTitle = lines.first().trim()
                finalContent = lines.drop(1).joinToString("\n").trim()
            } else {
                finalTitle = ""
                finalContent = ""
            }
        }

        viewModelScope.launch {
            val combinedContent = "$finalTitle===NOTE_TITLE===$finalContent"
            val currentEditing = editingNotice
            if (currentEditing != null) {
                dao.insertNotice(
                    currentEditing.copy(
                        content = combinedContent,
                        timestamp = System.currentTimeMillis()
                    )
                )
                editingNotice = null
            } else {
                dao.insertNotice(
                    NoticeEntity(
                        content = combinedContent,
                        timestamp = System.currentTimeMillis(),
                        colorHex = selectedNoticeColorHex
                    )
                )
            }
            noticeContentInput = ""
            noticeTitleInput = ""
        }
    }

    fun updateNotice(notice: NoticeEntity) {
        viewModelScope.launch {
            dao.insertNotice(notice)
        }
    }

    fun deleteNotice(notice: NoticeEntity) {
        viewModelScope.launch {
            dao.deleteNotice(notice)
        }
    }

    // Helper parser/formatters
    private fun parseDateTime(str: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.parse(str)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun formatDateTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

class FinanceViewModelFactory(private val dao: FinanceDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FinanceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FinanceViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ============================================================================
// MAIN ACTIVITY & COMPOSABLE LAYOUT
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = FinanceDatabase.getDatabase(this)
        val viewModelFactory = FinanceViewModelFactory(db.financeDao())

        setContent {
            MaterialTheme(
                colorScheme = androidx.compose.material3.lightColorScheme(
                    primary = Color(0xFF2E7D32), // Darker green for light theme readability
                    secondary = Color(0xFF1976D2), // Darker blue for readability
                    background = Color(0xFFFFFFFF), // White background
                    surface = Color(0xFFF5F6FA), // Off-white/Light grey card
                    onBackground = Color(0xFF1C1B1F), // Dark text on background
                    onSurface = Color(0xFF1C1B1F) // Dark text on surface
                )
            ) {
                val viewModel: FinanceViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = viewModelFactory
                )
                FinanceApp(viewModel)
            }
        }
    }
}

// ============================================================================
// APP ENTRY VIEW
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FinanceApp(viewModel: FinanceViewModel) {
    val context = LocalContext.current
    val profileState by viewModel.profile.collectAsStateWithLifecycle()
    val transactionsState by viewModel.transactions.collectAsStateWithLifecycle()
    val noticesState by viewModel.notices.collectAsStateWithLifecycle()

    val profile = profileState ?: ProfileEntity()

    // ------------------------------------------------------------------------
    // CALCULATIONS
    // ------------------------------------------------------------------------
    // Filter transactions to get the current month's totals
    val calNow = Calendar.getInstance()
    val thisMonth = calNow.get(Calendar.MONTH)
    val thisYear = calNow.get(Calendar.YEAR)

    var currentMonthTotalIncome = 0.0
    var currentMonthTotalExpense = 0.0

    var todayTotalIncome = 0.0
    var todayTotalExpense = 0.0

    var allTimeTotalIncome = 0.0
    var allTimeTotalExpense = 0.0

    val generalTransactions = expandTransactions(transactionsState)

    generalTransactions.forEach { tx ->
        val calTx = Calendar.getInstance().apply { timeInMillis = tx.dateTime }
        val isThisMonth = calTx.get(Calendar.YEAR) == thisYear && calTx.get(Calendar.MONTH) == thisMonth
        val isToday = isThisMonth && calTx.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)

        if (tx.type == "INCOME") {
            allTimeTotalIncome += tx.amount
            if (isThisMonth) {
                currentMonthTotalIncome += tx.amount
            }
            if (isToday) {
                todayTotalIncome += tx.amount
            }
        } else {
            allTimeTotalExpense += tx.amount
            if (isThisMonth) {
                currentMonthTotalExpense += tx.amount
            }
            if (isToday) {
                todayTotalExpense += tx.amount
            }
        }
    }

    // Include monthly salary if toggled
    val finalIncomeBudget = if (profile.isSalaryIncluded) {
        currentMonthTotalIncome + profile.monthlySalary
    } else {
        currentMonthTotalIncome
    }

    val currentBalance = profile.openingBalance +
            (if (profile.isSalaryIncluded) profile.monthlySalary else 0.0) +
            allTimeTotalIncome - allTimeTotalExpense

    val expenseRatio = if (finalIncomeBudget > 0.0) {
        (currentMonthTotalExpense / finalIncomeBudget).coerceIn(0.0, 1.0)
    } else {
        if (currentMonthTotalExpense > 0.0) 1.0 else 0.0
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val showSnackbarWithUndo: (String, () -> Unit) -> Unit = { message, undoAction ->
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Undo ↩️",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                undoAction()
            }
        }
    }

    if (viewModel.showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showDeleteConfirmDialog = false },
            title = { Text(text = viewModel.deleteConfirmTitle, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text(text = viewModel.deleteConfirmMessage, color = Color(0xFF475569), fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.pendingDeleteAction?.invoke()
                        viewModel.showDeleteConfirmDialog = false
                        viewModel.pendingDeleteAction = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C))
                ) {
                    Text("Yes, Delete", color = Color(0xFF0F172A))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.showDeleteConfirmDialog = false
                        viewModel.pendingDeleteAction = null
                    }
                ) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (viewModel.showBackupDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showBackupDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💾 Backup Data", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Your data has been compiled into backup format. Copy it to your clipboard or share it using the buttons below.",
                        fontSize = 12.sp,
                        color = Color(0xFF475569)
                    )
                    
                    // Display box
                    OutlinedTextField(
                        value = viewModel.backupJsonText,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF334155),
                            unfocusedTextColor = Color(0xFF334155),
                            focusedBorderColor = Color(0xFFCBD5E1),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        )
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clipData = android.content.ClipData.newPlainText("FinanceManagerBackup", viewModel.backupJsonText)
                                clipboardManager.setPrimaryClip(clipData)
                                Toast.makeText(context, "Backup copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text("📋 Copy", fontSize = 12.sp, color = Color.White)
                        }
                        
                        Button(
                            onClick = {
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, viewModel.backupJsonText)
                                    type = "text/plain"
                                }
                                val shareIntent = android.content.Intent.createChooser(sendIntent, "Share Backup JSON")
                                context.startActivity(shareIntent)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text("📤 Share", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.showBackupDialog = false }) {
                    Text("Close", color = Color(0xFF64748B))
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (viewModel.showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showRestoreDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔄 Restore Data", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Paste your backup JSON text below to restore all sections (Transactions, People Dues, and Sticky Notes). Warning: This will overwrite current data!",
                        fontSize = 11.sp,
                        color = Color(0xFFC62828)
                    )
                    
                    OutlinedTextField(
                        value = viewModel.restoreInputText,
                        onValueChange = { viewModel.restoreInputText = it },
                        placeholder = { Text("Paste JSON here...", fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF1976D2),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        )
                    )
                    
                    Button(
                        onClick = {
                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = clipboardManager.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text = clip.getItemAt(0).text?.toString() ?: ""
                                viewModel.restoreInputText = text
                            } else {
                                Toast.makeText(context, "Clipboard is empty!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64748B)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("📋 Paste from Clipboard", fontSize = 12.sp, color = Color.White)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (viewModel.restoreInputText.trim().isEmpty()) {
                            Toast.makeText(context, "Please paste valid JSON text!", Toast.LENGTH_SHORT).show()
                        } else {
                            coroutineScope.launch {
                                val success = viewModel.restoreFromJsonString(viewModel.restoreInputText)
                                if (success) {
                                    Toast.makeText(context, "Data Restored Successfully!", Toast.LENGTH_LONG).show()
                                    viewModel.showRestoreDialog = false
                                } else {
                                    Toast.makeText(context, "Failed to restore. Invalid backup JSON!", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Restore", color = Color.White, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showRestoreDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomNavigationBar(
                activeTab = viewModel.currentTab,
                onTabSelected = { viewModel.currentTab = it }
            )
        },
        floatingActionButton = {
            if (viewModel.currentTab == "NOTICE") {
                FloatingActionButton(
                    onClick = { viewModel.showAddNoticeDialog = true },
                    containerColor = Color(0xFF3498DB),
                    contentColor = Color.White,
                    modifier = Modifier.testTag("add_note_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add New Note"
                    )
                }
            } else if (viewModel.currentTab == "ACCOUNT") {
                FloatingActionButton(
                    onClick = { viewModel.showAddPersonDialog = true },
                    containerColor = Color(0xFF2E7D32),
                    contentColor = Color.White,
                    modifier = Modifier.testTag("add_person_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add New Person"
                    )
                }
            } else if (viewModel.currentTab == "TRACKER") {
                FloatingActionButton(
                    onClick = {
                        viewModel.cancelEditing()
                        viewModel.activeFormType = "EXPENSE"
                        viewModel.resetTrackerFormDateTime()
                        viewModel.showAddTransactionDialog = true
                    },
                    containerColor = Color(0xFF1976D2),
                    contentColor = Color.White,
                    modifier = Modifier.testTag("add_transaction_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Transaction"
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                snackbar = { snackbarData ->
                    Snackbar(
                        snackbarData = snackbarData,
                        containerColor = Color(0xFF1E1E24),
                        contentColor = Color.White,
                        actionColor = Color(0xFF2ECC71),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(innerPadding)
        ) {
            // Main Content Area with scroll state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top App Header
                AppHeader(
                    onBackupClick = {
                        viewModel.backupJsonText = viewModel.generateBackupJsonString()
                        viewModel.showBackupDialog = true
                    },
                    onRestoreClick = {
                        viewModel.restoreInputText = ""
                        viewModel.showRestoreDialog = true
                    }
                )

                // Animated views based on tab choice
                AnimatedContent(
                    targetState = viewModel.currentTab,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "tabChange"
                ) { targetTab ->
                    when (targetTab) {
                        "ACCOUNT" -> {
                            val personsState by viewModel.persons.collectAsStateWithLifecycle()
                            val filteredPersons = remember(personsState) {
                                personsState.filter { !it.name.trim().equals("সাধারণ", ignoreCase = true) && !it.name.trim().equals("general", ignoreCase = true) && it.name.trim().isNotEmpty() }
                            }
                            DuesLedgerSessionView(
                                viewModel = viewModel,
                                transactions = transactionsState,
                                persons = filteredPersons,
                                showSnackbarWithUndo = showSnackbarWithUndo
                            )
                        }
                        "TRACKER" -> {
                            TrackerSessionView(
                                viewModel = viewModel,
                                transactions = transactionsState,
                                thisMonth = thisMonth,
                                thisYear = thisYear,
                                showSnackbarWithUndo = showSnackbarWithUndo
                            )
                        }
                        "NOTICE" -> {
                            NoticeSessionView(
                                viewModel = viewModel,
                                notices = noticesState,
                                showSnackbarWithUndo = showSnackbarWithUndo
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(40.dp))
            }

            // Hidden Note Double Click Popup Dialog
            viewModel.selectedTransactionForDetails?.let { tx ->
                DoubleTapDetailsDialog(
                    transaction = tx,
                    onDismiss = { viewModel.selectedTransactionForDetails = null }
                )
            }
        }
    }
}

// ============================================================================
// HEADER VIEW
// ============================================================================

@Composable
fun AppHeader(onBackupClick: () -> Unit, onRestoreClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Finance Manager 💰",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "All-in-One Personal Dashboard",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Backup Button
                Card(
                    modifier = Modifier
                        .clickable { onBackupClick() }
                        .testTag("backup_header_button"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1976D2).copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(0.5.dp, Color(0xFF1976D2).copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Backup,
                            contentDescription = "Backup",
                            tint = Color(0xFF1976D2),
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "Backup",
                            color = Color(0xFF1976D2),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Restore Button
                Card(
                    modifier = Modifier
                        .clickable { onRestoreClick() }
                        .testTag("restore_header_button"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32).copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(0.5.dp, Color(0xFF2E7D32).copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = "Restore",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "Restore",
                            color = Color(0xFF2E7D32),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// SESSION 1: DUES LEDGER SESSION VIEW (replaces Account Session View)
// ============================================================================

data class Repayment(val amount: Double, val timestamp: Long)

fun parseRepayments(repaymentsCsv: String): List<Repayment> {
    if (repaymentsCsv.trim().isEmpty()) return emptyList()
    return repaymentsCsv.split(";").mapNotNull {
        val parts = it.split("|")
        if (parts.size == 2) {
            val amt = parts[0].toDoubleOrNull()
            val ts = parts[1].toLongOrNull()
            if (amt != null && ts != null) {
                Repayment(amt, ts)
            } else null
        } else null
    }
}

fun formatRepayments(repayments: List<Repayment>): String {
    return repayments.joinToString(";") { "${it.amount}|${it.timestamp}" }
}

fun expandTransactions(txList: List<TransactionEntity>): List<TransactionEntity> {
    val result = ArrayList<TransactionEntity>()
    for (tx in txList) {
        result.add(tx)
        val isPersonal = tx.personName.isNotEmpty() &&
                !tx.personName.trim().equals("সাধারণ", ignoreCase = true) &&
                !tx.personName.trim().equals("general", ignoreCase = true)
        if (isPersonal && tx.repaymentsCsv.trim().isNotEmpty()) {
            val repayments = parseRepayments(tx.repaymentsCsv)
            for (rep in repayments) {
                val syntheticType = if (tx.type == "EXPENSE") "INCOME" else "EXPENSE"
                val syntheticCategory = "Repayment: ${tx.category}"
                result.add(
                    TransactionEntity(
                        id = -tx.id - rep.timestamp.toInt().coerceAtLeast(1),
                        amount = rep.amount,
                        type = syntheticType,
                        category = syntheticCategory,
                        dateTime = rep.timestamp,
                        note = "From/To ${tx.personName}",
                        personName = tx.personName,
                        paidAmount = 0.0,
                        repaymentsCsv = ""
                    )
                )
            }
        }
    }
    return result
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DuesLedgerSessionView(
    viewModel: FinanceViewModel,
    transactions: List<TransactionEntity>,
    persons: List<PersonEntity>,
    showSnackbarWithUndo: (String, () -> Unit) -> Unit
) {
    val context = LocalContext.current
    var expandedPersonId by remember { mutableStateOf<Int?>(null) }

    var showEditPersonDialog by remember { mutableStateOf(false) }
    var personToEdit by remember { mutableStateOf<PersonEntity?>(null) }
    var editPersonNameInput by remember { mutableStateOf("") }

    var showAddTxDialogForPerson by remember { mutableStateOf<PersonEntity?>(null) }
    var showAddRepaymentDialogForTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var showEditTransactionAndRepaymentsDialog by remember { mutableStateOf<TransactionEntity?>(null) }

    data class PersonDuesModel(
        val person: PersonEntity,
        val totalGiven: Double,
        val totalTaken: Double,
        val netBalance: Double,
        val txCount: Int
    )

    // Calculations across all people
    var totalReceivable = 0.0
    var totalPayable = 0.0

    val personDuesList = persons.map { person ->
        val personTx = transactions.filter { it.personName.trim().lowercase() == person.name.trim().lowercase() }
        val incomeFromPerson = personTx.filter { it.type == "INCOME" }.sumOf { maxOf(0.0, it.amount - it.paidAmount) }
        val expenseToPerson = personTx.filter { it.type == "EXPENSE" }.sumOf { maxOf(0.0, it.amount - it.paidAmount) }
        val netBalance = expenseToPerson - incomeFromPerson

        totalReceivable += expenseToPerson
        totalPayable += incomeFromPerson

        PersonDuesModel(
            person = person,
            totalGiven = expenseToPerson,
            totalTaken = incomeFromPerson,
            netBalance = netBalance,
            txCount = personTx.size
        )
    }

    val netStanding = totalReceivable - totalPayable

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // 1. BIG SUMMARY CARD FOR DUES
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Total Receivable Block
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(text = "Receiveable", color = Color(0xFF475569), fontSize = 9.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "৳ " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", totalReceivable)),
                            color = Color(0xFF2E7D32),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    // Divider 1
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(30.dp)
                            .background(Color(0xFFE2E8F0))
                    )

                    // Total Payable Block
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Payable", color = Color(0xFF475569), fontSize = 9.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "৳ " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", totalPayable)),
                            color = Color(0xFFC62828),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    // Divider 2
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(30.dp)
                            .background(Color(0xFFE2E8F0))
                    )

                    // Net Status Block
                    val netStandingAbs = if (netStanding >= 0.0) netStanding else -netStanding
                    val netColor = if (netStanding > 0.0) Color(0xFF2E7D32) else if (netStanding < 0.0) Color(0xFFC62828) else Color(0xFF1976D2)
                    val netPrefix = if (netStanding >= 0.0) "৳ " else "-৳ "

                    Column(
                        modifier = Modifier
                            .weight(1.2f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(text = "Net Status", color = Color(0xFF475569), fontSize = 9.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = netPrefix + convertToBengaliNumber(String.format(Locale.US, "%,.0f", netStandingAbs)),
                            color = netColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        // 3. INDIVIDUAL DUES LIST
        Text(
            text = "📋 People Dues List (" + convertToBengaliNumber(persons.size.toString()) + ")",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A),
            modifier = Modifier.padding(top = 4.dp)
        )

        if (persons.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("👥", fontSize = 32.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No people found!",
                        color = Color(0xFF475569),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Click the floating action button (+) at the bottom right corner to add a new person to start tracking individual debts and dues.",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                personDuesList.forEach { model ->
                    val person = model.person
                    val totalGiven = model.totalGiven
                    val totalTaken = model.totalTaken
                    val netBalance = model.netBalance
                    val txCount = model.txCount

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (netBalance > 0) Color(0xFF2E7D32).copy(alpha = 0.25f) else if (netBalance < 0) Color(0xFFC62828).copy(alpha = 0.25f) else Color(0xFFE2E8F0)
                        )
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            expandedPersonId = if (expandedPersonId == person.id) null else person.id
                                        },
                                        onLongClick = {
                                            viewModel.confirmDelete(
                                                title = "Delete Person Confirmation",
                                                message = "Do you want to delete ${person.name} and all of their transactions?",
                                                action = {
                                                    viewModel.deletePersonWithUndo(person, showSnackbarWithUndo)
                                                }
                                            )
                                        }
                                    )
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    // Avatar circle with first letter
                                    val avatarChar = if (person.name.trim().isNotEmpty()) person.name.trim()[0].toString() else "?"
                                    val avatarBgColor = if (netBalance > 0) Color(0xFF2E7D32).copy(alpha = 0.12f) else if (netBalance < 0) Color(0xFFC62828).copy(alpha = 0.12f) else Color(0xFFF1F5F9)
                                    val avatarTextColor = if (netBalance > 0) Color(0xFF2E7D32) else if (netBalance < 0) Color(0xFFC62828) else Color(0xFF0F172A)
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(avatarBgColor)
                                    ) {
                                        Text(
                                            text = avatarChar,
                                            color = avatarTextColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = person.name,
                                            color = Color(0xFF0F172A),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val netText = if (netBalance > 0) {
                                            "Net: You will receive ৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", netBalance))
                                        } else if (netBalance < 0) {
                                            "Net: You owe ৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", -netBalance))
                                        } else {
                                            "Net: Balanced"
                                        }
                                        val netColor = if (netBalance > 0) Color(0xFF2E7D32) else if (netBalance < 0) Color(0xFFC62828) else Color(0xFF64748B)
                                        Text(
                                            text = netText,
                                            color = netColor,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                // Edit button
                                if (!person.name.trim().equals("সাধারণ", ignoreCase = true) && !person.name.trim().equals("general", ignoreCase = true)) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF1976D2).copy(alpha = 0.08f))
                                            .clickable {
                                                personToEdit = person
                                                editPersonNameInput = person.name
                                                showEditPersonDialog = true
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✏️", fontSize = 10.sp)
                                    }
                                }
                            }

                            // EXPANDED SECTION WITH INDIVIDUAL TRANSACTION DETAILS
                            if (expandedPersonId == person.id) {
                                Divider(color = Color(0xFFE2E8F0), thickness = 1.dp)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF8FAFC))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // "Pressing a person's name shows a button to add a new transaction"
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Button(
                                            onClick = { showAddTxDialogForPerson = person },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text("➕ Add New Transaction", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    // Transaction history list for this person
                                    val personTx = transactions.filter { it.personName.trim().lowercase() == person.name.trim().lowercase() }
                                        .sortedByDescending { it.dateTime }

                                    Text(
                                        text = "📜 Transaction History (" + convertToBengaliNumber(personTx.size.toString()) + "):",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF475569)
                                    )

                                    if (personTx.isEmpty()) {
                                        Text(
                                            text = "No previous transactions.",
                                            fontSize = 10.sp,
                                            color = Color(0xFF64748B),
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            personTx.forEach { tx ->
                                                val dateStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(tx.dateTime))
                                                val isInc = tx.type == "INCOME"
                                                val prefix = if (isInc) "+" else "-"
                                                val color = if (isInc) Color(0xFF2E7D32) else Color(0xFFC62828)
                                                
                                                val repayments = parseRepayments(tx.repaymentsCsv)
                                                val totalRepaymentsAmount = repayments.sumOf { it.amount }
                                                val remaining = maxOf(0.0, tx.amount - totalRepaymentsAmount)
                                                val isFullyPaid = totalRepaymentsAmount >= tx.amount

                                                // Card layout representing one transaction block
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .combinedClickable(
                                                            onClick = {
                                                                // Single click does nothing or triggers details, let's keep it safe
                                                            },
                                                            onDoubleClick = {
                                                                // "লিস্টে ডাবল টেপ একটা পপাপ উইন্ডো তে বিবরন তারিখ ও টাকা ও প্রতেক্টা পরিশোধ তারিখ ও টাকা এডিট করা যাবে"
                                                                showEditTransactionAndRepaymentsDialog = tx
                                                            },
                                                            onLongClick = {
                                                                // "লং প্রেস ডিলেট, আর ডিলেট বাটন বাদ"
                                                                viewModel.confirmDelete(
                                                                    title = "Delete Transaction Confirmation",
                                                                    message = "Are you sure you want to delete this transaction?",
                                                                    action = {
                                                                        viewModel.deleteTransactionWithUndo(tx, showSnackbarWithUndo)
                                                                    }
                                                                )
                                                            }
                                                        ),
                                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                                    shape = RoundedCornerShape(8.dp),
                                                    border = BorderStroke(0.5.dp, Color(0xFFE2E8F0))
                                                ) {
                                                    Column(modifier = Modifier.padding(8.dp)) {
                                                        // Line 1: বিবরন তারিখ-সময় টাকা
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                Text(
                                                                    text = tx.category,
                                                                    color = Color(0xFF0F172A),
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                Text(
                                                                    text = convertToBengaliNumber(dateStr),
                                                                    color = Color(0xFF64748B),
                                                                    fontSize = 8.sp
                                                                )
                                                            }
                                                            Text(
                                                                text = "$prefix ৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", tx.amount)),
                                                                color = color,
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }

                                                        // Line 2+: repayments listed on their own line
                                                        repayments.forEachIndexed { repIdx, rep ->
                                                            val repDateStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(rep.timestamp))
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(top = 4.dp, start = 8.dp),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                                ) {
                                                                    Text(
                                                                        text = "Repaid",
                                                                        color = Color(0xFF1976D2),
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Medium
                                                                    )
                                                                    Text(
                                                                        text = convertToBengaliNumber(repDateStr),
                                                                        color = Color(0xFF64748B),
                                                                        fontSize = 8.sp
                                                                    )
                                                                }
                                                                Text(
                                                                    text = "৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", rep.amount)),
                                                                    color = Color(0xFF475569),
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                            }
                                                        }

                                                        Divider(color = Color(0xFFF1F5F9), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))

                                                        // Bottom Line: পরিশোধ যোগ(বাটন) বাকী: <amount> টাকা
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Button(
                                                                onClick = { showAddRepaymentDialogForTx = tx },
                                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                                                                shape = RoundedCornerShape(6.dp),
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                modifier = Modifier.height(24.dp)
                                                            ) {
                                                                Text("Add Pay ➕", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                                            }

                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                            ) {
                                                                Text(
                                                                    text = "Remaining:",
                                                                    color = Color(0xFF64748B),
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                                val remainingColor = if (isFullyPaid) Color(0xFF2E7D32) else Color(0xFFC62828)
                                                                val remainingWeight = if (isFullyPaid) FontWeight.Normal else FontWeight.Bold
                                                                val remainingText = if (isFullyPaid) "Fully Paid ✅" else "৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", remaining))
                                                                Text(
                                                                    text = remainingText,
                                                                    color = remainingColor,
                                                                    fontSize = 10.sp,
                                                                    fontWeight = remainingWeight
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // POPUP DIALOGS
    // ------------------------------------------------------------------------

    // Add Person Dialog (triggered from FAB in Parent Scaffold)
    if (viewModel.showAddPersonDialog) {
        Dialog(onDismissRequest = { viewModel.showAddPersonDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "👥 Add New Person",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    OutlinedTextField(
                        value = viewModel.newPersonNameInput,
                        onValueChange = { viewModel.newPersonNameInput = it },
                        placeholder = { Text("Enter name...", fontSize = 14.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF2E7D32),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { viewModel.showAddPersonDialog = false }) {
                            Text("Cancel", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                if (viewModel.newPersonNameInput.trim().isNotEmpty()) {
                                    viewModel.addPerson()
                                    viewModel.showAddPersonDialog = false
                                    Toast.makeText(context, "New person added successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Please enter a valid name!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Save 💾", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Add Transaction for specific person Dialog
    if (showAddTxDialogForPerson != null) {
        val person = showAddTxDialogForPerson!!
        var quickAmountInput by remember { mutableStateOf("") }
        var quickCategoryInput by remember { mutableStateOf("") }

        Dialog(onDismissRequest = { showAddTxDialogForPerson = null }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "➕ New Transaction (${person.name})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    OutlinedTextField(
                        value = quickAmountInput,
                        onValueChange = { quickAmountInput = it },
                        placeholder = { Text("Amount ৳", fontSize = 14.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF1976D2),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                    OutlinedTextField(
                        value = quickCategoryInput,
                        onValueChange = { quickCategoryInput = it },
                        placeholder = { Text("Description (e.g. loan, payment)", fontSize = 14.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF1976D2),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                val amt = quickAmountInput.toDoubleOrNull() ?: 0.0
                                if (amt > 0.0) {
                                    val desc = quickCategoryInput.trim().ifEmpty { "Gave Money" }
                                    viewModel.viewModelScope.launch {
                                        viewModel.dao.insertTransaction(
                                            TransactionEntity(
                                                amount = amt,
                                                type = "EXPENSE",
                                                category = desc,
                                                dateTime = System.currentTimeMillis(),
                                                note = "",
                                                personName = person.name
                                            )
                                        )
                                    }
                                    showAddTxDialogForPerson = null
                                    Toast.makeText(context, "Transaction saved successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Please enter a valid amount!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f).height(34.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("I Gave 📉", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }

                        Button(
                            onClick = {
                                val amt = quickAmountInput.toDoubleOrNull() ?: 0.0
                                if (amt > 0.0) {
                                    val desc = quickCategoryInput.trim().ifEmpty { "Received Money" }
                                    viewModel.viewModelScope.launch {
                                        viewModel.dao.insertTransaction(
                                            TransactionEntity(
                                                amount = amt,
                                                type = "INCOME",
                                                category = desc,
                                                dateTime = System.currentTimeMillis(),
                                                note = "",
                                                personName = person.name
                                            )
                                        )
                                    }
                                    showAddTxDialogForPerson = null
                                    Toast.makeText(context, "Transaction saved successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Please enter a valid amount!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f).height(34.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("I Got 📈", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }

    // Add Repayment Dialog
    if (showAddRepaymentDialogForTx != null) {
        val tx = showAddRepaymentDialogForTx!!
        var repaymentAmountInput by remember { mutableStateOf("") }

        Dialog(onDismissRequest = { showAddRepaymentDialogForTx = null }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "💸 Add New Repayment",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    OutlinedTextField(
                        value = repaymentAmountInput,
                        onValueChange = { repaymentAmountInput = it },
                        placeholder = { Text("Amount ৳", fontSize = 14.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF2E7D32),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showAddRepaymentDialogForTx = null }) {
                            Text("Cancel", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                val amt = repaymentAmountInput.toDoubleOrNull() ?: 0.0
                                if (amt > 0.0) {
                                    val repayments = parseRepayments(tx.repaymentsCsv).toMutableList()
                                    repayments.add(Repayment(amt, System.currentTimeMillis()))
                                    val updatedCsv = formatRepayments(repayments)
                                    val totalPaid = repayments.sumOf { it.amount }

                                    viewModel.viewModelScope.launch {
                                        viewModel.dao.updateTransaction(
                                            tx.copy(
                                                repaymentsCsv = updatedCsv,
                                                paidAmount = totalPaid
                                            )
                                        )
                                    }
                                    showAddRepaymentDialogForTx = null
                                    Toast.makeText(context, "Repayment added successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Please enter a valid amount!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Edit Transaction and Repayments Dialog
    if (showEditTransactionAndRepaymentsDialog != null) {
        val tx = showEditTransactionAndRepaymentsDialog!!
        var categoryInput by remember { mutableStateOf(tx.category) }
        var amountInput by remember { mutableStateOf(tx.amount.toString()) }
        var dateTimeInput by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(tx.dateTime))) }
        var typeInput by remember { mutableStateOf(tx.type) }

        val initialRepayments = remember(tx.repaymentsCsv) { parseRepayments(tx.repaymentsCsv) }
        var repaymentsState by remember { mutableStateOf(initialRepayments) }

        Dialog(onDismissRequest = { showEditTransactionAndRepaymentsDialog = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .heightIn(max = 520.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "✏️ Edit Transaction & Repayments",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = { typeInput = "EXPENSE" },
                                modifier = Modifier.weight(1f).height(32.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (typeInput == "EXPENSE") Color(0xFFC62828) else Color(0xFFF1F5F9),
                                    contentColor = if (typeInput == "EXPENSE") Color.White else Color(0xFF0F172A)
                                ),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("I Gave 📉", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }

                            Button(
                                onClick = { typeInput = "INCOME" },
                                modifier = Modifier.weight(1f).height(32.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (typeInput == "INCOME") Color(0xFF2E7D32) else Color(0xFFF1F5F9),
                                    contentColor = if (typeInput == "INCOME") Color.White else Color(0xFF0F172A)
                                ),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("I Got 📈", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                        }

                        OutlinedTextField(
                            value = categoryInput,
                            onValueChange = { categoryInput = it },
                            label = { Text("Description", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFF0F172A),
                                unfocusedTextColor = Color(0xFF0F172A),
                                focusedBorderColor = Color(0xFF1976D2),
                                unfocusedBorderColor = Color(0xFFCBD5E1)
                            ),
                            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                        )

                        OutlinedTextField(
                            value = amountInput,
                            onValueChange = { amountInput = it },
                            label = { Text("Main Amount ৳", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFF0F172A),
                                unfocusedTextColor = Color(0xFF0F172A),
                                focusedBorderColor = Color(0xFF1976D2),
                                unfocusedBorderColor = Color(0xFFCBD5E1)
                            ),
                            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                        )

                        val calendar = Calendar.getInstance()
                        if (dateTimeInput.isNotEmpty()) {
                            try {
                                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                val parsedDate = sdf.parse(dateTimeInput)
                                if (parsedDate != null) {
                                    calendar.time = parsedDate
                                }
                            } catch (e: Exception) {}
                        }

                        val datePickerDialog = android.app.DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                calendar.set(Calendar.YEAR, year)
                                calendar.set(Calendar.MONTH, month)
                                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                
                                android.app.TimePickerDialog(
                                    context,
                                    { _, hourOfDay, minute ->
                                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                                        calendar.set(Calendar.MINUTE, minute)
                                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                        dateTimeInput = sdf.format(calendar.time)
                                    },
                                    calendar.get(Calendar.HOUR_OF_DAY),
                                    calendar.get(Calendar.MINUTE),
                                    true
                                ).show()
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { datePickerDialog.show() }
                        ) {
                            OutlinedTextField(
                                value = dateTimeInput,
                                onValueChange = { },
                                readOnly = true,
                                enabled = false,
                                label = { Text("Date (YYYY-MM-DD HH:MM)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = Color(0xFF0F172A),
                                    disabledBorderColor = Color(0xFFCBD5E1),
                                    disabledPlaceholderColor = Color(0xFF64748B)
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                            )
                        }

                        if (repaymentsState.isNotEmpty()) {
                            Text(
                                text = "💸 Repayments List:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF475569)
                            )

                            repaymentsState.forEachIndexed { index, repayment ->
                                var repAmt by remember(repayment) { mutableStateOf(repayment.amount.toString()) }
                                var repDate by remember(repayment) { mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(repayment.timestamp))) }

                                val repCalendar = Calendar.getInstance().apply { timeInMillis = repayment.timestamp }
                                val repDatePickerDialog = android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        repCalendar.set(Calendar.YEAR, year)
                                        repCalendar.set(Calendar.MONTH, month)
                                        repCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                        
                                        android.app.TimePickerDialog(
                                            context,
                                            { _, hourOfDay, minute ->
                                                repCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                                                repCalendar.set(Calendar.MINUTE, minute)
                                                val ts = repCalendar.timeInMillis
                                                repaymentsState = repaymentsState.toMutableList().apply {
                                                    this[index] = Repayment(repaymentsState[index].amount, ts)
                                                }
                                                repDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
                                            },
                                            repCalendar.get(Calendar.HOUR_OF_DAY),
                                            repCalendar.get(Calendar.MINUTE),
                                            true
                                        ).show()
                                    },
                                    repCalendar.get(Calendar.YEAR),
                                    repCalendar.get(Calendar.MONTH),
                                    repCalendar.get(Calendar.DAY_OF_MONTH)
                                )

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                    border = BorderStroke(0.5.dp, Color(0xFFE2E8F0))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = repAmt,
                                            onValueChange = {
                                                repAmt = it
                                                val dVal = it.toDoubleOrNull() ?: 0.0
                                                repaymentsState = repaymentsState.toMutableList().apply {
                                                    this[index] = Repayment(dVal, repaymentsState[index].timestamp)
                                                }
                                            },
                                            label = { Text("৳", fontSize = 10.sp) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = Color(0xFF0F172A),
                                                unfocusedTextColor = Color(0xFF0F172A),
                                                focusedBorderColor = Color(0xFF1976D2),
                                                unfocusedBorderColor = Color(0xFFCBD5E1)
                                            ),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                                        )

                                        Box(
                                            modifier = Modifier
                                                .weight(1.8f)
                                                .clickable { repDatePickerDialog.show() }
                                        ) {
                                            OutlinedTextField(
                                                value = repDate,
                                                onValueChange = { },
                                                readOnly = true,
                                                enabled = false,
                                                label = { Text("Date", fontSize = 10.sp) },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    disabledTextColor = Color(0xFF0F172A),
                                                    disabledBorderColor = Color(0xFFCBD5E1),
                                                    disabledPlaceholderColor = Color(0xFF64748B)
                                                ),
                                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                repaymentsState = repaymentsState.toMutableList().apply {
                                                    removeAt(index)
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Text("🗑️", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showEditTransactionAndRepaymentsDialog = null }) {
                            Text("Cancel", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                val finalAmt = amountInput.toDoubleOrNull() ?: tx.amount
                                val finalCategory = categoryInput.trim().ifEmpty { tx.category }
                                val finalTime = try {
                                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(dateTimeInput)?.time ?: tx.dateTime
                                } catch (e: Exception) {
                                    tx.dateTime
                                }

                                val filteredRepayments = repaymentsState.filter { it.amount > 0.0 }
                                val updatedCsv = formatRepayments(filteredRepayments)
                                val finalPaid = filteredRepayments.sumOf { it.amount }

                                viewModel.viewModelScope.launch {
                                    viewModel.dao.updateTransaction(
                                        tx.copy(
                                            amount = finalAmt,
                                            type = typeInput,
                                            category = finalCategory,
                                            dateTime = finalTime,
                                            repaymentsCsv = updatedCsv,
                                            paidAmount = finalPaid
                                        )
                                    )
                                }
                                showEditTransactionAndRepaymentsDialog = null
                                Toast.makeText(context, "Transaction updated successfully!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Person Name Editor Dialog
    if (showEditPersonDialog && personToEdit != null) {
        AlertDialog(
            onDismissRequest = {
                showEditPersonDialog = false
                personToEdit = null
            },
            title = { Text("Edit Name", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 14.sp) },
            text = {
                OutlinedTextField(
                    value = editPersonNameInput,
                    onValueChange = { editPersonNameInput = it },
                    placeholder = { Text("Enter new name", fontSize = 14.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF0F172A),
                        unfocusedTextColor = Color(0xFF0F172A),
                        focusedBorderColor = Color(0xFF1976D2),
                        unfocusedBorderColor = Color(0xFFCBD5E1)
                    ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newName = editPersonNameInput.trim()
                        if (newName.isNotEmpty()) {
                            viewModel.updatePersonName(personToEdit!!, newName)
                            showEditPersonDialog = false
                            personToEdit = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Save", color = Color.White, fontSize = 11.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showEditPersonDialog = false
                        personToEdit = null
                    }
                ) {
                    Text("Cancel", color = Color(0xFF64748B), fontSize = 11.sp)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

fun convertToBengaliNumber(numberStr: String): String {
    return numberStr
}

// ============================================================================
// DAILY SUMMARY MODEL
// ============================================================================
data class DailySummaryModel(
    val dateString: String,
    val timestamp: Long,
    var income: Double = 0.0,
    var expense: Double = 0.0,
    var balanceAtEnd: Double = 0.0
)

@Composable
fun TrackerSessionView(
    viewModel: FinanceViewModel,
    transactions: List<TransactionEntity>,
    thisMonth: Int,
    thisYear: Int,
    showSnackbarWithUndo: (String, () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val profileState by viewModel.profile.collectAsStateWithLifecycle()
    val profile = profileState ?: ProfileEntity()

    var dialogType by remember { mutableStateOf("EXPENSE") } // "INCOME" or "EXPENSE"

    // Sync dialogType with activeFormType when dialog is shown
    LaunchedEffect(viewModel.showAddTransactionDialog) {
        if (viewModel.showAddTransactionDialog) {
            dialogType = viewModel.activeFormType
        }
    }

    // If we enter edit mode from somewhere, automatically show the dialog
    LaunchedEffect(viewModel.editingTransactionId) {
        if (viewModel.editingTransactionId != null) {
            dialogType = viewModel.activeFormType
            viewModel.showAddTransactionDialog = true
        }
    }

    val generalTransactions = expandTransactions(transactions)

    // 1. Calculate Carry-over Balance from previous months if month is filtered
    val isMonthFiltered = viewModel.filterYear != "ALL" && viewModel.filterMonth != "ALL"
    val carryOverFromPrevMonths = if (isMonthFiltered) {
        val selYear = viewModel.filterYear.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
        val selMonth = viewModel.filterMonth.toIntOrNull() ?: Calendar.getInstance().get(Calendar.MONTH)
        
        val selCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, selYear)
            set(Calendar.MONTH, selMonth)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfSelectedMonth = selCal.timeInMillis
        
        val prevTxs = generalTransactions.filter { it.dateTime < startOfSelectedMonth }
        val prevInc = prevTxs.filter { it.type == "INCOME" }.sumOf { it.amount }
        val prevExp = prevTxs.filter { it.type == "EXPENSE" }.sumOf { it.amount }
        
        val sal = if (profile.isSalaryIncluded) profile.monthlySalary else 0.0
        profile.openingBalance + sal + prevInc - prevExp
    } else {
        0.0
    }

    // 2. Process all transactions chronologically to calculate running balance and daily summary
    val allTransactionsSorted = generalTransactions.sortedBy { it.dateTime }
    val dailyStatsMap = LinkedHashMap<String, DailySummaryModel>()
    
    // Running balance starts with opening balance (and monthly salary if included)
    var runningBalance = profile.openingBalance + (if (profile.isSalaryIncluded) profile.monthlySalary else 0.0)
    
    val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val labelFormat = SimpleDateFormat("d MMMM yy", Locale.US)
    
    allTransactionsSorted.forEach { tx ->
        val key = keyFormat.format(Date(tx.dateTime))
        
        if (tx.type == "INCOME") {
            runningBalance += tx.amount
        } else {
            runningBalance -= tx.amount
        }
        
        val stats = dailyStatsMap.getOrPut(key) {
            DailySummaryModel(
                dateString = convertToBengaliNumber(labelFormat.format(Date(tx.dateTime))),
                timestamp = tx.dateTime,
                income = 0.0,
                expense = 0.0,
                balanceAtEnd = 0.0
            )
        }
        
        if (tx.type == "INCOME") {
            stats.income += tx.amount
        } else {
            stats.expense += tx.amount
        }
        stats.balanceAtEnd = runningBalance
    }
    
    // Filter the daily summary days based on dropdown filters (Year, Month, Day) and sort descending (newest first)
    val activeDailySummaries = dailyStatsMap.values.toList().filter { summary ->
        val cal = Calendar.getInstance().apply { timeInMillis = summary.timestamp }
        val matchYear = viewModel.filterYear == "ALL" || cal.get(Calendar.YEAR).toString() == viewModel.filterYear
        val matchMonth = viewModel.filterMonth == "ALL" || cal.get(Calendar.MONTH).toString() == viewModel.filterMonth
        val matchDay = viewModel.filterDay == "ALL" || cal.get(Calendar.DAY_OF_MONTH).toString() == viewModel.filterDay
        
        matchYear && matchMonth && matchDay
    }.sortedByDescending { it.timestamp }

    // Category Lists
    val defaultIncomeCategories = listOf("Salary 💼", "Business 📈", "Freelancing 💻", "Investment 🏦", "Gift 🎁", "Others 🪙")
    val defaultExpenseCategories = listOf("Food 🍔", "Rent 🏠", "Bills ⚡", "Transport 🚗", "Shopping 🛍️", "Medicine 💊", "Entertainment 🎬", "Education 📚", "Others 💸")

    val activeCategories = if (viewModel.activeFormType == "INCOME") defaultIncomeCategories else defaultExpenseCategories

    // Apply Filters first so we can calculate and display totals at the absolute top
    val filteredTxList = generalTransactions.filter { tx ->
        val calTx = Calendar.getInstance().apply { timeInMillis = tx.dateTime }
        val matchYear = viewModel.filterYear == "ALL" || calTx.get(Calendar.YEAR).toString() == viewModel.filterYear
        val matchMonth = viewModel.filterMonth == "ALL" || calTx.get(Calendar.MONTH).toString() == viewModel.filterMonth
        val matchDay = viewModel.filterDay == "ALL" || calTx.get(Calendar.DAY_OF_MONTH).toString() == viewModel.filterDay
        val matchCategory = viewModel.filterCategoryQuery.trim().isEmpty() || tx.category.contains(viewModel.filterCategoryQuery, ignoreCase = true)

        matchYear && matchMonth && matchDay && matchCategory
    }.sortedByDescending { it.dateTime }

    // Summary calculations filter ONLY by date, completely ignoring search filter
    val dateFilteredTxList = generalTransactions.filter { tx ->
        val calTx = Calendar.getInstance().apply { timeInMillis = tx.dateTime }
        val matchYear = viewModel.filterYear == "ALL" || calTx.get(Calendar.YEAR).toString() == viewModel.filterYear
        val matchMonth = viewModel.filterMonth == "ALL" || calTx.get(Calendar.MONTH).toString() == viewModel.filterMonth
        val matchDay = viewModel.filterDay == "ALL" || calTx.get(Calendar.DAY_OF_MONTH).toString() == viewModel.filterDay

        matchYear && matchMonth && matchDay
    }

    val filteredIncomeSum = dateFilteredTxList.filter { it.type == "INCOME" }.sumOf { it.amount }
    val filteredExpenseSum = dateFilteredTxList.filter { it.type == "EXPENSE" }.sumOf { it.amount }
    val filteredBalance = filteredIncomeSum - filteredExpenseSum + carryOverFromPrevMonths

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // 0. Dynamic visual summary card for the active filtered set - AT THE VERY TOP
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = BorderStroke(1.dp, Color(0xFFCBD5E1))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFEFF6FF), // Soft elegant blue
                                Color(0xFFF5F3FF)  // Soft elegant purple
                            )
                        )
                    )
                    .padding(14.dp)
            ) {
                if (isMonthFiltered) {
                    Text(
                        text = "Pre. Month: ৳ ${convertToBengaliNumber(String.format(Locale.US, "%,.0f", carryOverFromPrevMonths))}",
                        fontSize = 11.sp,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                        Text("income", fontSize = 9.sp, color = Color(0xFF64748B))
                        Text(
                            text = "৳ ${convertToBengaliNumber(String.format(Locale.US, "%,.0f", filteredIncomeSum))}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF2E7D32),
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(30.dp)
                            .background(Color(0xFFE2E8F0))
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text("Expense", fontSize = 9.sp, color = Color(0xFF64748B))
                        Text(
                            text = "৳ ${convertToBengaliNumber(String.format(Locale.US, "%,.0f", filteredExpenseSum))}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFC62828),
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(30.dp)
                            .background(Color(0xFFE2E8F0))
                    )
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1.2f)) {
                        Text("Balance", fontSize = 9.sp, color = Color(0xFF64748B))
                        Text(
                            text = "৳ ${convertToBengaliNumber(String.format(Locale.US, "%,.0f", filteredBalance))}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = if (filteredBalance >= 0.0) Color(0xFF2E7D32) else Color(0xFFC62828),
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
            }
        }

        if (viewModel.showAddTransactionDialog) {
            AlertDialog(
                onDismissRequest = {
                    viewModel.showAddTransactionDialog = false
                    viewModel.cancelEditing()
                },
                title = {
                    val titleText = if (viewModel.editingTransactionId != null) {
                        "Edit Transaction ✏️"
                    } else if (dialogType == "INCOME") {
                        "Add Income 📈"
                    } else {
                        "Add Expense 📉"
                    }
                    Text(
                        text = titleText,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 0. Toggle between INCOME and EXPENSE
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    dialogType = "INCOME"
                                    viewModel.activeFormType = "INCOME"
                                },
                                modifier = Modifier.weight(1f).height(36.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (dialogType == "INCOME") Color(0xFF2E7D32) else Color(0xFFF1F5F9),
                                    contentColor = if (dialogType == "INCOME") Color.White else Color(0xFF0F172A)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Income 📈", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    dialogType = "EXPENSE"
                                    viewModel.activeFormType = "EXPENSE"
                                },
                                modifier = Modifier.weight(1f).height(36.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (dialogType == "EXPENSE") Color(0xFFC62828) else Color(0xFFF1F5F9),
                                    contentColor = if (dialogType == "EXPENSE") Color.White else Color(0xFF0F172A)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Expense 📉", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        val calendar = Calendar.getInstance()
                        if (viewModel.dateTimeInput.isNotEmpty()) {
                            try {
                                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                val parsedDate = sdf.parse(viewModel.dateTimeInput)
                                if (parsedDate != null) {
                                    calendar.time = parsedDate
                                }
                            } catch (e: Exception) {}
                        } else {
                            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            viewModel.dateTimeInput = sdf.format(calendar.time)
                        }

                        val datePickerDialog = android.app.DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                calendar.set(Calendar.YEAR, year)
                                calendar.set(Calendar.MONTH, month)
                                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                
                                android.app.TimePickerDialog(
                                    context,
                                    { _, hourOfDay, minute ->
                                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                                        calendar.set(Calendar.MINUTE, minute)
                                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                        viewModel.dateTimeInput = sdf.format(calendar.time)
                                    },
                                    calendar.get(Calendar.HOUR_OF_DAY),
                                    calendar.get(Calendar.MINUTE),
                                    true
                                ).show()
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        )

                        // 1 & 2. Category & Amount side-by-side
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = viewModel.categoryInput,
                                onValueChange = { viewModel.categoryInput = it },
                                placeholder = { Text("Category (e.g. food)", fontSize = 14.sp, maxLines = 1, softWrap = false) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFF0F172A),
                                    unfocusedTextColor = Color(0xFF0F172A),
                                    focusedBorderColor = if (dialogType == "INCOME") Color(0xFF2E7D32) else Color(0xFFC62828),
                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                                modifier = Modifier.weight(1.2f).height(56.dp)
                            )

                            OutlinedTextField(
                                value = viewModel.amountInput,
                                onValueChange = { viewModel.amountInput = it },
                                placeholder = { Text("Amount (৳)", fontSize = 14.sp, maxLines = 1, softWrap = false) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFF0F172A),
                                    unfocusedTextColor = Color(0xFF0F172A),
                                    focusedBorderColor = if (dialogType == "INCOME") Color(0xFF2E7D32) else Color(0xFFC62828),
                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                                modifier = Modifier.weight(1f).height(56.dp)
                            )
                        }

                        // 3. Date & Time picker
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { datePickerDialog.show() }
                        ) {
                            OutlinedTextField(
                                value = viewModel.dateTimeInput,
                                onValueChange = { },
                                readOnly = true,
                                enabled = false,
                                placeholder = { Text("Date & Time", fontSize = 14.sp, maxLines = 1, softWrap = false) },
                                trailingIcon = {
                                    IconButton(onClick = { datePickerDialog.show() }) {
                                        Text("🕒", fontSize = 13.sp)
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = Color(0xFF0F172A),
                                    disabledBorderColor = if (dialogType == "INCOME") Color(0xFF2E7D32) else Color(0xFFC62828),
                                    disabledPlaceholderColor = Color(0xFF64748B)
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            )
                        }

                        // 4. Description (Details)
                        OutlinedTextField(
                            value = viewModel.noteInput,
                            onValueChange = { viewModel.noteInput = it },
                            placeholder = { Text("Enter details (optional)...", fontSize = 14.sp) },
                            singleLine = false,
                            minLines = 2,
                            maxLines = 2,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFF0F172A),
                                unfocusedTextColor = Color(0xFF0F172A),
                                focusedBorderColor = if (dialogType == "INCOME") Color(0xFF2E7D32) else Color(0xFFC62828),
                                unfocusedBorderColor = Color(0xFFCBD5E1)
                            ),
                            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val amount = viewModel.amountInput.toDoubleOrNull()
                            if (amount == null || amount <= 0.0) {
                                Toast.makeText(context, "Please enter a valid amount!", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.saveTransaction()
                                viewModel.showAddTransactionDialog = false
                                Toast.makeText(context, "Record saved successfully!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (dialogType == "INCOME") Color(0xFF2E7D32) else Color(0xFFC62828)
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        val btnText = if (viewModel.editingTransactionId != null) "Update" else "Save"
                        Text(text = btnText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            viewModel.showAddTransactionDialog = false
                            viewModel.cancelEditing()
                        }
                    ) {
                        Text("Cancel", color = Color(0xFF64748B), fontSize = 11.sp)
                    }
                },
                containerColor = Color.White,
                shape = RoundedCornerShape(12.dp)
            )
        }

        // 2. Filter System Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🔍 Filter & Search Records",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )

                var isSearchVisible by remember { mutableStateOf(false) }
                var yearMenuExpanded by remember { mutableStateOf(false) }
                var monthMenuExpanded by remember { mutableStateOf(false) }
                var dayMenuExpanded by remember { mutableStateOf(false) }

                val monthNames = listOf(
                    "January", "February", "March", "April", "May", "June",
                    "July", "August", "September", "October", "November", "December"
                )

                val selectedYearText = viewModel.filterYear

                val availableMonths = if (viewModel.filterYear == "ALL") {
                    emptyList()
                } else {
                    (0..11).toList()
                }

                val selectedMonthText = if (viewModel.filterMonth == "ALL") {
                    "All Months"
                } else {
                    monthNames.getOrNull(viewModel.filterMonth.toIntOrNull() ?: -1) ?: "All Months"
                }

                val availableDays = if (viewModel.filterYear == "ALL" || viewModel.filterMonth == "ALL") {
                    emptyList()
                } else {
                    val yearVal = viewModel.filterYear.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
                    val monthVal = viewModel.filterMonth.toIntOrNull() ?: Calendar.getInstance().get(Calendar.MONTH)
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, yearVal)
                        set(Calendar.MONTH, monthVal)
                        set(Calendar.DAY_OF_MONTH, 1)
                    }
                    val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    (1..maxDays).toList()
                }

                val selectedDayText = if (viewModel.filterDay == "ALL") {
                    "All Days"
                } else {
                    "Day ${viewModel.filterDay}"
                }

                // Row of cascading filters: Year, Month, Day, Search button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Year Dropdown
                    Box(modifier = Modifier.weight(1.1f)) {
                        Button(
                            onClick = { yearMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth().height(34.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (viewModel.filterYear == "ALL") "All Years" else "$selectedYearText",
                                    color = Color(0xFF0F172A),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("▼", color = Color(0xFF64748B), fontSize = 6.sp)
                            }
                        }

                        DropdownMenu(
                            expanded = yearMenuExpanded,
                            onDismissRequest = { yearMenuExpanded = false },
                            modifier = Modifier
                                .background(Color.White)
                                .heightIn(max = 240.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("All Years", color = Color(0xFF0F172A), fontSize = 11.sp) },
                                onClick = {
                                    viewModel.filterYear = "ALL"
                                    viewModel.filterMonth = "ALL"
                                    viewModel.filterDay = "ALL"
                                    yearMenuExpanded = false
                                }
                            )
                            (2026..2099).forEach { y ->
                                DropdownMenuItem(
                                    text = { Text(y.toString(), color = Color(0xFF0F172A), fontSize = 11.sp) },
                                    onClick = {
                                        viewModel.filterYear = y.toString()
                                        viewModel.filterMonth = "ALL"
                                        viewModel.filterDay = "ALL"
                                        yearMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // 2. Month Dropdown
                    Box(modifier = Modifier.weight(1.1f)) {
                        Button(
                            onClick = {
                                if (viewModel.filterYear == "ALL") {
                                    Toast.makeText(context, "Please select year first!", Toast.LENGTH_SHORT).show()
                                } else {
                                    monthMenuExpanded = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(34.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (viewModel.filterYear == "ALL") Color(0xFFF1F5F9).copy(alpha = 0.5f) else Color(0xFFF1F5F9)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (viewModel.filterMonth == "ALL") "All Months" else "$selectedMonthText",
                                    color = if (viewModel.filterYear == "ALL") Color(0xFF94A3B8) else Color(0xFF0F172A),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("▼", color = Color(0xFF64748B), fontSize = 6.sp)
                            }
                        }

                        DropdownMenu(
                            expanded = monthMenuExpanded,
                            onDismissRequest = { monthMenuExpanded = false },
                            modifier = Modifier
                                .background(Color.White)
                                .heightIn(max = 240.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("All Months", color = Color(0xFF0F172A), fontSize = 11.sp) },
                                onClick = {
                                    viewModel.filterMonth = "ALL"
                                    viewModel.filterDay = "ALL"
                                    monthMenuExpanded = false
                                }
                            )
                            availableMonths.forEach { mIdx ->
                                DropdownMenuItem(
                                    text = { Text(monthNames[mIdx], color = Color(0xFF0F172A), fontSize = 11.sp) },
                                    onClick = {
                                        viewModel.filterMonth = mIdx.toString()
                                        viewModel.filterDay = "ALL"
                                        monthMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // 3. Day Dropdown
                    Box(modifier = Modifier.weight(1.1f)) {
                        Button(
                            onClick = {
                                if (viewModel.filterYear == "ALL" || viewModel.filterMonth == "ALL") {
                                    Toast.makeText(context, "Please select year and month first!", Toast.LENGTH_SHORT).show()
                                } else {
                                    dayMenuExpanded = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(34.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (viewModel.filterYear == "ALL" || viewModel.filterMonth == "ALL") Color(0xFFF1F5F9).copy(alpha = 0.5f) else Color(0xFFF1F5F9)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (viewModel.filterDay == "ALL") "All Days" else "$selectedDayText",
                                    color = if (viewModel.filterYear == "ALL" || viewModel.filterMonth == "ALL") Color(0xFF94A3B8) else Color(0xFF0F172A),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("▼", color = Color(0xFF64748B), fontSize = 6.sp)
                            }
                        }

                        DropdownMenu(
                            expanded = dayMenuExpanded,
                            onDismissRequest = { dayMenuExpanded = false },
                            modifier = Modifier
                                .background(Color.White)
                                .heightIn(max = 240.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("All Days", color = Color(0xFF0F172A), fontSize = 11.sp) },
                                onClick = {
                                    viewModel.filterDay = "ALL"
                                    dayMenuExpanded = false
                                }
                            )
                            availableDays.forEach { d ->
                                DropdownMenuItem(
                                    text = { Text("Day $d", color = Color(0xFF0F172A), fontSize = 11.sp) },
                                    onClick = {
                                        viewModel.filterDay = d.toString()
                                        dayMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // 4. Toggle Search Button
                    Button(
                        onClick = { isSearchVisible = !isSearchVisible },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSearchVisible) Color(0xFF1976D2) else Color(0xFFF1F5F9)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        modifier = Modifier.weight(0.9f).height(34.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("🔍", fontSize = 9.sp)
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = if (isSearchVisible) "Hide" else "Search",
                                color = if (isSearchVisible) Color.White else Color(0xFF0F172A),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Smooth animated visibility for category search field
                AnimatedVisibility(
                    visible = isSearchVisible,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedTextField(
                        value = viewModel.filterCategoryQuery,
                        onValueChange = { viewModel.filterCategoryQuery = it },
                        placeholder = { Text("e.g. food", fontSize = 14.sp) },
                        trailingIcon = { Text("🔍", modifier = Modifier.padding(end = 8.dp), fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF1976D2),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        )
                    )
                }
            }
        }

        // 3. Transactions Record Card List
        Text(
            text = "📋 Records List (" + filteredTxList.size.toString() + " found)",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A),
            modifier = Modifier.padding(top = 4.dp)
        )

        if (filteredTxList.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("💸", fontSize = 32.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No records found!",
                        color = Color(0xFF475569),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Add new income or expense entries or change your filters.",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        } else {
            val itemsToShow = if (viewModel.showAllTransactions) filteredTxList else filteredTxList.take(5)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsToShow.forEach { tx ->
                    val isPersonal = tx.personName.isNotEmpty() &&
                            !tx.personName.trim().equals("সাধারণ", ignoreCase = true) &&
                            !tx.personName.trim().equals("general", ignoreCase = true)
                    TransactionItemRow(
                        transaction = tx,
                        onEdit = {
                            if (isPersonal) {
                                Toast.makeText(context, "Please edit/delete dues, debts, and repayments in the Debts/Credits tab.", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.startEditingTransaction(tx)
                            }
                        },
                        onDelete = {
                            if (isPersonal) {
                                Toast.makeText(context, "Please edit/delete dues, debts, and repayments in the Debts/Credits tab.", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.confirmDelete(
                                    title = "Delete Transaction",
                                    message = "Are you sure you want to delete this transaction?",
                                    action = {
                                        viewModel.deleteTransactionWithUndo(tx, showSnackbarWithUndo)
                                    }
                                )
                            }
                        },
                        onDoubleClick = { viewModel.selectedTransactionForDetails = tx }
                    )
                }

                if (filteredTxList.size > 5) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { viewModel.showAllTransactions = !viewModel.showAllTransactions },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (viewModel.showAllTransactions) "Show Less 🔼" else "Show All 🔽",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 4. Daily Income & Expense Summary List Card
        var showAllDailySummaries by remember { mutableStateOf(false) }
        val dailySummariesToShow = if (showAllDailySummaries) activeDailySummaries else activeDailySummaries.take(7)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "📅 Daily Summary (" + activeDailySummaries.size.toString() + " days)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Date", fontSize = 10.sp, color = Color(0xFF64748B), modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold)
                        Text(text = "Income", fontSize = 10.sp, color = Color(0xFF2E7D32), modifier = Modifier.weight(1.0f), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
                        Text(text = "Expense", fontSize = 10.sp, color = Color(0xFFC62828), modifier = Modifier.weight(1.0f), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
                        Text(text = "Balance", fontSize = 10.sp, color = Color(0xFF0F172A), modifier = Modifier.weight(1.2f), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
                    }
                    
                    Divider(color = Color(0xFFE2E8F0), thickness = 1.dp)

                    dailySummariesToShow.forEach { summary ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 0.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = summary.dateString,
                                fontSize = 10.sp,
                                color = Color(0xFF334155),
                                modifier = Modifier.weight(1.2f),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (summary.income > 0.0) "৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", summary.income)) else "0",
                                fontSize = 10.sp,
                                color = if (summary.income > 0.0) Color(0xFF2E7D32) else Color(0xFF94A3B8),
                                modifier = Modifier.weight(1.0f),
                                textAlign = TextAlign.End,
                                fontWeight = if (summary.income > 0.0) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = if (summary.expense > 0.0) "৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", summary.expense)) else "0",
                                fontSize = 10.sp,
                                color = if (summary.expense > 0.0) Color(0xFFC62828) else Color(0xFF94A3B8),
                                modifier = Modifier.weight(1.0f),
                                textAlign = TextAlign.End,
                                fontWeight = if (summary.expense > 0.0) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = "৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", summary.balanceAtEnd)),
                                fontSize = 10.sp,
                                color = if (summary.balanceAtEnd >= 0.0) Color(0xFF2E7D32) else Color(0xFFC62828),
                                modifier = Modifier.weight(1.2f),
                                textAlign = TextAlign.End,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Divider(color = Color(0xFFE2E8F0), thickness = 0.5.dp)
                    }

                    if (activeDailySummaries.size > 7) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { showAllDailySummaries = !showAllDailySummaries },
                            modifier = Modifier.fillMaxWidth().height(34.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (showAllDailySummaries) "Show Less 🔼" else "Show All 🔽",
                                color = Color(0xFF0F172A),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionItemRow(
    transaction: TransactionEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val dateString = SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(transaction.dateTime))
    val isIncome = transaction.type == "INCOME"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onDoubleClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onDelete
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(
            width = 1.dp,
            color = if (isIncome) Color(0xFF2E7D32).copy(alpha = 0.12f) else Color(0xFFC62828).copy(alpha = 0.12f)
        )
    ) {
        val startColor = if (isIncome) Color(0xFF2E7D32).copy(alpha = 0.15f) else Color(0xFFC62828).copy(alpha = 0.15f)
        val endColor = Color.Transparent

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(
                    brush = Brush.horizontalGradient(
                        0.0f to startColor,
                        0.8f to startColor.copy(alpha = 0.03f),
                        1.0f to endColor
                    )
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left color bar, flush to the left border
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(if (isIncome) Color(0xFF2E7D32) else Color(0xFFC62828))
            )

            // Content padding starts here
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left block: category name & details
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Column {
                        val isPersonal = transaction.personName.isNotEmpty() &&
                                !transaction.personName.trim().equals("সাধারণ", ignoreCase = true) &&
                                !transaction.personName.trim().equals("general", ignoreCase = true)

                        val dispCategory = if (isPersonal) transaction.personName else transaction.category
                        val dispDetails = if (isPersonal) transaction.category else ""

                        Text(
                            text = dispCategory,
                            color = Color(0xFF0F172A),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = convertToBengaliNumber(dateString),
                            color = Color(0xFF64748B),
                            fontSize = 9.sp,
                            modifier = Modifier.padding(top = 0.dp)
                        )
                    }
                }

                // Right block
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${if (isIncome) "+" else ""} ৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", transaction.amount)),
                        color = if (isIncome) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )

                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1976D2).copy(alpha = 0.08f))
                            .clickable { onEdit() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✏️", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// ============================================================================
// SESSION 3: NOTEBOOK (NOTICE) SESSION VIEW
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoticeSessionView(
    viewModel: FinanceViewModel,
    notices: List<NoticeEntity>,
    showSnackbarWithUndo: (String, () -> Unit) -> Unit
) {
    val context = LocalContext.current
    var noticeContentInputState by remember(viewModel.showAddNoticeDialog, viewModel.editingNotice) {
        mutableStateOf(TextFieldValue(viewModel.noticeContentInput))
    }

    // Note Creator Dialog
    if (viewModel.showAddNoticeDialog) {
        Dialog(onDismissRequest = { 
            viewModel.showAddNoticeDialog = false 
            viewModel.editingNotice = null
            viewModel.noticeTitleInput = ""
            viewModel.noticeContentInput = ""
        }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dialogTitle = if (viewModel.editingNotice != null) "📓 Edit Note" else "📓 New Note & Checklist"
                        Text(
                            text = dialogTitle,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                        IconButton(
                            onClick = { 
                                viewModel.showAddNoticeDialog = false 
                                viewModel.editingNotice = null
                                viewModel.noticeTitleInput = ""
                                viewModel.noticeContentInput = ""
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text("❌", fontSize = 11.sp)
                        }
                    }

                    // Title/Heading input field
                    OutlinedTextField(
                        value = viewModel.noticeTitleInput,
                        onValueChange = { viewModel.noticeTitleInput = it },
                        placeholder = { Text("Header", fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF1976D2),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )

                    // Content input field
                    OutlinedTextField(
                        value = noticeContentInputState,
                        onValueChange = { 
                            noticeContentInputState = it 
                            viewModel.noticeContentInput = it.text
                        },
                        placeholder = { Text("Details", fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF1976D2),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )

                    // Checkbox helper row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val currentText = noticeContentInputState.text
                                val selectionStart = noticeContentInputState.selection.start
                                val selectionEnd = noticeContentInputState.selection.end

                                val insertString = if (currentText.isEmpty()) {
                                    "[ ] "
                                } else if (currentText.substring(0, selectionStart).endsWith("\n")) {
                                    "[ ] "
                                } else {
                                    "\n[ ] "
                                }

                                val newText = currentText.substring(0, selectionStart) + insertString + currentText.substring(selectionEnd)
                                val newCursorPosition = selectionStart + insertString.length

                                noticeContentInputState = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(newCursorPosition)
                                )
                                viewModel.noticeContentInput = newText
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("☑️ Add Checkbox", fontSize = 10.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            if (viewModel.noticeContentInput.trim().isEmpty() && viewModel.noticeTitleInput.trim().isEmpty()) {
                                Toast.makeText(context, "Please enter a header or details!", Toast.LENGTH_SHORT).show()
                            } else {
                                val msg = if (viewModel.editingNotice != null) "Note updated successfully! 📓" else "Note added successfully! 📓"
                                viewModel.saveNotice()
                                viewModel.showAddNoticeDialog = false
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        val buttonText = if (viewModel.editingNotice != null) "Update Note 📓" else "Add Note 📓"
                        Text(buttonText, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "📋 Saved Notes (" + notices.size.toString() + ")",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A),
            modifier = Modifier.padding(top = 4.dp)
        )

        if (notices.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📓", fontSize = 32.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your notebook is empty!",
                        color = Color(0xFF475569),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Save your notes, shopping lists, or targets here. Tap the plus (+) button to start.",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                notices.forEach { notice ->
                    StickyNoteCard(
                        notice = notice,
                        onDelete = {
                            viewModel.confirmDelete(
                                title = "Delete Note",
                                message = "Are you sure you want to delete this note?",
                                action = {
                                    viewModel.deleteNoticeWithUndo(notice, showSnackbarWithUndo)
                                }
                            )
                        },
                        onEdit = {
                            viewModel.editingNotice = notice
                            val parsedTitle = if (notice.content.contains("===NOTE_TITLE===")) {
                                notice.content.substringBefore("===NOTE_TITLE===")
                            } else {
                                ""
                            }
                            val parsedContent = if (notice.content.contains("===NOTE_TITLE===")) {
                                notice.content.substringAfter("===NOTE_TITLE===")
                            } else {
                                notice.content
                            }
                            viewModel.noticeTitleInput = parsedTitle
                            viewModel.noticeContentInput = parsedContent
                            viewModel.showAddNoticeDialog = true
                        },
                        onUpdate = { updated ->
                            viewModel.updateNotice(updated)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickyNoteCard(
    notice: NoticeEntity,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onUpdate: (NoticeEntity) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val dateString = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(notice.timestamp))

    val parsedTitle = if (notice.content.contains("===NOTE_TITLE===")) {
        notice.content.substringBefore("===NOTE_TITLE===")
    } else {
        ""
    }

    val parsedContent = if (notice.content.contains("===NOTE_TITLE===")) {
        notice.content.substringAfter("===NOTE_TITLE===")
    } else {
        notice.content
    }

    val displayTitle = parsedTitle.ifBlank {
        val clean = parsedContent.replace(Regex("\\[[ x]\\]"), "").trim()
        if (clean.isNotEmpty()) {
            if (clean.length > 25) clean.take(25) + "..." else clean
        } else {
            "Untitled Note 📝"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { isExpanded = !isExpanded },
                onLongClick = onDelete
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "📓", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = displayTitle,
                        color = Color(0xFF0F172A),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Text(
                    text = if (isExpanded) "▲" else "▼",
                    color = Color(0xFF64748B),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color(0xFFE2E8F0), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📅 " + convertToBengaliNumber(dateString), fontSize = 8.sp, color = Color(0xFF64748B))
                }

                Spacer(modifier = Modifier.height(6.dp))

                val lines = parsedContent.lines()
                val hasChecklist = lines.any { it.startsWith("[ ] ") || it.startsWith("[x] ") }

                if (hasChecklist) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        lines.forEachIndexed { index, line ->
                            if (line.startsWith("[ ] ") || line.startsWith("[x] ")) {
                                val isChecked = line.startsWith("[x] ")
                                val text = line.substring(4)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val newLines = lines.toMutableList()
                                            newLines[index] = if (isChecked) "[ ] $text" else "[x] $text"
                                            val newCombined = "$parsedTitle===NOTE_TITLE===${newLines.joinToString("\n")}"
                                            onUpdate(notice.copy(content = newCombined))
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            val newLines = lines.toMutableList()
                                            newLines[index] = if (checked) "[x] $text" else "[ ] $text"
                                            val newCombined = "$parsedTitle===NOTE_TITLE===${newLines.joinToString("\n")}"
                                            onUpdate(notice.copy(content = newCombined))
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color(0xFF1976D2),
                                            uncheckedColor = Color(0xFF64748B),
                                            checkmarkColor = Color.White
                                        ),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = text,
                                        color = if (isChecked) Color(0xFF94A3B8) else Color(0xFF334155),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None
                                    )
                                }
                            } else {
                                if (line.trim().isNotEmpty()) {
                                    Text(
                                        text = line,
                                        color = Color(0xFF334155),
                                        fontSize = 14.sp,
                                        lineHeight = 19.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = parsedContent,
                        color = Color(0xFF334155),
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1976D2).copy(alpha = 0.08f))
                            .clickable { onEdit() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✏️", fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun DoubleTapDetailsDialog(transaction: TransactionEntity, onDismiss: () -> Unit) {
    val formattedDate = SimpleDateFormat("dd MMMM yyyy, hh:mm a", Locale.US).format(Date(transaction.dateTime))
    val isIncome = transaction.type == "INCOME"
    val isPersonal = transaction.personName.isNotEmpty() &&
            !transaction.personName.trim().equals("সাধারণ", ignoreCase = true) &&
            !transaction.personName.trim().equals("general", ignoreCase = true)

    val displayDetails = if (isPersonal) {
        if (transaction.category.isNotBlank()) transaction.category else "No details provided."
    } else {
        if (transaction.note.isNotBlank()) transaction.note else "No details provided."
    }

    val displayCategory = if (isPersonal) transaction.personName else transaction.category

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(
                width = 1.dp,
                color = if (isIncome) Color(0xFF2E7D32).copy(alpha = 0.5f) else Color(0xFFC62828).copy(alpha = 0.5f)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Close button at top right
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                ) {
                    Text("❌", fontSize = 11.sp)
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header Title
                    Text(
                        text = "Transaction Details 🔎",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )

                    // Main Content: Details (Note)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "Details / বিবরণ:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = displayDetails,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0F172A),
                            lineHeight = 18.sp
                        )
                    }

                    // Bottom info: Category on left, Money & Date small in the right corner
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // Category (on the bottom left)
                        Column {
                            Text(
                                text = "Category",
                                fontSize = 8.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = displayCategory,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF334155)
                            )
                            if (isPersonal) {
                                Text(
                                    text = "Person: ${transaction.personName}",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1976D2)
                                )
                            }
                        }

                        // Money & Date in a corner (bottom right, small)
                        Column(
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = "Amount: " + (if (isIncome) "+" else "-") + "৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", transaction.amount)),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isIncome) Color(0xFF2E7D32) else Color(0xFFC62828)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = convertToBengaliNumber(formattedDate),
                                fontSize = 9.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Close", color = Color(0xFF0F172A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ============================================================================
// SYSTEM NAVIGATION BAR
// ============================================================================

@Composable
fun BottomNavigationBar(activeTab: String, onTabSelected: (String) -> Unit) {
    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        NavigationBarItem(
            selected = activeTab == "ACCOUNT",
            onClick = { onTabSelected("ACCOUNT") },
            icon = { Text("👥", fontSize = 18.sp) },
            label = { Text("Debts/Credits", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF2E7D32),
                unselectedIconColor = Color(0xFF64748B),
                selectedTextColor = Color(0xFF2E7D32),
                unselectedTextColor = Color(0xFF64748B),
                indicatorColor = Color(0xFF2E7D32).copy(alpha = 0.12f)
            )
        )

        NavigationBarItem(
            selected = activeTab == "TRACKER",
            onClick = { onTabSelected("TRACKER") },
            icon = { Text("💸", fontSize = 18.sp) },
            label = { Text("Tracker", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF2E7D32),
                unselectedIconColor = Color(0xFF64748B),
                selectedTextColor = Color(0xFF2E7D32),
                unselectedTextColor = Color(0xFF64748B),
                indicatorColor = Color(0xFF2E7D32).copy(alpha = 0.12f)
            )
        )

        NavigationBarItem(
            selected = activeTab == "NOTICE",
            onClick = { onTabSelected("NOTICE") },
            icon = { Text("📓", fontSize = 18.sp) },
            label = { Text("Notebook", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF2E7D32),
                unselectedIconColor = Color(0xFF64748B),
                selectedTextColor = Color(0xFF2E7D32),
                unselectedTextColor = Color(0xFF64748B),
                indicatorColor = Color(0xFF2E7D32).copy(alpha = 0.12f)
            )
        )
    }
}
