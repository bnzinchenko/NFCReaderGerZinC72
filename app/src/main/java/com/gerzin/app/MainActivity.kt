package com.gerzin.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.rscja.barcode.BarcodeDecoder
import com.rscja.barcode.BarcodeFactory
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.*

enum class WorkMode { SCANNER, LIST }
enum class ScannerState { IDLE, SCANNING, WAITING_TAG, PROCESSING }

class MainActivity : ComponentActivity() {

    private val barcodeDecoder: BarcodeDecoder by lazy {
        BarcodeFactory.getInstance().barcodeDecoder
    }

    private val scanFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var nfcAdapter: NfcAdapter? = null

    private var csvQueue: MutableList<String> = mutableListOf()
    private var workMode by mutableStateOf(WorkMode.SCANNER)
    private var scannerState by mutableStateOf(ScannerState.IDLE)
    private var lastScanData by mutableStateOf("")
    private var statusText by mutableStateOf("Готов")
    private var pendingCsvIndex by mutableStateOf(0)
    private var showSuccessIndicator by mutableStateOf(false)
    private var isListModeActive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        barcodeDecoder.open(this)
        barcodeDecoder.setDecodeCallback { entity ->
            if (entity.resultCode == BarcodeDecoder.DECODE_SUCCESS) {
                lastScanData = entity.barcodeData
                barcodeDecoder.stopScan()
                scannerState = ScannerState.WAITING_TAG
                statusText = "Поднесите метку"
                scanFlow.tryEmit(entity.barcodeData)
            }
        }

        setContent {
            AppUI(
                scanFlow = scanFlow,
                onStartScan = { startScanning() },
                onStopScan = { stopScanning() },
                csvQueue = csvQueue,
                onPickCsv = { pickCsv() },
                workMode = workMode,
                onToggleMode = { workMode = if (workMode == WorkMode.SCANNER) WorkMode.LIST else WorkMode.SCANNER },
                statusText = statusText,
                lastScan = lastScanData,
                pendingCsvIndex = pendingCsvIndex,
                scannerState = scannerState,
                showSuccessIndicator = showSuccessIndicator,
                isListModeActive = isListModeActive,
                onStartListMode = { startListMode() },
                onStopListMode = { stopListMode() }
            )
        }
    }
    override fun onResume() {
        super.onResume()
        enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    private fun enableReaderMode() {
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> handleTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NFC_BARCODE,
            Bundle()
        )
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    private fun handleTag(tag: Tag) {
        val dataToWrite: String? = when (workMode) {
            WorkMode.SCANNER -> if (scannerState == ScannerState.WAITING_TAG) lastScanData else null
            WorkMode.LIST -> if (isListModeActive && csvQueue.isNotEmpty()) csvQueue.getOrNull(pendingCsvIndex) else null
        }
        
        if (dataToWrite.isNullOrEmpty()) return
        
        scannerState = ScannerState.PROCESSING
        statusText = "Записываем..."

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            writeNdef(ndef, dataToWrite)
            return
        }
        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            formatAndWrite(formatable, dataToWrite)
            return
        }
        statusText = "Ошибка: метка не поддерживает NDEF"
    }

    private fun buildTextRecord(text: String, locale: Locale = Locale("ru")): NdefRecord {
        val langBytes = locale.language.toByteArray(Charsets.US_ASCII)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + langBytes.size + textBytes.size)
        payload[0] = langBytes.size.toByte()
        System.arraycopy(langBytes, 0, payload, 1, langBytes.size)
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.size, textBytes.size)
        return NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), payload)
    }

    private fun writeNdef(ndef: Ndef, text: String) {
        try {
            ndef.connect()
            val record = buildTextRecord(text)
            val message = NdefMessage(arrayOf(record))
            val bytes = message.toByteArray()
            if (ndef.maxSize > 0 && bytes.size > ndef.maxSize) {
                statusText = "Данные слишком длинные для этой метки"
                return
            }
            val hadMessage = (ndef.ndefMessage != null)
            ndef.writeNdefMessage(message)
            onWriteSuccess()
        } catch (e: Exception) {
            statusText = "Ошибка записи"
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    private fun formatAndWrite(formatable: NdefFormatable, text: String) {
        try {
            val record = buildTextRecord(text)
            val message = NdefMessage(arrayOf(record))
            formatable.connect()
            formatable.format(message)
            onWriteSuccess()
        } catch (e: Exception) {
            statusText = "Ошибка записи"
        } finally {
            try { formatable.close() } catch (_: Exception) {}
        }
    }

    private fun onWriteSuccess() {
        statusText = "Записано ✓"
        showSuccessIndicator = true
        
        // Hide success indicator after 1 second
        lifecycleScope.launch {
            delay(1000)
            showSuccessIndicator = false
        }
        
        when (workMode) {
            WorkMode.SCANNER -> {
                // Restart scanning after 2 seconds
                lifecycleScope.launch {
                    delay(2000)
                    if (scannerState == ScannerState.PROCESSING) {
                        startScanning()
                    }
                }
            }
            WorkMode.LIST -> {
                if (pendingCsvIndex < csvQueue.size - 1) {
                    pendingCsvIndex++
                    statusText = "Поднесите метку для записи: ${csvQueue[pendingCsvIndex]}"
                } else {
                    statusText = "Список завершен"
                    isListModeActive = false
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeDecoder.close()
    }

    private fun startScanning() {
        scannerState = ScannerState.SCANNING
        statusText = "Сканирование..."
        barcodeDecoder.startScan()
    }

    private fun stopScanning() {
        scannerState = ScannerState.IDLE
        statusText = "Готов"
        barcodeDecoder.stopScan()
    }

    private fun startListMode() {
        if (csvQueue.isEmpty()) {
            statusText = "CSV файл не выбран"
            return
        }
        isListModeActive = true
        pendingCsvIndex = 0
        statusText = "Поднесите метку для записи: ${csvQueue[pendingCsvIndex]}"
    }

    private fun stopListMode() {
        isListModeActive = false
        statusText = "Готов"
    }

    // CSV picker
    private val pickCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            csvQueue = readCsvFirstColumn(uri).toMutableList()
            pendingCsvIndex = 0
            statusText = "Готов"
        }
    }

    private fun pickCsv() {
        pickCsvLauncher.launch(arrayOf("text/*", "application/*"))
    }

    private fun readCsvFirstColumn(uri: Uri): List<String> {
        val result = mutableListOf<String>()
        contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charset.forName("UTF-8"))).use { reader ->
                reader.lineSequence().forEach { line ->
                    val cell = line.split(';', ',').firstOrNull()?.trim()
                    if (!cell.isNullOrEmpty()) {
                        result.add(cell)
                    }
                }
            }
        }
        return result
    }
}

@Composable
private fun AppUI(
    scanFlow: MutableSharedFlow<String>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    csvQueue: List<String>,
    onPickCsv: () -> Unit,
    workMode: WorkMode,
    onToggleMode: () -> Unit,
    statusText: String,
    lastScan: String,
    pendingCsvIndex: Int,
    scannerState: ScannerState,
    showSuccessIndicator: Boolean,
    isListModeActive: Boolean,
    onStartListMode: () -> Unit,
    onStopListMode: () -> Unit
) {
    var isFlipped by remember { mutableStateOf(false) }

    // Keep the collector for future UI reactions if needed
    LaunchedEffect(Unit) {
        scanFlow.collectLatest { }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { rotationZ = if (isFlipped) 180f else 0f }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { isFlipped = !isFlipped }) { Text(text = "Перевернуть экран") }
                FilledTonalButton(onClick = onToggleMode) {
                    Text(if (workMode == WorkMode.SCANNER) "Работа со сканером" else "Работа со списком")
                }
            }

            // Success indicator
            if (showSuccessIndicator) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color.Green, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Успешно записано!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Green
                    )
                }
            }

            // Data display
            when (workMode) {
                WorkMode.SCANNER -> {
                    Text(
                        text = if (lastScan.isEmpty()) "Нет данных" else lastScan,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                WorkMode.LIST -> {
                    if (csvQueue.isNotEmpty()) {
                        Text(
                            text = csvQueue.getOrNull(pendingCsvIndex) ?: "",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${pendingCsvIndex + 1} из ${csvQueue.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Text(text = statusText, style = MaterialTheme.typography.bodyLarge)

            // Control buttons
            when (workMode) {
                WorkMode.SCANNER -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onStartScan,
                            enabled = scannerState != ScannerState.SCANNING
                        ) { 
                            Text("Сканировать") 
                        }
                        OutlinedButton(
                            onClick = onStopScan,
                            enabled = scannerState != ScannerState.IDLE
                        ) { 
                            Text("Стоп") 
                        }
                    }
                }
                WorkMode.LIST -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onPickCsv) { Text("Выбрать CSV") }
                        if (!isListModeActive) {
                            Button(
                                onClick = onStartListMode,
                                enabled = csvQueue.isNotEmpty()
                            ) { 
                                Text("Начать") 
                            }
                        } else {
                            OutlinedButton(onClick = onStopListMode) { 
                                Text("Стоп") 
                            }
                        }
                    }
                }
            }
        }
    }
}

