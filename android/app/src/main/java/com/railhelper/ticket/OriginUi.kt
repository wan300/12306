package com.railhelper.ticket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object OriginColors {
    val Blue = Color(0xFF3B99FC)
    val BlueDark = Color(0xFF298CCF)
    val Page = Color(0xFFF5F5F5)
    val Card = Color.White
    val TextPrimary = Color(0xFF333333)
    val TextSecondary = Color(0xFF666666)
    val TextMuted = Color(0xFF999999)
    val Divider = Color(0xFFEAEAEA)
    val Warning = Color(0xFFFF7A1A)
    val Success = Color(0xFF32B976)
    val Danger = Color(0xFFE03412)
}

val OriginPagePadding = 12.dp
val OriginCardInnerPadding = 23.dp
val OriginCardShape = RoundedCornerShape(12.dp)
val OriginSmallShape = RoundedCornerShape(4.dp)

private val originColorScheme: ColorScheme
    @Composable get() = lightColorScheme(
        primary = OriginColors.Blue,
        onPrimary = Color.White,
        secondary = OriginColors.BlueDark,
        onSecondary = Color.White,
        primaryContainer = Color(0xFFE9F4FF),
        onPrimaryContainer = OriginColors.Blue,
        secondaryContainer = Color(0xFFE9F4FF),
        onSecondaryContainer = OriginColors.Blue,
        tertiary = OriginColors.Blue,
        tertiaryContainer = Color(0xFFE9F4FF),
        onTertiaryContainer = OriginColors.Blue,
        background = OriginColors.Page,
        onBackground = OriginColors.TextPrimary,
        surface = OriginColors.Card,
        onSurface = OriginColors.TextPrimary,
        surfaceVariant = Color(0xFFF5F5F5),
        onSurfaceVariant = OriginColors.TextSecondary,
        surfaceTint = Color.Transparent,
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Color(0xFFF5F5F5),
        surfaceContainerLow = Color.White,
        surfaceContainerLowest = Color.White,
        outline = Color(0xFFDDDDDD),
        outlineVariant = OriginColors.Divider,
        error = OriginColors.Danger,
    )

@Composable
fun OriginTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = originColorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}

fun Modifier.originPage(): Modifier = background(OriginColors.Page)

@Composable
fun OriginTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = OriginColors.Blue, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.84f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            actions()
        }
    }
}

@Composable
fun OriginCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = OriginCardInnerPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = OriginCardShape,
        colors = CardDefaults.elevatedCardColors(containerColor = OriginColors.Card),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun OriginPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = OriginSmallShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = OriginColors.Blue,
            contentColor = Color.White,
            disabledContainerColor = OriginColors.Blue.copy(alpha = 0.35f),
            disabledContentColor = Color.White,
        ),
        content = content,
    )
}

@Composable
fun OriginSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = OriginSmallShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = OriginColors.Blue,
            disabledContentColor = OriginColors.TextMuted,
        ),
        content = content,
    )
}

@Composable
fun OriginTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = OriginColors.Blue),
        content = content,
    )
}

@Composable
fun OriginFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = OriginColors.Blue,
    unfocusedBorderColor = OriginColors.Divider,
    focusedLabelColor = OriginColors.Blue,
    unfocusedLabelColor = OriginColors.TextSecondary,
    focusedTextColor = OriginColors.TextPrimary,
    unfocusedTextColor = OriginColors.TextPrimary,
    cursorColor = OriginColors.Blue,
)

@Composable
fun OriginSectionHeader(title: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = OriginColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (action != null) action()
    }
}

@Composable
fun OriginDivider(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(OriginColors.Divider))
}

@Composable
fun OriginStatusChip(text: String, color: Color = OriginColors.Blue) {
    Surface(
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        shape = RoundedCornerShape(22.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

fun originStatusColor(status: String): Color = when (status) {
    "running" -> OriginColors.Blue
    "success" -> OriginColors.Success
    "failed", "cancelled" -> OriginColors.Danger
    "paused" -> OriginColors.Warning
    else -> OriginColors.TextSecondary
}
