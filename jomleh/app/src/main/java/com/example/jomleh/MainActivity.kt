@file:OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
package com.example.jomleh

import androidx.compose.foundation.border
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ---------------- ذخیره/بازیابی تنظیمات ----------------

fun saveLastPosition(context: Context, index: Int) {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    prefs.edit { putInt("last_index", index) }
}

fun getLastPosition(context: Context): Int {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return prefs.getInt("last_index", 0)
}

fun saveSpeechSpeed(context: Context, speed: String) {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    prefs.edit { putString("speech_speed", speed) }
}

fun getSpeechSpeed(context: Context): String {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    // تغییر مقدار پیش‌فرض از "معمولی" به "آهسته"
    return prefs.getString("speech_speed", "آهسته") ?: "آهسته"
}

fun saveSelectedCategory(context: Context, category: String) {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    prefs.edit { putString("last_category", category) }
}

fun getSelectedCategory(context: Context): String {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return prefs.getString("last_category", "همه") ?: "همه"
}

fun speedToRate(speed: String): Float {
    return when (speed) {
        "آهسته" -> 0.3f
        "خیلی آهسته" -> 0.1f
        else -> 0.6f
    }
}

// ---------------- مدل داده و مدیریت فایل ----------------

data class Sentence(
    val persian: String,
    val english: String,
    val topic: String
)

fun loadSentences(context: Context, uriString: String?): List<Sentence> {
    if (uriString == null) return emptyList()
    val uri = uriString.toUri()
    val sentences = mutableListOf<Sentence>()

    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val text = input.bufferedReader().readText()
            if (text.isNotBlank()) {
                val jsonArray = JSONArray(text)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    sentences.add(
                        Sentence(
                            persian = obj.getString("p"),
                            english = obj.getString("e"),
                            topic = obj.getString("t")
                        )
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.e("FileHandler", "Error loading sentences: ${e.message}")
        e.printStackTrace()
    }
    return sentences
}

fun saveSentences(context: Context, uriString: String?, sentences: List<Sentence>) {
    if (uriString == null) return
    val uri = uriString.toUri()
    try {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            val jsonArray = JSONArray()
            sentences.forEach { s ->
                val obj = JSONObject()
                obj.put("p", s.persian)
                obj.put("e", s.english)
                obj.put("t", s.topic)
                jsonArray.put(obj)
            }
            val compressedJson = jsonArray.toString(0)
            output.write(compressedJson.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    } catch (e: Exception) {
        Log.e("FileHandler", "Error saving sentences: ${e.message}")
        e.printStackTrace()
    }
}

/**
 * تابع ترمیم شده برای بارگذاری داده‌های اولیه پس از نصب مجدد.
 * فرض بر این است که فایل R.raw.jomleh در پروژه شما وجود دارد.
 */
suspend fun loadInitialDataIfFirstRun(context: Context, savedUri: String?) {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val isInitialDataLoaded = prefs.getBoolean("is_initial_data_loaded", false)

    if (isInitialDataLoaded || savedUri == null) {
        return
    }

    val sentences = mutableListOf<Sentence>()

    try {
        withContext(Dispatchers.IO) {
            // منطق خواندن فایل منابع R.raw.jomleh (باید در پروژه شما تعریف شده باشد)
            // ❗ اگر فایل در res/raw/jomleh.json وجود ندارد، این خط خطا می‌دهد
            // و باید آن را اضافه کنید.
            context.resources.openRawResource(R.raw.jomleh).use { input ->
                val text = input.bufferedReader(Charsets.UTF_8).readText()
                if (text.isNotBlank()) {
                    val jsonArray = JSONArray(text)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        sentences.add(
                            Sentence(
                                persian = obj.getString("p"),
                                english = obj.getString("e"),
                                topic = obj.getString("t")
                            )
                        )
                    }
                }
            }

            if (sentences.isNotEmpty()) {
                saveSentences(context, savedUri, sentences)
                prefs.edit { putBoolean("is_initial_data_loaded", true) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "✅ داده‌های اولیه بازیابی شدند.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "❌ خطا: فایل R.raw.jomleh پیدا نشد یا فرمت آن اشتباه است.", Toast.LENGTH_LONG).show()
        }
        Log.e("FileHandler", "Error loading initial data from raw resource: ${e.message}")
        e.printStackTrace()
    }
}

// ---------------- کلاس MainActivity ----------------

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var currentSpeed: String = "معمولی"
    private val allSentences = mutableStateListOf<Sentence>()
    private var speakJob: Job? = null
    private var currentWordForRepeat: String? = null

    private val createFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            try {
                val savedUriString = it.toString()

                // درخواست مجوز پایداری برای دسترسی به URI
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(it, takeFlags)

                // ذخیره URI در Shared Preferences
                getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit {
                    putString("json_file_uri", savedUriString)
                }

                // اجرای منطق بارگذاری در Coroutine
                lifecycleScope.launch {
                    loadInitialDataIfFirstRun(this@MainActivity, savedUriString)

                    val loaded = withContext(Dispatchers.IO) {
                        loadSentences(this@MainActivity, savedUriString)
                    }
                    allSentences.clear()
                    allSentences.addAll(loaded)

                    Toast.makeText(this@MainActivity, "✅ فایل ذخیره و جملات اولیه بارگذاری شد.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "❌ خطا در گرفتن دسترسی و ذخیره‌سازی فایل: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        } ?: run {
            Toast.makeText(this@MainActivity, "🚫 مکان فایل ذخیره مشخص نشد. برنامه بدون جملات اولیه اجرا می‌شود.", Toast.LENGTH_LONG).show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentSpeed = getSpeechSpeed(this)
        tts = TextToSpeech(this, this)

        setContent {
            MyApp(
                onSupportClick = { startActivity(Intent(this, SupportActivity::class.java)) },
                // ✅ امضای speakEnglish تغییر کرد تا onDone Callback را بپذیرد
                speakEnglish = { text, onDone -> speakOut(text, onDone) },
                speakWord = { word, start -> speakOutWord(word, start) },
                updateSpeed = { speed ->
                    currentSpeed = speed
                    saveSpeechSpeed(this, speed)
                    if (::tts.isInitialized) {
                        tts.setSpeechRate(speedToRate(speed))
                    }
                },
                allSentences = allSentences
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val sharedPref = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedUri = sharedPref.getString("json_file_uri", null)

        if (savedUri == null) {
            // اگر URI ذخیره نشده، درخواست ایجاد فایل جدید
            Toast.makeText(this, "مکان ذخیره سازی را تعیین کنید", Toast.LENGTH_LONG).show()
            createFileLauncher.launch("jomleh.json")
            return
        }

        lifecycleScope.launch {
            // اجرای بارگذاری اولیه هنگام نصب مجدد (ترمیم شده)
            loadInitialDataIfFirstRun(this@MainActivity, savedUri)

            // منطق بارگذاری جملات کاربر
            val loaded = withContext(Dispatchers.IO) {
                loadSentences(this@MainActivity, savedUri)
            }
            // به‌روزرسانی لیست جملات
            allSentences.clear()
            allSentences.addAll(loaded)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.ENGLISH)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "زبان انگلیسی پشتیبانی نمی‌شود", Toast.LENGTH_LONG).show()
            } else {
                tts.setSpeechRate(speedToRate(currentSpeed))
            }
        }
    }

    // ✅ تابع speakOut تغییر کرد تا یک onDone Callback را بپذیرد و UtteranceProgressListener را پیاده سازی کند
    private fun speakOut(text: String, onDone: () -> Unit) {
        stopWordRepeat()
        val rate = speedToRate(currentSpeed)
        tts.setSpeechRate(rate)

        // 💡 منطق جدید: استفاده از UtteranceProgressListener برای تشخیص پایان تلفظ
        val utteranceId = text.hashCode().toString()

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // شروع تلفظ (اختیاری)
            }

            override fun onDone(utteranceId: String?) {
                // پایان موفقیت‌آمیز تلفظ
                lifecycleScope.launch(Dispatchers.Main) {
                    onDone()
                    // Listener را پس از اتمام حذف می‌کنیم
                    tts.setOnUtteranceProgressListener(null)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                // در صورت بروز خطا
                lifecycleScope.launch(Dispatchers.Main) {
                    onDone()
                    tts.setOnUtteranceProgressListener(null)
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // هنگام توقف
                lifecycleScope.launch(Dispatchers.Main) {
                    onDone()
                    tts.setOnUtteranceProgressListener(null)
                }
            }
        })

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        // فراخوانی speak با Utterance ID
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun speakOutWord(word: String, start: Boolean) {
        if (!start) {
            stopWordRepeat()
            return
        }

        if (word == currentWordForRepeat && speakJob?.isActive == true) return

        stopWordRepeat()

        currentWordForRepeat = word
        val rate = speedToRate(currentSpeed)

        speakJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                while (true) {
                    tts.setSpeechRate(rate)
                    tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, null)
                    delay(2000)
                }
            }
        }
    }

    private fun stopWordRepeat() {
        speakJob?.cancel()
        currentWordForRepeat = null
        if (::tts.isInitialized) {
            tts.stop()
        }
    }

    override fun onDestroy() {
        stopWordRepeat()
        if (::tts.isInitialized) {
            tts.shutdown()
        }
        super.onDestroy()
    }
}

// ---------------- کامپوزبل‌های UI ----------------

@Composable
fun MyApp(
    onSupportClick: () -> Unit,
    // ✅ امضای speakEnglish تغییر کرد تا onDone Callback را بپذیرد
    speakEnglish: (String, () -> Unit) -> Unit,
    speakWord: (String, Boolean) -> Unit,
    updateSpeed: (String) -> Unit,
    allSentences: MutableList<Sentence>
) {
    val context = LocalContext.current
    val sharedPref = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val savedUri = sharedPref.getString("json_file_uri", null)

    var selectedTopic by remember { mutableStateOf(getSelectedCategory(context)) }
    var selectedSpeed by remember { mutableStateOf(getSpeechSpeed(context)) }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var zoomedWord by remember { mutableStateOf<String?>(null) }

    // 1. 💡 متغیر حالت جدید برای اجبار به Recomposition
    var forceUpdateKey by remember { mutableIntStateOf(0) }

    // ✅ متغیر حالت جدید برای نمایش کادر زرد دور جمله در حال تلفظ
    var speakingSentenceKey by remember { mutableStateOf<String?>(null) } // <-- اضافه شد

    // 3. متغیرهای حالت برای جستجو
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // ✅ منطق بازیابی اسکرول پس از بارگذاری داده‌ها
    LaunchedEffect(allSentences.isNotEmpty(), forceUpdateKey) {
        if (allSentences.isNotEmpty()) {
            val lastIndex = getLastPosition(context)
            val safeIndex = if (lastIndex < allSentences.size) lastIndex else 0
            if (safeIndex > 0) {
                listState.scrollToItem(safeIndex)
            }
        }
    }

    // 🌟 بلوک ذخیرهٔ مکان اسکرول حفظ می‌شود
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(500L)
            .collect { index ->
                saveLastPosition(context, index)
            }
    }


    LaunchedEffect(zoomedWord) {
        if (zoomedWord != null) {
            speakWord(zoomedWord!!, true)
        } else {
            speakWord("", false)
        }
    }

    val topics by remember {
        derivedStateOf { listOf("همه") + allSentences.map { it.topic }.distinct() }
    }
    val speeds = listOf("معمولی", "آهسته", "خیلی آهسته")

    // 4. به‌روزرسانی منطق فیلترینگ برای اعمال جستجو
    val filteredSentences = remember(selectedTopic, searchQuery, allSentences.size, forceUpdateKey) {
        val baseFiltered = if (selectedTopic == "همه") {
            allSentences
        } else {
            allSentences.filter { it.topic == selectedTopic }
        }

        if (searchQuery.isBlank()) {
            baseFiltered
        } else {
            baseFiltered.filter { sentence ->
                sentence.persian.contains(searchQuery.trim(), ignoreCase = true) ||
                        sentence.english.contains(searchQuery.trim(), ignoreCase = true)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (zoomedWord != null) {
            WordZoomScreen(
                word = zoomedWord!!,
                onDismiss = {
                    zoomedWord = null
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF8D8B8B))
                    // 🌟 تغییر اعمال شده: فاصله بالا (top) به 0.dp تغییر کرد.
                    .padding(start = 4.dp, end = 4.dp, bottom = 15.dp, top = 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                if (isSearching) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("جستجو (فارسی/انگلیسی)") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(onClick = {
                                isSearching = false
                                searchQuery = ""
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "بستن جستجو")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            cursorColor = Color.Black,
                            focusedBorderColor = Color.Black,
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = Color.Black,
                            unfocusedLabelColor = Color.Gray,
                        )
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        // 1. آیکون جستجو (سمت چپ)
                        IconButton(onClick = { isSearching = true }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "جستجو",
                                tint = Color(0xFFFADE02),
                                modifier = Modifier.size(80.dp)
                            )
                        }

                        // 2. متن "جمله" (وسط)
                        Text(
                            text = "جمله",
                            fontSize = 50.sp,
                            color = Color(0xFF90EE90),
                            textAlign = TextAlign.Center,
                            style = TextStyle(
                                shadow = Shadow(
                                    color = Color.Black,
                                    offset = Offset(5f, 5f),
                                    blurRadius = 5f
                                )
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        // 3. تصویر jomleh.png (سمت راست) - با اندازه بزرگتر (80.dp) برای تقارن
                        Image(
                            painter = painterResource(id = R.drawable.jomleh),
                            contentDescription = "آیکون جمله",
                            modifier = Modifier
                                .size(80.dp)
                                .align(Alignment.CenterVertically)
                        )
                    }
                }

                // Box قبلی "افزودن جمله، ذخیره، بازیابی"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.dp)
                        .background(Color(0xFF006400), shape = RoundedCornerShape(40.dp))
                        .border(
                            width = 2.dp,
                            color = Color.Gray,
                            shape = RoundedCornerShape(40.dp)
                        )
                        .clickable { onSupportClick() }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "افزودن جمله ، ذخیره ، بازیابی",
                            color = Color.White,
                            fontSize = 18.sp
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "رفتن به صفحه افزودن جمله",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.dp)
                        .background(Color(0xFF1B6AA8), shape = RoundedCornerShape(38.dp))
                        .border(
                            width = 1.dp,
                            color = Color.Gray,
                            shape = RoundedCornerShape(38.dp)
                        )
                        .padding(6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    DropdownMenuBox(
                        label = "موضوع",
                        selectedOption = selectedTopic,
                        options = topics,
                        onOptionSelected = {
                            selectedTopic = it
                            saveSelectedCategory(context, it)
                            coroutineScope.launch {
                                listState.scrollToItem(0)
                            }
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.dp)
                        .background(Color(0xFF7951C5), shape = RoundedCornerShape(38.dp))
                        .border(
                            width = 1.dp,
                            color = Color.Gray,
                            shape = RoundedCornerShape(38.dp)
                        )
                        .padding(6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    DropdownMenuBox(
                        label = "سرعت تلفظ",
                        selectedOption = selectedSpeed,
                        options = speeds,
                        onOptionSelected = {
                            selectedSpeed = it
                            updateSpeed(it)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (filteredSentences.isEmpty()) {
                    Text(
                        "هیچ جمله‌ای برای این موضوع/جستجو پیدا نشد",
                        color = Color.White
                    )
                } else {
                    // **شروع بلوک دکمه‌های اسکرول**
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f) // برای پر کردن فضای باقی‌مانده
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(top = 0.dp, bottom = 16.dp)
                        ) {
                            items(
                                items = filteredSentences,
                                key = { it.persian + it.english + it.topic }
                            ) { sentence ->

                                val index = filteredSentences.indexOf(sentence)

                                var showDeleteDialog by remember { mutableStateOf(false) }
                                var showMoveDialog by remember { mutableStateOf(false) } // انتقال به بالا
                                var showEditDialog by remember { mutableStateOf(false) }
                                // ✅ اضافه شدن متغیر حالت برای انتقال به انتهای لیست
                                var showMoveToBottomDialog by remember { mutableStateOf(false) }

                                var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    shape = RoundedCornerShape(15.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFF06988A)
                                    ),
                                    elevation = CardDefaults.cardElevation(2.dp)
                                ) {
                                    // 🌟 ترمیم شده: pointerInput برای onDoubleTap به این Box اصلی منتقل شد
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // این ژست، دوبار کلیک روی ناحیه فارسی و ناحیه میانی را هندل می‌کند.
                                            .pointerInput(sentence.english + "allTap") {
                                                detectTapGestures(
                                                    onDoubleTap = {
                                                        // 1. کلید جمله در حال تلفظ را تنظیم کنید
                                                        speakingSentenceKey = sentence.english

                                                        // 2. فراخوانی speakEnglish با onDone Callback
                                                        speakEnglish(sentence.english) {
                                                            // 3. پس از اتمام تلفظ (onDone) کلید را null کنید.
                                                            speakingSentenceKey = null
                                                        }
                                                    }
                                                )
                                            }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(5.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            // مستطیل آبی رنگ (جمله‌ی فارسی)
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF62ABFF), shape = RoundedCornerShape(8.dp))
                                                    .padding(5.dp)
                                            ) {
                                                Text(
                                                    text = sentence.persian,
                                                    color = Color.Black,
                                                    fontSize = 16.sp,
                                                    textAlign = TextAlign.Right,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            // 🌟 ردیف آیکون‌ها و متن موضوع
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 4.dp)
                                                    .background(Color(0xFF009688).copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp))
                                                    .padding(vertical = 2.dp, horizontal = 0.dp)
                                            ) {
                                                // 1. شماره جمله (سمت راست)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = (index + 1).toString(),
                                                        color = Color(0xFF4D3D38),
                                                        fontSize = 20.sp
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                }

                                                // 2. متن موضوع (مرکز - اشغال کننده فضای باقی‌مانده)
                                                Text(
                                                    text = "موضوع: ${sentence.topic}",
                                                    modifier = Modifier.weight(1f),
                                                    color = Color(0xFF6C6125),
                                                    fontSize = 8.sp,
                                                    textAlign = TextAlign.Center
                                                )

                                                // 3. ردیف آیکون‌ها (سمت چپ)
                                                Row(verticalAlignment = Alignment.CenterVertically) {

                                                    Spacer(modifier = Modifier.width(8.dp)) // فاصله دهنده اولیه

                                                    // 1. آیکون انتقال به بالا (فلش بالا - آبی)
                                                    IconButton(onClick = { showMoveDialog = true }) {
                                                        Icon(
                                                            imageVector = Icons.Default.ArrowUpward,
                                                            contentDescription = "انتقال جمله به بالا",
                                                            tint = Color(0xFF043681),
                                                            modifier = Modifier.size(32.dp)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.width(0.dp))

                                                    // 2. آیکون حذف (سطل زباله - قرمز تیره)
                                                    IconButton(onClick = { showDeleteDialog = true }) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "حذف جمله",
                                                            tint = Color(0xFF851D1D),
                                                            modifier = Modifier.size(28.dp)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.width(8.dp))

                                                    // 3. آیکون ویرایش (قلم - زرد تیره)
                                                    IconButton(onClick = { showEditDialog = true }) {
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = "ویرایش جمله",
                                                            tint = Color(0xFF363633),
                                                            modifier = Modifier.size(28.dp)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.width(8.dp))

                                                    // 4. آیکون انتقال به پایین (فلش پایین - قرمز)
                                                    IconButton(onClick = { showMoveToBottomDialog = true }) {
                                                        Icon(
                                                            imageVector = Icons.Default.ArrowDownward,
                                                            contentDescription = "انتقال جمله به پایین",
                                                            tint = Color(0xFF64560B),
                                                            modifier = Modifier.size(32.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // مستطیل بنفش رنگ (جمله‌ی انگلیسی)
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFFAE82B6), shape = RoundedCornerShape(8.dp))
                                                    // ✅ تغییر: border ابتدا اعمال شد تا فضای padding را نیز شامل شود
                                                    .border(
                                                        width = if (speakingSentenceKey == sentence.english) 3.dp else 0.dp,
                                                        color = Color(0xFFFADE02), // زرد
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(6.dp) // <- padding پس از border اعمال شد
                                            ) {
                                                Text(
                                                    text = sentence.english.replaceFirstChar {
                                                        if (it.isLowerCase()) it.titlecase() else it.toString()
                                                    },
                                                    color = Color.Black,
                                                    fontSize = 18.sp,
                                                    // 🌟 لمس طولانی و دوبار کلیک روی خود Text انگلیسی
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .pointerInput(sentence.english) {
                                                            detectTapGestures(
                                                                // ✅ FIX: اعمال منطق Callback به onDoubleTap
                                                                onDoubleTap = {
                                                                    speakingSentenceKey = sentence.english
                                                                    speakEnglish(sentence.english) {
                                                                        speakingSentenceKey = null
                                                                    }
                                                                },
                                                                onLongPress = { offset ->
                                                                    textLayoutResult?.let { layoutResult ->
                                                                        val characterIndex = layoutResult.getOffsetForPosition(offset)
                                                                        val wordBoundary = layoutResult.getWordBoundary(characterIndex)
                                                                        val selectedWord = sentence.english.substring(wordBoundary.start, wordBoundary.end).trim()

                                                                        if (selectedWord.isNotBlank() && selectedWord.any { it.isLetter() }) {
                                                                            zoomedWord = selectedWord
                                                                        }
                                                                    }
                                                                }
                                                            )
                                                        },
                                                    onTextLayout = { result -> textLayoutResult = result },
                                                    style = LocalTextStyle.current.copy(
                                                        textAlign = TextAlign.Left,
                                                        textDirection = TextDirection.Ltr
                                                    )
                                                )
                                            }

                                        }


                                    }


                                    if (showEditDialog) {
                                        var editedPersian by remember { mutableStateOf(sentence.persian) }
                                        var editedEnglish by remember { mutableStateOf(sentence.english) }
                                        var editedTopic by remember { mutableStateOf(sentence.topic) }

                                        AlertDialog(
                                            onDismissRequest = { showEditDialog = false },
                                            title = { Text("ویرایش جمله", color = Color.White) },
                                            text = {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(Color(0xFF949A99))
                                                        .padding(16.dp)
                                                ) {
                                                    OutlinedTextField(
                                                        value = editedPersian,
                                                        onValueChange = { editedPersian = it },
                                                        label = { Text("متن فارسی") },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Right)
                                                    )
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    OutlinedTextField(
                                                        value = editedEnglish,
                                                        onValueChange = { editedEnglish = it },
                                                        label = { Text("متن انگلیسی") },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        textStyle = LocalTextStyle.current.copy(
                                                            textAlign = TextAlign.Left,
                                                            textDirection = TextDirection.Ltr
                                                        )
                                                    )
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    OutlinedTextField(
                                                        value = editedTopic,
                                                        onValueChange = { editedTopic = it },
                                                        label = { Text("موضوع") },
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                }

                                            },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    val originalIndex = allSentences.indexOf(sentence)
                                                    if (originalIndex != -1) {
                                                        val updatedSentence = sentence.copy(
                                                            persian = editedPersian,
                                                            english = editedEnglish,
                                                            topic = editedTopic.trim()
                                                        )

                                                        allSentences[originalIndex] = updatedSentence
                                                        forceUpdateKey++

                                                        coroutineScope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                saveSentences(context, savedUri, allSentences)
                                                            }
                                                        }
                                                        Toast.makeText(context, "جمله ویرایش و ذخیره شد", Toast.LENGTH_SHORT).show()
                                                    }
                                                    showEditDialog = false
                                                }) { Text("ذخیره", color = Color.White) }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showEditDialog = false }) {
                                                    Text("لغو", color = Color.White)
                                                }
                                            },
                                            containerColor = Color(0xFF053D69)
                                        )
                                    }

                                    if (showDeleteDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showDeleteDialog = false },
                                            title = { Text("حذف جمله", color = Color.White) },
                                            text = { Text("آیا می‌خواهید این جمله حذف شود؟", color = Color.White) },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    val originalIndex = allSentences.indexOf(sentence)
                                                    if (originalIndex != -1) {
                                                        allSentences.removeAt(originalIndex)
                                                        forceUpdateKey++

                                                        coroutineScope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                saveSentences(context, savedUri, allSentences)
                                                            }
                                                        }
                                                        Toast.makeText(context, "جمله حذف شد", Toast.LENGTH_SHORT).show()
                                                    }
                                                    showDeleteDialog = false
                                                }) { Text("بله", color = Color.White) }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showDeleteDialog = false }) {
                                                    Text("نه", color = Color.White)
                                                }
                                            },
                                            containerColor = Color(0xFF9B5858)
                                        )
                                    }

                                    if (showMoveDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showMoveDialog = false },
                                            title = { Text("انتقال جمله", color = Color.White) },
                                            text = { Text("آیا می‌خواهید این جمله به ابتدای لیست منتقل شود؟", color = Color.White) },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    val originalIndex = allSentences.indexOf(sentence)
                                                    if (originalIndex != -1) {
                                                        val itemToMove = allSentences.removeAt(originalIndex)
                                                        allSentences.add(0, itemToMove)
                                                        forceUpdateKey++

                                                        coroutineScope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                saveSentences(context, savedUri, allSentences)
                                                            }
                                                        }
                                                        Toast.makeText(context, "جمله به ابتدای لیست منتقل شد. موقعیت صفحه حفظ شد.", Toast.LENGTH_LONG).show()
                                                    }
                                                    showMoveDialog = false
                                                }) { Text("بله", color = Color.White) }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showMoveDialog = false }) {
                                                    Text("نه", color = Color.White)
                                                }
                                            },
                                            containerColor = Color(0xFF3A5BA0)
                                        )
                                    }

                                    // ✅ دیالوگ جدید برای انتقال به انتهای لیست
                                    if (showMoveToBottomDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showMoveToBottomDialog = false },
                                            title = { Text("انتقال جمله", color = Color.White) },
                                            text = { Text("آیا می‌خواهید این جمله به انتهای لیست منتقل شود؟", color = Color.White) },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    val originalIndex = allSentences.indexOf(sentence)
                                                    if (originalIndex != -1) {
                                                        val itemToMove = allSentences.removeAt(originalIndex)
                                                        allSentences.add(itemToMove) // انتقال به انتهای لیست
                                                        forceUpdateKey++

                                                        coroutineScope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                saveSentences(context, savedUri, allSentences)
                                                            }
                                                        }
                                                        Toast.makeText(context, "جمله به انتهای لیست منتقل شد.", Toast.LENGTH_LONG).show()
                                                    }
                                                    showMoveToBottomDialog = false
                                                }) { Text("بله", color = Color.White) }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showMoveToBottomDialog = false }) {
                                                    Text("نه", color = Color.White)
                                                }
                                            },
                                            containerColor = Color(0xFF3E703C) // رنگ سبز تیره برای تمایز
                                        )
                                    }
                                    // ✅ پایان دیالوگ جدید


                                }
                            }
                        }

                        // دکمه "رفتن به بالا"
                        FloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    // اسکرول به آیتم اول لیست فیلتر شده
                                    listState.scrollToItem(0)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomStart) // گوشه پایین سمت چپ
                                .padding(bottom = 24.dp, start = 8.dp)
                                .size(48.dp),
                            containerColor = Color(0xC11B6AA8) // آبی
                        ) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "رفتن به بالا", tint = Color.White)
                        }

                        // دکمه "رفتن به پایین"
                        FloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    // اسکرول به انتهای لیست فیلتر شده
                                    val lastIndex = filteredSentences.size - 1
                                    listState.scrollToItem(if (lastIndex >= 0) lastIndex else 0)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd) // گوشه پایین سمت راست
                                .padding(bottom = 24.dp, end = 8.dp)
                                .size(48.dp),
                            containerColor = Color(0xC164560B) // سبز
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "رفتن به پایین", tint = Color.White)
                        }
                    }
                    // **پایان بلوک تغییر یافته**
                }
            }

        }
    }
}

// ---------------- کامپوزبل‌های کمکی ----------------

@Composable
fun WordZoomScreen(word: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF001A33).copy(alpha = 0.95f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp)
        ) {
            Text(
                text = word,
                fontSize = 70.sp,
                color = Color(0xFFFFFFB3),
                textAlign = TextAlign.Center,
                style = LocalTextStyle.current.copy(
                    textDirection = TextDirection.Ltr
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "برای بستن و توقف تلفظ کلیک کنید",
                fontSize = 18.sp,
                color = Color.LightGray
            )
        }
    }
}

@Composable
fun DropdownMenuBox(
    label: String,
    selectedOption: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val labelColor = when (label) {
        "موضوع" -> Color(0xFF02FAE3)
        "سرعت تلفظ" -> Color(0xFF02FAE3)
        else -> Color.White
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { expanded = true }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp)
        ) {
            Text(
                text = label,
                color = labelColor,
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "انتخاب $label",
                tint = Color(0xFFF6DE04),
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = selectedOption,
                color = if (selectedOption.isNotEmpty()) Color(0xF0D2D9EE) else Color.White,
                fontSize = 16.sp
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Color(0xFF314CE3)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}