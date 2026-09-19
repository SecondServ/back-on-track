package com.hector.backontrack.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.hector.backontrack.MainActivity
import com.hector.backontrack.R
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面小组件：一键进入 App + 未完成的单次待办，点圈就能勾掉。
 *
 * 小组件的界面是 RemoteViews，由桌面进程画，跑不了我们的代码 ——
 * 但上面的元素可以挂 PendingIntent，点一下就会回到**我们自己的进程**里执行。
 * 勾掉待办走的就是这条路：点击发一条广播回到这个类，改完状态再刷新小组件。
 *
 * 刻意不显示循环待办 —— 它们每个周期都会回来，天天占着这几行
 * 只会把真正要做的事挤下去。
 */
class TasksWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> render(ctx, mgr, id) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) { super.onReceive(ctx, intent); return }

        val mode = intent.getStringExtra(EXTRA_MODE)
        if (mode == MODE_ADD) {
            // 直接进 App 的待办页。之前弹一个输入框，多此一举 ——
            // 这个 App 的新增方式是说话，不是打字。
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_TASKS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            return
        }

        val taskId = intent.getStringExtra(EXTRA_ID)
        if (taskId.isNullOrBlank()) {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            return
        }

        if (mode == MODE_DETAIL) {
            // 点文字只看详情，不改状态 —— 防误触
            ctx.startActivity(
                Intent(ctx, TaskPeekActivity::class.java)
                    .putExtra(EXTRA_ID, taskId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        // 点圈 = 切换完成状态。已完成的再点一下就翻回来，所以点错了有救。
        val wasDone = WidgetCache.load(ctx).firstOrNull { it.id == taskId }?.done ?: false
        WidgetCache.save(ctx, WidgetCache.load(ctx)
            .map { if (it.id == taskId) it.copy(done = !wasDone) else it })
        refresh(ctx)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Session.restoreInto(ctx)) {
                    Api.setTaskStatus(taskId, if (wasDone) "open" else "done")
                    // 拿服务端的结果重写一遍：万一刚才那下没成，它会自己回到原样
                    rebuildCache(ctx)
                    refresh(ctx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "小组件勾选失败", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "BotWidget"
        const val ACTION_TAP = "com.hector.backontrack.WIDGET_TAP"
        const val EXTRA_ID = "task_id"
        const val EXTRA_MODE = "mode"
        const val MODE_DONE = "done"
        const val MODE_ADD = "add"
        const val MODE_DETAIL = "detail"
        /** 从小组件进来时直接落在「待办」页 */
        const val EXTRA_OPEN_TASKS = "open_tasks"

        /**
         * 从服务端重建小组件缓存：未完成的在上，最近完成的划着线沉在底部。
         *
         * 只留最近一天、最多 5 条已完成 —— 它的用处是「点错了能点回来」，
         * 不是给你看历史；留多了会把真正要做的事挤下去。
         */
        suspend fun rebuildCache(ctx: Context) {
            val open = Api.listOpenTasks().filter { it.recurKind == null }
            // 划线区：最近 30 天内完成的，最多 5 条。
            // 循环待办不进来 —— 它们天天完成一次，会把这一小块刷满
            val done = Api.listDoneTasksSince(30).filter { it.recurKind == null }.take(5)
            WidgetCache.save(
                ctx,
                open.map {
                    WidgetCache.Row(it.id, it.title, it.priority, it.createdAt, it.dueAt, false)
                } + done.map {
                    WidgetCache.Row(it.id, it.title, it.priority, it.createdAt, it.dueAt, true)
                },
            )
        }

        /** App 拉完待办后调它，小组件立刻反映最新内容 */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TasksWidget::class.java))
            if (ids.isEmpty()) return
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            ids.forEach { render(ctx, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_tasks)

            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_open, open)
            views.setOnClickPendingIntent(R.id.widget_title, open)

            val svc = Intent(ctx, TasksWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // data 必须唯一，否则多个小组件实例会共用同一个 adapter
                data = android.net.Uri.parse("bot://widget/$widgetId")
            }
            views.setRemoteAdapter(R.id.widget_list, svc)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // 列表项的点击模板。必须是 MUTABLE —— 每一项的 fillInIntent
            // 要往里填 task_id，不可变的话填不进去，点谁都一样。
            val tap = PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, TasksWidget::class.java).setAction(ACTION_TAP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.widget_list, tap)

            val count = WidgetCache.load(ctx).count { !it.done }
            // 「N 条待办」而不是「待办 · N」：数字在前，扫一眼就知道积了多少
            views.setTextViewText(R.id.widget_title, if (count > 0) "$count 条待办" else "待办")

            mgr.updateAppWidget(widgetId, views)
        }
    }
}

/** ListView 的数据源。只读本地缓存，不联网 */
class TasksWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        object : RemoteViewsFactory {
            private var items: List<WidgetCache.Row> = emptyList()

            override fun onCreate() {}
            override fun onDataSetChanged() { items = WidgetCache.load(applicationContext) }
            override fun onDestroy() {}
            // 多出来的那一条是末尾的「＋ 新增待办」
            override fun getCount() = items.size + 1
            override fun getViewAt(position: Int): RemoteViews {
                if (position >= items.size) {
                    return RemoteViews(packageName, R.layout.widget_add_item).apply {
                        setOnClickFillInIntent(R.id.add_root, Intent()
                            .putExtra(TasksWidget.EXTRA_MODE, TasksWidget.MODE_ADD))
                    }
                }
                return RemoteViews(packageName, R.layout.widget_task_item).apply {
                    val row = items[position]
                    setTextViewText(R.id.item_title, row.title)
                    setTextViewText(R.id.item_age,
                        if (row.done) "已完成" else WidgetCache.ageLabel(row.createdAt))
                    setImageViewResource(R.id.item_dot,
                        if (row.done) R.drawable.widget_ring_done
                        else R.drawable.widget_ring_open)
                    // 划线。RemoteViews 复用视图，所以未完成的那支必须显式清掉，
                    // 否则滚动时会有没做完的条目莫名其妙带上删除线
                    setInt(R.id.item_title, "setPaintFlags",
                        if (row.done) Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
                        else Paint.ANTI_ALIAS_FLAG)
                    setInt(R.id.item_title, "setTextColor", applicationContext.getColor(
                        if (row.done) R.color.widget_text_dim else R.color.widget_text))
                    setInt(R.id.item_dot, "setColorFilter", applicationContext.getColor(
                        when (row.priority) {
                            "high" -> R.color.widget_p_high
                            "low" -> R.color.widget_p_low
                            else -> R.color.widget_p_med
                        }
                    ))
                    // 只有圈能改状态；点文字弹详情。集合项虽然共用一个点击模板，
                    // 但每个子控件可以带各自的 fillInIntent，所以两条路分得开。
                    setOnClickFillInIntent(R.id.item_dot, Intent()
                        .putExtra(TasksWidget.EXTRA_ID, row.id)
                        .putExtra(TasksWidget.EXTRA_MODE, TasksWidget.MODE_DONE))
                    setOnClickFillInIntent(R.id.item_title, Intent()
                        .putExtra(TasksWidget.EXTRA_ID, row.id)
                        .putExtra(TasksWidget.EXTRA_MODE, TasksWidget.MODE_DETAIL))
                }
            }
            override fun getLoadingView(): RemoteViews? = null
            override fun getViewTypeCount() = 2
            override fun getItemId(position: Int) = position.toLong()
            override fun hasStableIds() = false
        }
}
