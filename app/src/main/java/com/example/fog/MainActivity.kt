package com.example.fog

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fog.ui.theme.FoGTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import kotlin.math.*

data class GaitReport(
    val hashId: String,
    val finalScore: Float,
    val logs: List<LogEntry>
)

interface GaitApiService {
    @POST("api/")
    suspend fun uploadReport(@Body report: GaitReport): retrofit2.Response<Unit>
}

object RetrofitClient {
    private const val BASE_URL = "https://example.com/"
    val instance: GaitApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
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

class SensorViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val SAMPLING_PERIOD_US = 10000 // 100 Hz
    private val WINDOW_SIZE = 256
    private val STEP_SIZE = 40

    private val _linearAccelData = MutableLiveData(Triple(0f, 0f, 0f))
    val linearAccelData: LiveData<Triple<Float, Float, Float>> = _linearAccelData

    var isLogging by mutableStateOf(false)
    var isCalibrating by mutableStateOf(false)
    var calibrationProgress by mutableStateOf(0f)
    var sessionScore by mutableStateOf(10f)
    var hashId by mutableStateOf("")
    var isSyncing by mutableStateOf(false)

    private val _loggedData = MutableLiveData<List<LogEntry>>(emptyList())
    val loggedData: LiveData<List<LogEntry>> = _loggedData

    private val currentLogSession = Collections.synchronizedList(mutableListOf<LogEntry>())
    private val sensorBuffer = mutableListOf<Float>()
    private var samplesSinceLastProcess = 0

    // Detection Stats
    var currentFI by mutableStateOf(0f)
    var currentEI by mutableStateOf(0f)
    var currentSC by mutableStateOf(0f)
    var isFogDetected by mutableStateOf(false)

    // Thresholds
    private var thresholdFI = 2.0f
    private var thresholdEI = 0.5f
    private val calibrationFI = mutableListOf<Float>()
    private val calibrationEI = mutableListOf<Float>()

    // Cadence History for Algorithm 2
    private val cadenceHistory = mutableListOf<Float>()

    init {
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SAMPLING_PERIOD_US)
        }
    }

    fun startLogging() {
        currentLogSession.clear()
        sensorBuffer.clear()
        cadenceHistory.clear()
        samplesSinceLastProcess = 0
        _loggedData.value = emptyList()
        isLogging = true
    }

    fun stopLogging() {
        isLogging = false
        calculateFinalScore()
        _loggedData.value = currentLogSession.toList()
        
        syncDataToBackend()
    }

    private fun syncDataToBackend() {
        if (hashId.isNotEmpty() && currentLogSession.isNotEmpty()) {
            isSyncing = true
            viewModelScope.launch {
                try {
                    val report = GaitReport(hashId, sessionScore, currentLogSession.toList())
                    val response = RetrofitClient.instance.uploadReport(report)
                    if (response.isSuccessful) {
                        Toast.makeText(getApplication(), "Data Synced Successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(getApplication(), "Sync Failed: ${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(getApplication(), "Sync Error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isSyncing = false
                }
            }
        }
    }

    fun scanPatientQr(context: Context) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(context, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val scannedValue = barcode.rawValue ?: ""
                if (scannedValue.isNotEmpty()) {
                    hashId = scannedValue
                    Toast.makeText(context, "Automatic Login: $hashId", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun calculateFinalScore() {
        val session = currentLogSession
        if (session.isEmpty()) {
            sessionScore = 10f
            return
        }
        val fogCount = session.count { it.isFog }
        val fogPercentage = fogCount.toFloat() / session.size
        sessionScore = (10f - (fogPercentage * 10f)).coerceIn(0f, 10f)
    }

    fun startCalibration() {
        calibrationFI.clear()
        calibrationEI.clear()
        sensorBuffer.clear()
        isCalibrating = true
        
        object : CountDownTimer(20000, 100) {
            override fun onTick(millisUntilFinished: Long) {
                calibrationProgress = (20000 - millisUntilFinished) / 20000f
            }
            override fun onFinish() {
                isCalibrating = false
                calculateThresholds()
            }
        }.start()
    }

    private fun calculateThresholds() {
        if (calibrationFI.isNotEmpty()) {
            thresholdFI = calibrationFI.average().toFloat() + (stdDev(calibrationFI))
            thresholdEI = calibrationEI.average().toFloat() + (stdDev(calibrationEI))
        }
    }

    private fun stdDev(list: List<Float>): Float {
        val avg = list.average()
        return sqrt(list.map { (it - avg).pow(2) }.average()).toFloat()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val z = event.values[2]
            _linearAccelData.postValue(Triple(event.values[0], event.values[1], z))

            sensorBuffer.add(z)
            if (sensorBuffer.size > WINDOW_SIZE) {
                sensorBuffer.removeAt(0)
            }

            samplesSinceLastProcess++
            if (samplesSinceLastProcess >= STEP_SIZE && sensorBuffer.size == WINDOW_SIZE) {
                processWindow()
                samplesSinceLastProcess = 0
            }
        }
    }

    private fun processWindow() {
        // 1. Apply Hamming Window (Crucial for reducing noise/false positives)
        val windowedData = FloatArray(WINDOW_SIZE) { i ->
            val hamming = 0.54f - 0.46f * cos(2 * PI.toFloat() * i / (WINDOW_SIZE - 1))
            sensorBuffer[i] * hamming
        }

        // 2. Compute FFT and Power Spectrum
        val fft = computeFFT(windowedData)
        val powerSpectrum = computePowerSpectrum(fft)
        
        // Resolution: 100Hz / 256 samples = 0.39 Hz per bin
        val binRes = 100f / 256f
        
        // 3. Calculate Power Bands
        val locoPower = sumPower(powerSpectrum, 0.5f, 3.0f, binRes)
        val freezePower = sumPower(powerSpectrum, 3.0f, 8.0f, binRes)
        
        currentFI = if (locoPower > 0) freezePower / locoPower else 0f
        currentEI = freezePower + locoPower
        currentSC = findSecondPeakFrequency(powerSpectrum, binRes)

        if (isCalibrating) {
            calibrationFI.add(currentFI)
            calibrationEI.add(currentEI)
        }

        // 4. Refined Detection Logic (Algorithm 2)
        // Moore-Bächlin remains the primary check
        val isAboveThreshold = (currentFI > thresholdFI) && (currentEI > thresholdEI)

        cadenceHistory.add(currentSC)
        if (cadenceHistory.size > 5) cadenceHistory.removeAt(0) // Longer history for stability
        
        var isShuffling = false
        if (cadenceHistory.size >= 3) {
            val cN = cadenceHistory.last()
            val cPrev = cadenceHistory[cadenceHistory.size - 2]
            
            // Rule A & B refined: FOG usually involves an INCREASE in frequency (shuffling)
            // but the energy (EI) must also be high to avoid trigger on minor jitters.
            val frequencyIncreasing = cN > cPrev && cPrev > 0.5f
            isShuffling = frequencyIncreasing && (currentEI > thresholdEI * 0.8f)
        }

        // Only detect FOG if thresholds are met AND it looks like a high-frequency shuffle
        // This '&&' instead of '||' significantly reduces false positives
        isFogDetected = isAboveThreshold && isShuffling 

        if (isLogging) {
            currentLogSession.add(LogEntry(
                System.currentTimeMillis(), 
                sensorBuffer.last(), 
                currentFI, 
                currentEI, 
                currentSC, 
                isFogDetected
            ))
        }
    }

    private fun sumPower(power: FloatArray, lowF: Float, highF: Float, res: Float): Float {
        val startBin = (lowF / res).toInt().coerceIn(0, power.size - 1)
        val endBin = (highF / res).toInt().coerceIn(0, power.size - 1)
        return power.sliceArray(startBin..endBin).sum()
    }

    private fun findSecondPeakFrequency(power: FloatArray, res: Float): Float {
        // Simple peak finding logic for demonstration
        var maxVal = -1f
        var maxBin = 0
        for (i in 2 until power.size / 2) {
            if (power[i] > maxVal) {
                maxVal = power[i]
                maxBin = i
            }
        }
        return maxBin * res
    }

    // Basic Iterative Cooley-Tukey FFT
    private fun computeFFT(input: FloatArray): Pair<FloatArray, FloatArray> {
        val n = input.size
        val real = input.copyOf()
        val imag = FloatArray(n) { 0f }
        
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m; m = m shr 1
            }
            j += m
        }

        var length = 1
        while (length < n) {
            val step = length shl 1
            val theta = -PI / length
            val wR = cos(theta).toFloat()
            val wI = sin(theta).toFloat()
            for (i in 0 until n step step) {
                var uR = 1f
                var uI = 0f
                for (k in 0 until length) {
                    val r = i + k
                    val s = r + length
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
            length = step
        }
        return real to imag
    }

    private fun computePowerSpectrum(fft: Pair<FloatArray, FloatArray>): FloatArray {
        val (real, imag) = fft
        return FloatArray(real.size) { i -> real[i] * real[i] + imag[i] * imag[i] }
    }

    fun getCsvFile(context: Context): File? {
        val data = _loggedData.value ?: return null
        return try {
            val file = File(context.cacheDir, "fog_analysis_data.csv")
            FileOutputStream(file).use { out ->
                out.write("Timestamp,VerticalAccel,FI,EI,SC,isFOG\n".toByteArray())
                data.forEach { out.write("${it.timestamp},${it.verticalAccel},${it.fi},${it.ei},${it.sc},${it.isFog}\n".toByteArray()) }
            }
            file
        } catch (e: Exception) { null }
    }

    fun clearLogs() { currentLogSession.clear(); _loggedData.value = emptyList() }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onCleared() { super.onCleared(); sensorManager.unregisterListener(this) }
}

@Composable
fun SensorScreen(viewModel: SensorViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loggedEntries by viewModel.loggedData.observeAsState(emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Header
        Text(
            "Gait Analysis Pro",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Freezing of Gait Detection System",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(Modifier.height(24.dp))

        // Step 1: Login Section
        LoginCard(
            hashId = viewModel.hashId,
            onScanClick = { viewModel.scanPatientQr(context) }
        )

        Spacer(Modifier.height(16.dp))

        if (viewModel.hashId.isNotEmpty()) {
            // Step 2 & 3: Perform Test
            ControlPanel(
                isLogging = viewModel.isLogging,
                isCalibrating = viewModel.isCalibrating,
                calibrationProgress = viewModel.calibrationProgress,
                onStartCalibration = { viewModel.startCalibration() },
                onStartLogging = { viewModel.startLogging() },
                onStopLogging = { viewModel.stopLogging() },
                canStartLogging = true
            )

            Spacer(Modifier.height(20.dp))

            // Live Monitoring (Active during test)
            if (viewModel.isLogging || viewModel.isCalibrating) {
                LiveMonitoringCard(
                    isFogDetected = viewModel.isFogDetected,
                    fi = viewModel.currentFI,
                    ei = viewModel.currentEI,
                    sc = viewModel.currentSC
                )
                Spacer(Modifier.height(20.dp))
            }

            // Step 4: Result & Background Sync Status
            if (loggedEntries.isNotEmpty() && !viewModel.isLogging) {
                if (viewModel.isSyncing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Syncing to server...", style = MaterialTheme.typography.labelSmall)
                    }
                }
                ScoreCard(score = viewModel.sessionScore)
                Spacer(Modifier.height(16.dp))
                ActionButtons(
                    onClear = { viewModel.clearLogs() },
                    onExport = {
                        val file = viewModel.getCsvFile(context)
                        if (file != null) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_STREAM, uri)
                                type = "text/csv"
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share FOG Report"))
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))
                LogSection(loggedEntries)
            }
        } else {
            EmptyState()
        }
    }
}

@Composable
fun LoginCard(hashId: String, onScanClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hashId.isEmpty())
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (hashId.isEmpty()) Icons.Default.AccountCircle else Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = if (hashId.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (hashId.isEmpty()) "Patient Not Identified" else "Active Patient Session",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = hashId.ifEmpty { "Awaiting QR Scan..." },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (hashId.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = onScanClick,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan")
            }
        }
    }
}

@Composable
fun ControlPanel(
    isLogging: Boolean,
    isCalibrating: Boolean,
    calibrationProgress: Float,
    onStartCalibration: () -> Unit,
    onStartLogging: () -> Unit,
    onStopLogging: () -> Unit,
    canStartLogging: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Calibration Button
            OutlinedButton(
                onClick = onStartCalibration,
                enabled = !isLogging && !isCalibrating,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Calibrate")
            }

            // Start/Stop Button
            Button(
                onClick = if (isLogging) onStopLogging else onStartLogging,
                enabled = canStartLogging && !isCalibrating,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLogging) Color(0xFFD32F2F) else Color(0xFF388E3C)
                ),
                contentPadding = PaddingValues(12.dp)
            ) {
                Icon(
                    imageVector = if (isLogging) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isLogging) "Stop Session" else "Start Logging")
            }
        }

        if (isCalibrating) {
            Spacer(Modifier.height(12.dp))
            Column {
                Text(
                    "Establishing baseline... ${(calibrationProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                LinearProgressIndicator(
                    progress = { calibrationProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
fun LiveMonitoringCard(isFogDetected: Boolean, fi: Float, ei: Float, sc: Float) {
    val statusColor = if (isFogDetected) Color(0xFFD32F2F) else Color(0xFF388E3C)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                brush = Brush.verticalGradient(listOf(statusColor.copy(alpha = 0.5f), Color.Transparent)),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "SYSTEM STATUS",
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.outline
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = if (isFogDetected) "FREEZE DETECTED" else "NORMAL GAIT",
                style = MaterialTheme.typography.headlineMedium,
                color = statusColor,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(Modifier.alpha(0.1f))
            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                MetricItem("FI", "%.2f".format(fi), if (fi > 2.0) Color.Red else statusColor)
                MetricItem("EI", "%.2f".format(ei), statusColor)
                MetricItem("CADENCE", "%.1f".format(sc), statusColor)
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun ScoreCard(score: Float) {
    val color = when {
        score >= 8f -> Color(0xFF388E3C)
        score >= 5f -> Color(0xFFFBC02D)
        else -> Color(0xFFD32F2F)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(color, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "%.0f".format(score),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Stability Score", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = when {
                        score >= 8f -> "Excellent gait stability maintained."
                        score >= 5f -> "Moderate freezing detected."
                        else -> "Significant gait impairment observed."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun ActionButtons(onClear: () -> Unit, onExport: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onClear, Modifier.weight(1f)) { Text("Clear Data") }
        Button(onClick = onExport, Modifier.weight(1f)) { Text("Export Report") }
    }
}

@Composable
fun LogSection(entries: List<LogEntry>) {
    Text(
        "Recent Logs",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        LogTable(entries, Modifier.fillMaxSize().padding(8.dp))
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.BarChart,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No Session Data",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            "Start a session to see analysis",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
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
                            if (entry.isFog) Color.Red else Color(0xFF388E3C),
                            RoundedCornerShape(4.dp)
                        )
                )
                Spacer(Modifier.width(12.dp))
                Text("FI: %.2f".format(entry.fi), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (entry.isFog) "FREEZE" else "WALKING",
                    color = if (entry.isFog) Color.Red else Color(0xFF388E3C),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            HorizontalDivider(modifier = Modifier.alpha(0.05f))
        }
    }
}

@Composable
fun InfoChip(text: String, modifier: Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FoGTheme {
            val viewModel: SensorViewModel = viewModel()
            Scaffold(Modifier.fillMaxSize()) { innerPadding ->
                SensorScreen(viewModel, Modifier.padding(innerPadding))
            }
        } }
    }
}
