package ke.elgonlabs.barberbeyond

import android.os.Bundle
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private val Gold = Color(0xFFFFD36E)
private val Dark = Color(0xFF0B0B0C)
private val Panel = Color(0xFF211D18)
private val TextColor = Color(0xFFF6F4EF)
private val Muted = Color(0xFFB6B4B0)
private val Danger = Color(0xFFFF8A80)
private data class BrandPalette(
    val gold: Color, val dark: Color, val panel: Color,
    val text: Color, val muted: Color, val danger: Color, val hero: Color
)
private val darkPalette = BrandPalette(Gold, Dark, Panel, TextColor, Muted, Danger, Color(0xFF443018))
private val lightPalette = BrandPalette(
    Color(0xFF7B580F), Color(0xFFF9F7F1), Color(0xFFFFFFFF),
    Color(0xFF21201D), Color(0xFF635F58), Color(0xFFC62828), Color(0xFFF4EBD9)
)
private val LocalBrandColors = compositionLocalOf { darkPalette }
private val settingsSections = listOf("Business Profile", "Appearance", "Security & Privacy", "Backup", "Preferences", "About Application")
private fun isSettingsDetail(page: String) = page in settingsSections

private fun makePinHash(pin: String): String {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val spec = PBEKeySpec(pin.toCharArray(), salt, 150_000, 256)
    val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    return "v1:" + Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + Base64.encodeToString(key, Base64.NO_WRAP)
}
private fun checkPin(pin: String, stored: String): Boolean = try {
    val parts = stored.split(':')
    require(parts.size == 3 && parts[0] == "v1")
    val salt = Base64.decode(parts[1], Base64.NO_WRAP)
    val storedKey = Base64.decode(parts[2], Base64.NO_WRAP)
    val spec = PBEKeySpec(pin.toCharArray(), salt, 150_000, storedKey.size * 8)
    val candidate = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    MessageDigest.isEqual(storedKey, candidate)
} catch (_: Exception) { false }
private val suggestedPrices = listOf(
    "Fade" to 100.0, "Beard Trim" to 50.0, "School Cuts" to 50.0,
    "Balding" to 100.0, "Balding + Facial Scrub" to 200.0,
    "Ear Piercing" to 250.0, "Dreadlocks Retouch" to 500.0,
    "Artificial Locks" to 2000.0, "Starter Locks" to 1000.0,
    "Facial Scrub" to 100.0, "Fade + Hair Rolling" to 150.0,
    "Hair Bleach" to 1000.0, "Hairline + Beard Trim" to 80.0
)
private fun money(amount: Double) = "KES " + "%,.2f".format(Locale.US, amount)
private fun date(time: Long) = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(time))
private fun backupName() = "barber-beyond-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())}.json"

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun periodStart(period: String): Long = when (period) {
    "Today" -> startOfToday()
    "7 days" -> System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    "Month" -> Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    else -> 0L
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(
            applicationContext, BusinessDatabase::class.java, "barber_beyond_local.db"
        ).build()
        setContent { Manager(db.records()) }
    }
}

@Composable
fun Manager(dao: RecordDao) {
    val records by dao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember(context) { context.getSharedPreferences("bb_manager_settings", android.content.Context.MODE_PRIVATE) }
    var themeChoice by remember { mutableStateOf(prefs.getString("appearance", "Dark Gold") ?: "Dark Gold") }
    var compactTiles by remember { mutableStateOf(prefs.getBoolean("compact_tiles", false)) }
    var shopName by remember { mutableStateOf(prefs.getString("shop_name", "Barber & Beyond Hair Studio") ?: "") }
    var shopPhone by remember { mutableStateOf(prefs.getString("shop_phone", "") ?: "") }
    var shopLocation by remember { mutableStateOf(prefs.getString("shop_location", "Kimumu, Eldoret") ?: "") }
    var defaultPayment by remember { mutableStateOf(prefs.getString("default_payment", "Cash") ?: "Cash") }
    var configuredPin by remember { mutableStateOf(prefs.getString("pin_hash", "") ?: "") }
    var unlocked by remember { mutableStateOf(configuredPin.isBlank()) }
    var unlockPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf("") }
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinSettingsError by remember { mutableStateOf("") }
    var lastBackup by remember { mutableStateOf(prefs.getString("last_backup", "Not yet backed up") ?: "Not yet backed up") }
    val useLight = themeChoice == "Light" || (themeChoice == "System" && !isSystemInDarkTheme())
    val palette = if (useLight) lightPalette else darkPalette
    val Gold = palette.gold
    val Dark = palette.dark
    val Panel = palette.panel
    val TextColor = palette.text
    val Muted = palette.muted
    val Danger = palette.danger
    var tab by remember { mutableStateOf("Dashboard") }
    var bookingSession by remember { mutableStateOf<BookingSession?>(null) }
    var backupParent by remember { mutableStateOf("Dashboard") }
    fun goBack() { tab = if (tab == "Backup") backupParent else if (isSettingsDetail(tab)) "Settings" else "Dashboard" }
    BackHandler(enabled = tab != "Dashboard") { goBack() }
    var search by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("Month") }
    var editing by remember { mutableStateOf<Record?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorType by remember { mutableStateOf("Sales") }
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var payment by remember { mutableStateOf(defaultPayment) }
    var formError by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Record?>(null) }
    var restoreCandidate by remember { mutableStateOf<List<Record>?>(null) }
    fun notice(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun openEditor(type: String, record: Record? = null) {
        editorType = type
        editing = record
        title = record?.title.orEmpty()
        amount = record?.let { if (type in listOf("Sales", "Expenses", "Services")) it.amount.toString() else "" }.orEmpty()
        notes = record?.notes.orEmpty()
        payment = record?.payment ?: defaultPayment
        formError = ""
        showEditor = true
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val snapshot = records.toList()
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Cannot write to the selected location")
                        output.use { stream ->
                            stream.write(BackupCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
                        }
                    }
                    lastBackup = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
                    prefs.edit().putString("last_backup", lastBackup).apply()
                    notice("Backup saved (${snapshot.size} records). Keep the JSON file private.")
                } catch (e: Exception) {
                    notice("Backup failed: ${e.message ?: "unknown error"}")
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Cannot read the selected file")
                        val raw = input.bufferedReader(Charsets.UTF_8).use { reader ->
                            // Avoid loading arbitrarily large documents into memory.
                            reader.readText().also { require(it.length <= 15_000_000) { "Backup is too large" } }
                        }
                        BackupCodec.decode(raw)
                    }
                    restoreCandidate = imported
                } catch (e: Exception) {
                    notice("Restore file rejected: ${e.message ?: "invalid JSON"}")
                }
            }
        }
    }

    val sales = records.filter { it.type == "Sales" }
    val expenses = records.filter { it.type == "Expenses" }
    val services = records.filter { it.type == "Services" }.sortedBy { it.title.lowercase(Locale.US) }
    val todayStart = startOfToday()
    val todaySales = sales.filter { it.createdAt >= todayStart }.sumOf { it.amount }
    val todayExpenses = expenses.filter { it.createdAt >= todayStart }.sumOf { it.amount }
    val periodRecords = records.filter { it.createdAt >= periodStart(period) }
    val periodSales = periodRecords.filter { it.type == "Sales" }
    val periodExpenses = periodRecords.filter { it.type == "Expenses" }

    CompositionLocalProvider(LocalBrandColors provides palette) {
    MaterialTheme(
        colorScheme = if (useLight) lightColorScheme(
            primary = Gold, onPrimary = Panel, background = Dark, surface = Panel,
            onBackground = TextColor, onSurface = TextColor, error = Danger
        ) else darkColorScheme(
            primary = Gold, onPrimary = Dark, background = Dark, surface = Panel,
            onBackground = TextColor, onSurface = TextColor, error = Danger
        )
    ) {
        Scaffold(
            containerColor = Dark,
            snackbarHost = { SnackbarHost(snackbar) }
        ) { contentPadding ->
            Column(Modifier.fillMaxSize().padding(contentPadding).background(Dark)) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (tab != "Dashboard") {
                        TextButton(onClick = { goBack() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(36.dp)) {
                            Text("‹", fontSize = 30.sp, color = Gold)
                        }
                    }
                    Image(
                        painter = painterResource(R.drawable.bb_logo),
                        contentDescription = "Barber and Beyond logo",
                        modifier = Modifier.size(38.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text("BARBER & BEYOND", color = Gold, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                        Text("MANAGER  •  OFFLINE FIRST", color = Muted, fontSize = 10.sp)
                    }
                    if (tab == "Dashboard") {
                        TextButton(onClick = { tab = "Settings" }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("⚙", color = Gold, fontSize = 23.sp)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
                    if (tab != "Dashboard") {
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(tab, color = TextColor, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (tab in listOf("Sales", "Expenses", "Services")) {
                                Button(onClick = { openEditor(tab) }) { Text("+ Add") }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    when (tab) {

                        "Dashboard" -> {
                            val todayCount = sales.count { it.createdAt >= todayStart }
                            val cashToday = sales.filter { it.createdAt >= todayStart && it.payment == "Cash" }.sumOf { it.amount }
                            val mpesaToday = sales.filter { it.createdAt >= todayStart && it.payment == "M-Pesa" }.sumOf { it.amount }
                            val recentActivity = records.filter { it.type == "Sales" || it.type == "Expenses" }
                                .sortedByDescending { it.createdAt }.take(2)
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Spacer(Modifier.height(2.dp))
                                BarberHeroBanner()
                                RevenueHero(money(todaySales), todayCount) { openEditor("Sales") }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    DashboardMiniMetric("EXPENSES", money(todayExpenses), "−", Modifier.weight(1f), false)
                                    DashboardMiniMetric("EST. PROFIT", money(todaySales - todayExpenses), "↗", Modifier.weight(1f), true)
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Quick access", color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f))
                                    Text("YOUR TOOLS", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    HomeTile("Sales", "＋", compactTiles, Modifier.weight(1f)) { tab = "Sales"; search = "" }
                                    HomeTile("Expenses", "−", compactTiles, Modifier.weight(1f)) { tab = "Expenses"; search = "" }
                                    HomeTile("Services", "✂", compactTiles, Modifier.weight(1f)) { tab = "Services"; search = "" }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    HomeTile("Reports", "▤", compactTiles, Modifier.weight(1f)) { tab = "Reports" }
                                    HomeTile("History", "◷", compactTiles, Modifier.weight(1f)) { tab = "History"; search = "" }
                                    HomeTile("Backup", "⇧", compactTiles, Modifier.weight(1f)) { backupParent = "Dashboard"; tab = "Backup" }
                                }
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { tab = "Bookings" },
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.5.dp, Gold.copy(alpha = .8f)),
                                    colors = CardDefaults.cardColors(containerColor = Panel),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth().background(
                                        Brush.horizontalGradient(listOf(Color(0xFF4C3317), Panel))
                                    ).padding(horizontal=16.dp,vertical=14.dp),
                                        verticalAlignment=Alignment.CenterVertically) {
                                        Text("▦",color=Gold,fontSize=26.sp)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Website Bookings",fontWeight=FontWeight.Bold,color=TextColor,fontSize=15.sp)
                                            Text("Open online requests",color=Muted,fontSize=11.sp)
                                        }
                                        Text("›",color=Gold,fontWeight=FontWeight.Bold,fontSize=25.sp)
                                    }
                                }
                                PaymentSplitCard(cashToday, mpesaToday)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Recent activity", color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f))
                                    TextButton(onClick = { tab = "History"; search = "" }) {
                                        Text("View all ›", color = Gold, fontSize = 12.sp)
                                    }
                                }
                                if (recentActivity.isEmpty()) {
                                    Card(colors = CardDefaults.cardColors(containerColor = Panel),
                                        shape = RoundedCornerShape(13.dp),
                                        border = BorderStroke(1.dp, Gold.copy(alpha = 0.12f))) {
                                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("✦", color = Gold, fontSize = 20.sp)
                                            Spacer(Modifier.width(12.dp))
                                            Column {
                                                Text("Your studio is ready", color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                Text("Add a sale to start today's activity.", color = Muted, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                } else {
                                    recentActivity.forEach { record ->
                                        RecentRecord(record) { tab = "History"; search = "" }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        "Reports" -> {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("Today", "7 days", "Month", "All").forEach { value ->
                                        FilterChip(selected = period == value, onClick = { period = value }, label = { Text(value) })
                                    }
                                }
                                StatCard("Sales (${period.lowercase(Locale.US)})", money(periodSales.sumOf { it.amount }), Modifier.fillMaxWidth())
                                StatCard("Expenses", money(periodExpenses.sumOf { it.amount }), Modifier.fillMaxWidth())
                                StatCard("Estimated profit*", money(periodSales.sumOf { it.amount } - periodExpenses.sumOf { it.amount }), Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    StatCard("Cash sales", money(periodSales.filter { it.payment == "Cash" }.sumOf { it.amount }), Modifier.weight(1f))
                                    StatCard("M-Pesa sales", money(periodSales.filter { it.payment == "M-Pesa" }.sumOf { it.amount }), Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("* Estimates exclude unrecorded costs and taxes. M-Pesa entries are manual, not payment verification.", color = Muted, fontSize = 11.sp)
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                        "Bookings" -> {
                            BookingInbox(bookingSession) { bookingSession = it }
                        }
                        "Settings" -> {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("Customize your shop manager", color = Muted, fontSize = 12.sp)
                                SettingLink("Business Profile", "Shop details and contact", "▣") { tab = "Business Profile" }
                                SettingLink("Appearance", "Dark Gold, light and compact layout", "◐") { tab = "Appearance" }
                                SettingLink("Security & Privacy", "Offline app PIN", "♙") { tab = "Security & Privacy" }
                                SettingLink("Backup & Restore", "Protect your records", "⇪") { backupParent="Settings"; tab = "Backup" }
                                SettingLink("Preferences", "Default payment method", "≡") { tab = "Preferences" }
                                SettingLink("About Application", "App version and developer", "ⓘ") { tab = "About Application" }
                            }
                        }
                        "Business Profile" -> {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(value=shopName, onValueChange={shopName=it}, singleLine=true, modifier=Modifier.fillMaxWidth(), label={Text("Business name")})
                                OutlinedTextField(value=shopPhone, onValueChange={shopPhone=it}, singleLine=true, modifier=Modifier.fillMaxWidth(), label={Text("Phone / contact")})
                                OutlinedTextField(value=shopLocation, onValueChange={shopLocation=it}, singleLine=true, modifier=Modifier.fillMaxWidth(), label={Text("Location")})
                                Button(onClick={
                                    if (shopName.isBlank()) notice("Enter a business name")
                                    else if (shopName.length > 100 || shopPhone.length > 50 || shopLocation.length > 200) notice("Please shorten the business details")
                                    else {
                                        prefs.edit().putString("shop_name", shopName.trim()).putString("shop_phone", shopPhone.trim())
                                            .putString("shop_location", shopLocation.trim()).apply()
                                        notice("Business profile saved offline")
                                    }
                                }, modifier=Modifier.fillMaxWidth()) { Text("Save business profile") }
                                Text("The app retains its existing Barber & Beyond logo. Custom logo uploads can be added in a later update.", color=Muted, fontSize=12.sp)
                            }
                        }
                        "Appearance" -> {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("App theme", color=Muted, fontSize=12.sp)
                                listOf("Dark Gold", "Light", "System").forEach { choice ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        themeChoice=choice; prefs.edit().putString("appearance",choice).apply()
                                    }.padding(vertical=6.dp), verticalAlignment=Alignment.CenterVertically) {
                                        RadioButton(selected=themeChoice==choice, onClick={themeChoice=choice; prefs.edit().putString("appearance",choice).apply()})
                                        Text(choice, color=TextColor)
                                    }
                                }
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Compact home tiles", fontWeight=FontWeight.SemiBold)
                                        Text("Smaller navigation buttons", color=Muted, fontSize=12.sp)
                                    }
                                    Switch(checked=compactTiles, onCheckedChange={ compactTiles=it; prefs.edit().putBoolean("compact_tiles",it).apply() })
                                }
                            }
                        }
                        "Security & Privacy" -> {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(if (configuredPin.isBlank()) "App PIN is off" else "App PIN is on", color=Gold, fontWeight=FontWeight.SemiBold)
                                Text("A PIN protects opening the app. It does not encrypt exported JSON backups. Keep your backups private.", color=Muted, fontSize=12.sp)
                                if (configuredPin.isNotBlank()) {
                                    OutlinedTextField(value=oldPin, onValueChange={oldPin=it.take(6);pinSettingsError=""},
                                        label={Text("Current PIN")}, visualTransformation=PasswordVisualTransformation(),
                                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword), singleLine=true, modifier=Modifier.fillMaxWidth())
                                }
                                OutlinedTextField(value=newPin, onValueChange={newPin=it.take(6);pinSettingsError=""},
                                    label={Text(if (configuredPin.isBlank()) "Create PIN" else "New PIN")}, visualTransformation=PasswordVisualTransformation(),
                                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword), singleLine=true, modifier=Modifier.fillMaxWidth())
                                OutlinedTextField(value=confirmPin, onValueChange={confirmPin=it.take(6);pinSettingsError=""},
                                    label={Text("Confirm new PIN")}, visualTransformation=PasswordVisualTransformation(),
                                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword), singleLine=true, modifier=Modifier.fillMaxWidth())
                                if (pinSettingsError.isNotBlank()) Text(pinSettingsError, color=Danger, fontSize=12.sp)
                                Button(onClick={
                                    when {
                                        configuredPin.isNotBlank() && !checkPin(oldPin, configuredPin) -> pinSettingsError="Current PIN is incorrect"
                                        !newPin.matches(Regex("[0-9]{4,6}")) -> pinSettingsError="Choose 4 to 6 digits"
                                        newPin!=confirmPin -> pinSettingsError="PINs do not match"
                                        else -> {
                                            val hashed=makePinHash(newPin)
                                            configuredPin=hashed; prefs.edit().putString("pin_hash",hashed).apply()
                                            oldPin="";newPin="";confirmPin=""; pinSettingsError=""
                                            unlocked=true; notice("App PIN saved")
                                        }
                                    }
                                }, modifier=Modifier.fillMaxWidth()) { Text(if (configuredPin.isBlank()) "Enable PIN" else "Change PIN") }
                                if (configuredPin.isNotBlank()) {
                                    OutlinedButton(onClick={
                                        if (!checkPin(oldPin, configuredPin)) pinSettingsError="Enter the current PIN to disable it"
                                        else {
                                            prefs.edit().remove("pin_hash").apply(); configuredPin=""; unlocked=true
                                            oldPin="";newPin="";confirmPin="";pinSettingsError=""; notice("App PIN disabled")
                                        }
                                    }, modifier=Modifier.fillMaxWidth()) { Text("Disable PIN") }
                                    OutlinedButton(onClick={unlockPin="";unlocked=false;tab="Dashboard"}, modifier=Modifier.fillMaxWidth()) { Text("Lock now") }
                                }
                                Text("If you forget your PIN, recovery may require clearing app data. Make a backup before enabling it.", color=Muted, fontSize=11.sp)
                            }
                        }
                        "Backup" -> {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Last successful export: $lastBackup", color=Muted, fontSize=13.sp)
                                Button(onClick={exportLauncher.launch(backupName())}, modifier=Modifier.fillMaxWidth()) { Text("Export JSON backup") }
                                OutlinedButton(onClick={importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))}, modifier=Modifier.fillMaxWidth()) { Text("Restore JSON backup") }
                                Text("Restoring replaces all app records after confirmation. Keep two copies of your current backup before restoring or reinstalling.", color=Muted, fontSize=12.sp)
                                Text("Backups are unencrypted. Do not share files containing your sales or other private records publicly.", color=Muted, fontSize=12.sp)
                            }
                        }
                        "Preferences" -> {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Default payment method", color=Muted, fontSize=12.sp)
                                listOf("Cash", "M-Pesa").forEach { option ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        defaultPayment=option; prefs.edit().putString("default_payment",option).apply()
                                    }, verticalAlignment=Alignment.CenterVertically) {
                                        RadioButton(selected=defaultPayment==option, onClick={defaultPayment=option; prefs.edit().putString("default_payment",option).apply()})
                                        Text(option, color=TextColor)
                                    }
                                }
                                HorizontalDivider()
                                Text("Currency: Kenyan Shilling (KES)", color=TextColor)
                                Text("All records remain stored offline on this device.", color=Muted, fontSize=12.sp)
                            }
                        }
                        "About Application" -> {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("BARBER & BEYOND MANAGER", color=Gold, fontSize=18.sp, fontWeight=FontWeight.Bold)
                                Text("Version 1.6.2 • Offline-First Edition", color=TextColor)
                                Text("Powered by TILLERN LABS", color=Gold)
                                Text("Offline sales, expenses, services and reports, plus a secure online booking inbox.", color=Muted)
                                Text("Local business records remain on this device. Booking requests require internet and an authorized Supabase admin account.", color=Muted, fontSize=12.sp)
                            }
                        }
                        else -> {
                            if (tab == "Services") {
                                Text("Manage prices below. Tap Sell to copy a service into a new sale.", color = Muted, fontSize = 12.sp)
                                if (services.isEmpty()) {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    suggestedPrices.forEach { (name, price) ->
                                                        dao.insert(Record(type = "Services", title = name, amount = price))
                                                    }
                                                }
                                                notice("Suggested prices loaded. Review and edit them.")
                                            } catch (e: Exception) { notice("Could not load prices: ${e.message}") }
                                        }
                                    }) { Text("Load suggested studio prices") }
                                }
                            }
                            OutlinedTextField(
                                value = search, onValueChange = { search = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("Search $tab") }
                            )
                            Spacer(Modifier.height(10.dp))
                            val matching = records.filter {
                                (it.type == tab || (tab == "History" && it.type in listOf("Sales", "Expenses"))) && (search.isBlank() ||
                                    it.title.contains(search, ignoreCase = true) ||
                                    it.notes.contains(search, ignoreCase = true) ||
                                    it.payment.contains(search, ignoreCase = true))
                            }
                            if (matching.isEmpty()) {
                                Text(if (search.isBlank()) if (tab == "History") "No transactions yet." else "No $tab records yet. Tap + Add." else "No matching records.", color = Muted)
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(bottom = 14.dp)
                                ) {
                                    items(matching, key = { it.id }) { record ->
                                        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                                Text(record.title, fontWeight = FontWeight.SemiBold, color = TextColor)
                                                if (tab in listOf("Sales", "Expenses", "History") && record.type != "Services") {
                                                    Text("${money(record.amount)}  •  ${record.payment}", color = Gold)
                                                } else if (tab == "Services") {
                                                    Text(money(record.amount), color = Gold)
                                                }
                                                if (record.notes.isNotBlank()) Text(record.notes, color = Muted, fontSize = 12.sp)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("${record.type} • ${date(record.createdAt)}", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                                    if (tab == "Services") {
                                                        TextButton(onClick = {
                                                            tab = "Sales"
                                                            openEditor("Sales")
                                                            title = record.title
                                                            amount = record.amount.toString()
                                                        }) { Text("Sell") }
                                                    }
                                                    TextButton(onClick = { openEditor(record.type, record) }) { Text("Edit") }
                                                    TextButton(onClick = { deleteTarget = record }) { Text("Delete", color = Danger) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    "Powered by TILLERN LABS",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = Muted, fontSize = 10.sp
                )
            }
        }

        if (configuredPin.isNotBlank() && !unlocked) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("App locked") },
                text = {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Enter your app PIN to continue.")
                        OutlinedTextField(
                            value=unlockPin, onValueChange={unlockPin=it.take(6);pinError=""},
                            label={Text("PIN")}, singleLine=true,
                            visualTransformation=PasswordVisualTransformation(),
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword)
                        )
                        if (pinError.isNotBlank()) Text(pinError, color=Danger, fontSize=12.sp)
                    }
                },
                confirmButton={
                    Button(onClick={
                        if (checkPin(unlockPin, configuredPin)) { unlocked=true;unlockPin="";pinError="" }
                        else {pinError="Incorrect PIN";unlockPin=""}
                    }) { Text("Unlock") }
                }
            )
        }

        if (showEditor && unlocked) {
            AlertDialog(
                onDismissRequest = { showEditor = false },
                title = { Text(if (editing == null) "Add $editorType" else "Edit $editorType") },
                text = {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = title, onValueChange = { title = it; formError = "" },
                            label = { Text(when (editorType) {
                                "Services" -> "Service name"
                                else -> "Service / description"
                            }) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        if (editorType == "Sales" && services.isNotEmpty()) {
                            Text("Quick fill from your service prices", color = Muted, fontSize = 12.sp)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                services.forEach { service ->
                                    AssistChip(onClick = { title = service.title; amount = service.amount.toString() }, label = { Text(service.title) })
                                }
                            }
                        }
                        if (editorType == "Sales" || editorType == "Expenses" || editorType == "Services") {
                            OutlinedTextField(
                                value = amount, onValueChange = { amount = it; formError = "" },
                                label = { Text("Amount (KES)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            if (editorType != "Services") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = payment == "Cash", onClick = { payment = "Cash" })
                                    Text("Cash", color = TextColor)
                                    Spacer(Modifier.width(12.dp))
                                    RadioButton(selected = payment == "M-Pesa", onClick = { payment = "M-Pesa" })
                                    Text("M-Pesa", color = TextColor)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = notes, onValueChange = { notes = it; formError = "" },
                            label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth()
                        )
                        if (formError.isNotBlank()) Text(formError, color = Danger, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val parsed = if (editorType in listOf("Sales", "Expenses", "Services")) amount.toDoubleOrNull() else 0.0
                        when {
                            title.isBlank() || title.length > 250 -> formError = "Enter a title (up to 250 characters)."
                            notes.length > 4_000 -> formError = "Notes are too long."
                            parsed == null || !parsed.isFinite() || parsed < 0 -> formError = "Enter a valid, non-negative amount."
                            else -> {
                                val old = editing
                                val updated = (old ?: Record(type = editorType, title = title.trim())).copy(
                                    title = title.trim(), amount = parsed, payment = payment, notes = notes.trim()
                                )
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            if (old == null) dao.insert(updated) else dao.update(updated)
                                        }
                                        notice(if (old == null) "Record added" else "Record updated")
                                    } catch (e: Exception) { notice("Save failed: ${e.message}") }
                                }
                                showEditor = false
                            }
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } }
            )
        }

        if (unlocked) deleteTarget?.let { candidate ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Delete this record?") },
                text = { Text("${candidate.title} will be permanently removed from this app. Export a backup first if needed.") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        scope.launch {
                            try { withContext(Dispatchers.IO) { dao.delete(candidate) }; notice("Record deleted") }
                            catch (e: Exception) { notice("Delete failed: ${e.message}") }
                        }
                    }) { Text("Delete", color = Danger) }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
            )
        }

        if (unlocked) restoreCandidate?.let { candidate ->
            AlertDialog(
                onDismissRequest = { restoreCandidate = null },
                title = { Text("Replace all records?") },
                text = { Text("The backup has ${candidate.size} records. Restoring will REPLACE the ${records.size} records currently in this app. Export your existing data first. Continue?") },
                confirmButton = {
                    TextButton(onClick = {
                        restoreCandidate = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { dao.replaceAll(candidate) }
                                notice("Restore complete: ${candidate.size} records")
                            } catch (e: Exception) { notice("Restore failed: ${e.message}") }
                        }
                    }) { Text("Replace records", color = Danger) }
                },
                dismissButton = { TextButton(onClick = { restoreCandidate = null }) { Text("Cancel") } }
            )
        }
    }
    }
}

@Composable
private fun StudioHero(
    salesAmount: String,
    dateLabel: String,
    onNewSale: () -> Unit,
    onNewExpense: () -> Unit
) {
    val palette = LocalBrandColors.current
    val Gold = palette.gold
    val Panel = palette.panel
    val TextColor = palette.text
    val Muted = palette.muted
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(brush = Brush.linearGradient(listOf(palette.hero, Panel, palette.dark)), shape = shape)
            .border(1.dp, Gold.copy(alpha = 0.35f), shape)
            .padding(18.dp)
    ) {
        Text("STUDIO OVERVIEW", color = Gold, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(5.dp))
        Text(dateLabel, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(13.dp))
        Text("Today's sales", color = TextColor, fontSize = 15.sp)
        Text(salesAmount, color = Gold, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onNewSale, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Text("+ New sale", fontSize = 12.sp, maxLines = 1)
            }
            OutlinedButton(onClick = onNewExpense, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Text("+ Expense", fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LaunchTile(label: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalBrandColors.current
    val Gold = palette.gold
    val Panel = palette.panel
    val TextColor = palette.text
    val Muted = palette.muted
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text(label, color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(5.dp))
            Text(detail, color = Muted, fontSize = 11.sp, maxLines = 2)
            Spacer(Modifier.height(14.dp))
            Text("OPEN  ›", color = Gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val palette = LocalBrandColors.current
    val Gold = palette.gold
    val Panel = palette.panel
    val Muted = palette.muted
    val interactiveModifier = if (onClick == null) modifier else modifier.clickable { onClick() }
    Card(interactiveModifier.padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            Text(value, color = Gold, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2)
        }
    }
}

@Composable
private fun RecentRecord(record: Record, onClick: () -> Unit) {
    val palette = LocalBrandColors.current
    val Gold = palette.gold
    val Panel = palette.panel
    val TextColor = palette.text
    val Muted = palette.muted
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(record.title, color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${record.type}  •  ${date(record.createdAt)}", color = Muted, fontSize = 11.sp)
        }
        if (record.type == "Sales" || record.type == "Expenses") {
            Text(money(record.amount), color = Gold, fontSize = 12.sp)
        }
    }
}


private data class HeroSlide(val imageId: Int, val description: String, val label: String)
private val dashboardHeroSlides = listOf(
    HeroSlide(R.drawable.hero_grooming, "Barber providing professional beard grooming", "BEARD GROOMING"),
    HeroSlide(R.drawable.hero_fade, "Barber cutting a modern fade haircut", "PRECISION HAIRCUTS"),
    HeroSlide(R.drawable.hero_studio, "Premium barber shop chairs and grooming stations", "PREMIUM BARBER STUDIO")
)

@Composable
private fun BarberHeroBanner() {
    val palette = LocalBrandColors.current
    val pagerState = rememberPagerState(pageCount = { dashboardHeroSlides.size })
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(18.dp)

    // Auto-advance every five seconds while Dashboard is on screen; swiping works natively.
    LaunchedEffect(pagerState) {
        while (true) {
            delay(5_000L)
            if (!pagerState.isScrollInProgress) {
                val nextPage = (pagerState.currentPage + 1) % dashboardHeroSlides.size
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier.fillMaxWidth().height(162.dp)
                .clip(shape)
                .border(1.5.dp, palette.gold.copy(alpha = .88f), shape)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                val slide = dashboardHeroSlides[page]
                Box(Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(slide.imageId),
                        contentDescription = slide.description,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            listOf(Color(0xCA070708), Color(0x85070708), Color(0x15070708))
                        )
                    ))
                    Column(
                        Modifier.align(Alignment.CenterStart).padding(start = 17.dp, end = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "WELCOME BACK",
                            fontSize = 10.sp, color = palette.gold,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                        )
                        Text(
                            "Shop Overview", fontSize = 23.sp, color = Color.White,
                            fontWeight = FontWeight.ExtraBold, maxLines = 1
                        )
                        Text(
                            SimpleDateFormat("EEE, dd MMM yyyy", Locale.ENGLISH).format(Date()),
                            fontSize = 12.sp, color = Color(0xFFFFE7B7)
                        )
                    }
                    Text(
                        slide.label,
                        color = Color(0xFFFFD77D), fontWeight = FontWeight.Bold,
                        fontSize = 9.sp, letterSpacing = .5.sp, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 11.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            dashboardHeroSlides.forEachIndexed { index, _ ->
                Box(
                    Modifier.size(width = 30.dp, height = 19.dp)
                        .clickable {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(if (index == pagerState.currentPage) 21.dp else 7.dp)
                            .height(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) palette.gold
                                else palette.muted.copy(alpha = .48f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun RevenueHero(salesAmount: String, salesCount: Int, onAddSale: () -> Unit) {
    val palette = LocalBrandColors.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(palette.hero, palette.panel)), shape)
            .border(1.dp, palette.gold.copy(alpha = 0.48f), shape)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("TODAY'S REVENUE", color = palette.muted, fontSize = 10.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(5.dp))
            Text(salesAmount, color = palette.gold, fontSize = 27.sp,
                fontWeight = FontWeight.ExtraBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(salesCount.toString() + if (salesCount == 1) " sale today" else " sales today",
                color = palette.muted, fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onAddSale,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 8.dp),
            modifier = Modifier.height(42.dp)
        ) {
            Text("+ Sale", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun DashboardMiniMetric(
    title: String, amount: String, symbol: String, modifier: Modifier = Modifier,
    emphasize: Boolean = false
) {
    val colors = LocalBrandColors.current
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = colors.panel),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, colors.gold.copy(alpha = 0.48f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = colors.muted, fontSize = 10.sp, letterSpacing = 0.7.sp,
                    modifier = Modifier.weight(1f), maxLines = 1)
                Text(symbol, color = colors.gold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(5.dp))
            Text(amount, color = if (emphasize) colors.gold else colors.text,
                fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PaymentSplitCard(cashAmount: Double, mpesaAmount: Double) {
    val colors = LocalBrandColors.current
    val total = cashAmount + mpesaAmount
    val cashFraction = if (total > 0.0) (cashAmount / total).toFloat().coerceIn(0f, 1f) else 0f
    Card(colors = CardDefaults.cardColors(containerColor = colors.panel),
        shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, colors.gold.copy(alpha = 0.15f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("TODAY'S PAYMENTS", color = colors.muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cash", color = colors.muted, fontSize = 11.sp)
                    Text(money(cashAmount), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("M-Pesa", color = colors.muted, fontSize = 11.sp)
                    Text(money(mpesaAmount), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp)
                .background(colors.gold.copy(alpha = 0.19f), RoundedCornerShape(6.dp))) {
                if (cashFraction > 0f) {
                    Box(Modifier.fillMaxWidth(cashFraction).fillMaxHeight()
                        .background(colors.gold, RoundedCornerShape(6.dp)))
                }
            }
        }
    }
}

@Composable
private fun HomeTile(label: String, glyph: String, compact: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalBrandColors.current
    Card(onClick = onClick,
        modifier = modifier.height(if (compact) 76.dp else 92.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.hero),
        border = BorderStroke(1.1.dp, colors.gold.copy(alpha = 0.59f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 7.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(if (compact) 29.dp else 36.dp)
                    .background(colors.hero, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(glyph, color = colors.gold, fontSize = if (compact) 19.sp else 22.sp,
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(7.dp))
            Text(label, color = colors.text, fontSize = 11.sp,
                maxLines = 1, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SettingLink(label: String, subtitle: String, glyph: String, onClick: () -> Unit) {
    val palette = LocalBrandColors.current
    Card(onClick=onClick, modifier=Modifier.fillMaxWidth(),
        colors=CardDefaults.cardColors(containerColor=palette.panel), shape=RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(glyph, color=palette.gold, fontSize=22.sp, modifier=Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color=palette.text, fontSize=14.sp, fontWeight=FontWeight.SemiBold)
                Text(subtitle, color=palette.muted, fontSize=11.sp)
            }
            Text("›", color=palette.gold, fontSize=22.sp)
        }
    }
}