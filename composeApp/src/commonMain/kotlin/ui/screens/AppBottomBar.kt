package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem

@Composable
fun AppBottomBar(page: AppPage, onSelect: (AppPage) -> Unit, showScan: Boolean = true) {
    NavigationBar {
        NavigationBarItem(
            selected = page == AppPage.CONVERSION,
            onClick = { onSelect(AppPage.CONVERSION) },
            icon = { Icon(Icons.Outlined.MusicNote, contentDescription = null) },
            label = { Text("转换") },
        )
        if (showScan) NavigationBarItem(
            selected = page == AppPage.SCAN,
            onClick = { onSelect(AppPage.SCAN) },
            icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
            label = { Text("扫描") },
        )
        NavigationBarItem(
            selected = page == AppPage.SETTINGS,
            onClick = { onSelect(AppPage.SETTINGS) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text("设置") },
        )
    }
}

