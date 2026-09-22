package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem

@Composable
fun AppBottomBar(settingsSelected: Boolean, onSelectSettings: (Boolean) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = !settingsSelected,
            onClick = { onSelectSettings(false) },
            icon = { Icon(Icons.Outlined.MusicNote, contentDescription = null) },
            label = { Text("转换") },
        )
        NavigationBarItem(
            selected = settingsSelected,
            onClick = { onSelectSettings(true) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text("设置") },
        )
    }
}

