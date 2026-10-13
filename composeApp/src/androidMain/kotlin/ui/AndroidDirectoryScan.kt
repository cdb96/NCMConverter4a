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
    val busy by rememberUpdatedState(conversionBusy)
    var onDirectoryPicked by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val callback = onDirectoryPicked
        onDirectoryPicked = null
        if (uri != null) callback?.invoke(uri)
    }

    fun initialUri(format: ScanFormat): Uri? {
        val directory = controller.state.directories[format] ?: return null
        directory.treeUri?.let { return Uri.parse(it) }
        return directory.path.takeIf { it.startsWith("/sdcard/") }?.let {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:${it.removePrefix("/sdcard/")}")
        }
    }

    fun selectDirectory(format: ScanFormat, onAuthorized: () -> Unit = {}) {
        if (busy || controller.state.isScanning || onDirectoryPicked != null) return
        onDirectoryPicked = { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val id = DocumentsContract.getTreeDocumentId(uri)
                val path = if (uri.authority == "com.android.externalstorage.documents" && id.startsWith("primary:")) {
                    "/sdcard/${id.removePrefix("primary:")}".trimEnd('/')
                } else DocumentFile.fromTreeUri(context, uri)?.name ?: id
                val directory = ScanDirectory(path, uri.toString())
                controller.setDirectory(format, directory)
                if (controller.state.directories[format] == directory) onAuthorized()
            } catch (error: Exception) {
                controller.showMessage("无法授权此目录，请重新选择: ${error.message}")
            }
        }
        try {
            picker.launch(initialUri(format))
        } catch (error: Exception) {
            onDirectoryPicked = null
            controller.showMessage("无法打开目录选择器: ${error.message}")
        }
    }

    fun scan(formats: Set<ScanFormat>) {
        if (busy || controller.state.isScanning || onDirectoryPicked != null) return
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val missing = formats.firstOrNull { format ->
            val directory = controller.state.directories[format]
            directory?.selectable == true && directory.treeUri !in granted
        }
        if (missing == null) {
            controller.start(formats)
        } else {
            selectDirectory(missing) { scan(formats) }
        }
    }

    return AndroidDirectoryScanActions(controller,
        selectDirectory = { selectDirectory(it) },
        scan = { scan(it.toSet()) },
    )
}
