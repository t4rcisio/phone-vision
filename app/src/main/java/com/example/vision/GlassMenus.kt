package com.example.vision

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassSheet(
    title: String,
    onDismiss: () -> Unit,
    closeEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val height = (LocalConfiguration.current.screenHeightDp * .88f).dp
    ModalBottomSheet(
        onDismissRequest = { if (closeEnabled) onDismiss() },
        sheetState = state,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color(0x77040913),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        GlassSurface(
            Modifier.fillMaxWidth()
                .padding(horizontal = 10.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 10.dp),
            RoundedCornerShape(32.dp),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = height)) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onDismiss, enabled = closeEnabled) {
                        Icon(painterResource(R.drawable.ic_close), "Close", tint = Ice)
                    }
                }
                content()
            }
        }
    }
}

@Composable
internal fun GlassMenuItem(
    label: String,
    icon: Int,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Ice.copy(alpha = .16f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = 54.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ice.copy(alpha = if (selected) .2f else .08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(21.dp), tint = Ice)
        }
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = if (enabled) 1f else .4f),
        )
        if (trailingIcon != null) trailingIcon()
        else if (selected)
            Box(
                Modifier.size(25.dp).clip(CircleShape).background(Ice),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = GlassNavy, style = MaterialTheme.typography.labelLarge)
            }
    }
}

internal val Skill.icon: Int
    get() =
        when (this) {
            Skill.QUERY -> R.drawable.ic_query
            Skill.CAPTION -> R.drawable.ic_caption
            Skill.DETECT -> R.drawable.ic_detect
            Skill.POINT -> R.drawable.ic_point
            Skill.SEGMENT -> R.drawable.ic_segment
            Skill.OCR -> R.drawable.ic_text
        }

@Composable
internal fun ResultPhoto(bitmap: Bitmap, marks: List<Mark>? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = minOf(maxWidth / (bitmap.width.toFloat() / bitmap.height), 420.dp)
        Box(
            Modifier.fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0x55101B2F)),
        ) {
            if (marks != null) Snapshot(bitmap, marks)
            else
                androidx.compose.foundation.Image(
                    bitmap.asImageBitmap(),
                    "Saved result",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
        }
    }
}
