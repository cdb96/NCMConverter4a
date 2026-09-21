package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cdb96.ncmconverter4a.service.FileConversionResult

@Composable
internal fun ConversionFilesDialog(
    fileNames: List<String>,
    fileResults: List<FileConversionResult>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (fileResults.isEmpty()) Icons.Outlined.Folder else Icons.Outlined.Warning, contentDescription = null) },
        title = { Text(if (fileResults.isEmpty()) "文件列表" else "未转换的文件") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("共 ${fileNames.size} 个文件", style = MaterialTheme.typography.labelLarge)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(fileNames) { index, name ->
                        val result = fileResults.getOrNull(index)
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SelectionContainer {
                                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                }
                                if (result != null) {
                                    Text(
                                        if (result.outputAlreadyExists) "同名文件已存在 · 原文件已保留" else "转换未完成",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (result.outputAlreadyExists) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                    )
                                    SelectionContainer {
                                        Text(result.error ?: "未提供具体原因", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
