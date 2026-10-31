package ke.elgonlabs.barberbeyond

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val gold = Color(0xFFE8BD64)
private val dark = Color.Black
private val white = Color(0xFFF6F4EF)
private val muted = Color(0xFFB6B4B0)

/** Branded opening screen: checks that the local database is readable before showing the app. */
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var retryNumber by remember { mutableIntStateOf(0) }
            var errorMessage by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(retryNumber) {
                errorMessage = null
                val started = SystemClock.elapsedRealtime()
                try {
                    // Read the first Room emission off the UI thread.
                    // This tests initialization; it does not change the user's records.
                    withContext(Dispatchers.IO) {
                        val db = Room.databaseBuilder(
                            applicationContext,
                            BusinessDatabase::class.java,
                            "barber_beyond_local.db"
                        ).build()
                        try {
                            db.records().observeAll().first()
                        } finally {
                            db.close()
                        }
                    }
                    // Small minimum display time for a readable logo, not fake progress.
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (elapsed < 700L) delay(700L - elapsed)
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java).apply {
                        putExtra("bb_open_bookings",
                            intent?.getBooleanExtra("bb_open_bookings", false) == true)
                    })
                    finish()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    errorMessage = "Couldn't open local records. Your saved data was not changed."
                }
            }
            SplashView(errorMessage) { retryNumber++ }
        }
    }
}

@Composable
private fun SplashView(errorMessage: String?, onRetry: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = gold, background = dark)) {
        Box(Modifier.fillMaxSize().background(dark)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Full original Barber & Beyond Hair Studio brand artwork.
                // The icon-only crop is used for the Android launcher/system splash.
                Image(
                    painter = painterResource(R.drawable.bb_brand),
                    contentDescription = "Barber & Beyond Hair Studio official logo",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(1f),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(8.dp))
                Text("OFFLINE MANAGER", color = white, fontSize = 13.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(38.dp))
                if (errorMessage == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = gold,
                        trackColor = Color(0xFF302B23),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Loading your business records…", color = muted, fontSize = 12.sp)
                } else {
                    Text(
                        errorMessage,
                        color = white,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onRetry) { Text("Try again") }
                }
            }
            Text(
                "Powered by TILLERN LABS",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp),
                color = muted,
                fontSize = 11.sp
            )
        }
    }
}
