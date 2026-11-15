package com.hector.backontrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.GlossaryTerm
import kotlinx.coroutines.launch

private val CATEGORIES = listOf("person" to "人", "place" to "地方", "other" to "事物")
private fun categoryLabel(c: String) = CATEGORIES.firstOrNull { it.first == c }?.second ?: "事物"

/** 编辑框里填的东西。空白一律当没填 */
private data class Draft(
    val term: String, val category: String, val fullName: String, val relation: String,
) {
    fun fullNameOrNull() = fullName.trim().ifBlank { null }
    fun relationOrNull() = relation.trim().ifBlank { null }
}

/**
 * 同一个名字的几行合成一组。
 *
 * 「侯丁豪」在不同日记里被听成侯迪豪、洪钧豪……每处都是一行，但对他来说是同一个人：
 * 界面上合成一张卡，确认、改名、补关系、删除都一次作用在整组上。
 */
private class TermGroup(val rows: List<GlossaryTerm>) {
    val first = rows.first()
    val term get() = first.term
    val ids = rows.map { it.id }
    val fullName = rows.firstNotNullOfOrNull { it.fullName?.takeIf(String::isNotBlank) }
    val relation = rows.firstNotNullOfOrNull { it.relation?.takeIf(String::isNotBlank) }
    val relationIsGuess = rows.firstOrNull { !it.relation.isNullOrBlank() }?.relationIsGuess ?: false
    /** 日记里实际写成的样子（跟名字本身一样的不算） */
    val variants = rows.mapNotNull { it.heard?.takeIf { h -> h != first.term } }.distinct()
    val contexts = rows.mapNotNull { it.context }.distinct()

    fun draft() = Draft(term, first.category, fullName ?: "", relation ?: "")

    /** 「张伟 · 同事」这样的一行说明。都没填就是 null */
    fun detail(): String? =
        listOfNotNull(fullName?.takeIf { it != term }, relation).joinToString(" · ").ifBlank { null }
}

private fun groupTerms(list: List<GlossaryTerm>): List<TermGroup> =
    list.groupBy { it.term.trim().lowercase() }.values.map(::TermGroup)

/**
 * 编辑时提示「是不是通讯录里已有的某一个」：跟这组的名字或任一写法共用一个汉字，
 * 或者英文前三个字母一样。「小」「老」这种谁都有的字不算。
 */
private fun similarGroups(g: TermGroup, all: List<TermGroup>): List<TermGroup> {
    val words = (listOf(g.term) + g.variants).map { it.lowercase() }
    val common = setOf('小', '老', '大', '阿', '的')
    fun close(a: String, b: String): Boolean {
        val ca = a.filter { it.code in 0x4E00..0x9FFF && it !in common }.toSet()
        if (ca.isNotEmpty() && b.any { it in ca }) return true
        return a.length >= 3 && b.length >= 3 && a.take(3) == b.take(3)
    }
    return all.filter { o ->
        o.term.lowercase() != g.term.lowercase() &&
            (listOf(o.term) + o.variants).any { w -> words.any { close(it, w.lowercase()) } }
    }.take(6)
}

/**
 * 通讯录（原「名词校对」）。
 *
 * AI 在后台扫日记，把人、地方、事物挑出来放在「待确认」里；
 * 他不定期进来过一遍：对的点「对的」，写法不对就改，顺手补全名和关系。
 *
 * 这些不是「A 一律换成 B」的替换规则，而是给 AI 的背景：
 * 同音的「小张（同事）」和「小章（室友）」可以同时在里面，转写时 AI 看上下文分辨。
 * 所以 AI 猜「小章 是不是 小张」时，他可以点「是另一个」，把小章单独存成一条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var terms by remember { mutableStateOf<List<GlossaryTerm>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 正在编辑的那一条；"new" = 正在手动添加 */
    var editingId by remember { mutableStateOf<String?>(null) }

    // 扫描以前的日记
    var unscanned by remember { mutableStateOf<Int?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanTotal by remember { mutableStateOf(0) }
    var scanFound by remember { mutableStateOf(0) }
    var scanNote by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try { terms = Api.listTerms(); error = null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "读取失败：${e.message?.take(60)}" }
        finally { loading = false }
    }
    LaunchedEffect(Unit) {
        load()
        unscanned = runCatching { Api.countUnscannedDiaries() }.getOrNull()
    }

    /** 先在界面上改掉，再去服务端；失败就重新拉一遍，以服务端为准 */
    fun act(ids: List<String>, local: (GlossaryTerm) -> GlossaryTerm?, remote: suspend () -> Unit) {
        terms = terms.mapNotNull { if (it.id in ids) local(it) else it }
        editingId = null
        scope.launch {
            try { remote() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = "没存上：${e.message?.take(60)}"; load() }
        }
    }

    /** 整组一起确认 / 修改。改成通讯录里已有的名字，就自然并进那一组 */
    fun save(ids: List<String>, d: Draft) = act(ids, {
        it.copy(term = d.term.trim(), category = d.category, fullName = d.fullNameOrNull(),
            relation = d.relationOrNull(), relationIsGuess = false, status = "confirmed")
    }) { Api.confirmTerms(ids, d.term.trim(), d.category, d.fullNameOrNull(), d.relationOrNull()) }

    /** 把某个写法从一组里拆出来，按它自己的样子单独待确认 */
    fun split(t: GlossaryTerm) {
        val word = t.heard ?: return
        act(listOf(t.id), { it.copy(term = word, status = "pending") }) { Api.splitTerm(t.id, word) }
    }

    /** 一批一批地扫，直到扫完；某一批一篇都没扫成就停，免得原地打转 */
    fun scanAll() {
        if (scanning) return
        scanning = true
        scanTotal = unscanned ?: 0
        scanFound = 0
        scanNote = null
        scope.launch {
            try {
                while (true) {
                    val p = Api.scanOldDiaries()
                    scanFound += p.found
                    unscanned = p.remaining
                    if (p.found > 0) load()
                    if (p.remaining == 0) break
                    if (p.scanned == 0) {
                        scanNote = "还有 ${p.remaining} 篇没扫成，过会儿再点一次"
                        break
                    }
                }
                if (scanNote == null) {
                    scanNote = if (scanFound > 0) "扫完了，找到 $scanFound 个，在下面等你确认"
                               else "扫完了，没找到新的"
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { scanNote = "扫描中断：${e.message?.take(60)}。再点一次会接着扫" }
            finally { scanning = false }
        }
    }

    // 待确认的一条一张卡，不合并：先逐条确认，确认之后同名的自然并成一组
    val pending = terms.filter { it.status == "pending" }.map { TermGroup(listOf(it)) }
    val confirmed = groupTerms(terms.filter { it.status == "confirmed" }).sortedBy { it.term.lowercase() }
    val confirmedNames = confirmed.map { it.term.lowercase() }.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通讯录") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 32.dp),
        ) {
            Text(
                "你生活里的人、地方和事物。AI 从日记里挑出来，你确认写法、补上全名和关系。" +
                    "转写时 AI 会参考这份通讯录，按上下文分辨说的是谁 —— 不会把同音的硬改成一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            ScanCard(unscanned, scanning, scanTotal, scanNote, onScan = { scanAll() })

            Spacer(Modifier.height(18.dp))
            Text("待确认 ${pending.size}", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            if (pending.isEmpty()) {
                Text("没有要确认的。写了新日记之后，这里会自己冒出来",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pending.forEach { g ->
                PendingCard(
                    g,
                    known = g.term.lowercase() in confirmedNames,
                    editing = editingId == g.first.id,
                    similar = similarGroups(g, confirmed),
                    onEdit = { editingId = g.first.id },
                    onCancelEdit = { editingId = null },
                    onSave = { d -> save(g.ids, d) },
                    onConfirm = { save(g.ids, g.draft()) },
                    // AI 猜「小章」是「小张」，其实是另一个人：按日记里的原样存成单独一条
                    onSeparate = { save(g.ids, g.draft().copy(term = g.variants.first())) },
                    onSplit = { word -> g.rows.filter { it.heard == word }.forEach(::split) },
                    onIgnore = { act(g.ids, { null }) { Api.ignoreTerms(g.ids) } },
                )
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已确认 ${confirmed.size}", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = { editingId = "new" }) { Text("+ 手动添加") }
            }
            if (editingId == "new") {
                TermEditor(
                    Draft("", "person", "", ""),
                    onDone = { d ->
                        editingId = null
                        scope.launch {
                            try {
                                Api.addTerm(d.term.trim(), d.category, d.fullNameOrNull(), d.relationOrNull())
                                load()
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { error = "没存上：${e.message?.take(60)}" }
                        }
                    },
                    onCancel = { editingId = null },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (confirmed.isEmpty() && editingId != "new") {
                Text("还没有确认过的",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CATEGORIES.forEach { (cat, label) ->
                val inCat = confirmed.filter {
                    (it.first.category.takeIf { c -> c in listOf("person", "place") } ?: "other") == cat
                }
                if (inCat.isEmpty()) return@forEach
                Spacer(Modifier.height(10.dp))
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                inCat.forEach { g ->
                    if (editingId == g.first.id) {
                        TermEditor(
                            g.draft(), similar = similarGroups(g, confirmed),
                            variants = g.variants,
                            onRemoveVariant = { word ->
                                // 这个写法其实不是他：从这组里去掉，以后也不再拿它来提示
                                val ids = g.rows.filter { it.heard == word }.map { it.id }
                                terms = terms.filterNot { it.id in ids }
                                scope.launch {
                                    try { Api.ignoreTerms(ids) }
                                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (e: Exception) { error = "没存上：${e.message?.take(60)}"; load() }
                                }
                            },
                            onDone = { d -> save(g.ids, d) }, onCancel = { editingId = null },
                        )
                    } else {
                        ConfirmedRow(g, onEdit = { editingId = g.first.id },
                            onDelete = { act(g.ids, { null }) { Api.deleteTerms(g.ids) } })
                    }
                }
            }
        }
    }
}

/** 「扫描以前的日记」：还有没扫过的才显示按钮；扫的时候显示进度 */
@Composable
private fun ScanCard(unscanned: Int?, scanning: Boolean, total: Int, note: String?, onScan: () -> Unit) {
    if (unscanned == null) return
    if (unscanned == 0 && note == null) return
    Spacer(Modifier.height(14.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            when {
                scanning -> {
                    val done = (total - unscanned).coerceAtLeast(0)
                    Text("正在扫以前的日记… $done / $total 篇",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                unscanned > 0 -> {
                    note?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("还有 $unscanned 篇以前的日记没扫过",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("只往通讯录里加待确认的词，日记原文一个字都不改。扫过的不会重复扫",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = onScan) { Text("扫描以前的日记") }
                }
                else -> Text(note ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ConfirmedRow(g: TermGroup, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onEdit() }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(g.term, style = MaterialTheme.typography.bodyLarge)
            val extra = listOfNotNull(
                g.detail(),
                g.variants.takeIf { it.isNotEmpty() }?.let { "也被写成 ${it.joinToString("、")}" },
            ).joinToString(" · ")
            Text(
                extra.ifBlank { "点一下补全名和关系" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = if (extra.isBlank()) 0.6f else 1f),
            )
        }
        TextButton(onClick = onEdit) { Text("改") }
        TextButton(onClick = onDelete) {
            Text("删", color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingCard(
    g: TermGroup,
    /** 通讯录里已经有这个名字了：确认就是并进去 */
    known: Boolean,
    editing: Boolean,
    /** 通讯录里写法相近的：直接在卡片上问「是同一个吗」 */
    similar: List<TermGroup>,
    onEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: (Draft) -> Unit,
    onConfirm: () -> Unit,
    onSeparate: () -> Unit,
    onSplit: (String) -> Unit,
    onIgnore: () -> Unit,
) {
    val single = g.rows.size == 1
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.term, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(categoryLabel(g.first.category),
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
                if (known) {
                    Spacer(Modifier.width(6.dp))
                    Text("通讯录里已有", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (!single) {
                    Spacer(Modifier.width(6.dp))
                    Text("${g.rows.size} 处", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 日记里实际写成的样子。只有一处就是一句话；好几处就列出来，点哪个能把它拆出去
            if (single && g.variants.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text("日记里写的是「${g.variants.first()}」，AI 猜是「${g.term}」",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary)
            } else if (g.variants.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("日记里写成了这几种，AI 觉得都是「${g.term}」。哪个不是，点它拆出去单独看：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    g.variants.forEach { v ->
                        AssistChip(onClick = { onSplit(v) }, label = { Text("$v  ×") })
                    }
                }
            }
            g.detail()?.let {
                Spacer(Modifier.height(2.dp))
                Text(if (g.relationIsGuess) "AI 从日记里看到：$it" else it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            g.contexts.firstOrNull()?.let {
                Spacer(Modifier.height(6.dp))
                val more = g.contexts.size - 1
                Text("“$it”" + (if (more > 0) "  等 ${g.contexts.size} 处" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 通讯录里有写法相近的：多半是同一个人被听错了，点一下就并过去
            if (!editing && !known && similar.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("通讯录里有相近的，是同一个吗？点一下就并过去：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    similar.forEach { o ->
                        AssistChip(
                            onClick = { onSave(o.draft()) },
                            label = { Text(listOfNotNull(o.term, o.relation).joinToString(" · ")) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (editing) {
                TermEditor(g.draft(), similar = similar, onDone = onSave, onCancel = onCancelEdit)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalButton(onClick = onConfirm) {
                        Text(if (single) "对的" else "都是他，确认")
                    }
                    OutlinedButton(onClick = onEdit) { Text("改一下") }
                    if (single && g.variants.isNotEmpty()) {
                        OutlinedButton(onClick = onSeparate) { Text("是另一个") }
                    }
                    TextButton(onClick = onIgnore) { Text("不用管") }
                }
            }
        }
    }
}

/**
 * 编辑一组：写法（必填）、类别、全名、关系。
 *
 * similar：通讯录里读音 / 字形相近的人。点一下就把写法和关系都换成那个人的 ——
 * 存下去就并进那一组，不用再手打一遍。
 * variants：这组曾被写成的样子，点 × 把不是他的去掉。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TermEditor(
    initial: Draft,
    onDone: (Draft) -> Unit,
    onCancel: () -> Unit,
    similar: List<TermGroup> = emptyList(),
    variants: List<String> = emptyList(),
    onRemoveVariant: (String) -> Unit = {},
) {
    var d by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (similar.isNotEmpty()) {
            Text("是不是通讯录里的这几个？点一下就并过去：",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                similar.forEach { o ->
                    FilterChip(
                        selected = d.term == o.term,
                        onClick = { d = o.draft() },
                        label = { Text(listOfNotNull(o.term, o.relation).joinToString(" · ")) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        OutlinedTextField(
            value = d.term,
            onValueChange = { d = d.copy(term = it.take(30)) },
            singleLine = true,
            label = { Text("写法") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CATEGORIES.forEach { (cat, label) ->
                FilterChip(
                    selected = d.category == cat,
                    onClick = { d = d.copy(category = cat) },
                    label = { Text(label) },
                )
            }
        }
        OutlinedTextField(
            value = d.fullName,
            onValueChange = { d = d.copy(fullName = it.take(40)) },
            singleLine = true,
            label = { Text("全名（可不填）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = d.relation,
            onValueChange = { d = d.copy(relation = it.take(40)) },
            singleLine = true,
            label = { Text("关系 / 说明（可不填）") },
            placeholder = { Text("比如：同事、大学室友、常去的咖啡馆") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (variants.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("曾被写成（不是他的点 × 去掉）：",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                variants.forEach { v ->
                    AssistChip(onClick = { onRemoveVariant(v) }, label = { Text("$v  ×") })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("取消") }
            TextButton(onClick = { onDone(d) }, enabled = d.term.isNotBlank()) { Text("确定") }
        }
    }
}
