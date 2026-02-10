package com.example.webtoonreader

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
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "WebtoonReader"

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<ReaderViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var tts by remember { mutableStateOf<TextToSpeech?>(null) }
                var isSpeaking by remember { mutableStateOf(false) }

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
                    onPickFiles = { uris -> viewModel.loadUris(contentResolver, uris) },
                    onReadAll = {
                        scope.launch {
                            val speaker = tts ?: return@launch
                            viewModel.state.pages.forEachIndexed { index, page ->
                                val text = page.extractedText.ifBlank { "No text found on page ${index + 1}" }
                                speaker.speak(
                                    "Page ${index + 1}. $text",
                                    TextToSpeech.QUEUE_ADD,
                                    null,
                                    "page_$index"
                                )
                            }
                        }
                    },
                    onStopReading = {
                        tts?.stop()
                        isSpeaking = false
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
    val errorLog: List<String> = emptyList()
)

class ReaderViewModel : ViewModel() {
    var state by mutableStateOf(ReaderUiState())
        private set

    fun loadUris(resolver: android.content.ContentResolver, uris: List<Uri>) {
        viewModelScope.launch {
            state = state.copy(loading = true, errorLog = emptyList(), pages = emptyList())
            val pages = mutableListOf<PagePreview>()
            val errors = mutableListOf<String>()

            uris.forEach { uri ->
                try {
                    resolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // picker may return temporary permission only
                }

                try {
                    val mimeType = resolver.getType(uri).orEmpty()
                    if (mimeType.contains("pdf")) {
                        pages += readPdf(resolver, uri)
                    } else {
                        pages += readImage(resolver, uri)
                    }
                } catch (ex: Exception) {
                    val msg = "Failed to open $uri: ${ex.message}"
                    Log.e(TAG, msg, ex)
                    errors += msg
                }
            }

            state = state.copy(loading = false, pages = pages, errorLog = errors)
        }
    }

    private suspend fun readImage(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): List<PagePreview> = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.isMutableRequired = false
        }
        val text = extractText(bitmap)
        listOf(PagePreview(bitmap = bitmap, extractedText = text, sourceName = uri.lastPathSegment.orEmpty()))
    }

    private suspend fun readPdf(
        resolver: android.content.ContentResolver,
        uri: Uri
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
                    val text = extractText(bitmap)
                    previews += PagePreview(
                        bitmap = bitmap,
                        extractedText = text,
                        sourceName = "${uri.lastPathSegment} - page ${i + 1}"
                    )
                }
            }
        }
        pfd.close()
        previews
    }

    private suspend fun extractText(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
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
                Button(onClick = {
                    picker.launch(arrayOf("application/pdf", "image/*"))
                }) {
                    Text("Pick PDF / Images")
                }
                Button(onClick = onReadAll, enabled = state.pages.isNotEmpty() && !state.loading) {
                    Text("Read Pages")
                }
                Button(onClick = onStopReading, enabled = isSpeaking) {
                    Text("Stop")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (state.loading) {
                Text("Loading pages and extracting text...")
            }
            if (state.errorLog.isNotEmpty()) {
                Text("Logs:", fontWeight = FontWeight.Bold)
                state.errorLog.forEach { Text("• $it") }
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
