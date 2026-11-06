package com.railhelper.ticket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LoginHeading(verifying: Boolean) {
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("1 账号登录", fontSize = 12.sp, color = if (!verifying) OriginColors.Blue else OriginColors.TextMuted)
        HorizontalDivider(Modifier.width(24.dp), color = OriginColors.Divider)
        Text("2 身份验证", fontSize = 12.sp, color = if (verifying) OriginColors.Blue else OriginColors.TextMuted)
    }
    Spacer(Modifier.height(10.dp))
    Text(if (verifying) "验证身份" else "登录 12306", fontSize = 28.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold, color = OriginColors.TextPrimary)
    Text(if (verifying) "完成验证，即可继续购票" else "登录后可添加乘车人、提交候补订单",
        fontSize = 14.sp, lineHeight = 22.sp, color = OriginColors.TextSecondary)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    TextField(value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        label = { Text(label, fontSize = 14.sp) },
        shape = RoundedCornerShape(10.dp),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        trailingIcon = trailingIcon,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFFF4F7FA), unfocusedContainerColor = Color(0xFFF4F7FA),
            disabledContainerColor = Color(0xFFF4F7FA),
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ))
}

@Composable
internal fun LoginAction(text: String, enabled: Boolean, loading: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(10.dp)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
