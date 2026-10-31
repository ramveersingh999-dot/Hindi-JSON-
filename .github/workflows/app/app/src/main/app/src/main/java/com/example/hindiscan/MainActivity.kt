package com.example.hindiscan

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.mlkit.document.scanner.GmsDocumentScannerOptions
import com.google.android.gms.mlkit.document.scanner.GmsDocumentScanning
import com.google.android.gms.mlkit.document.scanner.GmsDocumentScanningResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class Question(
    val topic: String = "सामान्य",
    val type: String = "hindi",
    val year: String = "",
    val board: String = "",
    val exam: String = "",
    val q: String = "",
    val options: List<String> = emptyList(),
    val ans: Int = 0,
    val exp: String = ""
)

@Serializable
data class Subject(
    val name: String,
    val topic: String,
    val type: String
)

enum class AppTab(val title: String) {
    SCAN("स्कैन"),
    LIBRARY("संग्रह"),
    PACKS("पैक")
}

class AppStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun getKey(): String = prefs.getString("gemini_key", "") ?: ""
    fun saveKey(key: String) = prefs.edit().putString("gemini_key", key).apply()
    fun clearKey() = prefs.edit().remove("gemini_key").apply()

    fun getSubjects(): List<Subject> {
        val default = listOf(
            Subject("हिंदी साहित्य", "हिंदी साहित्य", "hindi"),
            Subject("हिंदी व्याकरण", "हिंदी व्याकरण", "hindi"),
            Subject("संस्कृत व्याकरण", "संस्कृत व्याकरण", "hindi"),
            Subject("इतिहास (GS)", "इतिहास (GS)", "gs"),
            Subject("सामान्य ज्ञान (GK)", "सामान्य ज्ञान", "gs"),
            Subject("भूगोल", "भूगोल", "gs"),
            Subject("सामाजिक विज्ञान", "सामाजिक विज्ञान", "gs"),
            Subject("विज्ञान", "विज्ञान", "science"),
            Subject("जीव विज्ञान", "जीव विज्ञान", "science")
        )
        val customStr = prefs.getString("custom_subjects", "") ?: ""
        if (customStr.isBlank()) return default
        return try {
            val json = Json { ignoreUnknownKeys = true }
            default + json.decodeFromString<List<Subject>>(customStr)
        } catch (e: Exception) { default }
    }

    fun saveCustomSubject(subject: Subject) {
        val current = getSubjects().toMutableList()
        current.add(subject)
        val customOnly = current.drop(9)
        val json = Json { ignoreUnknownKeys = true }
        prefs.edit().putString("custom_subjects", json.encodeToString(customOnly)).apply()
    }
}

class MultiScriptOcr(private val context: Context) {
    private val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(uri: Uri): String = suspendCancellableCoroutine { cont ->
        try {
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType == "application/pdf") {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).run {
                    cont.resume(recognizePdf(uri))
                }
            } else {
                val image = InputImage.fromFilePath(context, uri)
                devanagari.process(image)
                    .addOnSuccessListener { result ->
                        if (result.text.isNotBlank()) cont.resume(result.text)
                        else {
                            latin.process(image)
                                .addOnSuccessListener { cont.resume(it.text) }
                                .addOnFailureListener { cont.resumeWithException(it) }
                        }
                    }
                    .addOnFailureListener {
                        latin.process(image)
                            .addOnSuccessListener { cont.resume(it.text) }
                            .addOnFailureListener { e -> cont.resumeWithException(e) }
                    }
            }
        } catch (e: Exception) { cont.resumeWithException(e) }
    }

    private fun recognizePdf(uri: Uri): String {
        val textBuilder = StringBuilder()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                renderer = PdfRenderer(pfd)
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val image = InputImage.fromBitmap(bitmap, 0)
                        var pageDone = false
                        var pageText = ""
                        devanagari.process(image)
                            .addOnSuccessListener { r ->
                                pageText = if (r.text.isNotBlank()) r.text else ""
                                pageDone = true
                            }
                            .addOnFailureListener { pageDone = true }

                        val start = System.currentTimeMillis()
                        while (!pageDone && System.currentTimeMillis() - start < 10000) {
                            Thread.sleep(50)
                        }
                        if (pageText.isNotBlank()) textBuilder.append(pageText).append("\n\n")
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() } finally {
            renderer?.close()
            pfd?.close()
        }
        return textBuilder.toString()
    }
    fun close() { devanagari.close(); latin.close() }
}

class HindiQuestionExtractor(private val apiKey: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun extract(
        text: String, subject: Subject, targetLang: String,
        defaultBoard: String, defaultYear: String
    ): List<Question> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext emptyList()
        val chunks = text.chunked(12000)
        val allQuestions = mutableListOf<Question>()

        for ((index, chunk) in chunks.withIndex()) {
            val prompt = buildStrictPrompt(chunk, subject, targetLang, defaultBoard, defaultYear, index + 1, chunks.size)
            allQuestions.addAll(callGemini(prompt))
        }

        return@withContext allQuestions
            .distinctBy { it.q.trim().lowercase() }
            .filter { q ->
                q.options.size == 4 && q.options.distinct().size == 4 &&
                q.options.none { it.isBlank() } && q.q.isNotBlank() && q.ans in 0..3
            }
    }

    private fun buildStrictPrompt(
        text: String, subject: Subject, targetLang: String,
        defaultBoard: String, defaultYear: String, part: Int, total: Int
    ): String {
        val typeValue = when (targetLang) {
            "English" -> "english"
            "संस्कृत" -> "sanskrit"
            else -> "hindi"
        }
        return """
            आप एक वरिष्ठ परीक्षा प्रश्न विश्लेषक, संपादक और अनुवादक हैं। OCR टेक्स्ट से केवल 100% प्रामाणिक प्रश्न निकालें।
            विषय: ${subject.name}
            लक्ष्य भाषा: $targetLang
            डिफ़ॉल्ट बोर्ड: $defaultBoard
            डिफ़ॉल्ट वर्ष: $defaultYear
            प्रसंस्करण भाग: $part / $total

            सख्त नियम:
            1. केवल प्रामाणिक प्रश्न निकालें।
            2. भाषा नीति:
               - यदि लक्ष्य भाषा 'हिंदी' है: अंग्रेजी का अनुवाद मानक हिंदी में करें।
               - अपवाद: यदि प्रश्न संस्कृत व्याकरण या श्लोक का है, तो अनिवार्य रूप से संस्कृत में रखें।
               - यदि लक्ष्य भाषा 'English' है: सब कुछ शुद्ध English में रखें।
               - यदि लक्ष्य भाषा 'संस्कृत' है: सब कुछ शुद्ध संस्कृत में रखें।
               - वर्तनी और व्याकरण सुधारें। अपठनीय प्रश्नों को छोड़ दें।
            3. बोर्ड और वर्ष: बोर्ड में "$defaultBoard" और वर्ष में "$defaultYear" का उपयोग करें। प्रश्न के अंत में '[बोर्ड वर्ष]' बैज लगाएँ।
            4. सुमेलित कीजिए: HTML तालिका प्रयोग करें:
               <table border='1' cellpadding='6' style='border-collapse:collapse; width:100%; text-align:left; margin:8px 0;'><tr style='background-color:#2a2a2a;'><th>सूची-I</th><th>सूची-II</th></tr><tr><td>(1) मद A</td><td>(a) मद 1</td></tr></table>
               विकल्प: 1-(b), 2-(a) शैली।
            5. उत्तर-लीक और गिवअवे वर्ष हटाएँ। चारों विकल्प अद्वितीय हों।
            6. डेटा स्कीमा:
               - "topic": "${subject.topic}"
               - "type": "$typeValue"
               - "ans": 0, 1, 2, या 3 (Integer)
               - आउटपुट केवल शुद्ध JSON Array हो। कोई मार्कडाउन नहीं।

            प्रारूप:
            [
              {
                "topic": "${subject.topic}",
                "type": "$typeValue",
                "year": "2021",
                "board": "UP PGT",
                "exam": "UP PGT 2021",
                "q": "प्रश्न पाठ [UP PGT 2021]",
                "options": ["Option A", "Option B", "Option C", "Option D"],
                "ans": 0,
                "exp": "विस्तृत व्याख्या।"
              }
            ]

            OCR Text:
            $text
        """.trimIndent()
    }

    private fun callGemini(prompt: String): List<Question> {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        val body = JSONObject().put("contents", JSONArray().put(
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )).toString()

        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val resp = JSONObject(r.body!!.string())
                var rawJson = resp.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?.optJSONObject(0)?.optString("text")?.trim() ?: return emptyList()

                rawJson = rawJson.replace("```json", "").replace("```", "").trim()
                val start = rawJson.indexOf('[')
                val end = rawJson.lastIndexOf(']')
                if (start == -1 || end == -1) return emptyList()
                rawJson = rawJson.substring(start, end + 1)

                val jsonArray = JSONArray(rawJson)
                val list = mutableListOf<Question>()
                val json = Json { ignoreUnknownKeys = true }
                for (i in 0 until jsonArray.length()) {
                    try {
                        list.add(json.decodeFromString<Question>(jsonArray.getJSONObject(i).toString()))
                    } catch (_: Exception) {}
                }
                list
            }
        } catch (e: Exception) { emptyList() }
    }
}

data class ScanUiState(
    val isProcessing: Boolean = false,
    val progressMsg: String = "",
    val questions: List<Question> = emptyList(),
    val rawJson: String = "",
    val error: String? = null,
    val savedMsg: String? = null,
    val pendingUris: List<Uri>? = null,
    val currentTab: AppTab = AppTab.SCAN
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val ocr = MultiScriptOcr(app)
    val store = AppStore(app)
    private val _state = MutableStateFlow(ScanUiState())
    val state = _state.asStateFlow()

    fun setTab(tab: AppTab) { _state.value = _state.value.copy(currentTab = tab) }

    fun startScanner(activity: Activity, launch: (IntentSenderRequest) -> Unit) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(150)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { _state.value = _state.value.copy(error = "स्कैनर आरंभ विफल: ${it.message}") }
    }

    fun onScanResult(data: Intent?) {
        val result = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return
        val uris = mutableListOf<Uri>()
        result.pages?.forEach { uris.add(it.imageUri) }
        result.pdf?.let { uris.add(it.uri) }
        if (uris.isNotEmpty()) _state.value = _state.value.copy(pendingUris = uris)
    }

    fun processDirect(pastedText: String, subject: Subject, targetLang: String, board: String, year: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessing = true, progressMsg = "टेक्स्ट का विश्लेषण चालू है…", error = null)
            try {
                var fullText = pastedText.trim()
                val uris = _state.value.pendingUris
                if (uris != null && uris.isNotEmpty()) {
                    val sb = StringBuilder()
                    for ((i, uri) in uris.withIndex()) {
                        _state.value = _state.value.copy(progressMsg = "OCR पृष्ठ (${i + 1}/${uris.size})…")
                        sb.append(ocr.recognize(uri)).append("\n\n")
                    }
                    fullText = if (fullText.isNotBlank()) "$fullText\n\n$sb" else sb.toString()
                }

                if (fullText.isBlank()) {
                    _state.value = _state.value.copy(isProcessing = false, error = "कृपया दस्तावेज़ स्कैन करें या टेक्स्ट चिपकाएँ।")
                    return@launch
                }

                _state.value = _state.value.copy(progressMsg = "AI विशेषज्ञ सत्यापन व शुद्धिकरण कर रहा है…")
                val extractor = HindiQuestionExtractor(store.getKey())
                val newQuestions = extractor.extract(fullText, subject, targetLang, board, year)

                if (newQuestions.isEmpty()) {
                    _state.value = _state.value.copy(isProcessing = false, error = "कोई मान्य प्रामाणिक प्रश्न नहीं मिला।")
                    return@launch
                }

                val allCombined = (_state.value.questions + newQuestions).distinctBy { it.q.trim().lowercase() }
                val json = Json { prettyPrint = true; encodeDefaults = true; prettyPrintIndent = "  " }
                val jsonStr = json.encodeToString(allCombined)

                _state.value = _state.value.copy(
                    isProcessing = false, progressMsg = "", questions = allCombined,
                    rawJson = jsonStr, pendingUris = null, currentTab = AppTab.LIBRARY,
                    savedMsg = "सफलतापूर्वक ${newQuestions.size} नए प्रश्न जोड़े गए।"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isProcessing = false, error = e.message ?: "अज्ञात त्रुटि")
            }
        }
    }

    fun markSaved(msg: String) { _state.value = _state.value.copy(savedMsg = msg) }
    override fun onCleared() { super.onCleared(); ocr.close() }
}

@Composable
fun HindiScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ComposeColor(0xFFE5A93C),
            onPrimary = ComposeColor.Black,
            primaryContainer = ComposeColor(0xFF3D2C0D),
            surface = ComposeColor(0xFF1A1E26),
            onSurface = ComposeColor(0xFFEDEDED),
            background = ComposeColor(0xFF0F1115),
            onBackground = ComposeColor(0xFFEDEDED),
            surfaceVariant = ComposeColor(0xFF282E3A),
            error = ComposeColor(0xFFE57373)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: ScanUiState, apiKey: String, subjects: List<Subject>,
    onTabSelected: (AppTab) -> Unit, onStartScan: () -> Unit,
    onSaveKey: (String) -> Unit, onRemoveKey: () -> Unit,
    onProcess: (String, Subject, String, String, String) -> Unit,
    onExportPacks: () -> Unit, onAddSubject: (Subject) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf(apiKey) }
    var selectedSubject by remember { mutableStateOf(subjects.first()) }
    var selectedLang by remember { mutableStateOf("हिंदी") }
    var boardInput by remember { mutableStateOf("UP PGT") }
    var yearInput by remember { mutableStateOf("2024") }
    var pastedText by remember { mutableStateOf("") }
    var showAddSubjectDialog by remember { mutableStateOf(false) }
    var subjectDropdownExpanded by remember { mutableStateOf(false) }
    var langDropdownExpanded by remember { mutableStateOf(false) }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("API Key सेटिंग्स", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyInput, onValueChange = { keyInput = it },
                        label = { Text("Gemini API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
            },
            confirmButton = { Button(onClick = { onSaveKey(keyInput.trim()); showSettings = false }) { Text("सेव करें") } },
            dismissButton = {
                if (apiKey.isNotBlank()) {
                    TextButton(onClick = { keyInput = ""; onRemoveKey(); showSettings = false }) {
                        Text("हटाएँ", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    if (showAddSubjectDialog) {
        var newSubName by remember { mutableStateOf("") }
        var newSubTopic by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddSubjectDialog = false },
            title = { Text("नया विषय जोड़ें") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newSubName, onValueChange = { newSubName = it }, label = { Text("विषय का नाम") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newSubTopic, onValueChange = { newSubTopic = it }, label = { Text("Topic Tag") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newSubName.isNotBlank()) {
                        val sub = Subject(newSubName, if (newSubTopic.isBlank()) newSubName else newSubTopic, "hindi")
                        onAddSubject(sub)
                        selectedSubject = sub
                        showAddSubjectDialog = false
                    }
                }) { Text("जोड़ें") }
            },
            dismissButton = { TextButton(onClick = { showAddSubjectDialog = false }) { Text("रद्द करें") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.padding(end = 10.dp)) {
                            Text("ह", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Column {
                            Text("HindiScan", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("स्कैन, शुद्ध हिंदी, प्रामाणिक JSON पैक", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Key, contentDescription = "API Key", tint = if (apiKey.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AppTab.values().forEach { tab ->
                    val isSelected = state.currentTab == tab
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else ComposeColor.Transparent)
                            .clickable { onTabSelected(tab) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (tab == AppTab.LIBRARY) "संग्रह ${state.questions.size}" else tab.title,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) ComposeColor.Black else MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            when (state.currentTab) {
                AppTab.SCAN -> {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("दस्तावेज़", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(12.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).clickable { onStartScan() }.padding(20.dp),
                                    color = ComposeColor.Transparent
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text("PDF, फ़ोटो या पाठ फ़ाइल छोड़ें", fontWeight = FontWeight.Medium)
                                        Text("अक्षर-पढ़ना इसी डिवाइस पर ऑफ़लाइन होता है।", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(12.dp))
                                        Button(onClick = { onStartScan() }) { Text("फ़ाइल चुनें / कैमरा") }
                                    }
                                }
                                if (state.pendingUris != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("✓ ${state.pendingUris.size} पेज चयनित", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(Modifier.height(14.dp))
                                Text("या पाठ चिपकाएँ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = pastedText, onValueChange = { pastedText = it },
                                    placeholder = { Text("OCR पाठ या पहले बना JSON पैक यहाँ डालें…") },
                                    modifier = Modifier.fillMaxWidth().height(110.dp), shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("विषय और परीक्षा", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Box {
                                    OutlinedTextField(
                                        value = selectedSubject.name, onValueChange = {}, readOnly = true, label = { Text("विषय") },
                                        trailingIcon = { IconButton(onClick = { subjectDropdownExpanded = true }) { Icon(Icons.Default.ArrowDropDown, contentDescription = null) } },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    DropdownMenu(expanded = subjectDropdownExpanded, onDismissRequest = { subjectDropdownExpanded = false }) {
                                        subjects.forEach { sub ->
                                            DropdownMenuItem(text = { Text(sub.name) }, onClick = { selectedSubject = sub; subjectDropdownExpanded = false })
                                        }
                                    }
                                }

                                Box {
                                    OutlinedTextField(
                                        value = selectedLang, onValueChange = {}, readOnly = true, label = { Text("OCR / लक्ष्य भाषा") },
                                        trailingIcon = { IconButton(onClick = { langDropdownExpanded = true }) { Icon(Icons.Default.ArrowDropDown, contentDescription = null) } },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    DropdownMenu(expanded = langDropdownExpanded, onDismissRequest = { langDropdownExpanded = false }) {
                                        listOf("हिंदी", "English", "संस्कृत").forEach { lang ->
                                            DropdownMenuItem(text = { Text(lang) }, onClick = { selectedLang = lang; langDropdownExpanded = false })
                                        }
                                    }
                                }

                                OutlinedTextField(value = boardInput, onValueChange = { boardInput = it }, label = { Text("बोर्ड (उदा. UP PGT)") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = yearInput, onValueChange = { yearInput = it }, label = { Text("वर्ष (उदा. 2024)") }, modifier = Modifier.fillMaxWidth())

                                TextButton(onClick = { showAddSubjectDialog = true }) { Text("▶ नया विषय जोड़ें") }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("जाँच", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                if (apiKey.isBlank()) {
                                    Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Text("ⓘ API कुंजी नहीं है। ऊपर ⚙ से Gemini API Key दर्ज करें।", modifier = Modifier.padding(10.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                }

                                Button(
                                    onClick = { onProcess(pastedText, selectedSubject, selectedLang, boardInput, yearInput) },
                                    enabled = !state.isProcessing && (apiKey.isNotBlank() || pastedText.isNotBlank()),
                                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(if (state.isProcessing) state.progressMsg.ifBlank { "प्रोसेसिंग…" } else "पढ़ें और प्रश्न जाँचें", fontWeight = FontWeight.Bold)
                                }

                                if (state.isProcessing) {
                                    Spacer(Modifier.height(10.dp))
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }

                                state.error?.let { err -> Spacer(Modifier.height(10.dp)); Text("त्रुटि: $err", color = MaterialTheme.colorScheme.error) }
                                state.savedMsg?.let { msg -> Spacer(Modifier.height(10.dp)); Text("✓ $msg", color = MaterialTheme.colorScheme.primary) }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("क्या तय है", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("1. केवल स्रोत के प्रामाणिक प्रश्न। कोई बनावटी प्रश्न नहीं।", style = MaterialTheme.typography.bodySmall)
                                Text("2. वर्तनी, व्याकरण और शब्द AI से सुधरेंगे।", style = MaterialTheme.typography.bodySmall)
                                Text("3. चार अलग विकल्प, उत्तर-लीक नहीं, और HTML तालिका।", style = MaterialTheme.typography.bodySmall)
                                Text("4. पैक में UTF-8 JSON, मास्टर फ़ाइल, और ZIP।", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                AppTab.LIBRARY -> {
                    Column(Modifier.fillMaxSize()) {
                        Text("सत्यापित संग्रह (${state.questions.size} प्रश्न)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        if (state.questions.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("अभी कोई प्रश्न नहीं है। 'स्कैन' से शुरू करें।", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                state.questions.forEachIndexed { i, q ->
                                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("Q${i + 1} • ${q.board} ${q.year} (${q.topic})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.height(4.dp))
                                            Text(q.q, fontWeight = FontWeight.Medium)
                                            Spacer(Modifier.height(4.dp))
                                            q.options.forEachIndexed { oIdx, opt ->
                                                Text("${('A' + oIdx)}. $opt ${if (oIdx == q.ans) "✓" else ""}", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                AppTab.PACKS -> {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Text("JSON पैक्स व आर्काइव", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("कुल तैयार प्रश्न: ${state.questions.size}", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = onExportPacks, enabled = state.questions.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                    Text("Download Complete Packs (JSON + ZIP)")
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Master JSON Preview", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Surface(color = ComposeColor.Black, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                            SelectionContainer {
                                Text(
                                    text = if (state.rawJson.isNotBlank()) state.rawJson.take(3000) else "कोई डेटा नहीं है।",
                                    modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                                    color = ComposeColor(0xFF81C784), fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: ScanViewModel by viewModels()

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.onScanResult(r.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HindiScanTheme {
                val state by vm.state.collectAsState()
                var currentKey by remember { mutableStateOf(vm.store.getKey()) }
                var subjectsList by remember { mutableStateOf(vm.store.getSubjects()) }

                MainScreen(
                    state = state, apiKey = currentKey, subjects = subjectsList,
                    onTabSelected = { vm.setTab(it) },
                    onStartScan = { vm.startScanner(this) { req -> launcher.launch(req) } },
                    onSaveKey = { currentKey = it; vm.store.saveKey(it) },
                    onRemoveKey = { currentKey = ""; vm.store.clearKey() },
                    onProcess = { text, sub, lang, board, year -> vm.processDirect(text, sub, lang, board, year) },
                    onExportPacks = { exportAllPacks(state.questions) },
                    onAddSubject = { sub -> vm.store.saveCustomSubject(sub); subjectsList = vm.store.getSubjects() }
                )
            }
        }
    }

    private fun exportAllPacks(allQuestions: List<Question>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val folderName = "HindiScan_Packs_$timestamp"
                val totalQ = allQuestions.size
                val json = Json { prettyPrint = true; encodeDefaults = true; prettyPrintIndent = "  " }

                val filesToZip = mutableMapOf<String, String>()
                filesToZip["Master_JSON_Pack_${totalQ}_Questions.json"] = json.encodeToString(allQuestions)

                if (totalQ > 900) {
                    val chunkSize = 850
                    allQuestions.chunked(chunkSize).forEachIndexed { index, chunk ->
                        val partNum = String.format("%02d", index + 1)
                        filesToZip["Part_${partNum}_${chunk.size}_Questions.json"] = json.encodeToString(chunk)
                    }
                }

                saveFilesToDownloads(folderName, filesToZip)
                withContext(Dispatchers.Main) {
                    vm.markSaved("Downloads/HindiScan_Packs/$folderName में सेव हुआ")
                    Toast.makeText(this@MainActivity, "पैक्स सेव हो गए!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "विफल: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun saveFilesToDownloads(folderName: String, files: Map<String, String>) {
        val resolver = contentResolver
        val zipFileName = "$folderName.zip"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/HindiScan_Packs/$folderName"
            for ((fileName, content) in files) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                }
            }

            val zipValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, zipFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HindiScan_Packs")
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, zipValues)?.let { zipUri ->
                resolver.openOutputStream(zipUri)?.use { os ->
                    ZipOutputStream(os).use { zos ->
                        for ((fileName, content) in files) {
                            zos.putNextEntry(ZipEntry(fileName))
                            zos.write(content.toByteArray(Charsets.UTF_8))
                            zos.closeEntry()
                        }
                    }
                }
            }
        } else {
            val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HindiScan_Packs/$folderName")
            baseDir.mkdirs()
            for ((fileName, content) in files) File(baseDir, fileName).writeText(content, Charsets.UTF_8)

            val zipFile = File(baseDir.parentFile, zipFileName)
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for ((fileName, content) in files) {
                    zos.putNextEntry(ZipEntry(fileName))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
    }
}
