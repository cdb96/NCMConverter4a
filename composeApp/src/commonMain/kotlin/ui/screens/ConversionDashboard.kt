package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ConversionDashboard(
    conversionState: ConversionUiState,
    settings: SettingsUiState,
    supportsRoot: Boolean,
    onPickFiles: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val automaticDatabase = supportsRoot && settings.kggRootMode
    val databaseReady = automaticDatabase || settings.kggDatabase != null
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        BoxWithConstraints {
            val importPanel: @Composable () -> Unit = {
                ImportPanel(conversionState, supportsRoot, onPickFiles)
            }
            val configurationPanel: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    ConfigurationPanel(settings, databaseReady, automaticDatabase, onOpenSettings)
                    OutputHint()
                }
            }
            if (maxWidth >= 720.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1.5f)) { importPanel() }
                    Box(Modifier.weight(1f)) { configurationPanel() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    importPanel()
                    configurationPanel()
                }
            }
        }

    }
}

@Composable
private fun OutputHint() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text("保存至 Music / NCMConverter4A", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("输出格式由音频内容决定，原始文件会保留。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImportPanel(state: ConversionUiState, android: Boolean, onPickFiles: () -> Unit) {
    val processing = state.isProcessing
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(
            listOf(colors.primaryContainer, colors.tertiaryContainer.copy(alpha = 0.55f))))) {
            Canvas(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 20.dp).size(132.dp, 48.dp)) {
                val bars = listOf(0.2f, 0.35f, 0.7f, 0.45f, 1f, 0.65f, 0.9f, 0.4f, 0.6f, 0.3f, 0.5f, 0.2f)
                bars.forEachIndexed { index, height ->
                    val x = size.width * (index + 0.5f) / bars.size
                    drawLine(colors.primary.copy(alpha = 0.16f),
                        Offset(x, size.height * (1 - height) / 2),
                        Offset(x, size.height * (1 + height) / 2),
                        strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = colors.surface.copy(alpha = 0.7f)) {
                    Icon(Icons.Outlined.GraphicEq, null, Modifier.padding(12.dp).size(24.dp), tint = colors.primary)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (state.hasConversionStarted) "音乐转换" else "添加音乐，开始转换",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = colors.onPrimaryContainer)
                    Text(if (android) "支持多选文件，NCM、KGM 与 KGG 可以一起转换。"
                        else "将文件拖入窗口，或点击下方按钮。支持混合批量转换。",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
                }
                HorizontalDivider(color = colors.onPrimaryContainer.copy(alpha = 0.12f))
                if (state.hasConversionStarted) {
                    ConversionStatusContent(state)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = colors.primary)
                        Text("准备就绪 · 选择文件后自动开始转换",
                            style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
                    }
                }
                Button(onClick = onPickFiles, enabled = !processing,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                    if (processing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (processing) "转换中，请稍候" else if (state.hasConversionStarted) "继续添加音乐" else "选择音乐文件")
                }
            }
        }
    }
}

@Composable
private fun ConfigurationPanel(
    settings: SettingsUiState,
    databaseReady: Boolean,
    automaticDatabase: Boolean,
    onOpenSettings: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("转换配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("设置")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormatBadge("NCM")
                FormatBadge("KGM")
                FormatBadge("KGG")
            }
            Text("${settings.threadCount} 个转换线程 · ${if (settings.rawWriteMode) "原始写入" else "保留 NCM 元数据"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(if (databaseReady) Icons.Outlined.CheckCircle else Icons.Outlined.Key, null,
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (databaseReady) "KGG 数据库已配置" else "KGG 需要配置数据库",
                        style = MaterialTheme.typography.titleSmall)
                    Text(when {
                        automaticDatabase -> "转换时通过 Root 自动获取"
                        databaseReady -> settings.kggDatabaseName ?: "已选择酷狗数据库"
                        else -> "在设置中选择；NCM、KGM 可直接转换。"
                    }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FormatBadge(name: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(name, Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
