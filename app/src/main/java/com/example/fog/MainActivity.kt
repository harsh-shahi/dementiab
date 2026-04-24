package com.example.fog

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import com.example.fog.ui.theme.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class GaitReport(
    val hashId: String,
    val finalScore: Float,
    val logs: List<LogEntry>
)

data class PatientResponse(val name: String, val email: String)

data class EmailAttachment(
    val content: String,
    val filename: String
)

data class EmailRequest(
    val from: String,
    val to: List<String>,
    val subject: String,
    val html: String,
    val attachments: List<EmailAttachment>
)

interface GaitApiService {
    @POST("api/users/{hashId}/basic")
    suspend fun uploadReport(@Path("hashId") hashId: String, @Body report: GaitReport): retrofit2.Response<Unit>

    @GET("api/users/{hashId}/basic")
    suspend fun getPatient(@Path("hashId") hashId: String): retrofit2.Response<PatientResponse>

    @POST("https://api.resend.com/emails")
    suspend fun sendEmail(
        @Header("Authorization") apiKey: String,
        @Body request: EmailRequest
    ): retrofit2.Response<Unit>
}

object RetrofitClient {
    private const val BASE_URL = "https://sevasmriti.onrender.com/"
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    val instance: GaitApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GaitApiService::class.java)
    }
}

data class LogEntry(
    val timestamp: Long,
    val verticalAccel: Float,
    val fi: Float,
    val ei: Float,
    val sc: Float,
    val isFog: Boolean
)

@Composable
fun WelcomeScreen(viewModel: SensorViewModel) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(SevaBackground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = SevaBlue.copy(alpha = 0.2f),
                modifier = Modifier.size(80.dp)
            )
            Spacer(Modifier.height(16.dp))
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(24.dp))
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Sevasmriti",
                style = MaterialTheme.typography.headlineLarge,
                color = SevaBlue,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                "Gait Analysis Pro",
                style = MaterialTheme.typography.bodyMedium,
                color = SevaTextLight
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = { viewModel.scanPatientQr(context) },
                colors = ButtonDefaults.buttonColors(containerColor = SevaBlue),
                modifier = Modifier
                    .height(56.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, null)
                Spacer(Modifier.width(12.dp))
                Text("SCAN PATIENT QR TO UNLOCK", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Clinical access only",
                style = MaterialTheme.typography.labelSmall,
                color = SevaTextLight.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun DashboardScreen(viewModel: SensorViewModel, modifier: Modifier = Modifier) {
    val logs by viewModel.loggedData.observeAsState(emptyList())

    Column(modifier.background(SevaBackground).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Logo",
                modifier = Modifier.size(40.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    viewModel.patientName.ifEmpty { "Sevasmriti" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SevaBlue
                )
                val scoreColor = when {
                    viewModel.sessionScore >= 8.0f -> SevaGreen
                    viewModel.sessionScore >= 5.0f -> SevaOrange
                    else -> SevaRed
                }
                Text("Stability Score: ${"%.1f".format(viewModel.sessionScore)} / 10", style = MaterialTheme.typography.bodySmall, color = scoreColor, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { viewModel.hashId = "" }) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, tint = SevaTextLight)
            }
        }

        Spacer(Modifier.height(24.dp))

        ElevatedCard(
            Modifier.fillMaxWidth().height(120.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (viewModel.isFogDetected) Color(0xFFFDECEA) else Color(0xFFE8F5E9)
            ),
            elevation = CardDefaults.elevatedCardElevation(4.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "live_dot")
            val dotAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ), label = "alpha"
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .alpha(dotAlpha)
                                .background(if (viewModel.isFogDetected) SevaRed else SevaGreen, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (viewModel.isFogDetected) "FREEZING DETECTED" else "STABLE WALKING",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (viewModel.isFogDetected) SevaRed else SevaGreen
                        )
                    }
                    Text(
                        text = if (viewModel.isFogDetected) "High Risk Level" else "Normal Gait Pattern",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (viewModel.isFogDetected) SevaRed else SevaGreen
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SevaSurface),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Current FI", style = MaterialTheme.typography.labelSmall)
                        Text("%.2f".format(viewModel.currentFI), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (viewModel.isFogDetected) SevaRed else SevaGreen)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            (if (viewModel.isFogDetected) "FREEZE" else "STABLE"),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (viewModel.isLogging) viewModel.stopLogging() else viewModel.startLogging() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (viewModel.isLogging) SevaRed else SevaBlue
                        )
                    ) {
                        Icon(if (viewModel.isLogging) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                        Text(if (viewModel.isLogging) "STOP" else "START")
                    }
                    OutlinedButton(
                        onClick = { viewModel.startCalibration() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("CALIBRATE")
                    }
                }
                
                if (viewModel.isCalibrating) {
                    Text(
                        "Calibrating sensors... keep still",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                        color = SevaBlue
                    )
                    LinearProgressIndicator(
                        progress = { viewModel.calibrationProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = SevaBlue
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text("RECENT ANALYSIS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        LogTable(logs, Modifier.weight(1f))
    }
}

@Composable
fun LogTable(entries: List<LogEntry>, modifier: Modifier) {
    LazyColumn(modifier) {
        items(entries.takeLast(100).reversed()) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (entry.isFog) SevaRed else SevaGreen,
                            RoundedCornerShape(4.dp)
                        )
                )
                Spacer(Modifier.width(12.dp))
                Text("FI: %.2f".format(entry.fi), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = SevaText)
                Text(
                    if (entry.isFog) "FREEZE" else "WALKING",
                    color = if (entry.isFog) SevaRed else SevaGreen,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            HorizontalDivider(modifier = Modifier.alpha(0.05f))
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val lightColorScheme = lightColorScheme(
                primary = Color(0xFF00B4D8),
                onPrimary = Color.White,
                background = Color(0xFFFAFAFA),
                onBackground = Color(0xFF333333),
                surface = Color.White,
                onSurface = Color(0xFF333333)
            )
            MaterialTheme(colorScheme = lightColorScheme) {
                val viewModel: SensorViewModel = viewModel()
                if (viewModel.hashId.isEmpty()) {
                    WelcomeScreen(viewModel)
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = SevaBackground
                    ) { innerPadding ->
                        DashboardScreen(viewModel, Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}

class SensorViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val samplingPeriodUs = 10000 
    private val windowSize = 256
    private val stepSize = 40

    private val _linearAccelData = MutableLiveData(Triple(0f, 0f, 0f))
    val linearAccelData: LiveData<Triple<Float, Float, Float>> = _linearAccelData

    var isLogging by mutableStateOf(false)
    var isCalibrating by mutableStateOf(false)
    var calibrationProgress by mutableFloatStateOf(0f)
    var sessionScore by mutableFloatStateOf(10f)
    var hashId by mutableStateOf("")
    var patientEmail by mutableStateOf("")
    var patientName by mutableStateOf("")
    
    private var sensorBuffer = mutableListOf<Float>()
    private var samplesSinceLastProcess = 0
    private val currentLogSession = mutableListOf<LogEntry>()
    private val _loggedData = MutableLiveData<List<LogEntry>>(emptyList())
    val loggedData: LiveData<List<LogEntry>> = _loggedData

    private val calibrationFI = mutableListOf<Float>()
    private val calibrationEI = mutableListOf<Float>()
    private var thresholdFI = 3.0f

    var currentFI by mutableFloatStateOf(0f)
    var currentEI by mutableFloatStateOf(0f)
    var isFogDetected by mutableStateOf(false)

    init {
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        sensorManager.registerListener(this, accelSensor, samplingPeriodUs)
    }

    fun startLogging() { 
        currentLogSession.clear()
        _loggedData.value = emptyList()
        isLogging = true 
    }

    fun stopLogging() { 
        isLogging = false
        _loggedData.value = currentLogSession.takeLast(100)
        calculateScore()
        generatePdfAndSend()
        syncDataToBackend()
    }

    private fun generatePdfAndSend() {
        val fogCount = currentLogSession.count { it.isFog }
        val totalPoints = currentLogSession.size
        val fogPercentage = if (totalPoints > 0) (fogCount.toFloat() / totalPoints) * 100 else 0f
        val context = getApplication<Application>()

        viewModelScope.launch {
            try {
                val pdfFile = File(context.cacheDir, "Gait_Report_${hashId}.pdf")
                val document = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                val paint = Paint()

                // Header - Logo and Title
                try {
                    BitmapFactory.decodeResource(context.resources, R.drawable.app_logo)?.let { logo ->
                        val scaledLogo = Bitmap.createScaledBitmap(logo, 70, 70, true)
                        canvas.drawBitmap(scaledLogo, (595f - 70f) / 2f, 40f, paint)
                    }
                } catch (ignore: Exception) {}

                paint.textSize = 22f
                paint.isFakeBoldText = true
                paint.color = 0xFF00B4D8.toInt()
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("Sevasmriti - Gait Analysis Report", 595f / 2f, 140f, paint)
                
                paint.color = android.graphics.Color.LTGRAY
                canvas.drawLine(50f, 160f, 545f, 160f, paint)

                // Patient Information
                paint.textAlign = Paint.Align.LEFT
                paint.color = android.graphics.Color.BLACK
                paint.textSize = 14f
                paint.isFakeBoldText = true
                canvas.drawText("PATIENT INFORMATION", 50f, 190f, paint)
                
                paint.textSize = 12f
                paint.isFakeBoldText = false
                val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                canvas.drawText("Patient ID: $hashId", 50f, 215f, paint)
                canvas.drawText("Email: $patientEmail", 50f, 235f, paint)
                canvas.drawText("Date: ${sdf.format(Date())}", 50f, 255f, paint)

                // Data Table
                val tableTop = 300f
                paint.isFakeBoldText = true
                paint.color = android.graphics.Color.DKGRAY
                canvas.drawText("Metric", 60f, tableTop, paint)
                canvas.drawText("Value", 300f, tableTop, paint)
                canvas.drawText("Status", 440f, tableTop, paint)
                
                paint.strokeWidth = 1f
                canvas.drawLine(50f, tableTop + 10, 545f, tableTop + 10, paint)

                paint.isFakeBoldText = false
                paint.color = android.graphics.Color.BLACK
                var currentY = tableTop + 40f

                // Row: Stability Score
                canvas.drawText("Final Score(Gait + E-MoCA)", 60f, currentY, paint)
                val scoreColor = when {
                    sessionScore >= 8.0f -> 0xFF2D6A4F.toInt()
                    sessionScore >= 5.0f -> 0xFFE67E22.toInt()
                    else -> 0xFFC92A2A.toInt()
                }
                paint.color = scoreColor
                canvas.drawText("${"%.1f".format(sessionScore)} / 10.0", 300f, currentY, paint)
                val statusText = when {
                    sessionScore >= 8.0f -> "STABLE"
                    else -> "RISK"
                }
                canvas.drawText(statusText, 440f, currentY, paint)
                paint.color = android.graphics.Color.BLACK
                currentY += 30f

                // Row: FOG Events
                canvas.drawText("FOG Events Detected", 60f, currentY, paint)
                canvas.drawText("$fogCount", 300f, currentY, paint)
                canvas.drawText(if (fogCount > 0) "OBSERVED" else "NONE", 440f, currentY, paint)
                currentY += 30f

                // Row: FOG Percentage
                canvas.drawText("FOG Percentage", 60f, currentY, paint)
                canvas.drawText("${"%.1f".format(fogPercentage)}%", 300f, currentY, paint)
                if (fogPercentage > 10f) {
                    paint.color = 0xFFC92A2A.toInt()
                    canvas.drawText("ELEVATED", 440f, currentY, paint)
                } else {
                    paint.color = 0xFF2D6A4F.toInt()
                    canvas.drawText("NORMAL", 440f, currentY, paint)
                }
                paint.color = android.graphics.Color.BLACK
                currentY += 50f

                // Stability Scale Legend
                paint.isFakeBoldText = true
                paint.textSize = 14f
                paint.color = android.graphics.Color.BLACK
                canvas.drawText("STABILITY CLASSIFICATION SCALE", 50f, currentY, paint)
                currentY += 25f
                
                paint.textSize = 11f
                paint.isFakeBoldText = false
                
                // Color Blocks
                val legendX = 60f
                val blockWidth = 12f
                
                paint.color = 0xFF2D6A4F.toInt()
                canvas.drawRect(legendX, currentY - 10f, legendX + blockWidth, currentY, paint)
                canvas.drawText("8.0 - 10.0: High Stability (Stable)", legendX + 20f, currentY, paint)
                currentY += 20f
                
                paint.color = 0xFFE67E22.toInt()
                canvas.drawRect(legendX, currentY - 10f, legendX + blockWidth, currentY, paint)
                canvas.drawText("5.0 - 7.9: Moderate Risk (Caution advised)", legendX + 20f, currentY, paint)
                currentY += 20f
                
                paint.color = 0xFFC92A2A.toInt()
                canvas.drawRect(legendX, currentY - 10f, legendX + blockWidth, currentY, paint)
                canvas.drawText("0.0 - 4.9: High Risk (Unstable / Clinical Intervention)", legendX + 20f, currentY, paint)
                currentY += 50f

                // Clinical Interpretation
                paint.color = android.graphics.Color.BLACK
                paint.isFakeBoldText = true
                paint.textSize = 14f
                canvas.drawText("CLINICAL INTERPRETATION", 50f, currentY, paint)
                currentY += 25f
                paint.isFakeBoldText = false
                paint.textSize = 12f
                val interpretation = when {
                    sessionScore >= 8.0f -> "Gait analysis indicates high stability. No significant freezing detected."
                    sessionScore >= 5.0f -> "Moderate freezing of gait detected. Caution advised during ambulation."
                    else -> "Significant freezing detected. Clinical intervention or mobility assistance recommended."
                }
                
                // Multiline text wrapping
                val words = interpretation.split(" ")
                var line = ""
                for (word in words) {
                    if (paint.measureText("$line$word ") < 500f) {
                        line += "$word "
                    } else {
                        canvas.drawText(line.trim(), 50f, currentY, paint)
                        currentY += 20f
                        line = "$word "
                    }
                }
                canvas.drawText(line.trim(), 50f, currentY, paint)

                // Note at the bottom
                paint.color = android.graphics.Color.GRAY
                paint.textSize = 10f
                canvas.drawText("Note: Analysis based on $totalPoints total data points.", 50f, 800f, paint)

                document.finishPage(page)
                FileOutputStream(pdfFile).use { document.writeTo(it) }
                document.close()

                val bytes = pdfFile.readBytes()
                val base64Pdf = Base64.encodeToString(bytes, Base64.NO_WRAP)

                val htmlContent = """
                    <div style="font-family: sans-serif; max-width: 600px; margin: auto; padding: 20px; border: 1px solid #e0e0e0; border-radius: 12px;">
                        <div style="text-align: center; margin-bottom: 20px;">
                            <h2 style="color: #00B4D8;">Sevasmriti Gait Analysis</h2>
                        </div>
                        <p>Hello,</p>
                        <p>Please find your organized clinical gait report attached for session <b>$hashId</b>.</p>
                        <p>This report includes your stability score, gait metrics, and clinical interpretation.</p>
                        <br>
                        <p>Best Regards,<br>Sevasmriti Clinical Team</p>
                    </div>
                """.trimIndent()

                val emailRequest = EmailRequest(
                    from = "onboarding@resend.dev",
                    to = listOf(patientEmail),
                    subject = "Medical Report: Gait Analysis - $hashId",
                    html = htmlContent,
                    attachments = listOf(EmailAttachment(base64Pdf, "Gait_Medical_Report_${hashId}.pdf"))
                )

                val response = RetrofitClient.instance.sendEmail(
                    apiKey = "Bearer re_RtrrpxzQ_C56F1aeuSmhtwPFhwFnV5AAm",
                    request = emailRequest
                )

                if (response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Report Sent to Clinical Team", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun syncDataToBackend() {
        if (hashId.isNotEmpty() && currentLogSession.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    val report = GaitReport(hashId, sessionScore, currentLogSession.toList())
                    RetrofitClient.instance.uploadReport(hashId, report)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun scanPatientQr(context: Context?) {
        if (context == null) return
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val scannedValue = barcode.rawValue ?: ""
                val id = if (scannedValue.contains("/")) scannedValue.substringAfterLast("/") else scannedValue
                fetchPatientDetails(id)
            }
    }

    private fun fetchPatientDetails(id: String) {
        viewModelScope.launch {
            try {
                val response = RetrofitClient.instance.getPatient(id)
                if (response.isSuccessful) {
                    val patient = response.body()
                    if (patient != null) {
                        hashId = id
                        patientEmail = patient.email
                        patientName = patient.name
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun calculateScore() {
        val fogCount = currentLogSession.count { it.isFog }
        val totalSamples = currentLogSession.size.coerceAtLeast(1)
        val fogRatio = fogCount.toFloat() / totalSamples
        sessionScore = (10f - (fogRatio * 20f)).coerceIn(0f, 10f)
    }

    fun startCalibration() {
        calibrationFI.clear(); calibrationEI.clear(); isCalibrating = true
        object : CountDownTimer(20000, 100) {
            override fun onTick(m: Long) { calibrationProgress = (20000 - m) / 20000f }
            override fun onFinish() {
                isCalibrating = false; calibrationProgress = 0f
                if (calibrationFI.isNotEmpty()) {
                    val avgFI = calibrationFI.average().toFloat()
                    val stdFI = sqrt(calibrationFI.map { (it - avgFI).pow(2) }.average()).toFloat()
                    thresholdFI = avgFI + (1.5f * stdFI)
                }
            }
        }.start()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val z = event.values[2]
            _linearAccelData.postValue(Triple(event.values[0], event.values[1], z))
            
            sensorBuffer.add(z)
            if (sensorBuffer.size > windowSize) {
                sensorBuffer.removeAt(0)
            }
            
            if (++samplesSinceLastProcess >= stepSize && sensorBuffer.size == windowSize) {
                processWindow(z)
                samplesSinceLastProcess = 0
            }
        }
    }

    private fun processWindow(lastZ: Float) {
        val windowedData = FloatArray(windowSize) { i ->
            (0.5f * (1f - cos(2 * PI.toFloat() * i / (windowSize - 1)))) * sensorBuffer[i]
        }
        
        val power = computePowerSpectrum(windowedData)
        val res = 100f / 256f 
        
        val locoPower = sumPower(power, 0.5f, 3.0f, res)
        val freezePower = sumPower(power, 3.0f, 8.0f, res)
        
        currentFI = if (locoPower > 0) freezePower / locoPower else 0f
        currentEI = freezePower + locoPower
        isFogDetected = currentFI > thresholdFI && currentEI > 0.01f
        
        if (isLogging) {
            val entry = LogEntry(System.currentTimeMillis(), lastZ, currentFI, currentEI, 0f, isFogDetected)
            currentLogSession.add(entry)
            _loggedData.postValue(currentLogSession.takeLast(100))
            calculateScore()
        }
        
        if (isCalibrating) {
            calibrationFI.add(currentFI)
            calibrationEI.add(currentEI)
        }
    }

    private fun computePowerSpectrum(input: FloatArray): FloatArray {
        val (real, imag) = computeFFT(input)
        return FloatArray(real.size) { i -> real[i] * real[i] + imag[i] * imag[i] }
    }

    private fun sumPower(p: FloatArray, low: Float, high: Float, res: Float): Float {
        val start = (low / res).roundToInt().coerceIn(0, p.size - 1)
        val end = (high / res).roundToInt().coerceIn(0, p.size - 1)
        var sum = 0f
        for (i in start until end) sum += p[i]
        return sum
    }

    private fun computeFFT(input: FloatArray): Pair<FloatArray, FloatArray> {
        val n = input.size
        val real = input.copyOf()
        val imag = FloatArray(n)

        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 1
        while (len < n) {
            val step = len shl 1
            val theta = -PI / len
            val wR = cos(theta).toFloat()
            val wI = sin(theta).toFloat()
            
            for (i in 0 until n step step) {
                var uR = 1f
                var uI = 0f
                for (k in 0 until len) {
                    val r = i + k
                    val s = r + len
                    val tr = uR * real[s] - uI * imag[s]
                    val ti = uR * imag[s] + uI * real[s]
                    real[s] = real[r] - tr
                    imag[s] = imag[r] - ti
                    real[r] += tr
                    imag[r] += ti
                    
                    val nextUR = uR * wR - uI * wI
                    uI = uR * wI + uI * wR
                    uR = nextUR
                }
            }
            len = step
        }
        return real to imag
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
