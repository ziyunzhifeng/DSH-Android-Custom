package io.github.jackwener.dsh.feature.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.MainActivity
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.data.ThemeMode
import io.github.jackwener.dsh.core.designsystem.*
import io.github.jackwener.dsh.core.wechat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@HiltViewModel
class WeChatCollectionViewModel @Inject constructor(
    val collection: WeChatCollection, private val controller: WeChatScanController,
) : ViewModel() {
    private val selectedAccount = MutableStateFlow<String?>(null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val ranking = selectedAccount.flatMapLatest { collection.dao.hot(it) }
    val accounts = collection.dao.accountHeat()
    private val selectedBatch = MutableStateFlow<String?>(null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val batch = selectedBatch.flatMapLatest { if (it == null) collection.dao.latestBatch() else collection.dao.observeBatch(it) }
    val recentBatches = collection.dao.recentBatches()
    @OptIn(ExperimentalCoroutinesApi::class)
    val batchProgress = batch.flatMapLatest { if (it == null) flowOf(emptyList()) else collection.dao.batchProgress(it.id) }
    fun selectAccount(id: String?) { selectedAccount.value = id }
    fun startFollowed(limit: Int) = operation { selectedBatch.value = controller.startFollowed(limit); "关注列表扫描已开始，每号最多 $limit 篇。" }
    fun resumeBatch(id: String) = operation { controller.resumeBatch(id); selectedBatch.value = id; "正在继续关注列表扫描。" }
    fun stopBatch(id: String) = operation { controller.stopBatch(id); "批量扫描已停止，进度和文章已保留。" }
    val scan = collection.dao.latest()
    private val mutable = MutableStateFlow("")
    val message = mutable.asStateFlow()
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()
    init { viewModelScope.launch { controller.recover() } }
    fun start() = operation { controller.start(); "扫描已开始，可在执行记录中查看大模型导航过程。" }
    fun resume(id: String) = operation { controller.resume(id); "正在继续扫描。" }
    fun stop(id: String) = operation { controller.stop(id); "已停止，已有数据保留。" }
    fun capture() = operation { val result = collection.capture(); "已保存：${result.getString("title")}" }
    private fun operation(block: suspend () -> String) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true; mutable.value = "正在准备…"
            try { mutable.value = block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = when (error) {
                is io.github.jackwener.dsh.core.engine.NoCredentialsException -> "请先在 DSH Custom 中配置模型服务或登录模型账号，再开始自动扫描。"
                else -> "未能执行：${error.message?.take(180) ?: "请检查连接与模型配置"}"
            } }
            finally { working.value = false }
        }
    }
}

@AndroidEntryPoint
class WeChatCollectionActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository
    private val vm: WeChatCollectionViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val preferences by settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val articles by vm.ranking.collectAsStateWithLifecycle(initialValue = emptyList())
            val scan by vm.scan.collectAsStateWithLifecycle(initialValue = null)
            val batch by vm.batch.collectAsStateWithLifecycle(initialValue = null)
            val recentBatches by vm.recentBatches.collectAsStateWithLifecycle(initialValue = emptyList())
            val batchAccounts by vm.batchProgress.collectAsStateWithLifecycle(initialValue = emptyList())
            val accounts by vm.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
            val message by vm.message.collectAsStateWithLifecycle()
            val busy by vm.busy.collectAsStateWithLifecycle()
            var articleLimit by rememberSaveable { mutableIntStateOf(50) }
            var showHistory by remember { mutableStateOf(false) }
            var editLimit by remember { mutableStateOf(false) }
            var limitText by remember { mutableStateOf("50") }
            var accountLabel by remember { mutableStateOf("全部公众号") }
            var selected by remember { mutableStateOf<CollectedArticle?>(null) }
            var comments by remember { mutableStateOf<List<CollectedComment>>(emptyList()) }
            val scope = rememberCoroutineScope()
            DshTheme(preferences?.theme ?: ThemeMode.System) {
                val c = Dsh.colors
                SettingsPage("公众号采集与热榜", { finish() }) {
                    Text("批量扫描：先在微信打开“我关注的公众号”列表，再回到这里开始。模型逐个进入账号，每号默认采集最近 50 篇，可调整；也可从当前文章单独扫描。请保持手机解锁及微信前台；离开微信会暂停。", Modifier.padding(start = 4.dp, top = 12.dp), style = DshType.xs13, color = c.labelTertiary)
                    SettingsGroupTitle("关注列表扫描")
                    SettingsCard {
                        SettingsRow("每号采集篇数", "新任务每号 $articleLimit 篇（1–50）；现有任务保持原数量", divider = false,
                            onClick = { limitText = articleLimit.toString(); editLimit = true })
                        batch?.let { run ->
                            val state = when (run.state) { "running" -> "扫描中"; "completed" -> "已到关注列表末尾"; "paused" -> "已暂停"; else -> "已停止" }
                            val finished = batchAccounts.count { it.scan.state in setOf("completed", "partial") }
                            val skipped = batchAccounts.count { it.scan.state == "skipped" }
                            SettingsRow("$state · 已处理 $finished 个公众号", "已保存 ${batchAccounts.sumOf { it.count }} 篇 · 每号 ${run.articleLimit} 篇 · 跳过 $skipped 个 · 名单总数未知", divider = false,
                                leading = { if (run.state == "running") Spinner() else StateDot(DotKind.Idle) })
                            batchAccounts.lastOrNull { it.scan.state == "running" || it.scan.state == "paused" }?.let {
                                SettingsRow("当前：${it.scan.account}", "${it.count}/${it.scan.limit} 篇 · ${scanStateLabel(it.scan.state)}")
                            }
                            SettingsRow("查看批量执行记录", run.reason?.let(::scanReasonLabel) ?: "逐号导航和采集记录", onClick = {
                                startActivity(Intent(this@WeChatCollectionActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, run.sessionId))
                            })
                            if (run.state in setOf("running", "paused")) SettingsRow("停止关注列表扫描", "保留账号进度和文章", onClick = { vm.stopBatch(run.id) }, titleColor = c.error)
                            if (run.state in setOf("paused", "stopped")) SettingsRow("继续关注列表扫描", "保留当前账号及已处理账号的去重记录", onClick = { if (!busy) vm.resumeBatch(run.id) })
                        }
                        Column(Modifier.padding(16.dp)) {
                            DButton("扫描我的关注公众号", { vm.startFollowed(articleLimit) }, Modifier.fillMaxWidth(), enabled = !busy && batch?.state != "running" && scan?.scan?.state != "running")
                        }
                        if (recentBatches.isNotEmpty()) SettingsRow("历史批次", "查看最近 10 批任务或继续未完成的批次", onClick = { showHistory = true })
                        batchAccounts.filter { it.scan.state in setOf("partial", "skipped") }.forEach {
                            SettingsRow(it.scan.account, "${if (it.scan.state == "skipped") "已跳过" else "部分采集"} · ${it.count} 篇 · ${it.scan.reason?.let(::scanReasonLabel) ?: "未知原因"}")
                        }
                    }
                    SettingsGroupTitle("单个公众号扫描")
                    SettingsCard {
                        scan?.takeIf { it.scan.batchId == null }?.let { progress ->
                            val run = progress.scan
                            val state = when (run.state) { "running" -> "扫描中"; "completed" -> "已达采集数量"; "partial" -> "部分完成"; "paused" -> "已暂停，检查执行记录后继续"; else -> "已停止" }
                            SettingsRow(run.account, "$state · ${progress.count}/${run.limit} 篇", divider = false,
                                leading = { if (run.state == "running") Spinner() else StateDot(if (run.state == "completed") DotKind.Done else DotKind.Idle) })
                            SettingsRow("查看执行记录", "模型操作、权限请求和失败原因", onClick = {
                                startActivity(Intent(this@WeChatCollectionActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, run.sessionId))
                            })
                            if (run.state == "running") SettingsRow("停止扫描", "停止本任务后续操作，保留已采集内容", onClick = { vm.stop(run.id) }, titleColor = c.error)
                            if (run.state in setOf("paused", "stopped", "partial")) SettingsRow("继续扫描", "复用当前任务和去重记录", onClick = { if (!busy) vm.resume(run.id) })
                        }
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DButton("开始扫描最近 50 篇", { vm.start() }, Modifier.fillMaxWidth(), enabled = !busy && batch?.state != "running" && scan?.scan?.state != "running")
                            DButton("仅采集当前文章", { vm.capture() }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, enabled = !busy && batch?.state != "running" && scan?.scan?.state != "running")
                        }
                    }
                    if (message.isNotBlank()) Text(message, Modifier.padding(4.dp, 8.dp), style = DshType.xs13, color = c.labelTertiary)
                    SettingsGroupTitle("公众号热度统计")
                    Text("按已采集文章的阅读数合计排序。仅统计有读数的文章，缩略数为估算，“+”为下界；不同账号覆盖篇数和时间可能不同，不代表全量影响力。", Modifier.padding(4.dp, 8.dp), style = DshType.xxs12, color = c.labelTertiary)
                    SettingsCard {
                        SettingsRow("全部公众号", "查看全部已采集文章", onClick = { accountLabel = "全部公众号"; vm.selectAccount(null) }, divider = false)
                        accounts.forEachIndexed { index, heat ->
                            SettingsRow("${index + 1}. ${heat.account}",
                                "${heat.articles} 篇 · 阅读合计 ${heat.totalReads?.toString() ?: "未知"}${if (heat.lowerBounds > 0) "（含下界）" else ""} · 有读数 ${heat.measuredReads}/${heat.articles}\n最高阅读 ${heat.peakReads?.toString() ?: "未知"} · 评论合计 ${heat.totalComments?.toString() ?: "未知"}（覆盖 ${heat.measuredComments}/${heat.articles} 篇）\n更新于 ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(heat.updatedAt))}",
                                onClick = { accountLabel = heat.account; vm.selectAccount(heat.accountId) })
                        }
                    }
                    SettingsGroupTitle("$accountLabel · 文章热榜")
                    Text("最多展示前 200 篇。按显示阅读数换算排序，评论总数作为次级排序；缺失值排后。缩略值不代表精确读数，“10万+”仅为下界，已采集条数不等于总评论数。", Modifier.padding(start = 4.dp, bottom = 8.dp), style = DshType.xxs12, color = c.labelTertiary)
                    val shown = articles
                    SettingsCard {
                        if (shown.isEmpty()) SettingsRow("暂无已采集文章", "完成采集后会自动更新热榜", divider = false)
                        shown.forEachIndexed { index, article ->
                            SettingsRow("${index + 1}. ${article.title}",
                                "${article.account} · 阅读 ${article.readText ?: "未提供"} · 评论 ${article.commentText ?: "未知"}\n本次已载入 ${article.collectedComments} 条留言 · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(article.collectedAt))}",
                                divider = index > 0, onClick = { selected = article; comments = emptyList(); scope.launch { comments = vm.collection.dao.comments(article.id) } })
                        }
                    }
                }
                if (showHistory) DDialog({ showHistory = false }, "历史批次", buttons = {
                    DButton("关闭", { showHistory = false }, variant = BtnVariant.Outline)
                }) {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        recentBatches.forEach { previous ->
                            val resumable = previous.state in setOf("paused", "stopped") && !busy && batch?.state != "running" && scan?.scan?.state != "running"
                            SettingsRow(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(previous.startedAt)),
                                "${scanStateLabel(previous.state)} · 每号 ${previous.articleLimit} 篇 · ${if (resumable) "点击继续此批次" else "点击查看记录"}", onClick = {
                                    showHistory = false
                                    if (resumable) vm.resumeBatch(previous.id) else startActivity(Intent(this@WeChatCollectionActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, previous.sessionId))
                                })
                        }
                    }
                }
                if (editLimit) DDialog({ editLimit = false }, "每号采集篇数", buttons = {
                    DButton("取消", { editLimit = false }, variant = BtnVariant.Outline)
                    DButton("保存", { articleLimit = limitText.toInt(); editLimit = false }, enabled = (limitText.toIntOrNull() ?: 0) in 1..50)
                }) {
                    Text("默认 50 篇。可先选择较少篇数遍历账号，再发起更深入的扫描。仅影响新任务。", style = DshType.s14, color = c.labelSecondary)
                    DInput(limitText, { limitText = it.filter(Char::isDigit).take(2) }, placeholder = "1–50", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                }
                selected?.let { article ->
                    DDialog({ selected = null }, "已采集留言", buttons = { DButton("关闭", { selected = null }, variant = BtnVariant.Outline) }) {
                        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(article.title, style = DshType.s14, color = c.labelPrimary)
                            val coverage = when (article.commentStatus) { "not_loaded" -> "尚未加载"; "explicit_empty" -> "页面明确显示暂无留言"; "loaded_end" -> "页面显示已到留言末尾"; else -> "已加载部分留言" }
                            Text("仅展示实际已加载并保存的公开留言。$coverage，累计去重 ${comments.size} 条。", style = DshType.xs13, color = c.labelTertiary)
                            if (comments.isEmpty()) Text("尚未采集到留言，不代表该文章没有评论。", style = DshType.s14, color = c.labelSecondary)
                            comments.forEach { comment ->
                                Text(comment.author ?: "未显示昵称", style = DshType.xs13m, color = c.labelTertiary)
                                Text(comment.text, style = DshType.s14, color = c.labelPrimary)
                                HDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun scanStateLabel(state: String) = when (state) {
    "running" -> "扫描中"; "paused" -> "已暂停"; "completed" -> "已完成"
    "partial" -> "部分采集"; "skipped" -> "已跳过"; else -> "已停止"
}
private fun scanReasonLabel(reason: String) = when (reason) {
    "visible_list_end" -> "已到可访问列表末尾"; "limit_reached" -> "已达每号采集数量"
    "left_wechat" -> "已离开微信，扫描暂停"
    "blocked" -> "页面不可访问或操作受阻"; "no_progress", "no_progress_or_model_error" -> "暂无采集进展，请检查执行记录"
    "user_stopped" -> "用户停止"; "turn_limit" -> "已达本轮执行上限，可继续扫描"
    "interrupted", "interrupted_or_timeout", "batch_paused" -> "任务中断或超时，可继续扫描"
    "model_or_connection_error" -> "模型或连接异常"; else -> "请查看执行记录"
}
