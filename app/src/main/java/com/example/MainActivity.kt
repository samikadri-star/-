package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class AppState {
    IDLE,
    FILE_LOADED,
    CONVERTING,
    SUCCESS,
    ERROR
}

enum class ParsingEngine {
    SMART_VALLEY_DETECTOR, // Smart fixed-width dynamic detector (Recommended)
    SPACE_SPLITTER          // Simple regex 2+ spaces separator
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                // Set entire App Direction to Right-to-Left (RTL) for standard Arabic UX
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            ConverterDashboard()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConverterDashboard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // State definitions
    var currentAppState by remember { mutableStateOf(AppState.IDLE) }
    var selectedEngine by remember { mutableStateOf(ParsingEngine.SMART_VALLEY_DETECTOR) }
    
    // File state
    var sourceBytes by remember { mutableStateOf<ByteArray?>(null) }
    var originalFileName by remember { mutableStateOf("") }
    var decodedContent by remember { mutableStateOf("") }
    var estimatedOutputTitle by remember { mutableStateOf("") }
    
    // Excel generation states
    var parsedRowsCount by remember { mutableStateOf(0) }
    var parsedColsCount by remember { mutableStateOf(0) }
    var previewRows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var generatedExcelBytes by remember { mutableStateOf<ByteArray?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    // File Pick Launcher
    val getFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    currentAppState = AppState.CONVERTING
                    
                    // Retrieve standard metadata info
                    val name = getFileNameFromUri(context, uri)
                    originalFileName = name
                    
                    // Read data fully
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        throw Exception("الملف فارغ أو لا يمكن قراءته!")
                    }
                    sourceBytes = bytes
                    
                    // Decode bytes using ISO-8859-6
                    val text = ReportConverter.decodeISO88596(bytes)
                    decodedContent = text
                    
                    // Extract suitable title for target Excel
                    estimatedOutputTitle = ReportConverter.extractReportTitle(text, name)
                    
                    currentAppState = AppState.FILE_LOADED
                } catch (e: Exception) {
                    errorMessage = e.message ?: "حدث خطأ غير متوقع أثناء تحميل الملف."
                    currentAppState = AppState.ERROR
                }
            }
        }
    }

    // File Save Launcher (Create XLSX file)
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    generatedExcelBytes?.let { bytes ->
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(bytes)
                            }
                        }
                        Toast.makeText(context, "تم حفظ الملف بنجاح! 💾", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "فشل حفظ الملف: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Core Mandatory Requirement: Developer Credit Badge at the very top
        item {
            DeveloperHeader()
        }

        // Action Status or dynamic screens
        when (currentAppState) {
            AppState.IDLE -> {
                item {
                    UploadZone(onPickFile = { getFileLauncher.launch("*/*") })
                }
                item {
                    InfoGuidelinesCard()
                }
            }
            AppState.FILE_LOADED -> {
                item {
                    FileLoadedCard(
                        fileName = originalFileName,
                        outputTitle = estimatedOutputTitle,
                        onOutputTitleChange = { estimatedOutputTitle = it },
                        selectedEngine = selectedEngine,
                        onEngineChange = { selectedEngine = it },
                        onConvert = {
                            coroutineScope.launch {
                                try {
                                    currentAppState = AppState.CONVERTING
                                    
                                    // Parse data based on selected strategy
                                    val finalRows = withContext(Dispatchers.Default) {
                                        if (selectedEngine == ParsingEngine.SMART_VALLEY_DETECTOR) {
                                            ReportConverter.parseWithSmartValleyDetector(decodedContent)
                                        } else {
                                            ReportConverter.parseWithSpaceSplitter(decodedContent)
                                        }
                                    }
                                    
                                    if (finalRows.isEmpty()) {
                                        throw Exception("فشل استخراج أي صفوف صالحة من التقرير!")
                                    }
                                    
                                    parsedRowsCount = finalRows.size
                                    parsedColsCount = finalRows.maxOf { it.size }
                                    previewRows = finalRows.take(15) // Preview first 15 rows for UI comfort
                                    
                                    // Generate Excel sheet via Apache POI
                                    val excelBytes = withContext(Dispatchers.Default) {
                                        ReportConverter.generateExcel(finalRows)
                                    }
                                    
                                    generatedExcelBytes = excelBytes
                                    currentAppState = AppState.SUCCESS
                                    
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "حدث خطأ غير متوقع أثناء المعالجة."
                                    currentAppState = AppState.ERROR
                                }
                            }
                        },
                        onReset = {
                            sourceBytes = null
                            decodedContent = ""
                            originalFileName = ""
                            currentAppState = AppState.IDLE
                        }
                    )
                }
            }
            AppState.CONVERTING -> {
                item {
                    LoadingCard()
                }
            }
            AppState.SUCCESS -> {
                item {
                    SuccessActionCard(
                        outputName = estimatedOutputTitle,
                        rows = parsedRowsCount,
                        cols = parsedColsCount,
                        onSaveFile = {
                            saveFileLauncher.launch("$estimatedOutputTitle.xlsx")
                        },
                        onShareFile = {
                            val bytes = generatedExcelBytes
                            if (bytes != null) {
                                shareExcelFile(context, bytes, estimatedOutputTitle)
                            } else {
                                Toast.makeText(context, "البيانات فارغة", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onReset = {
                            sourceBytes = null
                            decodedContent = ""
                            originalFileName = ""
                            generatedExcelBytes = null
                            previewRows = emptyList()
                            currentAppState = AppState.IDLE
                        }
                    )
                }

                item {
                    TablePreviewCard(rows = previewRows)
                }
            }
            AppState.ERROR -> {
                item {
                    ErrorCard(
                        message = errorMessage,
                        onRetry = {
                            if (sourceBytes != null) {
                                currentAppState = AppState.FILE_LOADED
                            } else {
                                currentAppState = AppState.IDLE
                            }
                        },
                        onReset = {
                            sourceBytes = null
                            decodedContent = ""
                            originalFileName = ""
                            currentAppState = AppState.IDLE
                        }
                    )
                }
            }
        }
    }
}

/**
 * Beautiful editorial-styled header with high distinction, bold typography, and centered text.
 * Satisfies: "تطوير سامي القادري" and "Legacy System Utility" with Editorial Serif headings.
 */
@Composable
fun DeveloperHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "LEGACY SYSTEM UTILITY",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center
        )
        Text(
            text = "محول التقارير للـ Excel",
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.secondary,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center
        )
        Text(
            text = "تطوير سامي القادري",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Editorial design upload container featuring a customized rounded shape, elegant font weights, 
 * soft purple container background (SoftTealAlpha), and clear accessibility indicators.
 */
@Composable
fun UploadZone(onPickFile: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clickable { onPickFile() },
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        border = BorderStroke(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = "تحميل ملف",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "اختر ملف التقرير القديم",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "كشف حساب • ميزان مراجعة • كشف حركة بترميز ISO-8859-6",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Beautiful editorial-styled guidelines which list locked features as styled toggles
 * and introduces an automatic label-recognition highlight bar to mimic the Design HTML perfectly.
 */
@Composable
fun InfoGuidelinesCard() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Card 1: Smart Excel settings indicator list (Mimics the Design HTML container)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "إعدادات الإخراج الذكي (EXCEL)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.5.sp,
                    fontFamily = FontFamily.SansSerif
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                // Item 1: RTL Setup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "اتجاه ورقة العمل (عربي RTL)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // Styled static Pill toggle
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .align(Alignment.CenterStart)
                        )
                    }
                }
                
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                
                // Item 2: Bold Formatting
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "تنسيق الخط عريض بالكامل لدعم القراءة",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // Styled static Pill toggle
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .align(Alignment.CenterStart)
                        )
                    }
                }
                
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                
                // Item 3: Auto Column sizing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ضبط تلقائي لعرض الأعمدة ما عدا الأول",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // Styled static Pill toggle
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .align(Alignment.CenterStart)
                        )
                    }
                }
            }
        }
        
        // Card 2: Colored smart naming banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondary)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Article,
                    contentDescription = "التسمية الذكية",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "التسمية التلقائية الذكية",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "يتعرف النظام تلقائياً على تقارير كشف حساب - ميزان مراجعة - كشف حركة، ويقترح تسمية مثالية.",
                    fontSize = 12.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/**
 * Setup output title name and strategy engine.
 */
@Composable
fun FileLoadedCard(
    fileName: String,
    outputTitle: String,
    onOutputTitleChange: (String) -> Unit,
    selectedEngine: ParsingEngine,
    onEngineChange: (ParsingEngine) -> Unit,
    onConvert: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header showing selected file
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Article,
                    contentDescription = "تم اختيار الملف",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "تم تحميل ملف بنجاح",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = fileName,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Divider()

            // Suggested Excel Out File Name (Autocompleted & Editable)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "اسم ملف الإكسل الناتج:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = outputTitle,
                    onValueChange = onOutputTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "تحرير لاسم الملف"
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                Text(
                    text = "* يستخرج النظام الاسم ذكياً من محتوى التقرير (مثل كشف حساب أو ميزان مراجعة مع إضافة أرقام الحسابات أو الاسم الحركي والتواريخ المكتشفة).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            // Strategy tabs
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "طريقة استخراج البيانات وتحليل الأعمدة:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val activeWeight = 1f
                    
                    // Engine Smart Valley option
                    Button(
                        onClick = { onEngineChange(ParsingEngine.SMART_VALLEY_DETECTOR) },
                        modifier = Modifier.weight(activeWeight),
                        shape = RoundedCornerShape(8.dp),
                        colors = if (selectedEngine == ParsingEngine.SMART_VALLEY_DETECTOR) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        } else {
                            ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "مستخرج الجداول الذكي",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Simple Space splitter option
                    Button(
                        onClick = { onEngineChange(ParsingEngine.SPACE_SPLITTER) },
                        modifier = Modifier.weight(activeWeight),
                        shape = RoundedCornerShape(8.dp),
                        colors = if (selectedEngine == ParsingEngine.SPACE_SPLITTER) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        } else {
                            ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "مقسم المسافات البسيط",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Conversion and Reset Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("إلغاء", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        if (outputTitle.trim().isEmpty()) {
                            onOutputTitleChange("تقرير_اكسل_مستخرج")
                        }
                        onConvert()
                    },
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.CompareArrows,
                        contentDescription = "بدء التحويل"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("معالجة وتحويل للـ Excel", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * Conversion background task loader.
 */
@Composable
fun LoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "جاري تحليل الملف واستخراج البيانات...",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "نقوم بمعالجة ترميز ISO-8859-6 وبناء مصفوفة الجداول بدقة.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Excel File Action Buttons success screen state.
 */
@Composable
fun SuccessActionCard(
    outputName: String,
    rows: Int,
    cols: Int,
    onSaveFile: () -> Unit,
    onShareFile: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Success Emblem (Editorial style matching top logo assets)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "نجاح المعالجة",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                text = "اكتمل معالجة وتحويل التقرير بنجاح! 🎉",
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )

            // Basic stats columns
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("عدد الصفوف المستخرجة", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$rows صف", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("أعمدة البيانات المنسقة", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$cols أعمدة", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }

            // Target final Name badge
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Task,
                        contentDescription = "اسم الملف",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "$outputName.xlsx",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Save, Share, Open excel triggers formatted as beautiful rounded-full buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSaveFile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.Save, contentDescription = "حفظ الملف")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("حفظ ملف الـ Excel بالجهاز", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                OutlinedButton(
                    onClick = onShareFile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = "مشاركة")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("مشاركة الملف مع تطبيقات أخرى", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                TextButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "تحويل ملف آخر بدقة جديدة", 
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Live Table Preview Card displaying the column rows directly in GUI so user feels secure!
 */
@Composable
fun TablePreviewCard(rows: List<List<String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.GridView,
                    contentDescription = "معاينة",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "معاينة عيّنة من الجداول المستخرجة:",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Grid scroll horizontal + vertical container
            val horizontalScrollState = rememberScrollState()
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .horizontalScroll(horizontalScrollState)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .wrapContentWidth()
                ) {
                    for ((rIdx, rowData) in rows.withIndex()) {
                        val isHeader = (rIdx == 0)
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isHeader) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else if (rIdx % 2 == 1) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for ((cIdx, cellValue) in rowData.withIndex()) {
                                Box(
                                    modifier = Modifier
                                        // "وضبط الأعمدة تلقائي ما عدا العمود الاول"
                                        // Apply first column distinct structure visually
                                        .width(if (cIdx == 0) 100.dp else 140.dp)
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = if (isHeader) Alignment.Center else Alignment.CenterStart
                                ) {
                                    Text(
                                        text = cellValue,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold, // "الخط عريض"
                                        color = if (isHeader) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            Text(
                text = "* تعرض المعاينة أول 15 صفا فقط للسرعة، تم ضبط جميع خطوط ملف الإكسل الناتج كخط عريض والمقاسات مبرمجة تماماً لدعم تقارير ميزان المراجعة والحسابات.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                lineHeight = 15.sp
            )
        }
    }
}

/**
 * Handle conversions errors.
 */
@Composable
fun ErrorCard(message: String, onRetry: () -> Unit, onReset: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "فشل",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp)
            )

            Text(
                text = "تعذر تحويل التقرير",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )

            Text(
                text = message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f))
                ) {
                    Text("البدء من جديد")
                }

                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("إعادة المحاولة")
                }
            }
        }
    }
}

// ======================== HELPERS ========================

/**
 * Gets real display filename of selected URI.
 */
fun getFileNameFromUri(context: Context, uri: Uri): String {
    var result = "report.txt"
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    result = it.getString(nameIndex)
                }
            }
        }
    } else {
        uri.path?.let {
            val cut = it.lastIndexOf('/')
            if (cut != -1) {
                result = it.substring(cut + 1)
            }
        }
    }
    return result
}

/**
 * Share generated Excel bytes securely inside local caching FileProvider references, zero permission dialog needed.
 */
fun shareExcelFile(context: Context, bytes: ByteArray, title: String) {
    try {
        val cacheFile = File(context.cacheDir, "$title.xlsx")
        FileOutputStream(cacheFile).use { out ->
            out.write(bytes)
        }
        
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheFile
        )
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(shareIntent, "مشاركة تقرير الـ Excel العربي"))
        
    } catch (e: Exception) {
        Toast.makeText(context, "فشل تجهيز المشاركة: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

