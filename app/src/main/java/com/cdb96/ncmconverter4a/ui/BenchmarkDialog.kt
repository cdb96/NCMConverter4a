package com.cdb96.ncmconverter4a.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cdb96.ncmconverter4a.service.BenchmarkResult
import com.cdb96.ncmconverter4a.service.BenchmarkService
import com.cdb96.ncmconverter4a.service.SizeThroughput
import com.cdb96.ncmconverter4a.service.TestStatus
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 基准测试Dialog - 现代化UI设计
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var testing by remember { mutableStateOf(false) }
    var currentStatus by remember { mutableStateOf(TestStatus.IDLE) }
    var benchmarkResult by remember { mutableStateOf<BenchmarkResult?>(null) }

    val benchmarkService = remember { BenchmarkService() }
    val coroutineScope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = {
            if (!testing) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !testing,
            dismissOnClickOutside = !testing,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                // 标题栏
                DialogHeader(
                    onClose = { if (!testing) onDismiss() },
                    closeEnabled = !testing
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 主要内容区域
                when {
                    testing -> {
                        // 测试进行中
                        TestingContent(status = currentStatus)
                    }
                    benchmarkResult != null -> {
                        // 显示结果
                        ResultsContent(
                            result = benchmarkResult!!,
                            onRetest = {
                                benchmarkResult = null
                                currentStatus = TestStatus.IDLE
                            },
                            onClose = onDismiss
                        )
                    }
                    else -> {
                        // 初始状态 - 显示开始按钮
                        StartContent(
                            onStart = {
                                testing = true
                                currentStatus = TestStatus.IDLE
                                coroutineScope.launch {
                                    try {
                                        benchmarkResult = benchmarkService.runBenchmark { status ->
                                            currentStatus = status
                                        }
                                    } catch (e: Exception) {
                                        currentStatus = TestStatus.IDLE
                                    } finally {
                                        testing = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 对话框标题
 */
@Composable
private fun DialogHeader(
    onClose: () -> Unit,
    closeEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "性能基准测试",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        IconButton(
            onClick = onClose,
            enabled = closeEnabled
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                tint = if (closeEnabled) 
                    MaterialTheme.colorScheme.onSurface 
                else 
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
    }
}

/**
 * 初始状态 - 开始测试按钮
 */
@Composable
private fun StartContent(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 说明文字
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "测试说明",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• 测试 KGM 和 NCM 解密算法性能\n• 对比 ByteBuffer 与 ByteArray 模式",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 开始按钮
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "开始测试",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 测试进行中的内容
 */
@Composable
private fun TestingContent(status: TestStatus) {
    val progress = when (status) {
        TestStatus.IDLE -> 0f
        TestStatus.WARMING -> 0.15f
        TestStatus.TESTING_KGM -> 0.4f
        TestStatus.TESTING_NCM_BUFFER -> 0.65f
        TestStatus.TESTING_NCM_ARRAY -> 0.85f
        TestStatus.COMPLETED -> 1f
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(500),
        label = "progress"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 进度圆环
        Box(
            modifier = Modifier
                .size(120.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 8.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 当前状态
        Text(
            text = getStatusDisplayName(status),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 步骤指示器
        StepIndicator(currentStatus = status)
    }
}

/**
 * 步骤指示器
 */
@Composable
private fun StepIndicator(currentStatus: TestStatus) {
    val steps = listOf(
        TestStatus.WARMING to "预热",
        TestStatus.TESTING_KGM to "KGM",
        TestStatus.TESTING_NCM_BUFFER to "NCM Buffer",
        TestStatus.TESTING_NCM_ARRAY to "NCM Array"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        steps.forEachIndexed { index, (status, label) ->
            val isCompleted = currentStatus.ordinal > status.ordinal
            val isCurrent = currentStatus == status

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                isCompleted -> MaterialTheme.colorScheme.primary
                                isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            isCompleted -> MaterialTheme.colorScheme.onPrimary
                            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent || isCompleted) 
                        MaterialTheme.colorScheme.onSurface 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * 结果显示内容
 */
@Composable
private fun ResultsContent(
    result: BenchmarkResult,
    onRetest: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 性能卡片
        PerformanceCard(
            title = "KGM 解密",
            icon = Icons.Default.Star,
            results = result.kgmResults,
            gradientColors = listOf(
                Color(0xFF6366F1),
                Color(0xFF8B5CF6)
            )
        )

        PerformanceCard(
            title = "NCM 解密 (DirectBuffer)",
            icon = Icons.Default.Star,
            results = result.ncmBufferResults,
            gradientColors = listOf(
                Color(0xFF10B981),
                Color(0xFF34D399)
            )
        )

        PerformanceCard(
            title = "NCM 解密 (ByteArray)",
            icon = Icons.Default.Star,
            results = result.ncmArrayResults,
            gradientColors = listOf(
                Color(0xFFF59E0B),
                Color(0xFFFBBF24)
            )
        )

        // 总耗时
        SummaryCard(totalDuration = result.totalDuration)

        Spacer(modifier = Modifier.height(8.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetest,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("重新测试")
            }
            Button(
                onClick = onClose,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("完成")
            }
        }
    }
}

/**
 * 性能结果卡片
 */
@Composable
private fun PerformanceCard(
    title: String,
    icon: ImageVector,
    results: List<SizeThroughput>,
    gradientColors: List<Color>
) {
    // 提前计算 maxThroughput，避免在循环中重复计算
    val maxThroughput = remember(results) {
        results.maxOfOrNull { it.throughputKBps } ?: 100.0
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(gradientColors)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 结果列表
            results.forEach { item ->
                PerformanceBar(
                    sizeKB = item.sizeKB,
                    throughput = item.throughputKBps,
                    maxThroughput = maxThroughput,
                    barColor = gradientColors[0]
                )
            }
        }
    }
}

/**
 * 性能条
 */
@Composable
private fun PerformanceBar(
    sizeKB: Int,
    throughput: Double,
    maxThroughput: Double,
    barColor: Color
) {
    val progress = (throughput / maxThroughput.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${String.format(Locale.US, "%.2f", sizeKB / 1024.0)}MB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Text(
                text = "${String.format(Locale.US, "%.2f", throughput / 1024.0)} MB/s",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 进度条 - 移除动画，直接显示静态进度
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
    }
}

/**
 * 汇总卡片
 */
@Composable
private fun SummaryCard(totalDuration: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "测试总耗时",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = "${String.format(Locale.US, "%.2f", totalDuration / 1000.0)} 秒",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * 获取状态显示名称
 */
private fun getStatusDisplayName(status: TestStatus): String {
    return when (status) {
        TestStatus.IDLE -> "准备就绪"
        TestStatus.WARMING -> "正在预热 JNI..."
        TestStatus.TESTING_KGM -> "测试 KGM 解密性能..."
        TestStatus.TESTING_NCM_BUFFER -> "测试 NCM (DirectBuffer)..."
        TestStatus.TESTING_NCM_ARRAY -> "测试 NCM (ByteArray)..."
        TestStatus.COMPLETED -> "测试完成"
    }
}
