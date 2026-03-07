package com.cdb96.ncmconverter4a

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cdb96.ncmconverter4a.converter.kgg.KggDecoder
import com.cdb96.ncmconverter4a.ui.theme.NCMConverter4aTheme
import kotlinx.coroutines.launch

class KGGDecryptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NCMConverter4aTheme {
                KGGDecryptScreen(
                    onNavigateBack = { finish() }
                )
            }
        }
    }

    private var decryptedDbData: ByteArray? = null

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun KGGDecryptScreen(
        onNavigateBack: () -> Unit
    ) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var dbFileUri by remember { mutableStateOf<Uri?>(null) }
        var dbFileName by remember { mutableStateOf<String?>(null) }
        var audioFileUri by remember { mutableStateOf<Uri?>(null) }
        var audioFileName by remember { mutableStateOf<String?>(null) }
        var isProcessing by remember { mutableStateOf(false) }
        var decryptResult by remember { mutableStateOf<String?>(null) }
        var isDbDecrypted by remember { mutableStateOf(false) }

        fun getFileName(uri: Uri): String {
            var name = "未知文件"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
            return name
        }

        val dbFilePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = { uri: Uri? ->
                dbFileUri = uri
                dbFileName = getFileName(dbFileUri!!)
                isDbDecrypted = false
                decryptedDbData = null
            }
        )

        val audioFilePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = { uri: Uri? ->
                audioFileUri = uri
                audioFileName = getFileName(audioFileUri!!)
            }
        )

        val exportDbPicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
            onResult = { uri: Uri? ->
                uri?.let { exportUri ->
                    decryptedDbData?.let { data ->
                        try {
                            contentResolver.openOutputStream(exportUri)?.use { output ->
                                output.write(data)
                            }
                            Toast.makeText(context, "数据库已导出", Toast.LENGTH_SHORT).show()
                            decryptResult = "数据库导出成功！"
                        } catch (e: Exception) {
                            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("KGG解密", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "KGG解密功能需要同时选择DB文件和对应的音频文件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                FileSelectCard(
                    title = "DB文件",
                    description = "KGG数据库文件",
                    selectedFileName = dbFileName,
                    onSelectClick = { dbFilePicker.launch(arrayOf("*/*")) },
                    enabled = !isProcessing
                )

                FileSelectCard(
                    title = "音频文件",
                    description = "需要解密的KGG音频文件",
                    selectedFileName = audioFileName,
                    onSelectClick = { audioFilePicker.launch(arrayOf("*/*")) },
                    enabled = !isProcessing
                )

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (dbFileUri != null && audioFileUri != null) {
                                isProcessing = true
                                decryptResult = "正在解密文件..."

                                coroutineScope.launch {
                                    try {
                                        val kggDecoder = KggDecoder(context)
                                        kggDecoder.decryptWithUri(audioFileUri!!, dbFileUri!!)
                                        decryptResult = "文件解密完成！"
                                        Toast.makeText(context, "文件解密完成", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        decryptResult = "文件解密失败: ${e.message}"
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            } else {
                                decryptResult = "请先选择DB文件和音频文件"
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isProcessing && decryptResult?.contains("文件") == true) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("解密中...")
                        } else {
                            Text("解密文件")
                        }
                    }
                }

                if (decryptResult != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                decryptResult!!.contains("完成") -> MaterialTheme.colorScheme.primaryContainer
                                decryptResult!!.contains("失败") -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Text(
                            text = decryptResult!!,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                decryptResult!!.contains("完成") -> MaterialTheme.colorScheme.onPrimaryContainer
                                decryptResult!!.contains("失败") -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                if (isDbDecrypted && decryptedDbData != null) {
                    OutlinedButton(
                        onClick = {
                            val suggestedName = dbFileName?.replace(".db", "_decrypted.db") ?: "decrypted.db"
                            exportDbPicker.launch(suggestedName)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("导出解密后的数据库")
                    }
                }
            }
        }
    }

    @Composable
    private fun FileSelectCard(
        title: String,
        description: String,
        selectedFileName: String?,
        onSelectClick: () -> Unit,
        enabled: Boolean
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = selectedFileName ?: "未选择文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedFileName != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = onSelectClick,
                    enabled = enabled
                ) {
                    Text("选择")
                }
            }
        }
    }
}
