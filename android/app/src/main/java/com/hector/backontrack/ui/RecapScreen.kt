package com.hector.backontrack.ui

import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hector.backontrack.net.Api
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

/** 回顾页的三个板块。顺序就是屏幕上从上到下的顺序 */
enum class RecapSection(
    val key: String, val title: String, val tab: String, val blurb: String,
) {
    Tasks("tasks", "做成了什么", "做了什么", "这个月完成了多少、哪几件真有分量"),
    Ideas("ideas", "在琢磨什么", "琢磨什么", "灵感与选题汇成的线索，以及照着它们搜来的资讯"),
    Meal("meal", "吃了什么", "吃了什么", "常吃的食物、饮食面貌，以及基于你实际在吃的建议"),
}

/** "2026-09" —— 服务端按这个字符串加本地时区偏移切月份边界 */
private fun monthKey(cal: Calendar): String =
    "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

private fun monthLabel(cal: Calendar): String =
    "%d 年 %d 月".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

/**
 * 回顾页：左右滑动的三张当月看板 + 最后一页的往期存档。
 *
 * 为什么不是「入口列表 → 点进去」：这页的用法是每天扫一眼，
 * 多一层点击就等于少看几次。滑动比点击便宜，所以当月内容直接铺开，
 * 只有「翻旧账」才需要进到最后一页去选月份。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapScreen(onOpen: (RecapSection, String) -> Unit, modifier: Modifier = Modifier) {
    val month = remember { monthKey(Calendar.getInstance()) }
    val pager = rememberPagerState(pageCount = { RecapSection.entries.size + 1 })
    val scope = rememberCoroutineScope()
    val tabs = RecapSection.entries.map { it.tab } + "往期"

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEachIndexed { i, label ->
                val on = pager.currentPage == i
                TextButton(onClick = { scope.launch { pager.animateScrollToPage(i) } }) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            if (page == RecapSection.entries.size) {
                ArchivePage(onOpen)
            } else {
                // 只在这一页是当前页时才去生成 —— Pager 会预先组合相邻页，
                // 不拦住的话一进回顾就同时开三次模型调用，又慢又费钱
                SectionPage(
                    section = RecapSection.entries[page],
                    month = month,
                    active = pager.currentPage == page,
                )
            }
        }
    }
}

/** 当月的一张看板。数据自己取、自己缓存 */
@Composable
private fun SectionPage(section: RecapSection, month: String, active: Boolean) {
    var data by remember(section, month) { mutableStateOf<JSONObject?>(null) }
    var error by remember(section, month) { mutableStateOf<String?>(null) }
    var loading by remember(section, month) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun fetch(force: Boolean) {
        loading = true; error = null
        scope.launch {
            try {
                data = loadRecap(month, section, force)
            } catch (e: Exception) {
                error = "生成失败：${e.message?.take(100)}"
            } finally { loading = false }
        }
    }
    LaunchedEffect(active, section, month) {
        if (active && data == null && !loading) fetch(false)
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loading -> LoadingHint()
            error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, Modifier.padding(24.dp), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { fetch(false) }) { Text("重试") }
                }
            }
            data != null -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(bottom = 140.dp),
            ) {
                RecapBody(section, month, data!!)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { fetch(true) }) { Text("重新生成") }
            }
        }
    }
}

/** 往期：选月份，点进去看那个月的某一板块 */
@Composable
private fun ArchivePage(onOpen: (RecapSection, String) -> Unit) {
    var monthsBack by remember { mutableIntStateOf(1) }
    val cal = remember(monthsBack) {
        Calendar.getInstance().apply { add(Calendar.MONTH, -monthsBack) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { monthsBack++ }) { Text("‹ 更早") }
            Spacer(Modifier.weight(1f))
            Text(monthLabel(cal), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            // 本月在前面三页，这里只管往期，所以最近只能退到上个月
            TextButton(onClick = { if (monthsBack > 1) monthsBack-- }, enabled = monthsBack > 1) {
                Text("更近 ›")
            }
        }
        RecapSection.entries.forEach { sec ->
            Card(
                onClick = { onOpen(sec, monthKey(cal)) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(sec.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(sec.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("当月的三张看板在前面几页，左右滑就能看。这里只放翻旧账用的。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingHint() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text("正在把这个月的记录读一遍…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 取一份回顾。
 *
 * 每加一种图就多一个字段，老缓存里没有 —— 缺哪个都得重算一次。
 * 判断条件写成「这个板块该有的字段」，加新图时只改这里
 */
private suspend fun loadRecap(month: String, section: RecapSection, force: Boolean): JSONObject {
    var d = Api.recap(month, section.key, force)
    val stale = !d.has("by_day") ||
        // created_done 是修完成率时加的；没有它的旧缓存算出来的是错的（217%），重算一次
        (section == RecapSection.Tasks && (!d.has("calendar") || !d.has("created_done"))) ||
        (section == RecapSection.Ideas && !d.has("feed")) ||
        (section == RecapSection.Meal &&
            (!d.has("health") || !d.has("calories") ||
                !d.has("styles") || !d.has("restaurants")))
    if (!force && stale) d = Api.recap(month, section.key, true)
    return d
}

/** 往期存档点进来的整页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapDetailScreen(section: RecapSection, month: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun fetch(force: Boolean) {
        loading = true; error = null
        scope.launch {
            try {
                data = loadRecap(month, section, force)
            } catch (e: Exception) {
                error = "生成失败：${e.message?.take(100)}"
            } finally { loading = false }
        }
    }
    LaunchedEffect(section, month) { fetch(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${section.title} · ${month.replace("-", " 年 ")} 月", maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
                actions = {
                    TextButton(onClick = { fetch(true) }, enabled = !loading) { Text("重新生成") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> LoadingHint()
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(error!!, Modifier.padding(24.dp), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error)
                }
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp).padding(bottom = 32.dp),
                ) { RecapBody(section, month, data!!) }
            }
        }
    }
}

@Composable
private fun RecapBody(section: RecapSection, month: String, d: JSONObject) {
    when (section) {
        RecapSection.Tasks -> TasksRecap(d, month)
        RecapSection.Ideas -> IdeasRecap(d, month)
        RecapSection.Meal -> MealRecap(d, month)
    }
}

/** by_day 是 [{d, n}]：d 是本地日期几号，n 是当天条数 */
private fun daySeries(d: JSONObject): Pair<List<Int>, List<String>> {
    val a = d.optJSONArray("by_day") ?: return emptyList<Int>() to emptyList()
    val values = (0 until a.length()).map { a.getJSONObject(it).optInt("n") }
    val labels = (0 until a.length()).map { a.getJSONObject(it).optInt("d").toString() }
    return values to labels
}

/** 去掉正文里的 markdown 链接和裸 URL，只留下人话 */
private fun stripLinks(text: String): String = text
    .replace(Regex("""\(?\[[^\]]*]\(https?://[^)]*\)\)?"""), "")
    .replace(Regex("""https?://\S+"""), "")
    .replace(Regex("""\s+"""), " ")
    .trim()

private fun pairs(d: JSONObject, key: String): List<Pair<String, Int>> {
    val a = d.optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).map {
        val o = a.getJSONObject(it)
        o.optString("name") to o.optInt("count")
    }
}

/** 把小时数说成人话：一小时以内说分钟，两天以上说天 */
private fun spanLabel(hours: Double): String = when {
    hours < 1 -> "${(hours * 60).toInt()} 分钟"
    hours < 48 -> "${hours.toInt()} 小时"
    else -> "${(hours / 24).toInt()} 天"
}

@Composable
private fun TasksRecap(d: JSONObject, month: String) {
    val done = d.optInt("done")
    val created = d.optInt("created")
    val rate = d.optInt("rate")
    val createdDone = d.optInt("created_done", done.coerceAtMost(created))

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        BigNumber("$done", "完成")
        BigNumber("$rate%", "完成率")
        BigNumber("${d.optInt("open_count")}", "还没做")
    }

    Section("这个月新建的，完成了多少")
    DonutChart(
        // 环里只算这个月建的那一拨：完成了几件、还剩几件。
        // 上面的「完成」是这个月做完的全部（含以前建的），两个数不是一回事
        slices = listOf("已完成" to createdDone, "还没做" to (created - createdDone).coerceAtLeast(0)),
        centerLabel = "$rate%",
    )
    Text("分母是这个月新建的 $created 件 —— 用历史积压当分母，数字只会一个月比一个月难看",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    MoodSection(month)

    // 循环待办每天打没打卡。直接读打卡记录，不走回顾缓存 —— 刚打的卡马上就该画上
    CheckinSection(month) { Section(it) }

    val calArr = d.optJSONArray("calendar")
    if (calArr != null && calArr.length() > 0) {
        Section("这个月的日历")
        val doneBy = HashMap<Int, List<String>>()
        val dueBy = HashMap<Int, List<String>>()
        (0 until calArr.length()).forEach { i ->
            val o = calArr.getJSONObject(i)
            val day = o.optInt("d")
            o.optJSONArray("done")?.let { arr ->
                doneBy[day] = (0 until arr.length()).map { arr.getString(it) }
            }
            o.optJSONArray("due")?.let { arr ->
                dueBy[day] = (0 until arr.length()).map { arr.getString(it) }
            }
        }
        val (y, m) = month.split("-").map { it.toInt() }
        var selected by remember(month) { mutableStateOf<Int?>(null) }
        TaskCalendar(y, m, doneBy, dueBy, selected, onSelect = { selected = it })

        // 点开某一天才列清单 —— 全铺出来这页会长得没边
        selected?.let { day ->
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("$m 月 $day 日", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    val dn = doneBy[day].orEmpty()
                    val du = dueBy[day].orEmpty()
                    if (dn.isEmpty() && du.isEmpty()) {
                        Text("这天没有记录", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    dn.forEach { Text("✓ $it", style = MaterialTheme.typography.bodyMedium) }
                    du.forEach {
                        Text("○ $it（到期未做）", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    d.optString("nudge").takeIf { it.isNotBlank() }?.let {
        Section("温馨提醒")
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }

    // 完成得快的：从建到完成花了多久，同样是代码算的
    val swift = d.optJSONArray("swift")
    if (swift != null && swift.length() > 0) {
        Section("完成得最快的")
        (0 until swift.length()).forEach { i ->
            val t = swift.getJSONObject(i)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.optString("title"), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Spacer(Modifier.width(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(spanLabel(t.optDouble("hours", 0.0)),
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        d.optString("praise").takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val notable = d.optJSONArray("notable")
    if (notable != null && notable.length() > 0) {
        Section("值得记一笔的")
        (0 until notable.length()).forEach { i -> Bullet(notable.getString(i)) }
    }

    Section("这个月")
    Text(d.optString("summary"), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun IdeasRecap(d: JSONObject, month: String) {
    val context = LocalContext.current
    val byKind = d.optJSONObject("by_kind")
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        BigNumber("${d.optInt("count")}", "条笔记")
        byKind?.let {
            BigNumber("${it.optInt("idea")}", "灵感")
            BigNumber("${it.optInt("content")}", "选题")
            BigNumber("${it.optInt("memo")}", "备忘")
        }
    }

    val a = d.optJSONArray("by_day")
    if (a != null && a.length() > 0) {
        Section("哪几天冒出了新东西")
        val counts = (0 until a.length()).associate {
            val o = a.getJSONObject(it)
            o.optInt("d") to o.optInt("n")
        }
        val (y, m) = month.split("-").map { it.toInt() }
        Heatmap(y, m, counts)
    }

    Section("最近在想什么")
    Text(d.optString("summary"), style = MaterialTheme.typography.bodyMedium)

    val themes = d.optJSONArray("themes")
    if (themes != null && themes.length() > 0) {
        Spacer(Modifier.height(10.dp))
        (0 until themes.length()).forEach { i ->
            val t = themes.getJSONObject(i)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("· ${t.optString("title")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(t.optString("note"),
                    Modifier.padding(start = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // 这一页的主体：按上面那些线索联网搜来的资讯
    val feed = d.optJSONArray("feed")
    if (feed != null && feed.length() > 0) {
        Section("给你的资讯")
        (0 until feed.length()).forEach { i ->
            val f = feed.getJSONObject(i)
            val url = f.optString("url")
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .clickable(enabled = url.isNotBlank()) {
                        runCatching {
                            context.startActivity(android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)))
                        }
                    },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(f.optString("title"), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    // 模型有时会把引用链接塞进正文里。链接已经单独存在 url 字段、
                    // 整张卡也能点，正文里再来一串 https:// 只是噪音 —— 清掉
                    Text(stripLinks(f.optString("why")),
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(f.optString("source"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text("点开直接跳浏览器。链接是联网搜来的，提示词里写明了不许编 —— " +
            "万一打不开，说明那条没搜准，点右上角「重新生成」再试一次",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val q = d.optString("questions")
    if (q.isNotBlank()) {
        Section("还悬着的问题")
        Text(q, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MealRecap(d: JSONObject, month: String) {
    val foods = pairs(d, "foods")
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        BigNumber("${d.optInt("count")}", "条记录")
        BigNumber("${foods.size}", "种食物")
        val avg = d.optInt("avg_kcal")
        if (avg > 0) BigNumber("$avg", "日均 kcal")
    }
    if (d.optInt("avg_kcal") > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "日均只除以**有记录的 ${d.optInt("kcal_days")} 天** —— 没记录的日子不是吃了 0 卡，是不知道"
                .replace("**", ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    d.optJSONObject("health")?.let { h ->
        Spacer(Modifier.height(18.dp))
        Gauge(h.optInt("score"), caption = h.optString("note"))
    }

    if (foods.isNotEmpty()) {
        Section("常吃的")
        // 面积 = 频次。取代了之前那个词云 —— 字号缩放在条目一多时就是一团乱
        Treemap(foods)
    }

    // 卡路里柱状图铺在整月上（没记录的那天留空柱），x 轴才跟下面的热力图对得上
    val calArr = d.optJSONArray("calories")
    val dayArr = d.optJSONArray("by_day")
    if (calArr != null && calArr.length() > 0 && dayArr != null) {
        val kcalByDay = (0 until calArr.length()).associate {
            val o = calArr.getJSONObject(it)
            o.optInt("d") to o.optInt("kcal")
        }
        val labels = (0 until dayArr.length())
            .map { dayArr.getJSONObject(it).optInt("d") }
        Section("每天摄入（估算）")
        BarChart(labels.map { kcalByDay[it] ?: 0 }, labels.map { it.toString() })
        Text("按你记下的食物、分量和做法粗估，用来看趋势，不必当精确数字",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val cats = pairs(d, "categories")
    if (cats.isNotEmpty()) {
        Section("吃的构成 · 按食物")
        DonutChart(slices = cats, centerLabel = "${cats.sumOf { it.second }}")
    }

    val styles = pairs(d, "styles")
    if (styles.isNotEmpty()) {
        Section("在哪吃 · 什么菜系")
        DonutChart(slices = styles, centerLabel = "${styles.sumOf { it.second }}")
    }

    val shops = pairs(d, "restaurants")
    if (shops.isNotEmpty()) {
        Section("常去的店")
        RankBars(shops)
    }

    val a = d.optJSONArray("by_day")
    if (a != null && a.length() > 0) {
        Section("打卡")
        val counts = (0 until a.length()).associate {
            val o = a.getJSONObject(it)
            o.optInt("d") to o.optInt("n")
        }
        val (y, m) = month.split("-").map { it.toInt() }
        Heatmap(y, m, counts)
    }

    Section("饮食面貌")
    Text(d.optString("summary"), style = MaterialTheme.typography.bodyMedium)

    val advice = d.optString("advice")
    if (advice.isNotBlank()) {
        Section("给你的建议")
        Text(advice, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── 小零件 ───────────────────────────────────────────────────────────────

@Composable
private fun BigNumber(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(bottom = 8.dp)) {
        Text("• ", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}


/**
 * 这个月的心情折线。
 *
 * 直接从日记读，不走回顾的缓存 —— 回顾一天才重算一次，
 * 刚记的日记、刚改的心情应该马上就画上去。
 * 心情功能上线前的旧日记没有心情，第一次打开时顺手补算（每次最多 20 篇）。
 */
@Composable
private fun MoodSection(month: String) {
    var points by remember(month) { mutableStateOf<List<com.hector.backontrack.net.MoodPoint>?>(null) }
    var filling by remember(month) { mutableStateOf(false) }
    LaunchedEffect(month) {
        val first = runCatching { Api.listDiaryMoods(month) }.getOrNull() ?: return@LaunchedEffect
        points = first
        val missing = first.filter { it.mood == null }.map { it.id }
        if (missing.isNotEmpty()) {
            filling = true
            // 每批 5 篇：一篇要几秒，一口气补十几篇会超时；分批还能边补边画
            for (chunk in missing.chunked(5)) {
                if (runCatching { Api.inferMoods(chunk) }.isFailure) break
                points = runCatching { Api.listDiaryMoods(month) }.getOrNull() ?: points
            }
            filling = false
        }
    }
    val p = points ?: return
    if (p.isEmpty()) return
    Section("这个月的心情")
    val (y, m) = month.split("-").map { it.toInt() }
    val rated = p.filter { it.mood != null }
    if (rated.isEmpty()) {
        Text(if (filling) "正在从日记里读心情…" else "这个月的日记还没有心情",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    MoodLineChart(rated, y, m)
    val avg = rated.sumOf { it.mood!! }.toDouble() / rated.size
    val label = MOOD_LABELS.getValue(avg.roundToInt().coerceIn(1, 5))
    Text(
        "${rated.size} 篇日记，平均下来偏「$label」" +
            if (filling) "（还在补算旧日记的心情…）" else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
