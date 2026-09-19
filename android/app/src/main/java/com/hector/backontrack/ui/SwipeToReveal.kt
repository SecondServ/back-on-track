package com.hector.backontrack.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ACTION_WIDTH = 88.dp

/**
 * 左滑露出操作按钮（类似 iOS 的列表）。
 *
 * 只露出按钮、不直接删除 —— 滑一下就删太容易误触，多一次点击换来的是安全感。
 * 只吃水平方向的拖动，竖直滑动照样交给列表滚动。
 */
@Composable
fun SwipeToReveal(
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (revealed: Boolean, close: () -> Unit) -> Unit,
) {
    val revealPx = with(LocalDensity.current) { ACTION_WIDTH.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val revealed = offset.value < -revealPx / 2

    fun settle(open: Boolean) {
        scope.launch { offset.animateTo(if (open) -revealPx else 0f) }
    }

    Box(modifier) {
        // 底下的删除区：卡片拉开之后才画，免得卡片圆角处漏出一圈红边
        if (offset.value < -1f) {
            Box(
                Modifier.matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier.fillMaxHeight().width(ACTION_WIDTH)
                        .clickable { settle(false); onAction() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(actionLabel, color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { settle(offset.value < -revealPx / 2) },
                        onDragCancel = { settle(offset.value < -revealPx / 2) },
                    ) { change, dx ->
                        change.consume()
                        scope.launch {
                            offset.snapTo((offset.value + dx).coerceIn(-revealPx, 0f))
                        }
                    }
                },
        ) { content(revealed) { settle(false) } }
    }
}
