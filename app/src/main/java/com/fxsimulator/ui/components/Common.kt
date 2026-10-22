package com.fxsimulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.fxsimulator.ui.theme.Size
import com.fxsimulator.ui.theme.TypeScale
import com.fxsimulator.ui.theme.Radius
import com.fxsimulator.ui.theme.Space
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily

/** 统一的深色面板容器 */
@Composable
fun GoldPanel(
    title: String? = null,
    modifier: Modifier = Modifier,
    accent: Color = GoldLabColors.Gold,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            // 面板底色做一层极轻的竖向渐变：纯色块在深色底上会显得"死"，
            // 渐变让相邻卡片有层次但不抢内容。
            .background(
                Brush.verticalGradient(
                    listOf(
                        GoldLabColors.SurfaceVariant.copy(alpha = 0.55f),
                        GoldLabColors.Surface,
                    ),
                ),
            )
            .border(1.dp, GoldLabColors.Outline.copy(alpha = 0.9f), RoundedCornerShape(Radius.card))
            .padding(Size.cardPadding),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(Size.accentBarWidth)
                        .height(Size.accentBarHeight)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(accent),
                )
                Spacer(Modifier.width(Space.md))
                Text(
                    text = title,
                    color = GoldLabColors.TextPrimary,
                    fontSize = TypeScale.sectionTitle,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = TypeScale.sectionTitleSpacing,
                )
            }
            Spacer(Modifier.height(Space.md))
        }
        content()
    }
}

/** 指标磁贴：标签在上、数值在下，数字用等宽字体 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = GoldLabColors.TextPrimary,
    sub: String? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = GoldLabColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = MonoFamily,
        )
        if (sub != null) {
            Text(
                text = sub,
                color = GoldLabColors.TextDisabled,
                fontSize = 10.sp,
                fontFamily = MonoFamily,
            )
        }
    }
}

/** 一行「标签 — 数值」 */
@Composable
fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = GoldLabColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = GoldLabColors.TextSecondary, fontSize = 12.5.sp)
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 小标签 */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text = text, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** 明确的风险 / 数据来源提示条 */
@Composable
fun NoticeBar(
    text: String,
    color: Color = GoldLabColors.Warning,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.notice))
            .background(color.copy(alpha = 0.07f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(Radius.notice))
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.Top,
    ) {
        // 左侧强调条：比整块染色更克制，长文本也不会糊成一片
        Box(
            Modifier
                .padding(top = 1.5.dp)
                .width(2.5.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(color.copy(alpha = 0.9f)),
        )
        Spacer(Modifier.width(Space.md))
        Text(text = text, color = color, fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}

/** 分隔线 */
@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GoldLabColors.Outline),
    )
}
