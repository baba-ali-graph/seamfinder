package com.baba.edgedock.data

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable

@Immutable
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val lastUsedMillis: Long? = null,
)
