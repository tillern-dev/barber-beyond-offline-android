package ke.elgonlabs.barberbeyond

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The Supabase publishable key is safe for clients. Access is restricted by Auth + RLS.
// Never put a secret or service_role key in this Android application.
private const val SUPABASE = "https://pgypziuvtaiywtdsyxyk.supabase.co"
private const val PUBLISHABLE_KEY = "sb_publishable_l_eusRTeUHRLZ30a1BFosQ_vWPTRXOu"
private val goldBooking = Color(0xFFE8BD64)
private val mutedBooking = Color(0xFFB6B4B0)
private val panelBooking = Color(0xFF22211F)

internal data class BookingSession(val access: String, val refresh: String, val expiresAt: Long)
internal data class WebBooking(
    val id: String, val customer: String, val phone: String, val service: String,
    val at: String, val notes: String, val status: String
)
internal data class WebService(val title: String, val price: Double)

private object BookingApi {
    private fun request(path: String, method: String = "GET", token: String? = null, data: String? = null): String {
        val conn = URL(SUPABASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.setRequestProperty("apikey", PUBLISHABLE_KEY)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Prefer", "return=representation")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token)
            if (data != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.bufferedWriter().use { it.write(data) }
            }
            val httpStatus = conn.responseCode
            if (httpStatus !in 200..299) throw IllegalStateException(when (httpStatus) {
                400, 401 -> "Invalid sign-in or expired session."
                403 -> "Business administrator access required."
                429 -> "Too many requests; retry later."
                else -> "Booking server error (HTTP " + httpStatus + ")."
            })
            return conn.inputStream.bufferedReader().use { it.readText().take(300000) }
        } finally { conn.disconnect() }
    }
    private fun decodeSession(json: String): BookingSession {
        val value = JSONObject(json)
        return BookingSession(value.getString("access_token"), value.getString("refresh_token"),
            System.currentTimeMillis() + 1000L * value.optLong("expires_in", 3600))
    }
    suspend fun login(email: String, password: String): BookingSession = withContext(Dispatchers.IO) {
        decodeSession(request("/auth/v1/token?grant_type=password", "POST",
            data = JSONObject().put("email", email.trim()).put("password", password).toString()))
    }
    private fun valid(session: BookingSession): BookingSession =
        if (System.currentTimeMillis() + 60000L < session.expiresAt) session else
            decodeSession(request("/auth/v1/token?grant_type=refresh_token", "POST",
                data = JSONObject().put("refresh_token", session.refresh).toString()))
    suspend fun load(session: BookingSession): Triple<BookingSession,List<WebBooking>,Map<String,WebService>> =
        withContext(Dispatchers.IO) {
            val active = valid(session)
            val bookingData = JSONArray(request(
                "/rest/v1/booking_requests?select=id,customer_name,phone,service_id,starts_at,notes,status&order=created_at.desc&limit=60",
                token = active.access))
            val rows = (0 until bookingData.length()).map { i ->
                val b = bookingData.getJSONObject(i)
                WebBooking(b.getString("id"),b.optString("customer_name"),b.optString("phone"),
                    b.optString("service_id"),b.optString("starts_at"),b.optString("notes"),b.optString("status"))
            }
            val serviceData = JSONArray(request(
                "/rest/v1/services?select=id,name,price_kes&active=eq.true&limit=200",
                token = active.access))
            val services = (0 until serviceData.length()).associate { i ->
                val b = serviceData.getJSONObject(i)
                b.getString("id") to WebService(b.optString("name"),b.optDouble("price_kes",0.0))
            }
            Triple(active, rows, services)
        }
    suspend fun decide(session: BookingSession, id: String, status: String): BookingSession =
        withContext(Dispatchers.IO) {
            require(status == "confirmed" || status == "declined")
            require(id.matches(Regex("[0-9a-fA-F-]{36}")))
            val active = valid(session)
            val result = JSONArray(request(
                "/rest/v1/booking_requests?id=eq." + id + "&status=eq.pending&select=id",
                "PATCH", active.access, JSONObject().put("status",status).toString()))
            require(result.length() == 1) { "This request is no longer pending or access was denied." }
            active
        }
}

private fun localBookingTime(value: String): String = try {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Africa/Nairobi"))
        .format(DateTimeFormatter.ofPattern("EEE, dd MMM • h:mm a", Locale.ENGLISH))
} catch (_: Exception) { value }

@Composable
internal fun BookingInbox(session: BookingSession?, onSession: (BookingSession?) -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<WebBooking>>(emptyList()) }
    var services by remember { mutableStateOf<Map<String, WebService>>(emptyMap()) }
    var pendingOnly by remember { mutableStateOf(true) }
    var decision by remember { mutableStateOf<Pair<WebBooking,String>?>(null) }

    suspend fun refresh(current: BookingSession) {
        busy = true
        try {
            val result = BookingApi.load(current)
            onSession(result.first)
            rows = result.second
            services = result.third
            notice = "Online requests updated."
        } catch (e: Exception) {
            notice = e.message ?: "Could not sync. Check internet."
        } finally { busy = false }
    }

    LaunchedEffect(session?.access) {
        if (session != null) {
            refresh(session)
            while (true) { delay(60000L); refresh(session) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(11.dp)) {
        if (session == null) {
            Text("Website booking inbox", color=goldBooking, fontSize=17.sp, fontWeight=FontWeight.Bold)
            Text("Sign in with your existing business admin account. Offline sales stay on your phone.",
                color=mutedBooking, fontSize=12.sp)
            OutlinedTextField(email, { email=it.take(150) }, label={Text("Admin email")},
                modifier=Modifier.fillMaxWidth(), singleLine=true)
            OutlinedTextField(password, { password=it }, label={Text("Admin password")},
                visualTransformation=PasswordVisualTransformation(),
                modifier=Modifier.fillMaxWidth(), singleLine=true)
            Button(enabled=!busy,onClick={
                if(email.isBlank()||password.isBlank()) notice="Enter your admin credentials."
                else scope.launch {
                    busy=true
                    try { val signed=BookingApi.login(email,password);password="";onSession(signed) }
                    catch(e:Exception){notice=e.message?:"Could not sign in"}
                    finally {busy=false}
                }
            }, modifier=Modifier.fillMaxWidth()) { Text(if(busy) "Connecting…" else "Connect booking inbox") }
            Text("Online only • Passwords are never saved in app storage.",
                color=mutedBooking,fontSize=11.sp)
        } else {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Online requests",fontWeight=FontWeight.Bold,color=goldBooking,
                    modifier=Modifier.weight(1f))
                TextButton(enabled=!busy,onClick={scope.launch{refresh(session)}}){Text("Refresh")}
                TextButton(onClick={onSession(null);rows=emptyList();notice="Signed out"}){
                    Text("Sign out",color=mutedBooking)
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                FilterChip(selected=pendingOnly,onClick={pendingOnly=true},label={Text("Pending")})
                FilterChip(selected=!pendingOnly,onClick={pendingOnly=false},label={Text("All")})
            }
            val filtered=if(pendingOnly) rows.filter{it.status=="pending"} else rows
            if(busy&&rows.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(filtered.isEmpty()&&!busy) Text("No bookings in this view.",color=mutedBooking)
            filtered.forEach { booking ->
                val service=services[booking.service]
                Card(colors=CardDefaults.cardColors(containerColor=panelBooking),
                    shape=RoundedCornerShape(15.dp),
                    border=BorderStroke(1.dp,goldBooking.copy(alpha=0.25f))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(booking.customer,color=Color.White,fontWeight=FontWeight.Bold,
                                modifier=Modifier.weight(1f),maxLines=1)
                            Text(booking.status.uppercase(Locale.US),color=goldBooking,fontSize=10.sp)
                        }
                        Text((service?.title?:"Selected service") + (service?.let {
                            " • KES " + "%.0f".format(Locale.US,it.price)
                        }?:""),color=goldBooking,fontSize=12.sp)
                        Text(localBookingTime(booking.at),color=Color.White,fontSize=12.sp)
                        Text("Phone: "+booking.phone,color=mutedBooking,fontSize=12.sp)
                        if(booking.notes.isNotBlank()&&booking.notes!="null")
                            Text(booking.notes,color=mutedBooking,fontSize=12.sp)
                        if(booking.status=="pending") Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={decision=booking to "declined"},
                                modifier=Modifier.weight(1f),enabled=!busy){Text("Decline")}
                            Button(onClick={decision=booking to "confirmed"},
                                modifier=Modifier.weight(1f),enabled=!busy){Text("Confirm")}
                        }
                    }
                }
            }
        }
        if(notice.isNotBlank()) Text(notice,color=mutedBooking,fontSize=12.sp)
        Spacer(Modifier.height(8.dp))
    }
    decision?.let { (booking,status) ->
        AlertDialog(
            onDismissRequest={decision=null},
            title={Text(if(status=="confirmed") "Confirm request?" else "Decline request?")},
            text={Text("This changes the online booking status. Contact the customer separately; no SMS is sent.")},
            confirmButton={TextButton(onClick={
                decision=null
                if(session!=null) scope.launch {
                    busy=true
                    try {
                        val updated=BookingApi.decide(session,booking.id,status)
                        onSession(updated)
                        refresh(updated)
                        notice="Booking "+status+". Inform the customer directly."
                    }catch(e:Exception){notice=e.message?:"Could not update"}
                    finally {busy=false}
                }
            }) {Text(if(status=="confirmed") "Confirm" else "Decline")}},
            dismissButton={TextButton(onClick={decision=null}){Text("Cancel")}}
        )
    }
}
