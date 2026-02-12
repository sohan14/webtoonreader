package com.example.webtoonreader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color as ComposeColor
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                var tts by remember { mutableStateOf<TextToSpeech?>(null) }
                var isSpeaking by remember { mutableStateOf(false) }
                var readerMode by remember { mutableStateOf(false) }
                var currentReadingIndex by remember { mutableStateOf(0) }
                var selectedVoiceName by remember { mutableStateOf<String?>(null) }
                var castVoiceAName by remember { mutableStateOf<String?>(null) }
                var castVoiceBName by remember { mutableStateOf<String?>(null) }
                var characterCastEnabled by remember { mutableStateOf(true) }
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
                    val voicesByName = speaker.voices.orEmpty().associateBy { it.name }
                    speaker.stop()
                    var dialogueTurnIndex = 0
                    viewModel.state.pages.drop(fromIndex).forEachIndexed { idx, page ->
                        val absoluteIndex = fromIndex + idx
                        val rawSegments = page.extractedText.extractSpeechSegments()
                        if (rawSegments.isEmpty()) {
                            AppLogStore.append(context, "INFO", "Skipped TTS for page_${absoluteIndex}: no text segment found")
                        } else {
                            rawSegments.forEachIndexed { segIndex, rawSegment ->
                                val normalized = rawSegment.normalizeForSpeech()
                                if (normalized.shouldSkipSpeechSegment()) {
                                    AppLogStore.append(context, "INFO", "Skipped TTS for page_${absoluteIndex}_seg_${segIndex}: filtered non-readable segment")
                                    return@forEachIndexed
                                }
                                val isDialogue = rawSegment.isLikelyDialogue()
                                val speakerRole = if (characterCastEnabled && isDialogue) {
                                    val role = if (dialogueTurnIndex % 2 == 0) "characterA" else "characterB"
                                    dialogueTurnIndex++
                                    role
                                } else {
                                    "narrator"
                                }
                                val selectedVoiceForSegment = when (speakerRole) {
                                    "characterA" -> castVoiceAName ?: selectedVoiceName
                                    "characterB" -> castVoiceBName ?: selectedVoiceName
                                    else -> selectedVoiceName
                                }
                                voicesByName[selectedVoiceForSegment]?.let { speaker.voice = it }

                                val tunedEmotion = selectedEmotion.tuneForSegment(normalized, speakerRole)
                                speaker.setSpeechRate(tunedEmotion.speechRate)
                                speaker.setPitch(tunedEmotion.pitch)

                                val utteranceId = "page_${absoluteIndex}_seg_$segIndex"
                                speaker.speak(
                                    normalized,
                                    if (idx == 0 && segIndex == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                                    null,
                                    utteranceId
                                )
                                AppLogStore.append(
                                    context,
                                    "INFO",
                                    "Queued TTS $utteranceId (textLength=${normalized.length}, role=$speakerRole, voice=${selectedVoiceForSegment ?: "default"})"
                                )
                            }
                        }
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
                                castVoiceAName = femaleVoices.getOrNull(1)?.name ?: femaleVoices.first().name
                                castVoiceBName = femaleVoices.getOrNull(2)?.name ?: femaleVoices.first().name
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
                            val index = utteranceId
                                ?.substringAfter("page_", "")
                                ?.substringBefore("_seg_")
                                ?.toIntOrNull()
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
                        castVoiceAName = castVoiceAName,
                        castVoiceBName = castVoiceBName,
                        characterCastEnabled = characterCastEnabled,
                        selectedEmotion = selectedEmotion,
                        availableFemaleVoiceNames = tts?.voices
                            ?.filter { it.isFemaleLikeVoice() }
                            ?.map { it.name }
                            ?.sorted()
                            .orEmpty(),
                        onPickFiles = { uris -> viewModel.loadUris(contentResolver, uris, context) },
                        onSelectVoice = { selectedVoiceName = it },
                        onCycleCastVoiceA = {
                            castVoiceAName = nextVoiceName(castVoiceAName, it)
                        },
                        onCycleCastVoiceB = {
                            castVoiceBName = nextVoiceName(castVoiceBName, it)
                        },
                        onToggleCharacterCast = { characterCastEnabled = it },
                        onSelectEmotion = { selectedEmotion = it },
                        onReadPages = {
                            readerMode = true
                            currentReadingIndex = 0
                            startReading(0)
                        },
                        onToggleVoice = {
                            selectedVoiceName = nextVoiceName(selectedVoiceName, it)
                        },
                        showVoiceToggleInHeader = true,
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
                    } else if (uri.isMhtFile(mimeType)) {
                        pages += readMht(resolver, uri, context)
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
                    val renderScale = minOf(1f, MAX_BITMAP_WIDTH.toFloat() / page.width.toFloat())
                    val width = (page.width * renderScale).toInt().coerceAtLeast(1)
                    val height = (page.height * renderScale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val panels = splitBitmapIntoPanels(bitmap, context, "pdf:${uri.lastPathSegment}:page${i + 1}")
                    panels.forEachIndexed { panelIdx, panelBitmap ->
                        val text = extractText(panelBitmap, context)
                        previews += PagePreview(
                            bitmap = panelBitmap,
                            extractedText = text,
                            sourceName = if (panels.size > 1) {
                                "${uri.lastPathSegment} - page ${i + 1} panel ${panelIdx + 1}/${panels.size}"
                            } else {
                                "${uri.lastPathSegment} - page ${i + 1}"
                            }
                        )
                    }
                }
            }
        }
        pfd.close()
        AppLogStore.append(context, "INFO", "PDF parsed: ${uri.lastPathSegment}, pages=${previews.size}")
        previews
    }

    private suspend fun readMht(
        resolver: android.content.ContentResolver,
        uri: Uri,
        context: Context
    ): List<PagePreview> = withContext(Dispatchers.IO) {
        val content = resolver.openInputStream(uri)?.use { input ->
            input.readBytes().decodeToString()
        }.orEmpty()

        if (content.isBlank()) {
            AppLogStore.append(context, "ERROR", "MHT parse failed: empty content for ${uri.lastPathSegment}")
            return@withContext listOf(
                PagePreview(
                    bitmap = createTextPlaceholderBitmap(),
                    extractedText = "No text detected.",
                    sourceName = "${uri.lastPathSegment} - mht"
                )
            )
        }

        val extracted = content.extractMhtReadableText().normalizeForSpeech().ifBlank { "No text detected." }
        AppLogStore.append(context, "INFO", "MHT parsed: ${uri.lastPathSegment}, textLength=${extracted.length}")
        listOf(
            PagePreview(
                bitmap = createTextPlaceholderBitmap(),
                extractedText = extracted,
                sourceName = "${uri.lastPathSegment} - mht"
            )
        )
    }

    private fun createTextPlaceholderBitmap(): Bitmap {
        return Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
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
                cont.resume(result.text)
            }
            .addOnFailureListener { e ->
                recognizer.close()
                AppLogStore.append(context, "ERROR", "OCR failed: ${e.message}")
                cont.resume("")
            }
    }
}

@Composable
private fun HomeScreen(
    state: ReaderUiState,
    selectedVoiceName: String?,
    castVoiceAName: String?,
    castVoiceBName: String?,
    characterCastEnabled: Boolean,
    selectedEmotion: EmotionProfile,
    availableFemaleVoiceNames: List<String>,
    onPickFiles: (List<Uri>) -> Unit,
    onSelectVoice: (String) -> Unit,
    onToggleVoice: (List<String>) -> Unit,
    onCycleCastVoiceA: (List<String>) -> Unit,
    onCycleCastVoiceB: (List<String>) -> Unit,
    onToggleCharacterCast: (Boolean) -> Unit,
    onSelectEmotion: (EmotionProfile) -> Unit,
    onReadPages: () -> Unit,
    showVoiceToggleInHeader: Boolean,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) onPickFiles(uris)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { picker.launch(arrayOf("application/pdf", "image/*", "message/rfc822", "multipart/related", "text/html", "application/octet-stream")) }) {
                    Text("Pick PDF / Images")
                }
                Button(onClick = onReadPages, enabled = state.pages.isNotEmpty() && !state.loading) {
                    Text("Read Pages")
                }
                if (showVoiceToggleInHeader && availableFemaleVoiceNames.isNotEmpty()) {
                    Button(onClick = { onToggleVoice(availableFemaleVoiceNames) }) {
                        Icon(imageVector = Icons.Filled.SwapHoriz, contentDescription = "Toggle voice")
                        Text("Voice")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (availableFemaleVoiceNames.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Voice:", fontWeight = FontWeight.Bold)
                    availableFemaleVoiceNames.take(6).forEach { voiceName ->
                        Button(onClick = { onSelectVoice(voiceName) }) {
                            val shortName = voiceName.takeLast(8)
                            Text(if (voiceName == selectedVoiceName) "✓ $shortName" else shortName)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cast:", fontWeight = FontWeight.Bold)
                    Switch(checked = characterCastEnabled, onCheckedChange = onToggleCharacterCast)
                    Button(onClick = { onCycleCastVoiceA(availableFemaleVoiceNames) }, enabled = availableFemaleVoiceNames.isNotEmpty()) {
                        Text("A: ${(castVoiceAName ?: selectedVoiceName ?: "Default").takeLast(8)}")
                    }
                    Button(onClick = { onCycleCastVoiceB(availableFemaleVoiceNames) }, enabled = availableFemaleVoiceNames.isNotEmpty()) {
                        Text("B: ${(castVoiceBName ?: selectedVoiceName ?: "Default").takeLast(8)}")
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
            modifier = Modifier.fillMaxSize().background(ComposeColor.White),
            contentPadding = PaddingValues(0.dp)
        ) {
            itemsIndexed(state.pages) { _, page ->
                Column(modifier = Modifier.fillParentMaxWidth().background(ComposeColor.White)) {
                    Image(
                        bitmap = page.bitmap.asImageBitmap(),
                        contentDescription = page.sourceName,
                        modifier = Modifier.fillParentMaxWidth(),
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

private fun String.shouldSkipSpeechSegment(): Boolean {
    val text = trim()
    if (text.isBlank()) return true
    val lowered = text.lowercase(Locale.getDefault())
    if (lowered == "no text detected." || lowered == "no text detected") return true
    if (lowered == "no text found." || lowered == "no text found") return true

    val alphaNumCount = text.count { it.isLetterOrDigit() }
    return alphaNumCount < 3
}

private fun String.extractSpeechSegments(): List<String> {
    return this
        .replace("\r", "\n")
        .split(Regex("\n{2,}|(?<=[.!?])\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun String.isLikelyDialogue(): Boolean {
    val trimmed = trim()
    if (trimmed.contains('"') || trimmed.contains('“') || trimmed.contains('”')) return true
    if (trimmed.startsWith("- ") || trimmed.startsWith("—")) return true
    val shortLine = trimmed.length in 6..140
    val punctuationCount = trimmed.count { it == '!' || it == '?' }
    return shortLine && punctuationCount > 0
}

private fun EmotionProfile.tuneForSegment(text: String, speakerRole: String): EmotionProfile {
    var rate = speechRate
    var tunedPitch = pitch
    if (text.contains('!')) {
        rate += 0.03f
        tunedPitch += 0.05f
    }
    if (text.contains("...")) {
        rate -= 0.05f
    }
    if (text.contains('?')) {
        tunedPitch += 0.03f
    }
    if (speakerRole == "characterB") {
        tunedPitch = (tunedPitch - 0.08f).coerceIn(0.75f, 1.35f)
    }
    return EmotionProfile(
        label = label,
        speechRate = rate.coerceIn(0.75f, 1.25f),
        pitch = tunedPitch.coerceIn(0.75f, 1.35f)
    )
}

private fun nextVoiceName(current: String?, voices: List<String>): String? {
    if (voices.isEmpty()) return current
    if (current == null) return voices.first()
    val idx = voices.indexOf(current)
    if (idx < 0) return voices.first()
    return voices[(idx + 1) % voices.size]
}

private fun Uri.isMhtFile(mimeType: String): Boolean {
    val name = lastPathSegment.orEmpty().lowercase(Locale.getDefault())
    val type = mimeType.lowercase(Locale.getDefault())
    return name.endsWith(".mht") ||
        name.endsWith(".mhtml") ||
        type.contains("message/rfc822") ||
        type.contains("multipart/related")
}

private fun String.extractMhtReadableText(): String {
    val htmlSections = Regex("(?is)<html.*?>.*?</html>").findAll(this).map { it.value }.toList()
    val body = if (htmlSections.isNotEmpty()) {
        htmlSections.joinToString("\n")
    } else {
        this
    }

    return body
        .replace(Regex("(?is)<script.*?>.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?>.*?</style>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun android.speech.tts.Voice.isFemaleLikeVoice(): Boolean {
    val token = "$name ${locale.displayName}".lowercase(Locale.getDefault())
    return token.contains("female") || token.contains("woman") || token.contains("girl") || token.contains("fem")
}
