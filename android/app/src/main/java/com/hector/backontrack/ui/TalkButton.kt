package com.hector.backontrack.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 松手时落在哪个扇区。forcedKind 为 null 表示交给 AI 判断 */
enum class TalkAction(val forcedKind: String?) {
    Send(null),      // 原地松手
    Cancel(null),    // 丢弃
    Todo("task"),
    Meal("meal"),
    /** 详情页：把这段话插进当前记录 */
    Append(null),
    /** 问问题：转成文字后让 AI 作答，不留录音 */
    Ask("ask"),      // 问问题：转成文字交给 AI 作答，不留录音
}

// 扇形几何。四片各占 45°，铺满按钮上方的半圆。
// 内径拉到 78dp（按钮半径 44dp 的近两倍），中间留出明显空隙，
// 相邻两片才不会因为手指粗而误判。
private val BUTTON_SIZE = 88.dp
// 环宽要能把文字「装进去」而不是「贴上去」：
// 文字高约 20dp，环宽 58dp 时上下各留 19dp，左右最窄处也有 6dp，
// 这样标签在视觉上是被弧条包住的。
// 环加厚会挤掉张角（间隙按重合量反推，环越宽间隙越大），
// 所以半径同步推远来补回弧长。
private val ARC_INNER = 118.dp
private val ARC_OUTER = 176.dp

/**
 * 相邻扇形的圆头互相压进去多少。
 *
 * 圆头（StrokeCap.Round）会在扇形两端各多伸出半个环宽，所以真正决定重合量的
 * 不是角度间隙，而是「环宽 − 想要的重合量」对应的弧长。直接写死角度的话，
 * 环宽一改重合量就失控 —— 之前留 6° 间隙，实际重合了 50dp。
 */
private val CAP_OVERLAP = 9.dp
private val BOTTOM_PAD = 18.dp
private val AREA_W = 360.dp
private val AREA_H = 245.dp

/** 每片扇形：数学角度（0°=右，90°=上），从右往左排 */
private data class Sector(val action: TalkAction, val centerDeg: Float, val label: String)

/** 按住键用在三个地方，可选的去向不一样 */
enum class TalkMode {
    /** 记录页：四个分类 */
    Full,
    /** 待办页：松手直接建待办，只留一个取消 */
    TodoOnly,
    /** 笔记详情页：左边取消，右边（和原地松手）追加到这条记录 */
    Append,
    /**
     * 灵感 / 备忘 / 选题 的详情页：原地松手 = 对 AI 下指令改写这条笔记，
     * 右滑才是把话追加进去。两件事差别很大，中心文案必须说清现在是哪一件
     */
    Command,
}

private val TODO_SECTORS = listOf(Sector(TalkAction.Cancel, 180f, "✕ 取消"))

// 右边这片必须是独立的动作。之前图省事复用了 Send，
// 而 Send 正是按下瞬间的默认状态 —— 它从第一帧就是选中的，
// 右滑时没有 0→1 的过渡，弹簧动画也就无从播放。
private val APPEND_SECTORS = listOf(
    Sector(TalkAction.Cancel, 180f, "✕ 取消"),
    Sector(TalkAction.Append, 0f, "✓ 追加"),
)

private val SECTORS = listOf(
    Sector(TalkAction.Todo, 22.5f, "✓ 待办"),
    Sector(TalkAction.Ask, 67.5f, "? 问题"),
    Sector(TalkAction.Meal, 112.5f, "🍽 饮食"),
    Sector(TalkAction.Cancel, 157.5f, "✕ 取消"),
)

/**
 * 按住说话 + 四向滑动分流。
 *
 *   按住            → 开始录音
 *   往左滑（取消）  → 松手丢弃，什么都不上传
 *   往左上（饮食）  → 直接归到饮食
 *   往右上（其他）  → 明确不是待办/饮食，剩下的交给 AI 细分
 *   往右滑（待办）  → 直接建待办，不在「记录」里留条目
 *   原地松手        → 正常处理
 *
 * 判定按角度分区而不是只看左右位移 —— 四个方向必须能区分开。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun TalkButtonWithSlide(
    recording: Boolean,
    busy: Boolean,
    onStart: () -> Unit,
    onEnd: (TalkAction) -> Unit,
    modifier: Modifier = Modifier,
    mode: TalkMode = TalkMode.Full,
) {
    var action by remember { mutableStateOf(TalkAction.Send) }
    val sectors = remember(mode) {
        when (mode) {
            TalkMode.Full -> SECTORS
            TalkMode.TodoOnly -> TODO_SECTORS
            TalkMode.Append, TalkMode.Command -> APPEND_SECTORS
        }
    }
    val measurer = rememberTextMeasurer()

    // 主题色必须在 composable 作用域里取出来，不能写进普通 lambda
    val cCancel = MaterialTheme.colorScheme.error
    val cTodo = MaterialTheme.colorScheme.tertiary
    val cMeal = Color(0xFFEF6C00)
    val cPrimary = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val colorOf: (TalkAction) -> Color = {
        when (it) {
            TalkAction.Cancel -> cCancel
            TalkAction.Todo -> cTodo
            TalkAction.Meal -> cMeal
            else -> cPrimary
        }
    }
    val appear by animateFloatAsState(if (recording) 1f else 0f, tween(160), label = "appear")
    // 虚线的流动相位 —— 让指引线看起来朝目标"流"过去，而不是一条死线
    val dashPhase by rememberInfiniteTransition(label = "dash").animateFloat(
        initialValue = 0f, targetValue = 28f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "phase",
    )
    // 被选中那片鼓起来，做出"被吸住"的手感
    val bulge = sectors.associate { sec ->
        sec.action to animateFloatAsState(
            if (action == sec.action) 1f else 0f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = sec.label,
        ).value
    }

    Box(modifier.size(AREA_W, AREA_H)) {

        if (appear > 0.01f) {
            Canvas(Modifier.matchParentSize()) {
                val cx = size.width / 2f
                val cy = size.height - BOTTOM_PAD.toPx() - BUTTON_SIZE.toPx() / 2f
                val inner = ARC_INNER.toPx()
                val outer = ARC_OUTER.toPx()
                val radius = (outer + inner) / 2f

                // 由重合量反推间隙角度：两端圆头各伸出 band/2，
                // 想让它们只压进去 CAP_OVERLAP，间隙弧长就得是 band - overlap
                val baseBand = outer - inner
                val gapDeg = Math.toDegrees(
                    ((baseBand - CAP_OVERLAP.toPx()) / radius).toDouble()).toFloat()
                val baseSweep = (45f - gapDeg).coerceAtLeast(8f)

                // 指向当前选中扇形的虚线：从按钮边缘连到扇形内缘。
                // 四个方向靠得近，光靠高亮不够 —— 有条线指过去才不会看错。
                sectors.firstOrNull { it.action == action }?.let { sec ->
                    val rad = Math.toRadians(sec.centerDeg.toDouble())
                    val ux = cos(rad).toFloat()
                    val uy = -sin(rad).toFloat()
                    val from = BUTTON_SIZE.toPx() / 2f + 8.dp.toPx()
                    val to = inner - 6.dp.toPx()
                    drawLine(
                        color = colorOf(sec.action),
                        start = Offset(cx + ux * from, cy + uy * from),
                        end = Offset(cx + ux * to, cy + uy * to),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(9.dp.toPx(), 7.dp.toPx()), -dashPhase),
                    )
                }

                sectors.forEach { sec ->
                    val active = action == sec.action
                    val grow = (bulge[sec.action] ?: 0f) * 9.dp.toPx()
                    val band = (outer - inner) + grow * 2
                    val color = colorOf(sec.action)
                    val sweep = baseSweep * appear
                    // Canvas 角度顺时针、0°在三点钟；数学角度要取负
                    drawArc(
                        color = if (active) color else color.copy(alpha = 0.20f),
                        startAngle = -sec.centerDeg - sweep / 2f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = band, cap = StrokeCap.Round),
                    )
                    // 文字画在圆环中线上，精确居中
                    val rad = Math.toRadians(sec.centerDeg.toDouble())
                    val lx = cx + radius * cos(rad).toFloat()
                    val ly = cy - radius * sin(rad).toFloat()
                    val style = TextStyle(
                        color = if (active) onAccent else color,
                        fontSize = if (active) 15.sp else 14.sp,
                        // 单行、不换行 —— 换行会撑破环宽，标签就"漏"出弧条了
                        lineHeight = 16.sp,
                    )
                    val laid = measurer.measure(sec.label, style)
                    drawText(
                        laid,
                        topLeft = Offset(
                            lx - laid.size.width / 2f,
                            ly - laid.size.height / 2f,
                        ),
                    )
                }
            }
        }

        val pressScale by animateFloatAsState(
            if (recording) 1.06f else 1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "press")

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = BOTTOM_PAD)
                .size(BUTTON_SIZE)
                .scale(pressScale)
                .clip(CircleShape)
                .background(
                    if (!recording) cPrimary
                    else colorOf(if (action == TalkAction.Send) TalkAction.Cancel else action)
                )
                .pointerInput(busy) {
                    if (busy) return@pointerInput
                    // 手指一离开按钮就开始分区判定 —— 判定必须比视觉更早，不能更晚
                    val threshold = BUTTON_SIZE.toPx() / 2f
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent().changes.firstOrNull { it.pressed }
                                ?: continue
                            action = TalkAction.Send
                            onStart()
                            var last = TalkAction.Send
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val dx = ch.position.x - down.position.x
                                val dy = ch.position.y - down.position.y
                                last = classify(dx, dy, threshold, mode)
                                action = last
                            }
                            onEnd(last)
                            action = TalkAction.Send
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(
                    when {
                        !recording -> "按住"
                        action == TalkAction.Cancel -> "✕"
                        action == TalkAction.Append -> "追加"
                        action == TalkAction.Todo -> "待办"
                        action == TalkAction.Meal -> "饮食"
                        action == TalkAction.Ask -> "问题"
                        mode == TalkMode.Command -> "指令"
                        else -> "松开"
                    },
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * 按手指相对按钮中心的角度分区。
 * 四片都在上半圈；手指往下滑时按左右就近归到「取消」或「待办」，
 * 因为按钮本来就贴着屏幕底部，往下没有空间做第五个选项。
 */
private fun classify(
    dx: Float, dy: Float, threshold: Float, mode: TalkMode,
): TalkAction {
    if (hypot(dx, dy) < threshold) return TalkAction.Send
    // 待办页和详情页都只有「往左取消」这一个岔路，其余一律是默认动作
    if (mode == TalkMode.TodoOnly) {
        return if (dx < -threshold) TalkAction.Cancel else TalkAction.Send
    }
    if (mode == TalkMode.Append || mode == TalkMode.Command) {
        return when {
            dx < -threshold -> TalkAction.Cancel
            dx > threshold -> TalkAction.Append
            else -> TalkAction.Send
        }
    }
    val up = -dy                                   // 屏幕 y 向下，转成数学坐标
    var deg = Math.toDegrees(atan2(up, dx).toDouble()).toFloat()
    if (deg < 0f) deg = if (dx >= 0f) 0f else 180f  // 下半圈就近吸附
    return when {
        deg < 45f -> TalkAction.Todo
        deg < 90f -> TalkAction.Ask
        deg < 135f -> TalkAction.Meal
        else -> TalkAction.Cancel
    }
}
