package com.hector.backontrack.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hector.backontrack.AppPrefs
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Capture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「记录」页顶部的筛选。枚举顺序 = 屏幕上从左到右的顺序。
 *
 * 日记排在最后且**单独加锁**：它不进「默认」、也不进「全部」，
 * 只能主动点进来并过一次系统验证。
 */
private enum class CaptureFilter(val label: String) {
    Memo("备忘"), Ask("问题"), All("全部"), Meal("饮食"), Diary("日记"),
}

/**
 * 「备忘」这一档里的三个题材。null 表示三个都看。
 *
 * 它们不再各占顶栏一格 —— 顶栏留给真正常用的几个大类，
 * 细分收进二级菜单：已经停在「备忘」上时再点一下就展开。
 */
private enum class MemoSub(val kind: String, val label: String) {
    Content("content", "选题"), Idea("idea", "灵感"), Note("memo", "备忘"),
}

/** 「备忘」这一档收哪些 kind。note 是迁到 memo 之前的老分类 */
private val MEMO_GROUP = setOf("memo", "note", "content", "idea", "inbox")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureListScreen(
    reloadKey: Int,
    /** 每录完一条自增。新条目排在最上面，得主动滚回去，否则用户在底部根本看不见 */
    newCaptureKey: Int,
    onOpenCapture: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var captures by remember { mutableStateOf<List<Capture>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    // 「没网」和「真的出错了」要分开说：断网时用户能做的只有等信号回来，
    // 冲他弹一串 Unable to resolve host supabase.co 既吓人又没有任何指导意义
    var offline by remember { mutableStateOf(false) }
    // 下拉刷新：自动刷新之外，总得留一个「我现在就想看看有没有新的」的动作
    var refreshing by remember { mutableStateOf(false) }
    // 刚删掉的那条，给几秒「撤销」的机会
    var justTrashed by remember { mutableStateOf<Capture?>(null) }
    val listState = rememberLazyListState()

    var filter by remember { mutableStateOf(CaptureFilter.Memo) }
    // 解锁只在前台这一程有效，退到后台就重新上锁（见下面的生命周期监听）
    var diaryUnlocked by remember { mutableStateOf(false) }
    val activity = context.findActivity()

    // 新筛选在旧筛选的右边还是左边 —— 决定内容从哪一侧滑进来
    var slideFrom by remember { mutableIntStateOf(1) }
    fun goTo(f: CaptureFilter) {
        val order = CaptureFilter.entries
        slideFrom = if (order.indexOf(f) >= order.indexOf(filter)) 1 else -1
        filter = f
    }

    fun pick(f: CaptureFilter) {
        if (f != CaptureFilter.Diary || diaryUnlocked) { goTo(f); return }
        val act = activity
        // 起不来就把话说出来。点了毫无反应是最难查的那种故障
        if (act == null) { error = "打不开系统验证，日记暂时进不去"; return }
        val launched = promptDiaryUnlock(act) { ok ->
            if (ok) { diaryUnlocked = true; goTo(f) }
        }
        if (!launched) error = "系统验证没能打开 —— 手机得先设置锁屏密码或指纹"
    }

    // 左右滑在筛选之间走一格。**日记不在滑动序列里** ——
    // 手指一划就蹦出验证框太吓人，它只能被主动点开
    fun step(dir: Int) {
        val order = CaptureFilter.entries.filter { it != CaptureFilter.Diary }
        val i = order.indexOf(filter)
        if (i < 0) { goTo(CaptureFilter.Memo); return }
        order.getOrNull(i + dir)?.let { goTo(it) }
    }
    // 长按进入「可删除」状态：卡片开始抖，这时候把某条往右滑走就是删除。
    // 平时（没抖动）左右滑是切筛选 —— 两种含义靠状态分开，不靠方向。
    var editing by remember { mutableStateOf(false) }
    // 抖动时按返回，退的是这个状态，而不是整个页面
    BackHandler(editing) { editing = false }
    var newestFirst by remember { mutableStateOf(AppPrefs.captureSortDesc(context)) }
    // 每次进这个页面重新读一次，从设置里改完回来能立刻生效
    // 「备忘」里选中的题材。null = 三个题材都显示（相当于原来的「默认」）
    var memoSub by remember { mutableStateOf<MemoSub?>(null) }
    var subMenu by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try {
            captures = Api.listCaptures(newestFirst = newestFirst)
            error = null; offline = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // reloadKey 一变这个 effect 就重启，上一次的 refresh 于是被「取消」——
            // 那是正常收尾，不是读取失败。把它当错误吞掉的后果是：
            // 列表卡在旧数据上（新笔记永远刷不出来），顶上还挂一条吓人的红条。
            throw e
        } catch (e: Exception) {
            // UnknownHostException / ConnectException / SocketTimeoutException
            // 都是 IOException；HTTP 层的错走 ApiException，不会被误判成断网。
            // 注意这里不清空 captures —— 没网时留着上次的内容看，比空白页有用。
            if (e is java.io.IOException) { offline = true; error = null }
            else { error = "读取失败：${e.message?.take(80)}"; offline = false }
        }
    }

    LaunchedEffect(reloadKey, newestFirst) { refresh() }

    // 只在「刚录完」时滚回顶部。
    // 不能挂在 reloadKey 上 —— 那个从别的 App 切回来也会变，
    // 那时候把用户正在看的位置弹走，比看不见新条目更烦。
    LaunchedEffect(newCaptureKey) {
        if (newCaptureKey > 0) {
            refresh()
            if (newestFirst) listState.animateScrollToItem(0)
        }
    }

    // 有条目还在处理中就轮询，处理完自动填上
    LaunchedEffect(captures) {
        if (captures.any { it.correctedText == null && !it.stalled }) {
            delay(2500); refresh()
        }
    }

    // 切换「默认 / 全部」后回到顶部：换了筛选却停在原来的滚动位置，
    // 新露出来的条目全在视野上方，看起来就像「切了没反应」
    LaunchedEffect(filter) {
        if (captures.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // 切筛选时内容从来的那一侧滑进来 —— 瞬间替换会让人分不清「到底切没切」。
    // 只动一层 graphicsLayer，列表本身不重建，滚动位置和回收都不受影响
    val enter = remember { Animatable(0f) }
    LaunchedEffect(filter) {
        enter.snapTo(slideFrom.toFloat())
        enter.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
    }

    // 离开前台就重新上锁：隔一会儿别人拿起手机，不该还停在日记上
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                diaryUnlocked = false
                if (filter == CaptureFilter.Diary) filter = CaptureFilter.Memo
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(justTrashed) {
        if (justTrashed != null) { delay(5000); justTrashed = null }
    }

    fun trash(c: Capture) {
        captures = captures.filterNot { it.id == c.id }
        justTrashed = c
        scope.launch {
            try { Api.trashCapture(c.id) }
            catch (e: Exception) { error = "删除失败：${e.message?.take(80)}"; refresh() }
        }
    }

    fun undo() {
        val c = justTrashed ?: return
        justTrashed = null
        scope.launch {
            try { Api.restoreCapture(c.id) } catch (_: Exception) {}
            refresh()
        }
    }

    // 还在转写的条目一律显示 —— 刚录完就被筛掉最让人困惑。
    // 处理失败的同样一律显示：它的「分类」正是没能确定下来的那个东西，
    // 拿分类去过滤它，等于让失败的记录恰好因为失败而消失。
    val shown = when (filter) {
        // 「默认」和「全部」都把日记挡在门外
        CaptureFilter.Memo -> {
            val sub = memoSub
            if (sub == null) captures.filter {
                // 还没分类完（kind 为空）和处理失败的也放这儿 ——
                // 刚说完的那条必须能立刻看见，否则会以为没录上
                it.kind == null || it.stalled || it.kind in MEMO_GROUP
            } else captures.filter {
                it.kind == sub.kind || (sub == MemoSub.Note && it.kind == "note")
            }
        }
        CaptureFilter.Ask -> captures.filter { it.kind == "ask" }
        CaptureFilter.All -> captures.filter { it.kind != "diary" }
        // 饮食按**标题上那个日期**排，不按录音时间 ——
        // 补录三天前的那条，录音时间是今天，按录音排就会插到最上面，
        // 而你想看的是「这个月每天吃了什么」，顺序必须跟日期一致
        CaptureFilter.Meal -> captures.filter { it.kind == "meal" }
            .sortedBy { mealDay(it) }
            .let { if (newestFirst) it.reversed() else it }
        // 没解锁就一条都不给，连在列表里闪一下都不行
        CaptureFilter.Diary ->
            if (diaryUnlocked) captures.filter { it.kind == "diary" } else emptyList()
    }

    Column(modifier.fillMaxSize()) {
        if (offline) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()) {
                Text(
                    "没网，显示的是上次加载的内容。说话照常，联网后会自动补传。",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        error?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        justTrashed?.let {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("已移到垃圾箱", Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = ::undo) {
                        Text("撤销", color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }

        ReminderHealthCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        if (captures.isNotEmpty()) {
            if (editing) {
                // 抖动时把筛选换成说明 + 完成：一边抖一边还能切筛选只会让人更懵
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("把笔记往右滑走就删掉",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editing = false }) { Text("完成") }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 七个筛选一行放不下，横向可滑；排序按钮钉在右边不跟着滑走
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CaptureFilter.entries.forEach { f ->
                            if (f == CaptureFilter.Memo) {
                                // 已经停在「备忘」上时再点一下 → 展开二级菜单
                                Box {
                                    FilterChip(
                                        selected = filter == f,
                                        onClick = {
                                            if (filter == f) subMenu = true else pick(f)
                                        },
                                        label = {
                                            Text(memoSub?.let { "备忘 · ${it.label}" } ?: "备忘")
                                        },
                                        trailingIcon = {
                                            Text("▾", style = MaterialTheme.typography.labelMedium)
                                        },
                                    )
                                    DropdownMenu(
                                        expanded = subMenu,
                                        onDismissRequest = { subMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("三个都看") },
                                            onClick = { memoSub = null; subMenu = false },
                                        )
                                        MemoSub.entries.forEach { sub ->
                                            DropdownMenuItem(
                                                text = { Text(sub.label) },
                                                onClick = { memoSub = sub; subMenu = false },
                                            )
                                        }
                                    }
                                }
                            } else {
                                FilterChip(filter == f, { pick(f) }, { Text(f.label) })
                            }
                        }
                    }
                    TextButton(onClick = {
                        newestFirst = !newestFirst
                        AppPrefs.setCaptureSortDesc(context, newestFirst)
                    }) {
                        Text(if (newestFirst) "最新在前 ↓" else "最早在前 ↑",
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; refresh(); refreshing = false } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (captures.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("按住下面的按钮说话",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            } else if (shown.isEmpty()) {
                // 这里最需要能划回去：空列表上没有卡片可划，手势就挂在整块空白上
                FilterSwipe(onSwitch = { step(it) }, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            if (filter == CaptureFilter.Diary) "日记已加锁"
                            else "这个分类下没有记录\n左右滑一下换一个",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                    }
                }
            } else {
                // 手势挂在整块列表区域上，不再只挂在卡片上 ——
                // 内容少的时候下面一大片留白，挂卡片上等于那片是死区
                FilterSwipe(onSwitch = { step(it) }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        translationX = enter.value * size.width * 0.14f
                        alpha = 1f - 0.45f * kotlin.math.abs(enter.value)
                    },
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp,
                        // 给悬浮按钮留出落脚空间，最后一条不会被压住
                        bottom = 130.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(shown, key = { _, c -> c.id }) { i, c ->
                        WiggleSwipeItem(
                            editing = editing,
                            index = i,
                            onDelete = { trash(c) },
                            // 这一条负责「上下挤过来填空」：某条被拿掉后，
                            // 其余条目是滑到新位置的，不是瞬间跳过去
                            modifier = Modifier.animateItem(),
                        ) {
                            CaptureCard(
                                capture = c,
                                // 抖动时点卡片＝退出抖动，不是打开笔记 —— 免得想删反而点进去了
                                onClick = {
                                    if (editing) editing = false else onOpenCapture(c.id)
                                },
                                onLongClick = { editing = true },
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

/**
 * 转写成功、但后面的分析失败了。
 *
 * 这种记录的 asr_status 仍然是 done（转写确实成功了），只看那个字段的话
 * 界面会永远显示「转写中…」，把失败藏起来 —— 它就是这么骗过我们两天的。
 */
/**
 * 这条饮食该排在哪一天。
 *
 * 没有归属日期就回退到录音时间，但**必须换算成本地日期**：
 * created_at 是 UTC，晚上录的那条在 UTC 里已经是第二天了。
 * 直接截前 10 个字符跟 logged_on（本地日期）混排，顺序必然错 —— 我就这么错过一次。
 */
private fun mealDay(c: Capture): String {
    c.loggedOn?.let { return it }
    val utc = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val d = runCatching { utc.parse(c.createdAt.take(19)) }.getOrNull()
        ?: return c.createdAt.take(10)
    return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(d)
}

private val Capture.stalled: Boolean
    get() = correctedText == null && (asrStatus == "failed" || asrError != null)

/**
 * 左右滑都切「默认 / 全部」。
 *
 * 只有两个筛选，所以不分方向：往哪边划都是拨到另一边，不用记左右各是谁。
 * 滑动删除已经撤掉了（改成长按），这条手势才敢占满整块列表。
 */
@Composable
private fun FilterSwipe(
    onSwitch: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 直接把 lambda 当 pointerInput 的 key，每次重组都会重建手势识别器，
    // 手指划到一半就断了 —— 所以用 rememberUpdatedState 取最新的回调
    val switch by rememberUpdatedState(onSwitch)
    Box(
        modifier.pointerInput(Unit) {
            // 划够这么远才算数，免得竖着滚列表时手指一歪就切了
            val threshold = 48.dp.toPx()
            var dxTotal = 0f
            var fired = false
            detectHorizontalDragGestures(
                onDragStart = { dxTotal = 0f; fired = false },
            ) { change, dx ->
                change.consume()
                dxTotal += dx
                // 一次手势只切一下，来回蹭不会连着翻好几次
                // 左滑去下一个筛选，右滑回上一个
                if (!fired && abs(dxTotal) > threshold) {
                    fired = true; switch(if (dxTotal < 0) 1 else -1)
                }
            }
        }
    ) { content() }
}

/**
 * 长按后抖动；抖动时往右滑，卡片就跟着手指走，滑过一半直接飞出去 = 删除。
 *
 * 不再挂一个 ✕ 在角上：那个按钮是贴在卡片外面的，卡片在转它却不动，
 * 看着像两层皮。让「滑走」本身成为删除动作，手感和视觉就是一件事。
 *
 * 平时（没抖动）左右滑仍然是切「默认 / 全部」——
 * 两种含义靠状态分开，而不是靠方向，所以不会互相抢。
 */
@Composable
private fun WiggleSwipeItem(
    editing: Boolean,
    index: Int,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    // 直接把 lambda 当 pointerInput 的 key 会让手势识别器每次重组都重建，
    // 手指划到一半就断了 —— 所以取最新值而不是当 key
    val delete by rememberUpdatedState(onDelete)

    val angle by rememberInfiniteTransition(label = "wiggle").animateFloat(
        initialValue = -1.1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            tween(150, easing = LinearEasing),
            RepeatMode.Reverse,
            // 每张卡错开一点点相位，否则整列像一块板子在晃，很假
            initialStartOffset = StartOffset(index * 37),
        ),
        label = "angle",
    )

    // 退出抖动时，把滑到一半没松手的卡片弹回原位
    LaunchedEffect(editing) { if (!editing && offset.value != 0f) offset.animateTo(0f) }

    Box(
        modifier
            .graphicsLayer {
                translationX = offset.value
                rotationZ = if (editing) angle else 0f
                // 越滑越淡，飞出去时已经透明，不会在边缘"啪"地一下消失
                alpha = if (size.width > 0f) {
                    (1f - offset.value / size.width).coerceIn(0f, 1f)
                } else 1f
            }
            // **只在抖动时**拦横向拖动。平时一点都不碰，手势直接落到外层，
            // 这样卡片上和留白处滑起来是同一回事
            .then(
                if (!editing) Modifier else Modifier.pointerInput(Unit) {
                    val width = size.width.toFloat()
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value > width * 0.32f) {
                                    // 先让它飞出屏幕，**再**从列表里拿掉：顺序反了的话
                                    // 卡片会凭空消失，上下两条也就没有「挤过来」这一下
                                    offset.animateTo(width, tween(160))
                                    delete()
                                } else {
                                    offset.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, dx ->
                        change.consume()
                        // 跟着手指走，只认往右：往左没有别的删除方式，别让人以为有
                        scope.launch { offset.snapTo((offset.value + dx).coerceAtLeast(0f)) }
                    }
                }
            )
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptureCard(
    capture: Capture,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                capture.kind?.let { KindChipPublic(it); Spacer(Modifier.width(8.dp)) }
                Text(
                    capture.summary ?: if (capture.stalled) "处理失败，点进去可以重试" else "转写中…",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (capture.correctedText == null && !capture.stalled) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    // 什么时候记下来的
                    Text(formatCreatedAt(capture.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            (capture.correctedText ?: capture.finalText)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
        }
    }
}

fun kindLabel(kind: String): String = when (kind) {
    "task" -> "待办"
    "meal" -> "饮食"
    "content" -> "选题"
    "memo", "note" -> "备忘"   // note 是旧分类，已迁到 memo
    "idea" -> "灵感"
    "diary" -> "日记"
    "ask" -> "问题"
    else -> "待分类"
}

@Composable
fun KindChipPublic(kind: String) {
    val label = kindLabel(kind)
    val color = when (kind) {
        "task" -> Color(0xFF2E7D32)
        "meal" -> Color(0xFFEF6C00)
        "content" -> Color(0xFFC62828)
        "memo", "note" -> Color(0xFF1565C0)
        "idea" -> Color(0xFF6A1B9A)
        "diary" -> Color(0xFFAD1457)
        "ask" -> Color(0xFF00838F)
        else -> Color(0xFF757575)
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
        Text(label, Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = color)
    }
}
