package com.cdb96.ncmconverter4a
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        val action = intent.action
        val data = intent.data
        val type = intent.type
        if (Intent.ACTION_SEND == action &&  type != null) {
            val uri:Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri != null) {
                solveFile(uri,this,false,false)
            }
        }
        if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            val uris:ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            if (uris != null) {
                var count = 0
                for (uri in uris){
                    if ( solveFile(uri,this,true,false) ) {
                        count++
                    }
                    else{
                        Toast.makeText(this, ("该文件转换失败！可能不是NCM文件"), Toast.LENGTH_SHORT).show()
                    }
                }
                Toast.makeText(this, ("转换${count}个文件完成！存储于Music文件夹 "), Toast.LENGTH_SHORT).show()
            }
        }
        if (Intent.ACTION_VIEW == action && data != null) {
            solveFile(data,this,false,false)
        }
        setContent {
            MainFrame()
        }
    }
}

fun getFileNameFromUri(uri: Uri,context: Context): String? {
    val documentFile: DocumentFile? = DocumentFile.fromSingleUri(context, uri)
    if (documentFile != null) {
        return documentFile.name
    }
    return null
}

fun solveFile(uri: Uri, context: Context, multiple:Boolean,rawWriteMode: Boolean):Boolean
{
    try {
        var inputStream = context.contentResolver.openInputStream(uri)

        if (!KGMConverter.KGMDetect(inputStream)) {
            inputStream = context.contentResolver.openInputStream(uri)
            val result = NCMConverter.convert(inputStream,false)
            val fileName = getMusicInfoData(result.musicInfoStringArrayValue, "musicName")
            val format = getMusicInfoData(result.musicInfoStringArrayValue,"format")
            val outputStream = getOutputStream(format,context,fileName)
            NCMConverter.outputMusic(outputStream,inputStream,result.RC4key,result.coverData,rawWriteMode,result.musicInfoStringArrayValue)
            outputStream?.close()
        }
        else{
            val musicFormat = KGMConverter.detectFormat(inputStream)
            val regex = Regex("(.kgm)|(.flac)")
            var fileName = getFileNameFromUri(uri,context)
            if (fileName != null) {
                fileName = fileName.replace(regex,"")
                val outputStream = getOutputStream(musicFormat,context,fileName)
                //加上先前读取的1字节
                KGMConverter.write(inputStream,outputStream,musicFormat,1)
            }
        }

        if (!multiple) {
            Toast.makeText(context, ("转换完成！存储于Music文件夹 "), Toast.LENGTH_SHORT).show()
        }
        return true
    } catch (_:Exception){
        if (!multiple) {
            Toast.makeText(context, ("转换失败！可能不是NCM文件"), Toast.LENGTH_SHORT).show()
        }
        return false
    }
}

fun getOutputStream(format: String,context: Context,fileName: String): OutputStream? {
    var musicName = "null"
    val values = ContentValues().apply {
        if (format == "flac" ) {
            musicName = "$fileName.flac"
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
        }else if ( format == "mp3" ){
            musicName = "$fileName.mp3"
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
        }
        put(MediaStore.Audio.Media.DISPLAY_NAME, musicName)
        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NCMConverter4A")
    }
    val uri:Uri? = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values)
    val oStream: OutputStream? = uri?.let { context.contentResolver.openOutputStream(it) }
    return oStream;
}

fun getMusicInfoData(arrayList: ArrayList<String>,key:String): String {
    return arrayList[(arrayList.indexOf(key) + 1)]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainFrame() {
    var convertResult by remember { mutableStateOf<String?>("null") }
    var musicName by remember { mutableStateOf("尚未选择文件") }
    var rawWriteMode by remember { mutableStateOf(false) }
    var conversionDurationMillis by remember { mutableStateOf<Long?>(null) } // 新增：存储耗时的状态

    var isSettingsExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let {
                var startTime = 0L
                try {
                    conversionDurationMillis = null
                    startTime = System.currentTimeMillis()

                    solveFile(uri, context, false, rawWriteMode)

                    val duration = System.currentTimeMillis() - startTime
                    conversionDurationMillis = duration // 记录耗时

                    getFileNameFromUri(uri, context)?.let { fileName -> musicName = fileName }
                    convertResult = "True"
                } catch (e: Exception) {
                    if (startTime > 0) { // 如果处理已开始，则记录到出错为止的耗时
                        val duration = System.currentTimeMillis() - startTime
                        conversionDurationMillis = duration
                    } else {
                        conversionDurationMillis = null // 处理未开始，无耗时
                    }
                    convertResult = "False"
                    e.printStackTrace()
                }
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NCMConverter4A") },
                navigationIcon = {
                    IconButton(onClick = { /* 通常用于打开抽屉菜单 */ }) {
                        Icon(Icons.Filled.Menu, contentDescription = "菜单")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch("*/*") }
            ) {
                Icon(Icons.Default.Add, contentDescription = "选择文件")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (convertResult == "False") {
                            "转换失败"
                        } else if (convertResult == "True") {
                            "已读取文件!"
                        } else {
                            "等待选择文件"
                        },
                        modifier = Modifier.padding(bottom = 16.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "文件名: $musicName",
                        modifier = Modifier.padding(bottom = 8.dp), // 调整间距为耗时信息留出空间
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // 显示耗时信息
                    if (conversionDurationMillis != null) {
                        val seconds = conversionDurationMillis!! / 1000.0
                        Text(
                            // 使用 Locale.US 确保小数点是点，避免某些地区是逗号导致格式化问题
                            text = String.format(Locale.US, "处理耗时: %.3f 秒", seconds),
                            modifier = Modifier.padding(bottom = 16.dp), // 与设置区域的间距
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall // 使用较小字体
                        )
                    } else {
                        // 如果没有耗时信息（例如初始状态），可以留一个占位间距，或者不显示
                        // 这里为了对齐，当没有耗时的时候，也添加一个与上面Text等效的间距
                        // 如果 conversionDurationMillis 为 null 且 musicName 不是初始值，
                        // 可能意味着文件选择被取消或出错在计时开始前。
                        // 简单起见，我们只在有耗时的时候显示它。
                        // 如果希望在有文件名但无耗时（例如选取失败）时也保持布局，
                        // 可以添加一个 Spacer(Modifier.height(MaterialTheme.typography.bodySmall.lineHeight.value.dp + 16.dp))
                        // 但这里选择仅当有耗时才显示，因此上面对musicName的bottom padding已调整。
                        // 若 musicName 和 conversionDurationMillis 都可能变化，且需要严格对齐，
                        // 则需要更复杂的 Spacer 逻辑或固定高度。
                        // 当前设计：只有当 conversionDurationMillis 非 null 时才显示耗时文本，
                        // musicName 下方的 padding 减少，耗时文本自己带 padding。
                        if (musicName != "尚未选择文件") { // 如果已选过文件但耗时为null(可能选取取消)
                            Spacer(modifier = Modifier.height(MaterialTheme.typography.bodySmall.fontSize.value.dp + 16.dp)) // 估算一个高度占位
                        } else {
                            Spacer(modifier = Modifier.height(0.dp)) // 初始状态，musicName下方已有较大padding
                        }
                    }


                    // "原始写入模式" 的折叠栏
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isSettingsExpanded = !isSettingsExpanded }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "兼容性设置",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Icon(
                                imageVector = if (isSettingsExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (isSettingsExpanded) "收起设置" else "展开设置"
                            )
                        }

                        AnimatedVisibility(visible = isSettingsExpanded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 8.dp, start = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("原始写入模式")
                                Switch(
                                    checked = rawWriteMode,
                                    onCheckedChange = { rawWriteMode = it },
                                    thumbContent = if (rawWriteMode) {
                                        {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = "原始写入模式已开启",
                                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                            )
                                        }
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}