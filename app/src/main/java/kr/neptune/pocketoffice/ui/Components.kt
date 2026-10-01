package kr.neptune.pocketoffice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.neptune.pocketoffice.core.DocKind
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun DocKind?.icon(): ImageVector = when (this) {
    DocKind.WORD -> Icons.Outlined.Description
    DocKind.SHEET -> Icons.Outlined.TableChart
    DocKind.SLIDE -> Icons.Outlined.Slideshow
    DocKind.PDF -> Icons.Outlined.PictureAsPdf
    null -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** 목록 왼쪽의 형식 배지 */
@Composable
fun DocBadge(kind: DocKind?, size: Dp = 40.dp) {
    val color = kind?.color() ?: Color.Gray
    Box(
        modifier = Modifier
            .size(size)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon(), contentDescription = kind?.label, tint = color, modifier = Modifier.size(size * 0.58f))
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** 오늘이면 시각만, 올해면 월·일, 그 전이면 연도까지 */
fun formatWhen(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val pattern = when {
        now.get(Calendar.YEAR) != then.get(Calendar.YEAR) -> "yyyy. M. d."
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "a h:mm"
        else -> "M월 d일"
    }
    return SimpleDateFormat(pattern, Locale.KOREA).format(Date(millis))
}
