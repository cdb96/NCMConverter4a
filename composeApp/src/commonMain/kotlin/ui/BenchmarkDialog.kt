package com.cdb96.ncmconverter4a.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cdb96.ncmconverter4a.service.BenchmarkResult
import com.cdb96.ncmconverter4a.service.SizeThroughput
import com.cdb96.ncmconverter4a.service.TestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 基准测试 Dialog - platform-agnostic */
@Composable
fun BenchmarkDialog(
    onDismiss: () -> Unit,
    onRunBenchmark: suspend (onProgress: suspend (TestStatus) -> Unit) -> BenchmarkResult,
    modifier: Modifier = Modifier,
) {
    var testing by remember { mutableStateOf(false) }
    var currentStatus by remember { mutableStateOf(TestStatus.IDLE) }
    var benchmarkResult by remember { mutableStateOf<BenchmarkResult?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = { if (!testing) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !testing,
            dismissOnClickOutside = !testing,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Card(
            modifier = modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DialogHeader(onClose = { if (!testing) onDismiss() }, closeEnabled = !testing)
                when {
                    testing -> TestingContent(status = currentStatus)
                    benchmarkResult != null -> ResultsContent(
                        result = benchmarkResult!!,
                        onRetest = {
                            benchmarkResult = null
                            currentStatus = TestStatus.IDLE
                        },
                        onClose = onDismiss,
                    )
                    else -> StartContent(
                        onStart = {
                            testing = true
                            currentStatus = TestStatus.IDLE
                            coroutineScope.launch {
                                try {
                                    benchmarkResult = onRunBenchmark { status ->
                                        withContext(Dispatchers.Main.immediate) { currentStatus = status }
                                    }
                                } catch (_: Exception) {
                                    currentStatus = TestStatus.IDLE
                                } finally {
                                    testing = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogHeader(onClose: () -> Unit, closeEnabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text("性能基准测试", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onClose, enabled = closeEnabled) {
            Icon(
                Icons.Default.Close,
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (closeEnabled) 1f else 0.38f),
            )
        }
    }
}

@Composable
private fun StartContent(onStart: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("测试说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "• 测试 KGM、NCM 和 KGG 解密算法性能\n• KGG 包含 RC4 和 Map 两种模式，计入初始化耗时\n• 结果取多次运行中的最高吞吐量",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3,
                )
            }
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("开始测试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TestingContent(status: TestStatus) {
    val (targetProgress, statusLabel) = status.progressAndLabel()
    val animatedProgress by animateFloatAsState(targetValue = targetProgress, animationSpec = tween(500), label = "progress")

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(120.dp).padding(8.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 8.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                "${(animatedProgress * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(statusLabel, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        StepIndicator(currentStatus = status)
    }
}

@Composable
private fun StepIndicator(currentStatus: TestStatus) {
    val steps = listOf(
        TestStatus.WARMING to "预热",
        TestStatus.TESTING_KGM to "KGM",
        TestStatus.TESTING_NCM to "NCM",
        TestStatus.TESTING_KGG to "KGG",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        steps.forEachIndexed { index, (status, label) ->
            val isCompleted = currentStatus.ordinal > status.ordinal
            val isCurrent = currentStatus == status
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                isCompleted -> MaterialTheme.colorScheme.primary
                                isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isCompleted -> MaterialTheme.colorScheme.onPrimary
                            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isCurrent || isCompleted) 1f else 0.5f),
                )
            }
        }
    }
}

private data class BenchmarkCardData(
    val title: String,
    val results: List<SizeThroughput>,
    val gradientColors: List<Color>,
)

@Composable
private fun ResultsContent(
    result: BenchmarkResult,
    onRetest: () -> Unit,
    onClose: () -> Unit,
) {
    val cards = listOf(
        BenchmarkCardData("KGM 解密", result.kgmResults, listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))),
        BenchmarkCardData("NCM 解密", result.ncmResults, listOf(Color(0xFF10B981), Color(0xFF34D399))),
        BenchmarkCardData("KGG 解密（RC4）", result.kggRc4Results, listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))),
        BenchmarkCardData("KGG 解密（Map）", result.kggMapResults, listOf(Color(0xFF0EA5E9), Color(0xFF38BDF8))),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cards.forEach { card ->
            PerformanceCard(card.title, card.results, card.gradientColors)
        }
        SummaryCard(totalDuration = result.totalDuration)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onRetest,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新测试")
            }
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("完成")
            }
        }
    }
}

@Composable
private fun PerformanceCard(
    title: String,
    results: List<SizeThroughput>,
    gradientColors: List<Color>,
) {
    val maxThroughput = remember(results) { results.maxOfOrNull { it.throughputKBps } ?: 100.0 }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(gradientColors)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            results.forEach { item ->
                val progress = (item.throughputKBps / maxThroughput.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${"%.2f".format(item.sizeKB / 1024.0)}MB", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Text("${"%.2f".format(item.throughputKBps / 1024.0)} MB/s", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(gradientColors[0]))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(totalDuration: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                Text("测试总耗时", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(
                "${"%.2f".format(totalDuration / 1000.0)} 秒",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private fun TestStatus.progressAndLabel(): Pair<Float, String> = when (this) {
    TestStatus.IDLE -> 0f to "准备就绪"
    TestStatus.WARMING -> 0.15f to "正在预热解密算法..."
    TestStatus.TESTING_KGM -> 0.4f to "测试 KGM 解密性能..."
    TestStatus.TESTING_NCM -> 0.65f to "测试 NCM 解密..."
    TestStatus.TESTING_KGG -> 0.85f to "测试 KGG 解密性能..."
    TestStatus.COMPLETED -> 1f to "测试完成"
}
