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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fog.ui.theme.FoGTheme
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import kotlin.math.*

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
        _loggedData.value = currentLogSession.toList()
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

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("FOG Detection Algorithm 2", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(Modifier.height(8.dp))
        
        // Calibration
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("Calibration", fontWeight = FontWeight.Bold)
                if (viewModel.isCalibrating) {
                    LinearProgressIndicator(progress = { viewModel.calibrationProgress }, Modifier.fillMaxWidth())
                } else {
                    Button(onClick = { viewModel.startCalibration() }) { Text("Start 20s Baseline") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // FOG STATUS
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (viewModel.isFogDetected) Color(0xFFFFEBEE) else Color(0xFFE8F5E9))
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CURRENT GAIT STATUS", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (viewModel.isFogDetected) "FREEZE DETECTED" else "NORMAL WALKING",
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (viewModel.isFogDetected) Color.Red else Color(0xFF2E7D32),
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoChip("FI: %.2f".format(viewModel.currentFI), Modifier.weight(1f))
            InfoChip("EI: %.2f".format(viewModel.currentEI), Modifier.weight(1f))
            InfoChip("Cadence: %.1f".format(viewModel.currentSC), Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.startLogging() }, enabled = !viewModel.isLogging, modifier = Modifier.weight(1f)) { Text("Log Start") }
            Button(onClick = { viewModel.stopLogging() }, enabled = viewModel.isLogging, modifier = Modifier.weight(1f)) { Text("Stop") }
        }

        Spacer(Modifier.height(8.dp))
        
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.clearLogs() }, Modifier.weight(1f)) { Text("Clear") }
            OutlinedButton(onClick = {
                val file = viewModel.getCsvFile(context)
                if (file != null) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "text/csv"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share FOG Report"))
                }
            }, Modifier.weight(1f)) { Text("Export") }
        }

        if (loggedEntries.isNotEmpty() && !viewModel.isLogging) {
            Spacer(Modifier.height(16.dp))
            LogTable(loggedEntries, Modifier.weight(1f))
        }
    }
}

@Composable
fun InfoChip(text: String, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LogTable(entries: List<LogEntry>, modifier: Modifier) {
    LazyColumn(modifier) {
        items(entries.takeLast(100)) { entry ->
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                Text("%.2f".format(entry.fi), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("%.2f".format(entry.ei), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(if (entry.isFog) "FOG" else "WALK", Modifier.weight(1f), color = if (entry.isFog) Color.Red else Color.Unspecified)
            }
            HorizontalDivider(
                modifier = Modifier.alpha(0.2f)
            )
        }
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
