package com.cdb96.ncmconverter4a.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class KggUiState(
    val dbFileName: String? = null,
    val audioFileName: String? = null,
    val isProcessing: Boolean = false,
    val decryptResult: String? = null,
    val isRooted: Boolean = false,
    val resultIsError: Boolean = false,
    val dbDisplayName: String? = null,
    val audioDisplayName: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KggScreen(
    state: KggUiState,
    onNavigateBack: () -> Unit,
    onSelectDbFile: () -> Unit,
    onSelectAudioFile: () -> Unit,
    onDecrypt: () -> Unit,
    onRootedChange: (Boolean) -> Unit,
    supportsRoot: Boolean = true,
) {
    val automaticDb = supportsRoot && state.isRooted
    val ready = state.audioFileName != null && (automaticDb || state.dbFileName != null)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KGG解密", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !state.isProcessing) {
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
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
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
                            text = "将 KGG 转为可播放的音频\n选择音频和对应的酷狗数据库，即可开始解密。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (supportsRoot) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动获取数据库", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "需要设备已 Root 并授权访问",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.isRooted,
                            onCheckedChange = onRootedChange,
                            enabled = !state.isProcessing,
                        )
                    }
                }

                if (!automaticDb) {
                    FileSelectCard(
                        title = "1 · 选择数据库",
                        description = if (supportsRoot) "包含对应音频密钥的 MMKV 文件"
                            else "包含对应音频密钥的 DB 或 MMKV 文件",
                        selectedFileName = state.dbDisplayName
                            ?: state.dbFileName?.substringAfterLast('\\')?.substringAfterLast('/'),
                        onSelectClick = onSelectDbFile,
                        enabled = !state.isProcessing
                    )
                }

                FileSelectCard(
                    title = if (automaticDb) "选择音频" else "2 · 选择音频",
                    description = "需要解密的 KGG 文件",
                    selectedFileName = state.audioDisplayName ?: state.audioFileName?.substringAfterLast('\\')?.substringAfterLast('/'),
                    onSelectClick = onSelectAudioFile,
                    enabled = !state.isProcessing
                )

                Text(
                    text = when {
                        state.isProcessing -> "正在处理，请稍候…"
                        ready -> "文件已就绪，可以开始解密"
                        automaticDb -> "请选择音频文件后开始解密"
                        else -> "请选择数据库和音频文件后开始解密"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDecrypt,
                        enabled = ready && !state.isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("解密中...")
                        } else {
                            Text("开始解密")
                        }
                    }
                }

                if (state.decryptResult != null && !state.isProcessing) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.resultIsError) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = state.decryptResult,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.resultIsError) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileSelectCard(
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = onSelectClick,
                enabled = enabled
            ) {
                Text(if (selectedFileName == null) "选择" else "更换")
            }
        }
    }
}
