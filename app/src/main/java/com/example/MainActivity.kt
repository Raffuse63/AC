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
    val personName: String = "সাধারণ",
    val paidAmount: Double = 0.0
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
}

@Database(
    entities = [ProfileEntity::class, TransactionEntity::class, NoticeEntity::class, PersonEntity::class],
    version = 3,
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
    var editingNotice by mutableStateOf<NoticeEntity?>(null)

    // Dialog state for Double Tap note review
    var selectedTransactionForDetails by mutableStateOf<TransactionEntity?>(null)

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

            onShowSnackbar("${person.name} কে মুছে ফেলা হয়েছে!") {
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
            
            onShowSnackbar("লেনদেনটি মুছে ফেলা হয়েছে!") {
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
            
            onShowSnackbar("নোটটি মুছে ফেলা হয়েছে!") {
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

        val category = categoryInput.trim().ifEmpty { "অন্যান্য 🪙" }
        val parsedTime = parseDateTime(dateTimeInput)
        val person = selectedPersonName.trim().ifEmpty { "সাধারণ" }

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
                colorScheme = darkColorScheme(
                    primary = Color(0xFF2ECC71), // Emerald Mint
                    secondary = Color(0xFF3498DB), // Sky Blue
                    background = Color(0xFF121214), // Coal black
                    surface = Color(0xFF1E1E22), // Dark Card
                    onBackground = Color.White,
                    onSurface = Color.White
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

    val generalTransactions = transactionsState.filter {
        it.personName.trim().isEmpty() || it.personName.trim().lowercase() == "সাধারণ"
    }

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
                actionLabel = "পূর্বাবস্থায় ফেরান ↩️",
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
            title = { Text(text = viewModel.deleteConfirmTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text(text = viewModel.deleteConfirmMessage, color = Color(0xFFC7C7CD), fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.pendingDeleteAction?.invoke()
                        viewModel.showDeleteConfirmDialog = false
                        viewModel.pendingDeleteAction = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C))
                ) {
                    Text("হ্যাঁ, ডিলিট করুন", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.showDeleteConfirmDialog = false
                        viewModel.pendingDeleteAction = null
                    }
                ) {
                    Text("বাতিল", color = Color(0xFF9E9E9E))
                }
            },
            containerColor = Color(0xFF1E1E22),
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
                        contentDescription = "নতুন নোট যোগ করুন"
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
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0F0F11), Color(0xFF15151A))
                    )
                )
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
                AppHeader()

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
                                personsState.filter { it.name != "সাধারণ" && it.name.trim().isNotEmpty() }
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
fun AppHeader() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
        border = BorderStroke(1.dp, Color(0xFF2E2E34))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "ফিন্যান্স ম্যানেজার 💰",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "অল-ইন-ওয়ান পার্সোনাল ড্যাশবোর্ড",
                    fontSize = 12.sp,
                    color = Color(0xFF9E9E9E),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2ECC71).copy(alpha = 0.15f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "PRO",
                    color = Color(0xFF2ECC71),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ============================================================================
// SESSION 1: DUES LEDGER SESSION VIEW (replaces Account Session View)
// ============================================================================

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

    var activeEditTxId by remember { mutableStateOf<Int?>(null) }
    var editAmountInput by remember { mutableStateOf("") }
    var editPaidAmountInput by remember { mutableStateOf("") }
    var editCategoryInput by remember { mutableStateOf("") }

    var showEditPersonDialog by remember { mutableStateOf(false) }
    var personToEdit by remember { mutableStateOf<PersonEntity?>(null) }
    var editPersonNameInput by remember { mutableStateOf("") }

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

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // 1. BIG SUMMARY CARD FOR DUES
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            border = BorderStroke(1.dp, Color(0xFF2E2E38))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "👥 সবার মোট দেনা-পাওনা হিসাব",
                    color = Color(0xFF9E9E9E),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2ECC71))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "মোট পাবো 📈", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "৳ " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", totalReceivable)),
                            color = Color(0xFF2ECC71),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    // Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(50.dp)
                            .background(Color(0xFF2E2E38))
                    )

                    // Total Payable Block
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE74C3C))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "মোট ঋণ 📉", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "৳ " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", totalPayable)),
                            color = Color(0xFFE74C3C),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                Divider(color = Color(0xFF2E2E38), thickness = 1.dp, modifier = Modifier.padding(vertical = 16.dp))

                // Net Standing Message
                val netStandingAbs = if (netStanding > 0) netStanding else -netStanding
                val standingBgColor = if (netStanding > 0) Color(0xFF2ECC71).copy(alpha = 0.12f) else if (netStanding < 0) Color(0xFFE74C3C).copy(alpha = 0.12f) else Color(0xFF3498DB).copy(alpha = 0.12f)
                val standingBorderColor = if (netStanding > 0) Color(0xFF2ECC71).copy(alpha = 0.4f) else if (netStanding < 0) Color(0xFFE74C3C).copy(alpha = 0.4f) else Color(0xFF3498DB).copy(alpha = 0.4f)
                val standingTextColor = if (netStanding > 0) Color(0xFF2ECC71) else if (netStanding < 0) Color(0xFFE74C3C) else Color(0xFF3498DB)

                Card(
                    colors = CardDefaults.cardColors(containerColor = standingBgColor),
                    border = BorderStroke(1.dp, standingBorderColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val statusEmoji = if (netStanding > 0) "🎉" else if (netStanding < 0) "⚠️" else "🤝"
                        val statusText = if (netStanding > 0) {
                            "নিট হিসাব : " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", netStanding)) + " পাবো"
                        } else if (netStanding < 0) {
                            "নিট হিসাব : " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", netStandingAbs)) + " ঋন"
                        } else {
                            "নিট হিসাব : ০ পাবো"
                        }
                        Text(
                            text = statusEmoji + " " + statusText,
                            color = standingTextColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // 2. ADD NEW PERSON CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
            border = BorderStroke(1.dp, Color(0xFF2E2E34))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "👥 নতুন ব্যক্তি বা নাম যোগ করুন",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = viewModel.newPersonNameInput,
                        onValueChange = { viewModel.newPersonNameInput = it },
                        placeholder = { Text("নাম লিখুন...", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF2ECC71),
                            unfocusedBorderColor = Color(0xFF3E3E44)
                        )
                    )

                    Button(
                        onClick = {
                            if (viewModel.newPersonNameInput.trim().isNotEmpty()) {
                                viewModel.addPerson()
                                Toast.makeText(context, "নতুন নাম যোগ করা হয়েছে!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "অনুগ্রহ করে সঠিক নাম লিখুন!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(54.dp)
                    ) {
                        Text(text = "যোগ করুন 💾", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        // 3. INDIVIDUAL DUES LIST
        Text(
            text = "📋 ব্যক্তিদের দেনা-পাওনা তালিকা (" + convertToBengaliNumber(persons.size.toString()) + " জন)",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (persons.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF2E2E34))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("👥", fontSize = 42.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "কোনো নাম খুঁজে পাওয়া যায়নি!",
                        color = Color(0xFF9E9E9E),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "উপরে নতুন নাম লিখে যোগ করুন যাতে আলাদাভাবে দেনা-পাওনা হিসাব করতে পারেন।",
                        color = Color(0xFF6E6E74),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                personDuesList.forEach { model ->
                    val person = model.person
                    val totalGiven = model.totalGiven
                    val totalTaken = model.totalTaken
                    val netBalance = model.netBalance
                    val txCount = model.txCount

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (netBalance > 0) Color(0xFF2ECC71).copy(alpha = 0.3f) else if (netBalance < 0) Color(0xFFE74C3C).copy(alpha = 0.3f) else Color(0xFF2E2E34)
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
                                                title = "ব্যক্তি ডিলিট নিশ্চিতকরণ",
                                                message = "${person.name} এবং তার সকল লেনদেন ডিলিট করতে চান?",
                                                action = {
                                                    viewModel.deletePersonWithUndo(person, showSnackbarWithUndo)
                                                }
                                            )
                                        }
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    // Avatar circle with first letter
                                    val avatarChar = if (person.name.trim().isNotEmpty()) person.name.trim()[0].toString() else "?"
                                    val avatarBgColor = if (netBalance > 0) Color(0xFF2ECC71).copy(alpha = 0.15f) else if (netBalance < 0) Color(0xFFE74C3C).copy(alpha = 0.15f) else Color(0xFF2A2A30)
                                    val avatarTextColor = if (netBalance > 0) Color(0xFF2ECC71) else if (netBalance < 0) Color(0xFFE74C3C) else Color.White
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(avatarBgColor)
                                    ) {
                                        Text(
                                            text = avatarChar,
                                            color = avatarTextColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = person.name,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val netText = if (netBalance > 0) {
                                            "নিট হিসাব: " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", netBalance)) + " পাবো"
                                        } else if (netBalance < 0) {
                                            "নিট হিসাব: " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", -netBalance)) + " ঋন"
                                        } else {
                                            "নিট হিসাব: ০"
                                        }
                                        val netColor = if (netBalance > 0) Color(0xFF2ECC71) else if (netBalance < 0) Color(0xFFE74C3C) else Color(0xFF9E9E9E)
                                        Text(
                                            text = netText,
                                            color = netColor,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                // Edit button
                                if (person.name != "সাধারণ") {
                                    IconButton(
                                        onClick = {
                                            personToEdit = person
                                            editPersonNameInput = person.name
                                            showEditPersonDialog = true
                                        },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(Color(0xFF3498DB).copy(alpha = 0.1f), CircleShape)
                                    ) {
                                        Text("✏️", fontSize = 11.sp)
                                    }
                                }
                            }

                            // EXPANDED SECTION WITH INDIVIDUAL TRANSACTION DETAILS & IN-PLACE FORM
                            if (expandedPersonId == person.id) {
                                Divider(color = Color(0xFF2E2E34), thickness = 1.dp)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF16161A))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Detailed summary card inside expanded profile
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color(0xFF2E2E34))
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "📊 বিস্তারিত দেনা-পাওনা হিসাব:",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "মোট দিয়েছি (পাবো): ৳ " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", totalGiven)),
                                                    color = Color(0xFF2ECC71),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "মোট নিয়েছি (ঋণ): ৳ " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", totalTaken)),
                                                    color = Color(0xFFE74C3C),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Divider(color = Color(0xFF2E2E34), thickness = 0.5.dp)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "নিট সমন্বয়:",
                                                    color = Color(0xFF9E9E9E),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                if (netBalance > 0) {
                                                    Text(
                                                        text = "৳ " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", netBalance)) + " পাবো",
                                                        color = Color(0xFF2ECC71),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp
                                                    )
                                                } else if (netBalance < 0) {
                                                    Text(
                                                        text = "৳ " + convertToBengaliNumber(String.format(Locale.US, "%,.0f", -netBalance)) + " ঋণ",
                                                        color = Color(0xFFE74C3C),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp
                                                    )
                                                } else {
                                                    Text(
                                                        text = "৳ ০ (সমতা)",
                                                        color = Color(0xFF9E9E9E),
                                                        fontWeight = FontWeight.Normal,
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 1. Mini-form to add a new transaction for this person
                                    Text(
                                        text = "➕ নতুন লেনদেন রেকর্ড করুন:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
 
                                    var quickAmountInput by remember(person.id) { mutableStateOf("") }
                                    var quickCategoryInput by remember(person.id) { mutableStateOf("") }
 
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = quickAmountInput,
                                            onValueChange = { quickAmountInput = it },
                                            placeholder = { Text("পরিমাণ ৳", fontSize = 11.sp) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                focusedBorderColor = Color(0xFF3498DB),
                                                unfocusedBorderColor = Color(0xFF3E3E44)
                                            ),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
 
                                        OutlinedTextField(
                                            value = quickCategoryInput,
                                            onValueChange = { quickCategoryInput = it },
                                            placeholder = { Text("বিবরণ (যেমন: ধার, পরিশোধ)", fontSize = 11.sp) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1.5f),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                focusedBorderColor = Color(0xFF3498DB),
                                                unfocusedBorderColor = Color(0xFF3E3E44)
                                            ),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                    }
 
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Give loan / Pay money to them (EXPENSE)
                                        Button(
                                            onClick = {
                                                val amt = quickAmountInput.toDoubleOrNull() ?: 0.0
                                                if (amt > 0.0) {
                                                    val desc = quickCategoryInput.trim().ifEmpty { "টাকা দিলাম" }
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
                                                    quickAmountInput = ""
                                                    quickCategoryInput = ""
                                                    Toast.makeText(context, "দেনা-পাওনা সংরক্ষিত হয়েছে!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "সঠিক পরিমাণ লিখুন!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C)),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(vertical = 8.dp)
                                        ) {
                                            Text("টাকা দিলাম 📉", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
 
                                        // Receive loan / Get money from them (INCOME)
                                        Button(
                                            onClick = {
                                                val amt = quickAmountInput.toDoubleOrNull() ?: 0.0
                                                if (amt > 0.0) {
                                                    val desc = quickCategoryInput.trim().ifEmpty { "টাকা পেলাম" }
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
                                                    quickAmountInput = ""
                                                    quickCategoryInput = ""
                                                    Toast.makeText(context, "দেনা-পাওনা সংরক্ষিত হয়েছে!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "সঠিক পরিমাণ লিখুন!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71)),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(vertical = 8.dp)
                                        ) {
                                            Text("টাকা পেলাম 📈", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                    }
 
                                    // 2. Transaction history list for this person
                                    val personTx = transactions.filter { it.personName.trim().lowercase() == person.name.trim().lowercase() }
                                        .sortedByDescending { it.dateTime }
 
                                    Text(
                                        text = "📜 লেনদেন ইতিহাস (" + convertToBengaliNumber(personTx.size.toString()) + " টি):",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF9E9E9E)
                                    )
 
                                    if (personTx.isEmpty()) {
                                        Text(
                                            text = "কোনো পূর্ববর্তী লেনদেন নেই।",
                                            fontSize = 11.sp,
                                            color = Color(0xFF6E6E74)
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            personTx.forEach { tx ->
                                                val dateStr = SimpleDateFormat("dd MMM, hh:mm a", Locale("bn")).format(Date(tx.dateTime))
                                                val isInc = tx.type == "INCOME"
                                                val prefix = if (isInc) "+" else "-"
                                                val color = if (isInc) Color(0xFF2ECC71) else Color(0xFFE74C3C)
                                                val remaining = maxOf(0.0, tx.amount - tx.paidAmount)
                                                val isFullyPaid = tx.paidAmount >= tx.amount
 
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(Color(0xFF1E1E22), RoundedCornerShape(8.dp))
                                                        .border(
                                                            width = 1.dp,
                                                            color = if (activeEditTxId == tx.id) Color(0xFF3498DB) else Color.Transparent,
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            if (activeEditTxId == tx.id) {
                                                                activeEditTxId = null
                                                            } else {
                                                                activeEditTxId = tx.id
                                                                editAmountInput = tx.amount.toString()
                                                                editPaidAmountInput = tx.paidAmount.toString()
                                                                editCategoryInput = tx.category
                                                            }
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                Text(
                                                                    text = tx.category,
                                                                    color = Color.White,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                if (isFullyPaid) {
                                                                    Card(
                                                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2ECC71).copy(alpha = 0.15f)),
                                                                        shape = RoundedCornerShape(4.dp)
                                                                    ) {
                                                                        Text(
                                                                            text = "সম্পূর্ণ পরিশোধ ✅",
                                                                            color = Color(0xFF2ECC71),
                                                                            fontSize = 9.sp,
                                                                            fontWeight = FontWeight.Bold,
                                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                        )
                                                                    }
                                                                } else if (tx.paidAmount > 0.0) {
                                                                    Card(
                                                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF39C12).copy(alpha = 0.15f)),
                                                                        shape = RoundedCornerShape(4.dp)
                                                                    ) {
                                                                        Text(
                                                                            text = "পরিশোধিত: ৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", tx.paidAmount)),
                                                                            color = Color(0xFFF39C12),
                                                                            fontSize = 9.sp,
                                                                            fontWeight = FontWeight.Bold,
                                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            Text(text = dateStr, color = Color(0xFF9E9E9E), fontSize = 10.sp)
                                                        }
 
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Column(horizontalAlignment = Alignment.End) {
                                                                Text(
                                                                    text = "$prefix ৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", tx.amount)),
                                                                    color = color,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                if (!isFullyPaid && tx.paidAmount > 0.0) {
                                                                    Text(
                                                                        text = "বাকি: ৳" + convertToBengaliNumber(String.format(Locale.US, "%,.0f", remaining)),
                                                                        color = Color(0xFFE74C3C),
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Bold
                                                                    )
                                                                }
                                                            }
 
                                                            // Delete button
                                                            IconButton(
                                                                onClick = {
                                                                    viewModel.confirmDelete(
                                                                        title = "লেনদেন ডিলিট নিশ্চিতকরণ",
                                                                        message = "আপনি কি এই লেনদেনটি ডিলিট করতে চান?",
                                                                        action = {
                                                                            viewModel.deleteTransactionWithUndo(tx, showSnackbarWithUndo)
                                                                        }
                                                                    )
                                                                },
                                                                modifier = Modifier.size(24.dp)
                                                            ) {
                                                                Text("🗑️", fontSize = 10.sp)
                                                            }
                                                        }
                                                    }
 
                                                    // INLINE EDITOR / PAY DETAILS
                                                    if (activeEditTxId == tx.id) {
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Card(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF25252B)),
                                                            border = BorderStroke(1.dp, Color(0xFF3498DB).copy(alpha = 0.5f)),
                                                            shape = RoundedCornerShape(8.dp)
                                                        ) {
                                                            Column(
                                                                modifier = Modifier.padding(10.dp),
                                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                Text(
                                                                    text = "✏️ লেনদেন সংশোধন ও পরিশোধ আপডেট করুন:",
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Color(0xFF3498DB)
                                                                )
 
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                                ) {
                                                                    // 1. Amount input
                                                                    OutlinedTextField(
                                                                        value = editAmountInput,
                                                                        onValueChange = { editAmountInput = it },
                                                                        label = { Text("মূল পরিমাণ ৳", fontSize = 10.sp) },
                                                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                                        singleLine = true,
                                                                        modifier = Modifier.weight(1f),
                                                                        colors = OutlinedTextFieldDefaults.colors(
                                                                            focusedTextColor = Color.White,
                                                                            unfocusedTextColor = Color.White,
                                                                            focusedBorderColor = Color(0xFF3498DB),
                                                                            unfocusedBorderColor = Color(0xFF3E3E44)
                                                                        ),
                                                                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                                                    )
 
                                                                    // 2. Paid amount input
                                                                    OutlinedTextField(
                                                                        value = editPaidAmountInput,
                                                                        onValueChange = { editPaidAmountInput = it },
                                                                        label = { Text("পরিশোধিত পরিমাণ ৳", fontSize = 10.sp) },
                                                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                                        singleLine = true,
                                                                        modifier = Modifier.weight(1f),
                                                                        colors = OutlinedTextFieldDefaults.colors(
                                                                            focusedTextColor = Color.White,
                                                                            unfocusedTextColor = Color.White,
                                                                            focusedBorderColor = Color(0xFF2ECC71),
                                                                            unfocusedBorderColor = Color(0xFF3E3E44)
                                                                        ),
                                                                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                                                    )
                                                                }
 
                                                                // Category/Description input
                                                                OutlinedTextField(
                                                                    value = editCategoryInput,
                                                                    onValueChange = { editCategoryInput = it },
                                                                    label = { Text("বিবরণ / ক্যাটাগরি", fontSize = 10.sp) },
                                                                    singleLine = true,
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    colors = OutlinedTextFieldDefaults.colors(
                                                                        focusedTextColor = Color.White,
                                                                        unfocusedTextColor = Color.White,
                                                                        focusedBorderColor = Color(0xFF3498DB),
                                                                        unfocusedBorderColor = Color(0xFF3E3E44)
                                                                    ),
                                                                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                                                )
 
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                                ) {
                                                                    // Quick Full Pay button
                                                                    Button(
                                                                        onClick = {
                                                                            val originalAmt = editAmountInput.toDoubleOrNull() ?: tx.amount
                                                                            editPaidAmountInput = originalAmt.toString()
                                                                        },
                                                                        modifier = Modifier.weight(1.2f),
                                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71)),
                                                                        shape = RoundedCornerShape(6.dp),
                                                                        contentPadding = PaddingValues(vertical = 6.dp)
                                                                    ) {
                                                                        Text("সম্পূর্ণ পরিশোধ ✅", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                                                    }
 
                                                                    // Save button
                                                                    Button(
                                                                        onClick = {
                                                                            val originalAmt = editAmountInput.toDoubleOrNull() ?: 0.0
                                                                            val paidAmt = editPaidAmountInput.toDoubleOrNull() ?: 0.0
                                                                            val cat = editCategoryInput.trim()
                                                                            if (originalAmt > 0.0 && cat.isNotEmpty()) {
                                                                                viewModel.viewModelScope.launch {
                                                                                    viewModel.dao.updateTransaction(
                                                                                        tx.copy(
                                                                                            amount = originalAmt,
                                                                                            paidAmount = paidAmt,
                                                                                            category = cat
                                                                                        )
                                                                                    )
                                                                                }
                                                                                activeEditTxId = null
                                                                                Toast.makeText(context, "লেনদেন আপডেট করা হয়েছে!", Toast.LENGTH_SHORT).show()
                                                                            } else {
                                                                                Toast.makeText(context, "সঠিক পরিমাণ ও বিবরণ লিখুন!", Toast.LENGTH_SHORT).show()
                                                                            }
                                                                        },
                                                                        modifier = Modifier.weight(1f),
                                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3498DB)),
                                                                        shape = RoundedCornerShape(6.dp),
                                                                        contentPadding = PaddingValues(vertical = 6.dp)
                                                                    ) {
                                                                        Text("সংরক্ষণ 💾", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                                                    }
 
                                                                    // Cancel button
                                                                    Button(
                                                                        onClick = { activeEditTxId = null },
                                                                        modifier = Modifier.weight(0.8f),
                                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E3E44)),
                                                                        shape = RoundedCornerShape(6.dp),
                                                                        contentPadding = PaddingValues(vertical = 6.dp)
                                                                    ) {
                                                                        Text("বাতিল ❌", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
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
        }
    }

    if (showEditPersonDialog && personToEdit != null) {
        AlertDialog(
            onDismissRequest = {
                showEditPersonDialog = false
                personToEdit = null
            },
            title = { Text("নাম পরিবর্তন করুন", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ব্যক্তির নতুন নাম লিখুন:", color = Color(0xFFC7C7CD), fontSize = 13.sp)
                    OutlinedTextField(
                        value = editPersonNameInput,
                        onValueChange = { editPersonNameInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3498DB),
                            unfocusedBorderColor = Color(0xFF3E3E44)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3498DB))
                ) {
                    Text("পরিবর্তন করুন", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showEditPersonDialog = false
                        personToEdit = null
                    }
                ) {
                    Text("বাতিল", color = Color(0xFF9E9E9E))
                }
            },
            containerColor = Color(0xFF1E1E22),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

fun convertToBengaliNumber(numberStr: String): String {
    val englishDigits = listOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
    val bengaliDigits = listOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
    return numberStr.map { char ->
        val idx = englishDigits.indexOf(char)
        if (idx != -1) bengaliDigits[idx] else char
    }.joinToString("")
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

    var showAddEditDialog by remember { mutableStateOf(false) }
    var dialogType by remember { mutableStateOf("EXPENSE") } // "INCOME" or "EXPENSE"

    // If we enter edit mode from somewhere, automatically show the dialog
    LaunchedEffect(viewModel.editingTransactionId) {
        if (viewModel.editingTransactionId != null) {
            dialogType = viewModel.activeFormType
            showAddEditDialog = true
        }
    }

    val generalTransactions = transactions.filter {
        it.personName.trim().isEmpty() || it.personName.trim().lowercase() == "সাধারণ"
    }

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
    val labelFormat = SimpleDateFormat("d MMMM yy", Locale("bn"))
    
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
    val defaultIncomeCategories = listOf("বেতন 💼", "ব্যবসা 📈", "ফ্রিল্যান্সিং 💻", "বিনিয়োগ 🏦", "উপহার 🎁", "অন্যান্য 🪙")
    val defaultExpenseCategories = listOf("খাবার 🍔", "ভাড়া 🏠", "বিল ⚡", "যাতায়াত 🚗", "কেনাকাটা 🛍️", "ওষুধ 💊", "বিনোদন 🎬", "শিক্ষা 📚", "অন্যান্য 💸")

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

    val filteredIncomeSum = filteredTxList.filter { it.type == "INCOME" }.sumOf { it.amount }
    val filteredExpenseSum = filteredTxList.filter { it.type == "EXPENSE" }.sumOf { it.amount }
    val filteredBalance = filteredIncomeSum - filteredExpenseSum

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // 0. Dynamic visual summary card for the active filtered set - AT THE VERY TOP
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
            border = BorderStroke(1.dp, Color(0xFF2E2E34))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📊 নির্বাচিত তালিকার হিসাব",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (isMonthFiltered) {
                        Text(
                            text = "আগের মাসের ক্যারি: ৳ ${convertToBengaliNumber(String.format(Locale.US, "%,.2f", carryOverFromPrevMonths))}",
                            fontSize = 10.sp,
                            color = Color(0xFF2ECC71),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                        Text("মোট আয় 📈", fontSize = 10.sp, color = Color(0xFF9E9E9E))
                        Text(
                            text = "৳ ${convertToBengaliNumber(String.format(Locale.US, "%,.2f", filteredIncomeSum))}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2ECC71),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(32.dp)
                            .background(Color(0xFF2E2E38))
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text("মোট ব্যয় 📉", fontSize = 10.sp, color = Color(0xFF9E9E9E))
                        Text(
                            text = "৳ ${convertToBengaliNumber(String.format(Locale.US, "%,.2f", filteredExpenseSum))}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE74C3C),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(32.dp)
                            .background(Color(0xFF2E2E38))
                    )
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1.2f)) {
                        Text("ব্যালেন্স 🪙", fontSize = 10.sp, color = Color(0xFF9E9E9E))
                        Text(
                            text = "৳ ${convertToBengaliNumber(String.format(Locale.US, "%,.2f", filteredBalance))}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (filteredBalance >= 0.0) Color(0xFF2ECC71) else Color(0xFFE74C3C),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        // 1. Double Form Entry Card (Replaced with 2 popup entry buttons per user request)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    viewModel.cancelEditing()
                    dialogType = "INCOME"
                    viewModel.activeFormType = "INCOME"
                    viewModel.resetTrackerFormDateTime()
                    showAddEditDialog = true
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ECC71)),
                shape = RoundedCornerShape(12.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("📈", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "আয় এন্ট্রি",
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Button(
                onClick = {
                    viewModel.cancelEditing()
                    dialogType = "EXPENSE"
                    viewModel.activeFormType = "EXPENSE"
                    viewModel.resetTrackerFormDateTime()
                    showAddEditDialog = true
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C)),
                shape = RoundedCornerShape(12.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("📉", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ব্যয় এন্ট্রি",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (showAddEditDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAddEditDialog = false
                    viewModel.cancelEditing()
                },
                title = {
                    val titleText = if (viewModel.editingTransactionId != null) {
                        "লেনদেন সংশোধন করুন ✏️"
                    } else if (dialogType == "INCOME") {
                        "আয় যোগ করুন 📈"
                    } else {
                        "ব্যয় যোগ করুন 📉"
                    }
                    Text(
                        text = titleText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
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

                        // 1. Category
                        OutlinedTextField(
                            value = viewModel.categoryInput,
                            onValueChange = { viewModel.categoryInput = it },
                            label = { Text("ক্যাটাগরি", color = Color(0xFFC7C7CD), fontSize = 13.sp) },
                            placeholder = { Text("যেমন: বেতন, খাবার, ভাড়া") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = if (dialogType == "INCOME") Color(0xFF2ECC71) else Color(0xFFE74C3C),
                                unfocusedBorderColor = Color(0xFF3E3E44)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 2. Amount
                        OutlinedTextField(
                            value = viewModel.amountInput,
                            onValueChange = { viewModel.amountInput = it },
                            label = { Text("পরিমাণ (৳)", color = Color(0xFFC7C7CD), fontSize = 13.sp) },
                            placeholder = { Text("টাকার পরিমাণ") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = if (dialogType == "INCOME") Color(0xFF2ECC71) else Color(0xFFE74C3C),
                                unfocusedBorderColor = Color(0xFF3E3E44)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

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
                                label = { Text("তারিখ ও সময়", color = Color(0xFFC7C7CD), fontSize = 13.sp) },
                                trailingIcon = {
                                    IconButton(onClick = { datePickerDialog.show() }) {
                                        Text("🕒", fontSize = 16.sp)
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = Color.White,
                                    disabledBorderColor = if (dialogType == "INCOME") Color(0xFF2ECC71) else Color(0xFFE74C3C),
                                    disabledLabelColor = Color(0xFF9E9E9E),
                                    disabledTrailingIconColor = Color(0xFF9E9E9E)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 4. Description (Note) - 3 lines height, enter key break
                        OutlinedTextField(
                            value = viewModel.noteInput,
                            onValueChange = { viewModel.noteInput = it },
                            label = { Text("বিবরণ", color = Color(0xFFC7C7CD), fontSize = 13.sp) },
                            placeholder = { Text("লেনদেনের বিস্তারিত বিবরণ লিখুন...") },
                            singleLine = false,
                            minLines = 3,
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Default
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = if (dialogType == "INCOME") Color(0xFF2ECC71) else Color(0xFFE74C3C),
                                unfocusedBorderColor = Color(0xFF3E3E44)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val amount = viewModel.amountInput.toDoubleOrNull()
                            if (amount == null || amount <= 0.0) {
                                Toast.makeText(context, "অনুগ্রহ করে সঠিক টাকার পরিমাণ লিখুন!", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.saveTransaction()
                                showAddEditDialog = false
                                Toast.makeText(context, "রেকর্ড সফলভাবে সংরক্ষিত হয়েছে!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (dialogType == "INCOME") Color(0xFF2ECC71) else Color(0xFFE74C3C)
                        )
                    ) {
                        val btnText = if (viewModel.editingTransactionId != null) "আপডেট করুন" else "সংরক্ষণ করুন"
                        Text(text = btnText, color = if (dialogType == "INCOME") Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAddEditDialog = false
                            viewModel.cancelEditing()
                        }
                    ) {
                        Text("বাতিল", color = Color(0xFF9E9E9E))
                    }
                },
                containerColor = Color(0xFF1E1E22),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // 2. Filter System Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
            border = BorderStroke(1.dp, Color(0xFF2E2E34))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🔍 রেকর্ড ফিল্টার ও অনুসন্ধান",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                var isSearchVisible by remember { mutableStateOf(false) }
                var yearMenuExpanded by remember { mutableStateOf(false) }
                var monthMenuExpanded by remember { mutableStateOf(false) }
                var dayMenuExpanded by remember { mutableStateOf(false) }

                val monthNames = listOf(
                    "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
                    "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"
                )

                val selectedYearText = convertToBengaliNumber(viewModel.filterYear) + " সাল"

                val availableMonths = if (viewModel.filterYear == "ALL") {
                    emptyList()
                } else {
                    (0..11).toList()
                }

                val selectedMonthText = if (viewModel.filterMonth == "ALL") {
                    "সকল মাস"
                } else {
                    monthNames.getOrNull(viewModel.filterMonth.toIntOrNull() ?: -1) ?: "সকল মাস"
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
                    "সকল দিন"
                } else {
                    "${convertToBengaliNumber(viewModel.filterDay)} তারিখ"
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
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A32)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (viewModel.filterYear == "ALL") "সকল বছর 🗓️" else "$selectedYearText 🗓️",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("▼", color = Color(0xFF9E9E9E), fontSize = 7.sp)
                            }
                        }

                        DropdownMenu(
                            expanded = yearMenuExpanded,
                            onDismissRequest = { yearMenuExpanded = false },
                            modifier = Modifier
                                .background(Color(0xFF1E1E22))
                                .heightIn(max = 280.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("সকল বছর", color = Color.White, fontSize = 12.sp) },
                                onClick = {
                                    viewModel.filterYear = "ALL"
                                    viewModel.filterMonth = "ALL"
                                    viewModel.filterDay = "ALL"
                                    yearMenuExpanded = false
                                }
                            )
                            (2026..2099).forEach { y ->
                                DropdownMenuItem(
                                    text = { Text(convertToBengaliNumber(y.toString()), color = Color.White, fontSize = 12.sp) },
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
                                    Toast.makeText(context, "প্রথমে বছর সিলেক্ট করুন!", Toast.LENGTH_SHORT).show()
                                } else {
                                    monthMenuExpanded = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (viewModel.filterYear == "ALL") Color(0xFF1A1A22) else Color(0xFF2A2A32)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (viewModel.filterMonth == "ALL") "সকল মাস 📅" else "$selectedMonthText 📅",
                                    color = if (viewModel.filterYear == "ALL") Color(0xFF6E6E74) else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("▼", color = Color(0xFF9E9E9E), fontSize = 7.sp)
                            }
                        }

                        DropdownMenu(
                            expanded = monthMenuExpanded,
                            onDismissRequest = { monthMenuExpanded = false },
                            modifier = Modifier
                                .background(Color(0xFF1E1E22))
                                .heightIn(max = 280.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("সকল মাস", color = Color.White, fontSize = 12.sp) },
                                onClick = {
                                    viewModel.filterMonth = "ALL"
                                    viewModel.filterDay = "ALL"
                                    monthMenuExpanded = false
                                }
                            )
                            availableMonths.forEach { mIdx ->
                                DropdownMenuItem(
                                    text = { Text(monthNames[mIdx], color = Color.White, fontSize = 12.sp) },
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
                                    Toast.makeText(context, "প্রথমে বছর ও মাস সিলেক্ট করুন!", Toast.LENGTH_SHORT).show()
                                } else {
                                    dayMenuExpanded = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (viewModel.filterYear == "ALL" || viewModel.filterMonth == "ALL") Color(0xFF1A1A22) else Color(0xFF2A2A32)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (viewModel.filterDay == "ALL") "সকল দিন ⏱️" else "$selectedDayText",
                                    color = if (viewModel.filterYear == "ALL" || viewModel.filterMonth == "ALL") Color(0xFF6E6E74) else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("▼", color = Color(0xFF9E9E9E), fontSize = 7.sp)
                            }
                        }

                        DropdownMenu(
                            expanded = dayMenuExpanded,
                            onDismissRequest = { dayMenuExpanded = false },
                            modifier = Modifier
                                .background(Color(0xFF1E1E22))
                                .heightIn(max = 280.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("সকল দিন", color = Color.White, fontSize = 12.sp) },
                                onClick = {
                                    viewModel.filterDay = "ALL"
                                    dayMenuExpanded = false
                                }
                            )
                            availableDays.forEach { d ->
                                DropdownMenuItem(
                                    text = { Text("${convertToBengaliNumber(d.toString())} তারিখ", color = Color.White, fontSize = 12.sp) },
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
                            containerColor = if (isSearchVisible) Color(0xFF3498DB) else Color(0xFF2A2A32)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier.weight(0.9f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("🔍", fontSize = 10.sp)
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = if (isSearchVisible) "Hide" else "Search",
                                color = Color.White,
                                fontSize = 10.sp,
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
                        label = { Text("ক্যাটাগরি ফিল্টার করুন", fontSize = 11.sp) },
                        placeholder = { Text("যেমন: খাবার", fontSize = 11.sp) },
                        trailingIcon = { Text("🔍", modifier = Modifier.padding(end = 12.dp)) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3498DB),
                            unfocusedBorderColor = Color(0xFF3E3E44)
                        )
                    )
                }
            }
        }

        // 3. Transactions Record Card List

        Text(
            text = "রেকর্ড তালিকা (${convertToBengaliNumber(filteredTxList.size.toString())} টি পাওয়া গেছে)",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (filteredTxList.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF2E2E34))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("💸", fontSize = 42.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "কোনো রেকর্ড খুঁজে পাওয়া যায়নি!",
                        color = Color(0xFF9E9E9E),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "নতুন আয় বা ব্যয় এন্ট্রি যুক্ত করুন অথবা আপনার ফিল্টার পরিবর্তন করুন।",
                        color = Color(0xFF6E6E74),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            val itemsToShow = if (viewModel.showAllTransactions) filteredTxList else filteredTxList.take(5)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsToShow.forEach { tx ->
                    TransactionItemRow(
                        transaction = tx,
                        onEdit = { viewModel.startEditingTransaction(tx) },
                        onDelete = {
                            viewModel.confirmDelete(
                                title = "লেনদেন ডিলিট নিশ্চিতকরণ",
                                message = "আপনি কি এই লেনদেনটি ডিলিট করতে চান?",
                                action = {
                                    viewModel.deleteTransactionWithUndo(tx, showSnackbarWithUndo)
                                }
                            )
                        },
                        onDoubleClick = { viewModel.selectedTransactionForDetails = tx }
                    )
                }

                if (filteredTxList.size > 5) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = { viewModel.showAllTransactions = !viewModel.showAllTransactions },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A32)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (viewModel.showAllTransactions) "কম রেকর্ড দেখুন 🔼" else "সব রেকর্ড দেখুন 🔽",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Daily Income & Expense Summary List Card
        var showAllDailySummaries by remember { mutableStateOf(false) }
        val dailySummariesToShow = if (showAllDailySummaries) activeDailySummaries else activeDailySummaries.take(7)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
            border = BorderStroke(1.dp, Color(0xFF2E2E34))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "📅 দৈনিক হিসাব সারসংক্ষেপ (${convertToBengaliNumber(activeDailySummaries.size.toString())} দিন)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Table Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121214), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "তারিখ",
                        fontSize = 11.sp,
                        color = Color(0xFF9E9E9E),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1.3f)
                    )
                    Text(
                        text = "আয়",
                        fontSize = 11.sp,
                        color = Color(0xFF2ECC71),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1.0f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = "ব্যয়",
                        fontSize = 11.sp,
                        color = Color(0xFFE74C3C),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1.0f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = "ব্যালেন্স",
                        fontSize = 11.sp,
                        color = Color(0xFF3498DB),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1.2f),
                        textAlign = TextAlign.End
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (activeDailySummaries.isEmpty()) {
                    Text(
                        text = "কোনো দৈনিক সারসংক্ষেপ পাওয়া যায়নি!",
                        color = Color(0xFF6E6E74),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    )
                } else {
                    dailySummariesToShow.forEach { summary ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = summary.dateString,
                                fontSize = 12.sp,
                                color = Color.White,
                                modifier = Modifier.weight(1.3f),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (summary.income > 0.0) "৳${convertToBengaliNumber(String.format(Locale.US, "%,.0f", summary.income))}" else "০",
                                fontSize = 12.sp,
                                color = if (summary.income > 0.0) Color(0xFF2ECC71) else Color(0xFF9E9E9E),
                                modifier = Modifier.weight(1.0f),
                                textAlign = TextAlign.End,
                                fontWeight = if (summary.income > 0.0) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = if (summary.expense > 0.0) "৳${convertToBengaliNumber(String.format(Locale.US, "%,.0f", summary.expense))}" else "০",
                                fontSize = 12.sp,
                                color = if (summary.expense > 0.0) Color(0xFFE74C3C) else Color(0xFF9E9E9E),
                                modifier = Modifier.weight(1.0f),
                                textAlign = TextAlign.End,
                                fontWeight = if (summary.expense > 0.0) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = "৳${convertToBengaliNumber(String.format(Locale.US, "%,.0f", summary.balanceAtEnd))}",
                                fontSize = 12.sp,
                                color = if (summary.balanceAtEnd >= 0.0) Color(0xFF2ECC71) else Color(0xFFE74C3C),
                                modifier = Modifier.weight(1.2f),
                                textAlign = TextAlign.End,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Divider(color = Color(0xFF2E2E34), thickness = 0.5.dp)
                    }

                    if (activeDailySummaries.size > 7) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showAllDailySummaries = !showAllDailySummaries },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A32)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (showAllDailySummaries) "কম সারসংক্ষেপ দেখুন 🔼" else "সব দৈনিক সারসংক্ষেপ দেখুন 🔽",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// TRANSACTION CARD ELEMENT WITH DOUBLE CLICK DETECTOR
// ============================================================================

@Composable
fun TransactionItemRow(
    transaction: TransactionEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val dateString = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale("bn")).format(Date(transaction.dateTime))
    val isIncome = transaction.type == "INCOME"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(transaction.id) {
                detectTapGestures(
                    onDoubleTap = { onDoubleClick() }
                )
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
        border = BorderStroke(
            width = 1.dp,
            color = if (isIncome) Color(0xFF2ECC71).copy(alpha = 0.4f) else Color(0xFFE74C3C).copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left block (Color bar indicator, info)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                // Color bar indicator
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isIncome) Color(0xFF2ECC71) else Color(0xFFE74C3C))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = transaction.category,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (transaction.personName.isNotEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF3498DB).copy(alpha = 0.12f)),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = transaction.personName,
                                    color = Color(0xFF3498DB),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = dateString,
                        color = Color(0xFF9E9E9E),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (transaction.note.isNotEmpty()) {
                        Text(
                            text = transaction.note,
                            color = Color(0xFFCCCCCC),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Right block (Amount and controls)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${if (isIncome) "+" else "-"}৳${String.format(Locale.US, "%,.2f", transaction.amount)}",
                    color = if (isIncome) Color(0xFF2ECC71) else Color(0xFFE74C3C),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                // Edit Button
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFF2A2A30), CircleShape)
                ) {
                    Text("✏️", fontSize = 12.sp)
                }

                // Delete Button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFE74C3C).copy(alpha = 0.15f), CircleShape)
                ) {
                    Text("🗑️", fontSize = 12.sp)
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
    var noticeContentInputState by remember { mutableStateOf(TextFieldValue(viewModel.noticeContentInput)) }

    // Sync input states
    LaunchedEffect(noticeContentInputState.text) {
        viewModel.noticeContentInput = noticeContentInputState.text
    }

    LaunchedEffect(viewModel.noticeContentInput) {
        if (viewModel.noticeContentInput.isEmpty() && noticeContentInputState.text.isNotEmpty()) {
            noticeContentInputState = TextFieldValue("")
        } else if (viewModel.noticeContentInput.isNotEmpty() && noticeContentInputState.text != viewModel.noticeContentInput) {
            noticeContentInputState = TextFieldValue(viewModel.noticeContentInput)
        }
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
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
                border = BorderStroke(1.dp, Color(0xFF2E2E34))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dialogTitle = if (viewModel.editingNotice != null) "📓 নোট সংশোধন করুন" else "📓 নতুন নোট ও চেকলিস্ট"
                        Text(
                            text = dialogTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
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
                            Text("❌", fontSize = 12.sp)
                        }
                    }

                    // Title/Heading input field
                    OutlinedTextField(
                        value = viewModel.noticeTitleInput,
                        onValueChange = { viewModel.noticeTitleInput = it },
                        label = { Text("Header") },
                        placeholder = { Text("Header") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3498DB),
                            unfocusedBorderColor = Color(0xFF3E3E44)
                        )
                    )

                    // Content input field (with TextFieldValue state)
                    OutlinedTextField(
                        value = noticeContentInputState,
                        onValueChange = { noticeContentInputState = it },
                        label = { Text("Details") },
                        placeholder = { Text("Details") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3498DB),
                            unfocusedBorderColor = Color(0xFF3E3E44)
                        )
                    )

                    // Checkbox helper row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
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
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A30)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("☑️ চেকবক্স যোগ করুন", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            text = "💡 লাইনের শুরুতে '[ ]' দিলে চেকবক্স হবে",
                            fontSize = 10.sp,
                            color = Color(0xFF9E9E9E)
                        )
                    }

                    Button(
                        onClick = {
                            if (viewModel.noticeContentInput.trim().isEmpty() && viewModel.noticeTitleInput.trim().isEmpty()) {
                                Toast.makeText(context, "অনুগ্রহ করে শিরোনাম বা নোটের বিবরণ লিখুন!", Toast.LENGTH_SHORT).show()
                            } else {
                                val msg = if (viewModel.editingNotice != null) "নোটবুক-এ আপডেট করা হয়েছে! 📓" else "নোটবুক-এ যুক্ত করা হয়েছে! 📓"
                                viewModel.saveNotice()
                                viewModel.showAddNoticeDialog = false
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3498DB)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        val buttonText = if (viewModel.editingNotice != null) "নোটবুক-এ আপডেট করুন 📓" else "নোটবুক-এ যোগ করুন 📓"
                        Text(buttonText, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 2. Notebook List Title
        Text(
            text = "সংরক্ষিত নোটসমূহ (${notices.size} টি)",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (notices.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF2E2E34))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📓", fontSize = 42.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "আপনার নোটবুক খালি!",
                        color = Color(0xFF9E9E9E),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "আপনার যেকোনো নোট, বাজারের চেকলিস্ট অথবা টার্গেট এখানে সংরক্ষণ করে রাখুন।",
                        color = Color(0xFF6E6E74),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            // Display notices in a clean single vertical list
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                notices.forEach { notice ->
                    StickyNoteCard(
                        notice = notice,
                        onDelete = {
                            viewModel.confirmDelete(
                                title = "নোট মুছে ফেলার নিশ্চিতকরণ",
                                message = "আপনি কি এই নোটটি মুছে ফেলতে চান?",
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

// ============================================================================
// STICKY NOTE CARD (NOTEBOOK CARD WITH CHECKBOX SUPPORT)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickyNoteCard(
    notice: NoticeEntity,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onUpdate: (NoticeEntity) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    val dateString = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale("bn")).format(Date(notice.timestamp))
    val stickyBgColor = Color(android.graphics.Color.parseColor(notice.colorHex))

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
            if (clean.length > 30) clean.take(30) + "..." else clean
        } else {
            "নামহীন নোট 📝"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { isExpanded = !isExpanded },
                onLongClick = onDelete
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = stickyBgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header: Only show Title when collapsed or expanded, with indicator
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = displayTitle,
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Text(
                    text = if (isExpanded) "▲" else "▼",
                    color = Color.Black.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Full view shown only when expanded
            if (isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = Color.Black.copy(alpha = 0.15f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📅 $dateString", fontSize = 9.sp, color = Color.Black.copy(alpha = 0.6f))
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                                            checkedColor = Color.Black.copy(alpha = 0.8f),
                                            uncheckedColor = Color.Black.copy(alpha = 0.6f),
                                            checkmarkColor = stickyBgColor
                                        ),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = text,
                                        color = if (isChecked) Color.Black.copy(alpha = 0.4f) else Color.Black,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None
                                    )
                                }
                            } else {
                                if (line.trim().isNotEmpty()) {
                                    Text(
                                        text = line,
                                        color = Color.Black,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
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
                        color = Color.Black,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.Black.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Text("✏️", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}


// ============================================================================
// DOUBLE TAP DETAILS POPUP DIALOG
// ============================================================================

@Composable
fun DoubleTapDetailsDialog(transaction: TransactionEntity, onDismiss: () -> Unit) {
    val formattedDate = SimpleDateFormat("dd MMMM yyyy, hh:mm a", Locale("bn")).format(Date(transaction.dateTime))
    val isIncome = transaction.type == "INCOME"

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
            border = BorderStroke(
                width = 1.dp,
                color = if (isIncome) Color(0xFF2ECC71) else Color(0xFFE74C3C)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "রেকর্ড বিস্তারিত বিবরণ 🔎",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Text("❌", fontSize = 12.sp)
                    }
                }

                Divider(color = Color(0xFF2E2E34))

                // Amount (মোট টাকা)
                Column {
                    Text(text = "মোট টাকা:", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                    Text(
                        text = "৳ ${String.format(Locale.US, "%,.2f", transaction.amount)} (${if (isIncome) "আয়" else "ব্যয়"})",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isIncome) Color(0xFF2ECC71) else Color(0xFFE74C3C)
                    )
                }

                // Date Time (তারিখ-সময়)
                Column {
                    Text(text = "তারিখ ও সময়:", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                    Text(text = formattedDate, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }



                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A30)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("বন্ধ করুন", color = Color.White)
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
        containerColor = Color(0xFF1E1E22),
        tonalElevation = 8.dp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        NavigationBarItem(
            selected = activeTab == "ACCOUNT",
            onClick = { onTabSelected("ACCOUNT") },
            icon = { Text("👥", fontSize = 20.sp) },
            label = { Text("দেনা-পাওনা", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF2ECC71),
                unselectedIconColor = Color(0xFF9E9E9E),
                selectedTextColor = Color(0xFF2ECC71),
                unselectedTextColor = Color(0xFF9E9E9E),
                indicatorColor = Color(0xFF2ECC71).copy(alpha = 0.12f)
            )
        )

        NavigationBarItem(
            selected = activeTab == "TRACKER",
            onClick = { onTabSelected("TRACKER") },
            icon = { Text("💸", fontSize = 20.sp) },
            label = { Text("আয় ও ব্যয়", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF2ECC71),
                unselectedIconColor = Color(0xFF9E9E9E),
                selectedTextColor = Color(0xFF2ECC71),
                unselectedTextColor = Color(0xFF9E9E9E),
                indicatorColor = Color(0xFF2ECC71).copy(alpha = 0.12f)
            )
        )

        NavigationBarItem(
            selected = activeTab == "NOTICE",
            onClick = { onTabSelected("NOTICE") },
            icon = { Text("📓", fontSize = 20.sp) },
            label = { Text("নোটবুক", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF2ECC71),
                unselectedIconColor = Color(0xFF9E9E9E),
                selectedTextColor = Color(0xFF2ECC71),
                unselectedTextColor = Color(0xFF9E9E9E),
                indicatorColor = Color(0xFF2ECC71).copy(alpha = 0.12f)
            )
        )
    }
}
