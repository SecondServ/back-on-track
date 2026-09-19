package com.hector.backontrack.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hector.backontrack.R
import com.hector.backontrack.AppPrefs
import android.net.ConnectivityManager
import android.net.Network
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Uploader
import com.hector.backontrack.reminder.NudgeScheduler
import com.hector.backontrack.reminder.RecapScheduler
import com.hector.backontrack.reminder.ReminderScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 底部导航的图标。矢量图，不用 emoji —— emoji 在不同字体下长相不一，也不跟主题变色 */
@Composable
private fun TabIcon(res: Int) {
    Icon(painterResource(res), contentDescription = null, modifier = Modifier.size(22.dp))
}

private sealed interface Screen {
    data object Captures : Screen
    data object Tasks : Screen
    data class CaptureDetail(val id: String) : Screen
    data class TaskDetail(val id: String) : Screen
    data object Recap : Screen
    data class RecapDetail(val section: RecapSection, val month: String) : Screen
    data object Settings : Screen
    data object Trash : Screen
    data object Profile : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(onSignOut: () -> Unit, startOnTasks: Boolean = false) {
    // 从小组件的「＋」进来时直接落在待办页
    var screen by remember { mutableStateOf<Screen>(
        if (startOnTasks) Screen.Tasks else Screen.Captures) }
    // 记住最后停留的标签页 —— 从详情或设置返回时回到原来那页，而不是一律回「记录」
    var lastTab by remember { mutableStateOf<Screen>(Screen.Captures) }
    LaunchedEffect(screen) {
        if (screen is Screen.Captures || screen is Screen.Tasks ||
            screen is Screen.Recap
        ) lastTab = screen
    }
    var reloadKey by remember { mutableIntStateOf(0) }
    var newCaptureKey by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    // 第一次进来先请他建档案。档案直接决定转写把名字写对没有，
    // 越早建，后面积累的记录越准 —— 但只问一次，拒绝了就不再打扰。
    var askProfile by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!AppPrefs.profilePrompted(context)) {
            try {
                if (Api.getProfile()?.body.isNullOrBlank()) askProfile = true
            } catch (_: Exception) {
            }
        }
    }
    if (askProfile) {
        AlertDialog(
            onDismissRequest = { askProfile = false },
            title = { Text("先建个用户档案？") },
            text = {
                Text("写下你常说到的人名、地名和手上的项目，" +
                    "AI 转写时就不会把它们听错，整理的时候也更懂你在说什么。几分钟就好。")
            },
            confirmButton = {
                TextButton(onClick = {
                    AppPrefs.setProfilePrompted(context, true)
                    askProfile = false
                    screen = Screen.Profile
                }) { Text("现在就建") }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppPrefs.setProfilePrompted(context, true)
                    askProfile = false
                }) { Text("以后再说") }
            },
        )
    }
    val scope = rememberCoroutineScope()

    // 离线时录下的东西存在本地，这里负责把它们送出去：
    // 开 App 补一次，之后网一回来就自动补。
    suspend fun flushPending() {
        // 用 newCaptureKey 而不是 reloadKey：补传进来的是**新条目**，
        // 列表得滚回顶部让用户看见。只 reload 的话它们被默默插在视野上方，
        // 用户还得自己往上拉才找得到。
        val n = try {
            Uploader.flushPending(context) { newCaptureKey++ }
        } catch (_: Exception) { 0 }
        if (n > 0) { status = "补传了 $n 条"; reloadKey++ }
    }
    LaunchedEffect(Unit) { flushPending() }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { flushPending() } }
        }
        try { cm?.registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
        onDispose { try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {} }
    }

    // 提示条几秒后自己消失，不用用户去关
    LaunchedEffect(status) { if (status != null) { delay(3500); status = null } }

    // 把服务端算好的提醒同步成手机上的闹钟。
    // 这是应用级的事，不该挂在某个列表页上 —— 之前挂在列表页，
    // 重构时把那一行弄丢了，提醒就整整齐齐地不再更新了。
    suspend fun syncReminders() {
        try { ReminderScheduler.syncFromServer(context) } catch (_: Exception) {}
    }
    LaunchedEffect(Unit) { syncReminders() }

    // 轻推的闹钟排在应用级：用户可能从不进设置页，但它得照常工作
    LaunchedEffect(Unit) { NudgeScheduler.schedule(context) }

    // 每晚 23:59 重算三张看板，第二天早上打开就是现成的
    LaunchedEffect(Unit) { RecapScheduler.schedule(context) }

    // 垃圾箱里删除超过 30 天的，打开 App 时顺手永久清掉 —— 不需要服务端定时任务
    LaunchedEffect(Unit) {
        try { Api.purgeExpiredTrash(AppPrefs.TRASH_KEEP_DAYS) } catch (_: Exception) {}
    }

    // 每次回到前台再同步一次：在别的端产生的提醒，
    // 不这样做的话这台手机要等下次冷启动才知道
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 先让列表去重新拉一次，**再**同步提醒。
                // 反过来写的话，刷新排在一次网络往返之后 —— 同步慢一点或者卡住，
                // 切回前台就看不到新记录，非得手动下拉才出得来。
                reloadKey++
                scope.launch { syncReminders() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 安卓的肌肉记忆：边缘一划就是返回。
    // 这个 App 只有一个 Activity，页面切换全靠自己的 screen 状态 ——
    // 不把系统返回接管过来，系统只知道「返回栈里没有上一个页面了」，于是直接退出 App。
    // 注意要放在下面那些 return 之前，否则进了子页面它根本不会被注册。
    BackHandler(enabled = screen !is Screen.Captures) {
        when (screen) {
            is Screen.CaptureDetail -> { screen = lastTab; reloadKey++ }
            is Screen.TaskDetail -> { screen = lastTab; reloadKey++ }
            is Screen.RecapDetail -> screen = Screen.Recap
            is Screen.Recap -> screen = Screen.Captures
            is Screen.Trash, is Screen.Profile -> screen = Screen.Settings
            is Screen.Settings -> screen = lastTab
            is Screen.Tasks -> screen = Screen.Captures
            else -> Unit
        }
    }

    (screen as? Screen.CaptureDetail)?.let { detail ->
        CaptureDetailScreen(detail.id, onBack = { screen = lastTab; reloadKey++ })
        return
    }
    (screen as? Screen.TaskDetail)?.let { detail ->
        TaskDetailScreen(
            detail.id,
            onBack = { screen = lastTab; reloadKey++ },
            onOpenCapture = { screen = Screen.CaptureDetail(it) },
        )
        return
    }
    (screen as? Screen.RecapDetail)?.let { d ->
        RecapDetailScreen(d.section, d.month, onBack = { screen = Screen.Recap })
        return
    }
    if (screen is Screen.Settings) {
        SettingsScreen(
            onBack = { screen = lastTab },
            onSignOut = onSignOut,
            onOpenTrash = { screen = Screen.Trash },
            onOpenProfile = { screen = Screen.Profile },
        )
        return
    }
    if (screen is Screen.Trash) {
        TrashScreen(onBack = { screen = Screen.Settings })
        return
    }

    if (screen is Screen.Profile) {
        ProfileScreen(onBack = { screen = Screen.Settings })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (screen) {
                        is Screen.Tasks -> "待办"
                        is Screen.Recap -> "回顾"
                        else -> "Back on Track"
                    })
                },
                actions = { TextButton(onClick = { screen = Screen.Settings }) { Text("设置") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen is Screen.Captures,
                    onClick = { screen = Screen.Captures },
                    icon = { TabIcon(R.drawable.ic_tab_mic) }, label = { Text("记录") },
                )
                NavigationBarItem(
                    selected = screen is Screen.Tasks,
                    onClick = { screen = Screen.Tasks },
                    icon = { TabIcon(R.drawable.ic_tab_check) }, label = { Text("待办") },
                )
                NavigationBarItem(
                    selected = screen is Screen.Recap,
                    onClick = { screen = Screen.Recap },
                    icon = { TabIcon(R.drawable.ic_tab_chart) }, label = { Text("回顾") },
                )
            }
        },
    ) { pad ->
        // 说话键提到这一层：盖在内容之上，两个标签页都能随时按住说话
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (screen) {
                is Screen.Recap -> RecapScreen(
                    onOpen = { sec, month -> screen = Screen.RecapDetail(sec, month) },
                    modifier = Modifier.fillMaxSize(),
                )
                is Screen.Tasks -> TaskListScreen(
                    reloadKey = reloadKey,
                    onOpenTask = { screen = Screen.TaskDetail(it) },
                    onOpenCapture = { screen = Screen.CaptureDetail(it) },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> CaptureListScreen(
                    reloadKey = reloadKey,
                    newCaptureKey = newCaptureKey,
                    onOpenCapture = { screen = Screen.CaptureDetail(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AnimatedVisibility(
                visible = status != null,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(status.orEmpty(),
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // 回顾页不放说话键：那是看板，不是记录场景，而且它正好压在日历上、
            // 挡得人点不到月末那几天。要说话，切回「记录」就是一下的事
            if (screen !is Screen.Recap) TalkOverlay(
                todoMode = screen is Screen.Tasks,
                onCaptureCreated = { newCaptureKey++ },
                onCaptureUpdated = { reloadKey++ },
                onWentToTasks = { screen = Screen.Tasks },
                // 提问笔记会被「默认显示的分类」过滤掉，留在列表里等于找不着，
                // 所以问完直接把对话页推到眼前。
                // 这段协程挂在 MainScaffold 上而不是 TalkOverlay 上：
                // 一跳进详情页 TalkOverlay 就退出组合，它的作用域会被取消，
                // 转写会断在半路。
                onAskRecorded = { file, ms ->
                    scope.launch {
                        try {
                            val id = Api.createAskPlaceholder()
                            screen = Screen.CaptureDetail(id)   // 先进对话页
                            val q = Api.transcribeAudio(file, ms)
                            if (q.isBlank()) throw IllegalStateException("没听清")
                            Api.fillAskQuestion(id, q)
                        } catch (e: Exception) {
                            status = "问答失败：${e.message?.take(120)}"
                        } finally {
                            file.delete()
                        }
                    }
                },
                onStatus = { status = it },
            )
        }
    }
}
