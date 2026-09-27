package com.phoneagent

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Small section header used across Miuix pages. */
@Composable
fun GroupTitle(text: String) {
    M3Text(
        text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
fun FieldLabel(text: String) {
    M3Text(text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
}

/**
 * A simple text button row inside a Card. Kept as a helper because Miuix
 * preference components persist state themselves, which the update actions
 * (one-shot commands) don't want.
 */
@Composable
fun UpdateRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    top.yukonga.miuix.kmp.basic.TextButton(
        text = label,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}
