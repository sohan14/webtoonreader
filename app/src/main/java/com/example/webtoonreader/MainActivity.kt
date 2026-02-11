package com.example.webtoonreader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "WebtoonReader"

object AppLogStore {
    private const val PREF_NAME = "webtoon_reader_logs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LINES = 400

    fun installCrashHandler(context: Context) {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            append(
                context,
                "CRASH",
                "${throwable.message}\n${throwable.stackTraceToStringSafe()}"
            )
            existing?.uncaughtException(thread, throwable)
        }
    }

    fun append(context: Context, level: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "[$timestamp][$level] $message"
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_LOGS, "").orEmpty()
        val merged = (current.lines().filter { it.isNotBlank() } + entry).takeLast(MAX_LINES)
        prefs.edit().putString(KEY_LOGS, merged.joinToString("\n")).apply()
    }

    fun getAll(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOGS, "No logs yet.")
            .orEmpty()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().remove(KEY_LOGS).apply()
    }

    private fun Throwable.stackTraceToStringSafe(): String {
        return try {
            val writer = StringWriter()
            printStackTrace(PrintWriter(writer))
            writer.toString()
        } catch (_: Exception) {
            toString()
        }
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<ReaderViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLogStore.installCrashHandler(applicationContext)
        AppLogStore.append(applicationContext, "INFO", "App launched")
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var tts by remember { mutableStateOf<TextToSpeech?>(null) }
                var isSpeaking by remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    viewModel.refreshLogs(context)
                    onDispose { }
                }

                DisposableEffect(Unit) {
                    var speakerRef: TextToSpeech? = null
                    val speaker = TextToSpeech(context) { status ->
                        val current = speakerRef ?: return@TextToSpeech
                        if (status == TextToSpeech.SUCCESS) {
                            val femaleVoice = current.voices?.firstOrNull {
                                val name = it.name.lowercase(Locale.getDefault())
                                name.contains("female") || name.contains("woman") || name.contains("girl")
                            }
                            if (femaleVoice != null) {
                                current.voice = femaleVoice
                            } else {
                                current.language = Locale.US
                            }
                            current.setSpeechRate(0.9f)
                            current.setPitch(1.08f)
                            AppLogStore.append(context, "INFO", "TTS initialized")
                        } else {
                            AppLogStore.append(context, "ERROR", "TTS init failed with status $status")
                        }
                    }
                    speakerRef = speaker
                    speaker.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isSpeaking = true
                        }

                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                        }

                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            Log.e(TAG, "TTS error for $utteranceId")
                            AppLogStore.append(context, "ERROR", "TTS error for $utteranceId")
                        }
                    })
                    tts = speaker
                    onDispose {
                        speaker.stop()
                        speaker.shutdown()
                    }
                }

                ReaderScreen(
                    state = viewModel.state,
                    onPickFiles = { uris -> viewModel.loadUris(contentResolver, uris, context) },
                    onReadAll = {
                        scope.launch {
                            val speaker = tts ?: return@launch
                            viewModel.state.pages.forEachIndexed { index, page ->
                                val text = page.extractedText.ifBlank { "No text found on page ${index + 1}" }
                                speaker.speak("Page ${index + 1}. $text", TextToSpeech.QUEUE_ADD, null, "page_$index")
                            }
                            AppLogStore.append(context, "INFO", "Started reading ${viewModel.state.pages.size} pages")
                            viewModel.refreshLogs(context)
                        }
                    },
                    onStopReading = {
                        tts?.stop()
                        isSpeaking = false
                        AppLogStore.append(context, "INFO", "Reading stopped by user")
                        viewModel.refreshLogs(context)
                    },
                    onShareLogs = {
                        val logs = viewModel.state.appLogs
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "WebtoonReader Logs")
                            putExtra(Intent.EXTRA_TEXT, logs)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share logs"))
                    },
                    onClearLogs = {
                        AppLogStore.clear(context)
                        AppLogStore.append(context, "INFO", "Logs cleared")
                        viewModel.refreshLogs(context)
                    },
                    isSpeaking = isSpeaking
                )
            }
        }
    }
}

data class PagePreview(
    val bitmap: Bitmap,
    val extractedText: String,
    val sourceName: String
)

data class ReaderUiState(
    val pages: List<PagePreview> = emptyList(),
    val loading: Boolean = false,
    val errorLog: List<String> = emptyList(),
    val appLogs: String = "No logs yet."
)

class ReaderViewModel : ViewModel() {
    var state by mutableStateOf(ReaderUiState())
        private set

    fun refreshLogs(context: Context) {
        state = state.copy(appLogs = AppLogStore.getAll(context))
    }

    fun loadUris(resolver: android.content.ContentResolver, uris: List<Uri>, context: Context) {
        viewModelScope.launch {
            AppLogStore.append(context, "INFO", "Loading ${uris.size} selected document(s)")
            state = state.copy(loading = true, errorLog = emptyList(), pages = emptyList(), appLogs = AppLogStore.getAll(context))
            val pages = mutableListOf<PagePreview>()
            val errors = mutableListOf<String>()

            uris.forEach { uri ->
                try {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    AppLogStore.append(context, "INFO", "Temporary URI permission used for $uri")
                }

                try {
                    val mimeType = resolver.getType(uri).orEmpty()
                    if (mimeType.contains("pdf")) {
                        pages += readPdf(resolver, uri, context)
                    } else {
                        pages += readImage(resolver, uri, context)
                    }
                } catch (ex: Exception) {
                    val msg = "Failed to open $uri: ${ex.message}"
                    Log.e(TAG, msg, ex)
                    AppLogStore.append(context, "ERROR", "$msg\n${ex.stackTraceToString()}")
                    errors += msg
                }
            }

            AppLogStore.append(context, "INFO", "Completed load: ${pages.size} page(s), ${errors.size} error(s)")
            state = state.copy(loading = false, pages = pages, errorLog = errors, appLogs = AppLogStore.getAll(context))
        }
    }

    private suspend fun readImage(
        resolver: android.content.ContentResolver,
        uri: Uri,
        context: Context
    ): List<PagePreview> = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.isMutableRequired = false
        }
        val text = extractText(bitmap, context)
        AppLogStore.append(context, "INFO", "Image parsed: ${uri.lastPathSegment}")
        listOf(PagePreview(bitmap = bitmap, extractedText = text, sourceName = uri.lastPathSegment.orEmpty()))
    }

    private suspend fun readPdf(
        resolver: android.content.ContentResolver,
        uri: Uri,
        context: Context
    ): List<PagePreview> = withContext(Dispatchers.IO) {
        val previews = mutableListOf<PagePreview>()
        val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r")
            ?: error("Cannot open PDF")
        PdfRenderer(pfd).use { renderer ->
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val width = page.width * 2
                    val height = page.height * 2
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val text = extractText(bitmap, context)
                    previews += PagePreview(
                        bitmap = bitmap,
                        extractedText = text,
                        sourceName = "${uri.lastPathSegment} - page ${i + 1}"
                    )
                }
            }
        }
        pfd.close()
        AppLogStore.append(context, "INFO", "PDF parsed: ${uri.lastPathSegment}, pages=${previews.size}")
        previews
    }

    private suspend fun extractText(bitmap: Bitmap, context: Context): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                recognizer.close()
                cont.resume(result.text.ifBlank { "No text detected." })
            }
            .addOnFailureListener { e ->
                recognizer.close()
                Log.e(TAG, "OCR failed", e)
                AppLogStore.append(context, "ERROR", "OCR failed: ${e.message}")
                cont.resume("No text detected.")
            }
    }
}

@Composable
private fun ReaderScreen(
    state: ReaderUiState,
    onPickFiles: (List<Uri>) -> Unit,
    onReadAll: () -> Unit,
    onStopReading: () -> Unit,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit,
    isSpeaking: Boolean
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            onPickFiles(uris)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }) {
                    Text("Pick PDF / Images")
                }
                Button(onClick = onReadAll, enabled = state.pages.isNotEmpty() && !state.loading) {
                    Text("Read Pages")
                }
                Button(onClick = onStopReading, enabled = isSpeaking) {
                    Text("Stop")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onShareLogs) { Text("Share Logs") }
                Button(onClick = onClearLogs) { Text("Clear Logs") }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (state.loading) {
                Text("Loading pages and extracting text...")
            }
            if (state.errorLog.isNotEmpty()) {
                Text("Processing Errors:", fontWeight = FontWeight.Bold)
                state.errorLog.forEach { Text("• $it") }
            }

            Text("App / Crash Logs:", fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = state.appLogs,
                    modifier = Modifier.padding(12.dp)
                )
            }

            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(state.pages) { index, page ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Page ${index + 1} - ${page.sourceName}", fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Image(
                                bitmap = page.bitmap.asImageBitmap(),
                                contentDescription = page.sourceName,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(page.extractedText)
                        }
                    }
                }
            }
        }
    }
}
