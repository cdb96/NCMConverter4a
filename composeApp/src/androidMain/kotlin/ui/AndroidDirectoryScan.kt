package com.cdb96.ncmconverter4a.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import com.cdb96.ncmconverter4a.service.AndroidDirectoryBrowser
import com.cdb96.ncmconverter4a.service.AndroidScanDirectoryPreference
import com.cdb96.ncmconverter4a.service.DirectoryScanService
import com.cdb96.ncmconverter4a.service.ScanDirectory
import com.cdb96.ncmconverter4a.service.ScanFormat
import com.cdb96.ncmconverter4a.ui.screens.DirectoryScanController
import kotlinx.coroutines.Dispatchers

data class AndroidDirectoryScanActions(
    val controller: DirectoryScanController,
    val selectDirectory: (ScanFormat) -> Unit,
    val scan: (Set<ScanFormat>) -> Unit,
)

@Composable
fun rememberAndroidDirectoryScan(conversionBusy: Boolean): AndroidDirectoryScanActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preference = remember { AndroidScanDirectoryPreference(context.applicationContext) }
    val controller = remember {
        DirectoryScanController(DirectoryScanService(AndroidDirectoryBrowser(context.applicationContext)),
            scope, Dispatchers.IO, Dispatchers.Main.immediate, preference.load(), preference::save)
    }
    var pickingFormat by remember { mutableStateOf<ScanFormat?>(null) }
    var pendingScan by remember { mutableStateOf<Set<ScanFormat>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val format = pickingFormat
        pickingFormat = null
        if (uri == null || format == null) {
            pendingScan = null
        } else {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val id = DocumentsContract.getTreeDocumentId(uri)
                val path = if (uri.authority == "com.android.externalstorage.documents" && id.startsWith("primary:")) {
                    "/sdcard/${id.removePrefix("primary:")}".trimEnd('/')
                } else DocumentFile.fromTreeUri(context, uri)?.name ?: id
                val directory = ScanDirectory(path, uri.toString())
                controller.setDirectory(format, directory)
                if (controller.state.directories[format] != directory) pendingScan = null
            } catch (error: Exception) {
                pendingScan = null
                controller.showMessage("无法授权此目录，请重新选择: ${error.message}")
            }
        }
    }

    fun initialUri(format: ScanFormat): Uri? {
        val directory = controller.state.directories[format] ?: return null
        directory.treeUri?.let { return Uri.parse(it) }
        return directory.path.takeIf { it.startsWith("/sdcard/") }?.let {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:${it.removePrefix("/sdcard/")}")
        }
    }

    // Authorize missing/revoked trees one by one, then scan the requested formats.
    LaunchedEffect(pendingScan, pickingFormat, controller.state.directories, conversionBusy) {
        val formats = pendingScan ?: return@LaunchedEffect
        if (pickingFormat != null) return@LaunchedEffect
        if (conversionBusy) { pendingScan = null; return@LaunchedEffect }
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val missing = formats.firstOrNull { format ->
            val directory = controller.state.directories[format]
            directory?.configured == true && directory.treeUri !in granted
        }
        if (missing == null) {
            pendingScan = null
            controller.start(formats)
        } else {
            pickingFormat = missing
            picker.launch(initialUri(missing))
        }
    }

    return AndroidDirectoryScanActions(controller,
        selectDirectory = { format ->
            if (!conversionBusy && !controller.state.isScanning) {
                pendingScan = null
                pickingFormat = format
                picker.launch(initialUri(format))
            }
        },
        scan = { formats -> if (!conversionBusy && !controller.state.isScanning) pendingScan = formats },
    )
}
