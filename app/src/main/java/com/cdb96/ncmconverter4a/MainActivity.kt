package com.cdb96.ncmconverter4a
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
                solveShareFile(uri,this,false)
            }
        }
        if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            val uris:ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            if (uris != null) {
                var count = 0
                for (uri in uris){
                    if ( solveShareFile(uri,this,true) ) {
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
            solveShareFile(data,this,false)
        }
        setContent {
            MainFrame()
        }
    }
}

fun solveShareFile(uri: Uri,context: Context,multiple:Boolean):Boolean
{
    try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val result = NCMConverter.convert(inputStream)
        val fileName = getMusicInfoData(result.StringArrayValue, "musicName")
        val musicData = result.byteArrayValue
        writeMusic(fileName, musicData, context,result.StringArrayValue)
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
    musicInfo:ArrayList<String>
)
{
    val musicName = "$fileName.mp3"
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, musicName)
        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NCMConverter4A")
    }
    val uri:Uri? = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values)
    val oStream: OutputStream? = uri?.let { context.contentResolver.openOutputStream(it) }
    //oStream?.write(header)
    oStream?.write(data)
    oStream?.close()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainFrame() {
    val (convertResult,setConvertResult) = remember { mutableStateOf<String?>("null") }
    val (musicName,setMusicName) = remember { mutableStateOf("尚未选择文件") }
    val context = LocalContext.current
    // 注册活动结果回调
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val result = NCMConverter.convert(inputStream)
                    val fileName = getMusicInfoData(result.StringArrayValue,"musicName")
                    val musicData = result.byteArrayValue
                    setMusicName(fileName)
                    writeMusic(fileName,musicData,context,result.StringArrayValue)
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
                onClick = { filePickerLauncher.launch("application/*") }
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
                        text = if (convertResult == "False") "转换失败" else if(convertResult == "True") "转换成功，生成文件位于Music文件夹" else "请选择文件！可以从右下方按钮选择,或者从文件管理器选择ncm文件打开",
                        modifier = Modifier.padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text(text = "歌曲名:${musicName}",
                        modifier = Modifier.padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    )
}