package app.lumo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val LumoControl = Color(0xFF4C8DFF)
private val LumoCard = Color(0xFF15171C)
private val LumoField = Color(0xFF181A20)
private val LumoDivider = Color(0xFF292C34)
private val LumoInactive = Color(0xFF8E94A3)

@Composable
fun LumoNeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(if (enabled) LumoControl else Color(0xFF343841))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color.White.copy(alpha = .48f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LumoNeonAvatar(
    name: String,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(LumoAvatarGradient),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(1).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = if (size >= 90.dp) {
                MaterialTheme.typography.displayLarge
            } else {
                MaterialTheme.typography.headlineSmall
            }
        )
    }
}

@Composable
fun LumoTabSymbol(index: Int, modifier: Modifier = Modifier) {
    val tint = Color.White
    Canvas(modifier.size(25.dp, 23.dp)) {
        val width = size.width
        val height = size.height
        val stroke = 1.8.dp.toPx()
        when (index) {
            0 -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(width * .10f, height * .10f),
                    size = androidx.compose.ui.geometry.Size(width * .78f, height * .62f),
                    cornerRadius = CornerRadius(width * .18f),
                    style = Stroke(stroke)
                )
                drawLine(tint, Offset(width * .27f, height * .71f), Offset(width * .19f, height * .92f), stroke)
                drawLine(tint, Offset(width * .19f, height * .92f), Offset(width * .43f, height * .72f), stroke)
            }
            1 -> {
                drawCircle(tint, width * .13f, Offset(width * .52f, height * .28f), style = Stroke(stroke))
                drawArc(
                    color = tint,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(width * .18f, height * .34f),
                    size = androidx.compose.ui.geometry.Size(width * .68f, height * .61f),
                    style = Stroke(stroke)
                )
            }
            else -> {
                drawCircle(tint, width * .26f, Offset(width * .5f, height * .48f), style = Stroke(stroke))
            }
        }
    }
}

@Composable
fun LumoBottomNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val names = listOf("Чаты", "Люди", "Профиль")
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF101116))
            .border(width = 1.dp, color = Color(0xFF1D2026))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        names.forEachIndexed { index, label ->
            val active = selected == index
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (active) {
                        Box(
                            Modifier
                                .size(width = 48.dp, height = 30.dp)
                                .background(Color(0xFF20345D), RoundedCornerShape(15.dp))
                        )
                    }
                    LumoTabSymbol(index)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
                    color = if (active) Color.White else LumoInactive,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
fun LumoSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                placeholder,
                color = Color(0xFF868C9A)
            )
        },
        leadingIcon = {
            Text(
                "⌕",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFF9AA0AD)
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = LumoDivider,
            unfocusedBorderColor = LumoDivider,
            cursorColor = LumoControl,
            focusedContainerColor = LumoField,
            unfocusedContainerColor = LumoField
        ),
        modifier = modifier
    )
}
