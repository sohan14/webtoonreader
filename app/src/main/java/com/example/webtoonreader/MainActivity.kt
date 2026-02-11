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
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

private const val MAX_BITMAP_WIDTH = 1440
private const val MAX_PANEL_HEIGHT = 2200
private const val MAX_PANEL_PIXELS = 2_200_000

object AppLogStore {
    private const val PREF_NAME = "webtoon_reader_logs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LINES = 400

    fun installCrashHandler(context: Context) {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            append(context, "CRASH", "${throwable.message}\n${throwable.stackTraceToStringSafe()}")
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
                var tts by remember { mutableStateOf<TextToSpeech?>(null) }
                var isSpeaking by remember { mutableStateOf(false) }
                var readerMode by remember { mutableStateOf(false) }
                var currentReadingIndex by remember { mutableStateOf(0) }
                var selectedVoiceName by remember { mutableStateOf<String?>(null) }
                var selectedEmotion by remember { mutableStateOf(emotionProfiles.first()) }

                fun startReading(fromIndex: Int = 0) {
                    val speaker = tts
                    if (speaker == null) {
                        AppLogStore.append(context, "ERROR", "Read requested but TTS engine is null")
                        return
                    }
                    if (viewModel.state.pages.isEmpty()) {
                        AppLogStore.append(context, "ERROR", "Read requested but there are no pages loaded")
                        return
                    }
                    speaker.voices
                        ?.firstOrNull { it.name == selectedVoiceName }
                        ?.let { speaker.voice = it }
                    speaker.setSpeechRate(selectedEmotion.speechRate)
                    speaker.setPitch(selectedEmotion.pitch)
                    speaker.stop()
                    viewModel.state.pages.drop(fromIndex).forEachIndexed { idx, page ->
                        val absoluteIndex = fromIndex + idx
                        val text = page.extractedText
                            .normalizeForSpeech()
                            .ifBlank { "No text found." }
                        val utteranceId = "page_$absoluteIndex"
                        speaker.speak(
                            text,
                            if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                            null,
                            utteranceId
                        )
                        AppLogStore.append(context, "INFO", "Queued TTS $utteranceId (textLength=${text.length})")
                    }
                    isSpeaking = true
                    AppLogStore.append(context, "INFO", "Started reading from page ${fromIndex + 1}")
                    viewModel.refreshLogs(context)
                }

                DisposableEffect(Unit) {
                    viewModel.refreshLogs(context)
                    onDispose { }
                }

                DisposableEffect(Unit) {
                    var speakerRef: TextToSpeech? = null
                    val speaker = TextToSpeech(context) { status ->
                        val current = speakerRef ?: return@TextToSpeech
                        if (status == TextToSpeech.SUCCESS) {
                            val femaleVoices = current.voices
                                ?.filter { it.isFemaleLikeVoice() }
                                ?.sortedBy { it.name }
                                .orEmpty()
                            if (femaleVoices.isNotEmpty()) {
                                current.voice = femaleVoices.first()
                                selectedVoiceName = femaleVoices.first().name
                            } else {
                                current.language = Locale.US
                            }
                            current.setSpeechRate(selectedEmotion.speechRate)
                            current.setPitch(selectedEmotion.pitch)
                            AppLogStore.append(context, "INFO", "TTS initialized")
                        } else {
                            AppLogStore.append(context, "ERROR", "TTS init failed with status $status")
                        }
                    }
                    speakerRef = speaker
                    speaker.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isSpeaking = true
                            val index = utteranceId?.removePrefix("page_")?.toIntOrNull()
                            if (index != null) currentReadingIndex = index
                        }

                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                            AppLogStore.append(context, "INFO", "TTS finished for $utteranceId")
                        }

                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            AppLogStore.append(context, "ERROR", "TTS error for $utteranceId")
                        }
                    })
                    tts = speaker
                    onDispose {
                        speaker.stop()
                        speaker.shutdown()
                    }
                }

                if (!readerMode) {
                    HomeScreen(
                        state = viewModel.state,
                        selectedVoiceName = selectedVoiceName,
                        selectedEmotion = selectedEmotion,
                        availableFemaleVoiceNames = tts?.voices
                            ?.filter { it.isFemaleLikeVoice() }
                            ?.map { it.name }
                            ?.sorted()
                            .orEmpty(),
                        onPickFiles = { uris -> viewModel.loadUris(contentResolver, uris, context) },
                        onSelectVoice = { selectedVoiceName = it },
                        onSelectEmotion = { selectedEmotion = it },
                        onReadPages = {
                            readerMode = true
                            currentReadingIndex = 0
                            startReading(0)
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
                        }
                    )
                } else {
                    ReaderModeScreen(
                        state = viewModel.state,
                        currentReadingIndex = currentReadingIndex,
                        isSpeaking = isSpeaking,
                        onBack = {
                            tts?.stop()
                            isSpeaking = false
                            readerMode = false
                            AppLogStore.append(context, "INFO", "Returned to upload/log screen")
                            viewModel.refreshLogs(context)
                        },
                        onPlayPause = {
                            if (isSpeaking) {
                                tts?.stop()
                                isSpeaking = false
                                AppLogStore.append(context, "INFO", "Reading paused at page ${currentReadingIndex + 1}")
                            } else {
                                startReading(currentReadingIndex)
                            }
                            viewModel.refreshLogs(context)
                        }
                    )
                }
            }
        }
    }
}

data class PagePreview(
    val bitmap: Bitmap,
    val extractedText: String,
    val sourceName: String
)

data class EmotionProfile(
    val label: String,
    val speechRate: Float,
    val pitch: Float
)

private val emotionProfiles = listOf(
    EmotionProfile("Natural", speechRate = 0.92f, pitch = 1.02f),
    EmotionProfile("Calm", speechRate = 0.86f, pitch = 0.96f),
    EmotionProfile("Cheerful", speechRate = 0.98f, pitch = 1.12f)
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
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.isMutableRequired = false
            val size = info.size
            if (size.width > MAX_BITMAP_WIDTH) {
                val scale = MAX_BITMAP_WIDTH.toFloat() / size.width.toFloat()
                decoder.setTargetSize(
                    (size.width * scale).toInt().coerceAtLeast(1),
                    (size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }

        val panels = splitBitmapIntoPanels(bitmap, context, "image:${uri.lastPathSegment}")
        val previews = panels.mapIndexed { idx, panelBitmap ->
            val text = extractText(panelBitmap, context)
            PagePreview(
                bitmap = panelBitmap,
                extractedText = text,
                sourceName = "${uri.lastPathSegment} - panel ${idx + 1}/${panels.size}"
            )
        }
        AppLogStore.append(context, "INFO", "Image parsed: ${uri.lastPathSegment}, panels=${previews.size}")
        previews
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
                    val scaleForWidth = MAX_BITMAP_WIDTH.toFloat() / page.width.toFloat()
                    val scaleForPixels = kotlin.math.sqrt(MAX_PANEL_PIXELS.toDouble() / (page.width.toDouble() * page.height.toDouble())).toFloat()
                    val renderScale = minOf(1f, scaleForWidth, scaleForPixels)

                    val width = (page.width * renderScale).toInt().coerceAtLeast(1)
                    val height = (page.height * renderScale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val safeBitmap = resizeBitmapIfNeeded(bitmap, context, "pdf:${uri.lastPathSegment}:page${i + 1}")
                    val text = extractText(safeBitmap, context)
                    previews += PagePreview(
                        bitmap = safeBitmap,
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

    private fun splitBitmapIntoPanels(bitmap: Bitmap, context: Context, sourceLabel: String): List<Bitmap> {
        val panels = mutableListOf<Bitmap>()
        var offsetY = 0
        while (offsetY < bitmap.height) {
            val chunkHeight = minOf(MAX_PANEL_HEIGHT, bitmap.height - offsetY)
            val panel = Bitmap.createBitmap(bitmap, 0, offsetY, bitmap.width, chunkHeight)
            val safePanel = resizeBitmapIfNeeded(panel, context, "$sourceLabel:y=$offsetY")
            panels += safePanel
            offsetY += chunkHeight
        }
        if (panels.size > 1) {
            AppLogStore.append(context, "INFO", "Split $sourceLabel into ${panels.size} panel(s)")
        }
        return panels
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap, context: Context, sourceLabel: String): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val widthScale = MAX_BITMAP_WIDTH.toFloat() / width.toFloat()
        val pixelScale = kotlin.math.sqrt(MAX_PANEL_PIXELS.toDouble() / (width.toDouble() * height.toDouble())).toFloat()
        val scale = minOf(1f, widthScale, pixelScale)

        if (scale >= 1f) return bitmap

        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled != bitmap) bitmap.recycle()
        AppLogStore.append(context, "INFO", "Bitmap downscaled for $sourceLabel from ${width}x${height} to ${targetWidth}x${targetHeight}")
        return scaled
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
                AppLogStore.append(context, "ERROR", "OCR failed: ${e.message}")
                cont.resume("No text detected.")
            }
    }
}

@Composable
private fun HomeScreen(
    state: ReaderUiState,
    selectedVoiceName: String?,
    selectedEmotion: EmotionProfile,
    availableFemaleVoiceNames: List<String>,
    onPickFiles: (List<Uri>) -> Unit,
    onSelectVoice: (String) -> Unit,
    onSelectEmotion: (EmotionProfile) -> Unit,
    onReadPages: () -> Unit,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) onPickFiles(uris)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }) {
                    Text("Pick PDF / Images")
                }
                Button(onClick = onReadPages, enabled = state.pages.isNotEmpty() && !state.loading) {
                    Text("Read Pages")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (availableFemaleVoiceNames.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Voice:", fontWeight = FontWeight.Bold)
                    availableFemaleVoiceNames.take(3).forEach { voiceName ->
                        Button(onClick = { onSelectVoice(voiceName) }) {
                            val shortName = voiceName.takeLast(8)
                            Text(if (voiceName == selectedVoiceName) "✓ $shortName" else shortName)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Emotion:", fontWeight = FontWeight.Bold)
                emotionProfiles.forEach { profile ->
                    Button(onClick = { onSelectEmotion(profile) }) {
                        Text(if (profile == selectedEmotion) "✓ ${profile.label}" else profile.label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onShareLogs) { Text("Share Logs") }
                Button(onClick = onClearLogs) { Text("Clear Logs") }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (state.loading) Text("Loading pages and extracting text...")

            Text("App / Crash Logs:", fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(text = state.appLogs, modifier = Modifier.padding(12.dp))
            }

            if (state.errorLog.isNotEmpty()) {
                Text("Processing Errors:", fontWeight = FontWeight.Bold)
                state.errorLog.forEach { Text("• $it") }
            }
        }
    }
}

@Composable
private fun ReaderModeScreen(
    state: ReaderUiState,
    currentReadingIndex: Int,
    isSpeaking: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit
) {
    val listState: LazyListState = rememberLazyListState()

    BackHandler(enabled = true) {
        onBack()
    }

    LaunchedEffect(currentReadingIndex, state.pages.size) {
        if (state.pages.isNotEmpty()) {
            listState.animateScrollToItem(currentReadingIndex.coerceIn(0, state.pages.lastIndex))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp)
        ) {
            itemsIndexed(state.pages) { _, page ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = page.bitmap.asImageBitmap(),
                        contentDescription = page.sourceName,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }

        SmallFloatingActionButton(
            onClick = onPlayPause,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = if (isSpeaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isSpeaking) "Pause" else "Play"
            )
        }
    }
}

private fun String.normalizeForSpeech(): String {
    return this
        .replace("\r", " ")
        .replace("\n", " ")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+([,.;:!?])"), "$1")
        .trim()
}

private fun android.speech.tts.Voice.isFemaleLikeVoice(): Boolean {
    val token = "$name ${locale.displayName}".lowercase(Locale.getDefault())
    return token.contains("female") || token.contains("woman") || token.contains("girl") || token.contains("fem")
}
