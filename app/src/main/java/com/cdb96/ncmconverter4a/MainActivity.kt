package com.cdb96.ncmconverter4a
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import java.io.OutputStream

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
                solveFile(uri,this,false)
            }
        }
        if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            val uris:ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            if (uris != null) {
                var count = 0
                for (uri in uris){
                    if ( solveFile(uri,this,true) ) {
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
            solveFile(data,this,false)
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

fun solveFile(uri: Uri, context: Context, multiple:Boolean):Boolean
{
    try {
        var inputStream = context.contentResolver.openInputStream(uri)

        if (!KGMConverter.KGMDetect(inputStream)) {
            inputStream = context.contentResolver.openInputStream(uri)
            val result = NCMConverter.convert(inputStream,false)
            val fileName = getMusicInfoData(result.musicInfoStringArrayValue, "musicName")
            val musicData = result.musicDataByteArray
            writeMusic(fileName, musicData, context)
        }
        else{
            val musicData = KGMConverter.decrypt(inputStream)
            val regex = Regex("(.kgm)|(.flac)")
            var fileName = getFileNameFromUri(uri,context)
            if (fileName != null) {
                fileName = fileName.replace(regex,"")
            }
            if (fileName != null) {
                writeMusic(fileName,musicData,context)
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

fun getMusicInfoData(arrayList: ArrayList<String>,key:String): String {
    return arrayList[(arrayList.indexOf(key) + 1)]
}

fun writeMusic(
    fileName:String,
    data:ByteArray,
    context:Context,
)
{
    var musicName = "null"
    val values = ContentValues().apply {
        if (data[0].toInt() == 0x66 ){
            musicName = "$fileName.flac"
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
        }else if (data[0].toInt() == 0x49){
            musicName = "$fileName.mp3"
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
        }
        put(MediaStore.Audio.Media.DISPLAY_NAME, musicName)
        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NCMConverter4A")
    }
    val uri:Uri? = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values)
    val oStream: OutputStream? = uri?.let { context.contentResolver.openOutputStream(it) }
    oStream?.write(data)
    oStream?.close()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainFrame() {
    val (convertResult,setConvertResult) = remember { mutableStateOf<String?>("null") }
    val (musicName,setMusicName) = remember { mutableStateOf("尚未选择文件") }
    var rawWriteMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 注册活动结果回调
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let {
                try {
                    solveFile(uri,context,false)
                    getFileNameFromUri(uri,context)?.let { it1 -> setMusicName(it1) }
                    setConvertResult("True")
                } catch (e:Exception){
                    setConvertResult("False")
                    e.printStackTrace()
                }
            }
        }
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NCMConverter4A") },
                colors = TopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch("*/*") }
            ) {
                Icon ( Icons.Default.Add, contentDescription = "Add" )
            }
        },
        content = { padding ->
            Column {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = if (convertResult == "False") "转换失败" else if(convertResult == "True") "已读取文件!" else "请选择文件！可以从右下方按钮选择,或者从文件管理器选择ncm文件打开",
                        modifier = Modifier.padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "文件名:${musicName}",
                        modifier = Modifier.padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                    Box (contentAlignment = Alignment.Center){
                        Text("原始写入模式", Modifier.padding(24.dp))
                        Switch(
                            modifier = Modifier.padding(padding),
                            checked = rawWriteMode,
                            onCheckedChange = {
                                rawWriteMode = it
                            },
                            thumbContent = if (rawWriteMode) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
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
    )
}