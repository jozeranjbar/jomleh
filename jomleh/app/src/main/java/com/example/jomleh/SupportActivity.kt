package com.example.jomleh

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val DEFAULT_TOPIC = "موضوع را انتخاب کنید"
private const val CUSTOM_TOPIC_OPTION = "موضوع جدید (تایپ کنید)"
private const val TOPICS_PREF_KEY = "custom_topics_list"

class SupportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SupportScreen(onBackToMain = { finish() })
        }
    }
}

private val initialTopicsList = listOf(
    "عبارات", "تفاوت جمله‌ها", "عمومی", "احوالپرسی و آشنایی", "احساسات",
    "موقعیت‌های اضطراری", "سفر و حمل و نقل", "هتل و رستوران", "خرید",
    "کار و آموزش", "بهداشت و بیماری", "تفریح و ورزش"
)

@Composable
fun SupportScreen(onBackToMain: () -> Unit) {
    var persianText by remember { mutableStateOf("") }
    var englishText by remember { mutableStateOf("") }
    var selectedTopic by remember { mutableStateOf(DEFAULT_TOPIC) }
    var isCustomTopicMode by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showManageTopicsDialog by remember { mutableStateOf(false) }
    var sentenceCount by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val customTopicFocusRequester = remember { FocusRequester() }

    val sharedPref = remember {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    var savedUri by remember {
        mutableStateOf(sharedPref.getString("json_file_uri", null)?.toUri())
    }

    var topics by remember { mutableStateOf(initialTopicsList + CUSTOM_TOPIC_OPTION) }

    LaunchedEffect(Unit) {
        val savedTopics = loadTopicsFromPrefs(context)
        if (savedTopics.isNotEmpty()) {
            val finalTopics = savedTopics.filter { it != CUSTOM_TOPIC_OPTION } + CUSTOM_TOPIC_OPTION
            topics = finalTopics
        }
    }


    LaunchedEffect(savedUri) {
        savedUri?.let { uri ->
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)

                sentenceCount = loadSentenceCount(context, uri)

            } catch (e: SecurityException) {
                sharedPref.edit { remove("json_file_uri") }
                savedUri = null
                sentenceCount = 0
            } catch (e: Exception) {
                sharedPref.edit { remove("json_file_uri") }
                savedUri = null
                sentenceCount = 0
            }
        }
    }

    val createFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri: Uri? ->
            uri?.let {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(it, takeFlags)
                    sharedPref.edit { putString("json_file_uri", it.toString()) }
                    savedUri = it

                    coroutineScope.launch(Dispatchers.IO) {
                        val initialSentences = loadSentencesFromRaw(context, R.raw.jomleh)
                        writeJsonArray(context, it, initialSentences.toMutableList())

                        val topicToSave = selectedTopic.trim()

                        if (persianText.isNotBlank() && englishText.isNotBlank() && topicToSave.isNotBlank() && topicToSave != DEFAULT_TOPIC) {

                            if (isCustomTopicMode) {
                                topics = saveNewTopic(context, topics, topicToSave)
                            }

                            saveToJson(context, it, persianText, englishText, topicToSave) {
                                persianText = ""
                                englishText = ""
                                selectedTopic = DEFAULT_TOPIC
                                isCustomTopicMode = false
                                sentenceCount = loadSentenceCount(context, it)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "✅ فایل پشتیبان ایجاد و جملات پیش‌فرض (${initialSentences.size} جمله) منتقل شدند.", Toast.LENGTH_LONG).show()
                                sentenceCount = loadSentenceCount(context, it)
                                selectedTopic = DEFAULT_TOPIC
                                isCustomTopicMode = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "❌ خطا در گرفتن دسترسی و ذخیره‌سازی فایل.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(it, takeFlags)
                } catch (e: Exception) {
                    Toast.makeText(context, "❌ خطا در گرفتن مجوز خواندن فایل بازیابی.", Toast.LENGTH_SHORT).show()
                    return@rememberLauncherForActivityResult
                }

                coroutineScope.launch(Dispatchers.IO) {
                    restoreFromJson(context, it) {
                        savedUri?.let { savedFileUri ->
                            sentenceCount = loadSentenceCount(context, savedFileUri)
                        }
                    }
                }
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF757474))
            .padding(6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "تعداد جمله‌ها : $sentenceCount",
                color = Color(0xFF4E342E),
                fontSize = 20.sp,
                lineHeight = 28.sp,
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = onBackToMain,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E590F)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        ) {
            Text("بازگشت به صفحه اصلی", color = Color.White, fontSize = 12.sp)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextField(
                value = persianText,
                onValueChange = { persianText = it },
                placeholder = {
                    Text(
                        "جمله فارسی را وارد کنید",
                        textAlign = TextAlign.Right,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 18.sp,
                        color = Color.Gray
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .shadow(10.dp, RoundedCornerShape(16.dp))
                    .background(Color(0xFF9AD2EE), RoundedCornerShape(16.dp)),
                textStyle = LocalTextStyle.current.copy(
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp,
                    lineHeight = 26.sp
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                ),
                shape = RoundedCornerShape(16.dp),
                singleLine = false
            )

            TextField(
                value = englishText,
                onValueChange = {
                    englishText = it.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                    }
                },
                placeholder = {
                    Text(
                        "Enter English sentence",
                        textAlign = TextAlign.Left,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 18.sp,
                        color = Color.Gray
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .shadow(10.dp, RoundedCornerShape(16.dp))
                    .background(Color(0xFFF6DCA1), RoundedCornerShape(16.dp)),
                textStyle = LocalTextStyle.current.copy(
                    textAlign = TextAlign.Start,
                    textDirection = TextDirection.Ltr,
                    fontSize = 18.sp,
                    lineHeight = 26.sp
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                ),
                shape = RoundedCornerShape(16.dp),
                singleLine = false
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .shadow(6.dp, RoundedCornerShape(12.dp))
                    .background(Color(0xFFBBAAE3), shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (isCustomTopicMode) {
                    TextField(
                        value = selectedTopic,
                        onValueChange = { selectedTopic = it.trimStart() },
                        placeholder = { Text("موضوع دلخواه را تایپ کنید...", color = Color.Gray, fontSize = 18.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(customTopicFocusRequester),
                        textStyle = LocalTextStyle.current.copy(
                            textAlign = TextAlign.Right,
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                            color = Color.Black
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            errorIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        label = { Text("موضوع دلخواه", color = Color(0xFF4A148C), fontSize = 16.sp) }
                    )

                    LaunchedEffect(isCustomTopicMode) {
                        if (isCustomTopicMode) {
                            customTopicFocusRequester.requestFocus()
                        }
                    }

                } else {
                    DropdownMenuBox(
                        label = "موضوع",
                        selectedOption = selectedTopic,
                        options = topics,
                        onOptionSelected = { newTopic ->
                            if (newTopic == CUSTOM_TOPIC_OPTION) {
                                isCustomTopicMode = true
                                selectedTopic = ""
                            } else {
                                isCustomTopicMode = false
                                selectedTopic = newTopic
                            }
                        },
                        textColor = Color.Black,
                        labelColor = Color(0xFF4A148C)
                    )
                }
            }


            Spacer(modifier = Modifier.height(16.dp))

            // بلوک Row برای دکمه‌های "ذخیره جمله" و "مدیریت موضوعات"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // دکمه "ذخیره جمله" (کوچک‌تر شده)
                Button(
                    onClick = {
                        val topicToSave = if (isCustomTopicMode) selectedTopic.trim() else selectedTopic

                        if (persianText.isNotBlank() && englishText.isNotBlank() && topicToSave.isNotBlank() && topicToSave != DEFAULT_TOPIC) {

                            if (savedUri == null) {
                                selectedTopic = topicToSave
                                createFileLauncher.launch("jomleh.json")
                            } else {
                                coroutineScope.launch(Dispatchers.IO) {

                                    if (isCustomTopicMode) {
                                        topics = saveNewTopic(context, topics, topicToSave)
                                    }

                                    saveToJson(context, savedUri!!, persianText, englishText, topicToSave) {
                                        persianText = ""
                                        englishText = ""
                                        selectedTopic = DEFAULT_TOPIC
                                        isCustomTopicMode = false
                                        sentenceCount = loadSentenceCount(context, savedUri!!)
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(context, "لطفاً همه فیلدها (فارسی، انگلیسی، موضوع) را پر کنید.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                    shape = RoundedCornerShape(92.dp),
                    modifier = Modifier.weight(0.6f)
                ) {
                    Text("ذخیره جمله", color = Color(0xFFFDE300), fontSize = 20.sp)
                }

                Spacer(modifier = Modifier.width(8.dp)) // فاصله

                // دکمه "مدیریت موضوعات"
                Button(
                    onClick = { showManageTopicsDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                    shape = RoundedCornerShape(92.dp),
                    modifier = Modifier.weight(0.4f)
                ) {
                    Text("مدیریت موضوعات", color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }


            Button(
                onClick = {
                    if (savedUri == null) {
                        Toast.makeText(context, "❌ ابتدا یک فایل پشتیبان با دکمه 'ذخیره جمله' ایجاد کنید تا مسیر فایل اصلی مشخص شود.", Toast.LENGTH_LONG).show()
                    } else {
                        openFileLauncher.launch(arrayOf("application/json"))
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA000)),
                shape = RoundedCornerShape(92.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
            ) {
                Text("بازیابی / ادغام جملات", color = Color.White, fontSize = 20.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { showHelpDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 65.dp)
        ) {
            Text("درباره برنامه", color = Color(0xFF151511), fontSize = 18.sp)
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(

                        """
       ✨ قابلیت‌های اپلیکیشن jomleh

۱. 🗂️ پرکاربردترین جملات در موضوعات متنوع
۲. 💬 توضیحات و نکات گرامری برخی جملات
۳. ➕ افزودن جمله به لیست، به‌صورت تکی یا گروهی
۴. 🔈 تلفظ کل جمله با دو بار ضربه روی مستطیل دوجمله‌ای
۵. 🔊 تلفظ پی‌در‌پی کلمات با نگه داشتن انگشت روی آن‌ها
۶. 🔍 جستجوی کلمات و جملات فارسی و انگلیسی داخل برنامه
۷. ⬆️⬇️ اسکرول سریع به اولین یا آخرین جمله صفحه
۸. 📌 انتقال جملات دلخواه به ابتدا یا انتهای لیست
۹. ✏️ ویرایش و حذف جملات و موضوعات
۱۰ . 📴 آفلاین و بدون نیاز به اینترنت
۱۱. 💾 ذخیره خودکار تنظیمات (اسکرول، سرعت، موضوع)
۱۲. 🧩 رایگان و با قابلیت به‌روز‌رسانی منظم
۱۳. 🔄 پشتیبان‌گیری و بازیابی کامل داده‌ها

📬 ارتباط با ما
✉️ ایمیل: ranjberan@gmail.com
📱 تلگرام: wajehha@

""".trimIndent(),
                        fontSize = 18.sp,
                        lineHeight = 28.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showHelpDialog = false }) {
                    Text("بستن", fontSize = 18.sp)
                }
            }
        )
    }

    // کامپوننت مدیریت موضوعات
    if (showManageTopicsDialog) {
        ManageTopicsDialog(
            context = context,
            currentTopics = topics.filter { it != DEFAULT_TOPIC && it != CUSTOM_TOPIC_OPTION },
            onDismiss = { showManageTopicsDialog = false },
            onTopicsUpdated = { newTopics ->
                topics = newTopics + CUSTOM_TOPIC_OPTION
            }
        )
    }
}

// --------------------------------------------------------------------------------
// 🔹 کامپوننت‌ها و توابع مدیریت موضوعات
// --------------------------------------------------------------------------------

@Composable
fun ManageTopicsDialog(
    context: Context,
    currentTopics: List<String>,
    onDismiss: () -> Unit,
    onTopicsUpdated: (List<String>) -> Unit
) {
    var topicToEdit by remember { mutableStateOf<String?>(null) }
    var newTopicName by remember { mutableStateOf("") }
    // State برای نگهداری موضوعی که باید حذف شود و نمایش تاییدیه
    var topicToDelete by remember { mutableStateOf<String?>(null) }

    val topicsState = remember { mutableStateListOf(*currentTopics.toTypedArray()) }

    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(currentTopics) {
        topicsState.clear()
        topicsState.addAll(currentTopics)
        onDispose {}
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مدیریت موضوعات", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (topicsState.isEmpty()) {
                    Text("موضوع سفارشی برای مدیریت وجود ندارد.", fontSize = 16.sp, color = Color.Gray)
                }
                topicsState.forEach { topic ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(topic, fontSize = 16.sp, modifier = Modifier.weight(1f))

                        Button(
                            onClick = {
                                topicToEdit = topic
                                newTopicName = topic
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFDD835)),
                            modifier = Modifier.padding(horizontal = 4.dp).width(60.dp)
                        ) {
                            Text("✏️", color = Color.Black)
                        }

                        Button(
                            onClick = {
                                // نمایش دیالوگ تأیید قبل از حذف
                                topicToDelete = topic
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            modifier = Modifier.width(60.dp)
                        ) {
                            Text("❌", color = Color.White)
                        }
                    }
                    // ⭐️ استفاده از HorizontalDivider
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("بستن")
            }
        }
    )

    // 🔑 AlertDialog تأیید حذف
    if (topicToDelete != null) {
        AlertDialog(
            onDismissRequest = { topicToDelete = null },
            title = { Text("تأیید حذف", textAlign = TextAlign.Right) },
            text = { Text("آیا مطمئن هستید که می‌خواهید موضوع \"${topicToDelete!!}\" را حذف کنید؟", textAlign = TextAlign.Right) },
            confirmButton = {
                Button(
                    onClick = {
                        val topic = topicToDelete!!
                        topicsState.remove(topic)
                        coroutineScope.launch(Dispatchers.IO) {
                            val updatedList = updateAndSaveTopics(context, topicsState.toList())
                            withContext(Dispatchers.Main) {
                                onTopicsUpdated(updatedList)
                                Toast.makeText(context, "موضوع '$topic' حذف شد.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        topicToDelete = null // بستن دیالوگ
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("حذف کن")
                }
            },
            dismissButton = {
                Button(onClick = { topicToDelete = null }) {
                    Text("لغو")
                }
            }
        )
    }

    // دیالوگ کوچک برای ویرایش نام موضوع
    if (topicToEdit != null) {
        AlertDialog(
            onDismissRequest = { topicToEdit = null },
            title = { Text("ویرایش موضوع", textAlign = TextAlign.Right) },
            text = {
                TextField(
                    value = newTopicName,
                    onValueChange = { newTopicName = it.trimStart() },
                    label = { Text("نام جدید") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val oldTopic = topicToEdit!!
                        val newName = newTopicName.trim()

                        if (newName.isBlank() || topicsState.any { it.equals(newName, ignoreCase = true) && it != oldTopic }) {
                            Toast.makeText(context, "نام موضوع معتبر نیست یا تکراری است.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val index = topicsState.indexOf(oldTopic)
                        if (index != -1) {
                            topicsState[index] = newName

                            coroutineScope.launch(Dispatchers.IO) {
                                val updatedList = updateAndSaveTopics(context, topicsState.toList())
                                withContext(Dispatchers.Main) {
                                    onTopicsUpdated(updatedList)
                                    updateSentencesTopic(context, newName, oldTopic)

                                    Toast.makeText(context, "موضوع '$oldTopic' به '$newName' تغییر کرد.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        topicToEdit = null
                    }
                ) {
                    Text("ذخیره")
                }
            },
            dismissButton = {
                Button(onClick = { topicToEdit = null }) {
                    Text("لغو")
                }
            }
        )
    }
}

@Composable
fun DropdownMenuBox(
    label: String,
    selectedOption: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    textColor: Color = Color.Black,
    labelColor: Color = Color.White
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { expanded = true }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp)
        ) {
            Text(
                text = label,
                color = labelColor,
                fontSize = 18.sp
            )

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "انتخاب $label",
                tint = Color.Black,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = selectedOption,
                color = if (selectedOption != DEFAULT_TOPIC) textColor else Color.Gray,
                fontSize = 18.sp
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

// --------------------------------------------------------------------------------
// 🔹 توابع کمکی مربوط به Shared Preferences
// --------------------------------------------------------------------------------

fun loadTopicsFromPrefs(context: Context): List<String> {
    return try {
        val sharedPref = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val jsonString = sharedPref.getString(TOPICS_PREF_KEY, null)
        if (jsonString.isNullOrBlank()) {
            initialTopicsList
        } else {
            val jsonArray = JSONArray(jsonString)
            (0 until jsonArray.length()).map { jsonArray.getString(it) }
        }
    } catch (_: Exception) {
        initialTopicsList
    }
}

fun updateAndSaveTopics(context: Context, topics: List<String>): List<String> {
    val finalTopics = topics.filter { it.isNotBlank() && it != CUSTOM_TOPIC_OPTION && it != DEFAULT_TOPIC }

    try {
        val jsonArray = JSONArray(finalTopics)
        val sharedPref = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        sharedPref.edit {
            putString(TOPICS_PREF_KEY, jsonArray.toString())
        }
    } catch (_: Exception) { /* خطا نادیده گرفته می‌شود */ }

    return finalTopics
}

fun saveNewTopic(context: Context, currentTopics: List<String>, newTopic: String): List<String> {
    val cleanTopic = newTopic.trim()
    if (cleanTopic.isBlank() || cleanTopic == CUSTOM_TOPIC_OPTION) return currentTopics

    val baseTopics = currentTopics
        .filter { it != CUSTOM_TOPIC_OPTION && it.equals(cleanTopic, ignoreCase = true).not() }
        .toMutableList()

    if (baseTopics.none { it.equals(cleanTopic, ignoreCase = true) }) {
        baseTopics.add(cleanTopic)
    }

    updateAndSaveTopics(context, baseTopics)

    return baseTopics + CUSTOM_TOPIC_OPTION
}

// --------------------------------------------------------------------------------
// 🔹 توابع JSON و فایل
// --------------------------------------------------------------------------------

fun loadSentencesFromRaw(context: Context, resourceId: Int): MutableList<JSONObject> {
    val list = mutableListOf<JSONObject>()
    try {
        val inputStream = context.resources.openRawResource(resourceId)
        inputStream.bufferedReader().use { reader ->
            val text = reader.readText()
            if (text.isNotBlank()) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    list.add(array.getJSONObject(i))
                }
            }
        }
    } catch (_: Exception) {}
    return list
}

suspend fun writeJsonArray(context: Context, uri: Uri, sentences: MutableList<JSONObject>) {
    withContext(Dispatchers.IO) {
        try {
            val finalArray = JSONArray(sentences)
            val compressedJson = finalArray.toString(0)
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(compressedJson.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun readJsonArray(context: Context, uri: Uri): MutableList<JSONObject> {
    val list = mutableListOf<JSONObject>()
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val text = input.bufferedReader().readText()
            if (text.isNotBlank()) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) list.add(array.getJSONObject(i))
            }
        }
    } catch (_: Exception) {}
    return list
}

suspend fun restoreFromJson(context: Context, restoreUri: Uri, onSuccess: () -> Unit) {
    withContext(Dispatchers.IO) {
        val sharedPref = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedUri = sharedPref.getString("json_file_uri", null)?.toUri()

        if (savedUri == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "❌ ابتدا یک جمله ذخیره کنید تا فایل اصلی ساخته شود.", Toast.LENGTH_LONG).show()
            }
            return@withContext
        }

        try {
            val currentSentences = readJsonArray(context, savedUri)
            val newSentences = readJsonArray(context, restoreUri)

            if (newSentences.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "❌ فایل JSON انتخاب شده حاوی جمله‌ای نبود.", Toast.LENGTH_LONG).show()
                }
                return@withContext
            }

            var addedCount = 0
            val allSentences = currentSentences.toMutableList()

            for (newSentence in newSentences) {
                val pNew = newSentence.optString("p").trim().replace("\\s+".toRegex(), " ")
                val eNew = newSentence.optString("e").trim().replace("\\s+".toRegex(), " ")

                val isDuplicate = allSentences.any {
                    val pExist = it.optString("p").trim().replace("\\s+".toRegex(), " ")
                    val eExist = it.optString("e").trim().replace("\\s+".toRegex(), " ")
                    pExist == pNew && eExist.equals(eNew, ignoreCase = true)
                }

                if (!isDuplicate) {
                    allSentences.add(0, newSentence)
                    addedCount++
                }
            }

            val finalArray = JSONArray(allSentences)
            val compressedJson = finalArray.toString(0)
            context.contentResolver.openOutputStream(savedUri, "w")?.use { output ->
                output.write(compressedJson.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "✅ $addedCount جمله جدید اضافه شد. تعداد کل: ${allSentences.size}", Toast.LENGTH_LONG).show()
                onSuccess()
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "❌ خطا در پردازش فایل بازیابی.", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        }
    }
}

suspend fun saveToJson(context: Context, uri: Uri, persian: String, english: String, topic: String, onSuccess: () -> Unit) {
    withContext(Dispatchers.IO) {
        try {
            val sentences = readJsonArray(context, uri)

            val persianClean = persian.trim().replace("\\s+".toRegex(), " ")
            val englishClean = english.trim().replace("\\s+".toRegex(), " ")
            val topicClean = topic.trim()

            val isDuplicate = sentences.any {
                val p = it.optString("p").trim().replace("\\s+".toRegex(), " ")
                val e = it.optString("e").trim().replace("\\s+".toRegex(), " ")
                p == persianClean && e.equals(englishClean, ignoreCase = true)
            }

            if (isDuplicate) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "❌ این جمله (فارسی و انگلیسی) قبلاً ذخیره شده است.", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            val newSentence = JSONObject().apply {
                put("p", persianClean)
                put("e", englishClean)
                put("t", topicClean)
            }
            sentences.add(0, newSentence)

            val newArray = JSONArray(sentences)
            val compressedJson = newArray.toString(0)
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(compressedJson.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "✅ جمله ذخیره شد (تعداد کل: ${sentences.size})", Toast.LENGTH_SHORT).show()
                onSuccess()
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "❌ خطا در ذخیره فایل پشتیبان.", Toast.LENGTH_SHORT).show()
            }
            e.printStackTrace()
        }
    }
}

fun loadSentenceCount(context: Context, uri: Uri): Int {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val text = input.bufferedReader().readText()
            if (text.isNotBlank()) JSONArray(text).length() else 0
        } ?: 0
    } catch (_: Exception) { 0 }
}

suspend fun updateSentencesTopic(context: Context, newTopic: String, oldTopic: String) {
    withContext(Dispatchers.IO) {
        try {
            val sharedPref = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedUri = sharedPref.getString("json_file_uri", null)?.toUri()

            savedUri?.let { uri ->
                val sentences = readJsonArray(context, uri)
                var updatedCount = 0

                sentences.forEach { json ->
                    if (json.optString("t").trim().equals(oldTopic.trim(), ignoreCase = true)) {
                        json.put("t", newTopic.trim())
                        updatedCount++
                    }
                }

                if (updatedCount > 0) {
                    writeJsonArray(context, uri, sentences)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "موضوع $updatedCount جمله به‌روزرسانی شد.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}