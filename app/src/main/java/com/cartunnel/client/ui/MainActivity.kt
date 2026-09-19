package com.cartunnel.client.ui

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.cartunnel.client.App
import com.cartunnel.client.R
import com.cartunnel.client.core.ProfileLatencyProbe
import com.cartunnel.client.core.XrayProfileLatencyProbe
import com.cartunnel.client.diag.RedactingLog
import com.cartunnel.client.profile.*
import com.cartunnel.client.vpn.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainActions : ViewModel() {
    val actions = SingleFlightGate()
    val vpn = PendingVpnStartGate()
    var connectToken: SingleFlightGate.Token? = null
    var importToken: SingleFlightGate.Token? = null
    var exportText: String? = null
}

class MainActivity : AppCompatActivity() {
    private val app get() = application as App
    private val states get() = app.tunnelStates
    private val profiles get() = app.profiles
    private val health get() = app.profileHealth
    private lateinit var actions: MainActions
    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var current: TextView
    private lateinit var listAdapter: ProfileListAdapter
    private var exportText: String?
        get() = actions.exportText
        set(value) { actions.exportText = value }
    private var profileProbeJob: Job? = null
    private var probeGeneration = 0L
    private var recoveryDialog: AlertDialog? = null
    internal var consentDialog: AlertDialog? = null
        private set
    private val latencyProbe: ProfileLatencyProbe by lazy { XrayProfileLatencyProbe(filesDir.absolutePath) }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = actions.vpn.pending ?: return@registerForActivityResult
        lifecycleScope.launch {
            // OEMs can return CANCELLED before their permission state is observable.
            if (result.resultCode != RESULT_OK) delay(250)
            if (result.resultCode == RESULT_OK || VpnService.prepare(this@MainActivity) == null) {
                continueVpn(pending.generation)
            } else {
                actions.vpn.cancel(pending.generation)
                releaseConnect()
                status.text = "未获得系统 VPN 授权，请重试连接并允许"
            }
        }
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument(), ::readImportFile)
    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent(), ::readImportFile)
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { writeExport(it) }
    private val createUriDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { writeExport(it) }
    private val profileEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        renderProfiles()
        if (it.resultCode == RESULT_OK) status.text = "节点已保存"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UserConsent.isAccepted(this)) {
            showUserConsent(savedInstanceState)
            return
        }
        initializeMain(savedInstanceState)
    }

    private fun showUserConsent(savedInstanceState: Bundle?) {
        val terms = assets.open("CarTunnel-MIT.txt").bufferedReader().use { it.readText() }
        val dialog = UiFeedback.dialog(this)
            .setTitle("使用协议与免责声明")
            .setMessage("本软件自主开发部分采用 MIT 开源协议，第三方组件遵循各自许可证。\n\n软件按原样提供，不提供任何明示或默示担保，包括适销性、特定用途适用性及不侵权担保。作者或版权持有人不对因软件或其使用产生的索赔、损害或其他责任负责。以下为 MIT 协议原文及免责声明，请阅读后选择是否同意。\n\n" + terms)
            .setCancelable(false)
            .setNegativeButton("不同意并退出") { _, _ -> finish() }
            .setPositiveButton("同意并继续", null)
            .create()
        consentDialog = dialog
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (UserConsent.accept(this)) {
                dialog.dismiss()
                consentDialog = null
                initializeMain(savedInstanceState)
                renderProfiles()
            } else {
                UiFeedback.notice(this, "无法保存同意状态，请释放存储空间后重试")
            }
        }
    }

    private fun initializeMain(savedInstanceState: Bundle?) {
        actions = ViewModelProvider(this)[MainActions::class.java]
        if (actions.vpn.pending == null) savedInstanceState?.getString("pendingVpn")?.let { actions.vpn.request(it) }
        setContentView(R.layout.activity_main)
        UiFeedback.applyInsets(this)
        button = findViewById(R.id.connect)
        status = findViewById(R.id.status)
        current = findViewById(R.id.current_profile)
        listAdapter = ProfileListAdapter(findViewById<LinearLayout>(R.id.profile_list), health, ::selectProfile, ::testProfileLatency, ::profileActions)
        findViewById<Button>(R.id.import_profile).setOnClickListener { launchFileImport() }
        findViewById<Button>(R.id.config_menu).setOnClickListener {
            UiFeedback.dialog(this).setTitle("配置").setItems(arrayOf("手工新增", "粘贴导入", "导出全部")) { _, which ->
                when (which) { 0 -> openProfileEditor(null); 1 -> pasteConfig(); 2 -> exportAll() }
            }.show()
        }
        findViewById<Button>(R.id.add_profile).setOnClickListener { openProfileEditor(null) }
        findViewById<Button>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        button.setOnClickListener {
            val token = accepted("connect") ?: return@setOnClickListener
            actions.connectToken = token
            button.isEnabled = false
            if (states.state.value.isActive() || states.settings().desiredRunning) {
                status.text = "正在断开"
                if (!requestStop()) releaseConnect()
            } else requestStart()
        }
        lifecycleScope.launch { states.state.collect { render(it) } }
    }

    override fun onResume() {
        super.onResume()
        if (!::actions.isInitialized) return
        renderProfiles()
        actions.vpn.pending?.let { if (VpnService.prepare(this) == null) continueVpn(it.generation) }
        val removed = profiles.migrationNotice()
        if (removed > 0) {
            UiFeedback.notice(this, "已移除 $removed 个旧协议节点")
            runCatching { profiles.acknowledgeMigration() }
        }
    }

    override fun onDestroy() {
        consentDialog?.dismiss()
        consentDialog = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::actions.isInitialized) outState.putString("pendingVpn", actions.vpn.pending?.profileId)
        // Export contains credentials: never persist it in saved state.
        super.onSaveInstanceState(outState)
    }

    private fun accepted(action: String): SingleFlightGate.Token? = actions.actions.begin(action).also {
        RedactingLog.write("UI_ACTION action=$action result=${if (it == null) "ignored" else "accepted"}")
    }

    private fun render(state: TunnelRuntimeState) {
        status.text = when (state) {
            is TunnelRuntimeState.Connected -> state.latencyMs?.let { "已连接 · 出口可用 ${it}ms" } ?: "VPN 已启动 · 出口待验证"
            is TunnelRuntimeState.Starting -> "正在连接：${state.step}"
            is TunnelRuntimeState.Reconnecting -> "正在重连（第 ${state.attempt} 次）"
            is TunnelRuntimeState.Error -> state.safeMessage
            TunnelRuntimeState.PermissionRequired -> "需要系统 VPN 授权"
            TunnelRuntimeState.Stopping -> "正在断开"
            TunnelRuntimeState.Stopped -> "未连接"
        }
        if (state is TunnelRuntimeState.Connected || state is TunnelRuntimeState.Reconnecting || state is TunnelRuntimeState.Error || state == TunnelRuntimeState.PermissionRequired ||
            (state == TunnelRuntimeState.Stopped && !states.settings().desiredRunning && actions.vpn.pending == null)) releaseConnect()
        button.text = when {
            actions.vpn.pending != null -> "等待 VPN 授权"
            state == TunnelRuntimeState.Stopping -> "正在断开"
            state is TunnelRuntimeState.Starting -> "正在连接"
            state.isActive() -> "断开"
            else -> "连接"
        }
        button.isEnabled = actions.connectToken == null && state != TunnelRuntimeState.Stopping
        status.setTextColor(getColor(when (state) {
            is TunnelRuntimeState.Connected -> R.color.success
            is TunnelRuntimeState.Error -> R.color.danger
            is TunnelRuntimeState.Starting, is TunnelRuntimeState.Reconnecting -> R.color.warning
            else -> R.color.text
        }))
        renderProfiles()
    }

    private fun releaseConnect() {
        actions.connectToken?.let(actions.actions::finish)
        actions.connectToken = null
        button.isEnabled = states.state.value != TunnelRuntimeState.Stopping
    }

    private fun requestStart() {
        if (profileProbeJob?.isActive == true) { status.text = "请等待节点测试完成"; releaseConnect(); return }
        val profile = runCatching { profiles.load().firstOrNull { it.id == states.settings().currentProfileId } }.getOrNull()
        if (profile == null) { status.text = "请先导入或选择节点"; releaseConnect(); return }
        val pending = actions.vpn.request(profile.id) ?: return
        status.text = "正在准备连接"
        val permission = VpnService.prepare(this)
        if (permission == null) continueVpn(pending.generation)
        else runCatching { vpnPermission.launch(permission); button.text = "等待 VPN 授权" }.onFailure {
            actions.vpn.cancel(pending.generation); releaseConnect(); status.text = "系统无法打开 VPN 授权页面"
        }
    }

    private fun continueVpn(generation: Long) {
        val pending = actions.vpn.consume(generation, true) ?: return
        runCatching {
            check(profiles.load().any { it.id == pending.profileId })
            states.updateSettings { it.copy(currentProfileId = pending.profileId, desiredRunning = true) }
            status.text = "正在连接"; button.text = "正在连接"; button.isEnabled = false
            CarTunnelService.start(this, pending.profileId)
        }.onFailure {
            runCatching { states.updateSettings { it.copy(desiredRunning = false) } }
            releaseConnect(); status.text = "系统拒绝启动 VPN 服务，请重试"
            RedactingLog.write("UI_START_FAILED type=${it.javaClass.simpleName}")
        }
    }

    private fun requestStop(): Boolean = runCatching {
        startService(Intent(this, CarTunnelService::class.java).setAction(CarTunnelService.ACTION_STOP))
        true
    }.getOrElse { status.text = "断开请求发送失败，请重试"; false }

    private fun renderProfiles() {
        val all = runCatching { profiles.load() }.getOrElse { error ->
            status.text = if (error is ConfigUnreadableException) "本地节点密文或系统密钥无法读取" else "本地节点读取或迁移失败，请重试"
            if (error is ConfigUnreadableException) showStorageRecovery()
            return
        }
        val selected = all.firstOrNull { it.id == states.settings().currentProfileId }
        listAdapter.render(all, selected?.id, states.state.value)
        findViewById<Button>(R.id.add_profile).visibility = if (all.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        current.text = if (selected == null) "当前节点：未选择" else "当前节点：${selected.name}\n${listAdapter.healthText(health.read(selected))}"
    }

    private fun selectProfile(profile: VmessWsProfile) {
        if (!requireStopped()) return
        runCatching { states.updateSettings { it.copy(currentProfileId = profile.id) } }
            .onSuccess { renderProfiles(); status.text = "已选择 ${profile.name}" }
            .onFailure { status.text = "选择保存失败，请重试" }
    }

    private fun profileActions(profile: VmessWsProfile) {
        UiFeedback.dialog(this).setTitle(profile.name)
            .setItems(arrayOf("编辑", "导出 JSON", "导出 VMess URI", "删除")) { _, which ->
                when (which) { 0 -> openProfileEditor(profile.id); 1 -> confirmExport(listOf(profile), false)
                    2 -> confirmExport(listOf(profile), true); 3 -> confirmDelete(profile) }
            }.show()
    }

    private fun testProfileLatency(profile: VmessWsProfile) {
        if (!requireStopped() || profileProbeJob?.isActive == true) return
        val generation = ++probeGeneration
        health.markTesting(profile); renderProfiles()
        profileProbeJob = lifecycleScope.launch {
            try {
                val latency = latencyProbe.measure(profile)
                if (generation == probeGeneration && profiles.load().any { it == profile }) {
                    health.markAvailable(profile, latency); status.text = "节点可用 · ${latency}ms"
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == probeGeneration && profiles.load().any { it == profile }) {
                    health.markFailed(profile, "ENDPOINT_UNREACHABLE"); status.text = "节点不可达，请检查网络或服务端"
                }
            } finally { health.cancelTesting(profile.id); profileProbeJob = null; renderProfiles() }
        }
    }

    private fun launchFileImport() {
        if (!requireStopped()) return
        actions.importToken = accepted("import") ?: return
        status.text = "请选择配置文件"
        try { openDocument.launch(arrayOf("application/json", "text/plain", "text/uri-list")) }
        catch (_: RuntimeException) {
            try { getContent.launch("*/*") } catch (_: RuntimeException) {
                releaseImport(); status.text = "系统没有文件选择器，请使用配置菜单中的粘贴导入"
            }
        }
    }
    private fun releaseImport() { actions.importToken?.let(actions.actions::finish); actions.importToken = null }
    private fun readImportFile(uri: Uri?) {
        if (uri == null) { releaseImport(); status.text = "已取消导入"; return }
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val buffer = CharArray(1_048_577); var length = 0
                while (length < buffer.size) { val n = reader.read(buffer, length, buffer.size - length); if (n < 0) break; length += n }
                require(length <= 1_048_576); String(buffer, 0, length)
            } ?: error("unreadable")
            importText(text)
        } catch (_: Exception) { status.text = "导入失败：文件无法读取或超过 1 MiB" }
        finally { releaseImport() }
    }

    private fun pasteConfig() {
        if (!requireStopped()) return
        val input = EditText(this).apply { hint = "粘贴 JSON 或 vmess:// 配置"; minLines = 4; maxLines = 8 }
        val dialog = UiFeedback.dialog(this).setTitle("粘贴导入").setView(input)
            .setPositiveButton("导入", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = accepted("import") ?: return@setOnClickListener
                if (importText(input.text.toString())) dialog.dismiss() else input.error = status.text
                actions.actions.finish(token)
            }
        }
        dialog.show()
    }

    internal fun importText(text: String): Boolean {
        if (!requireStopped()) return false
        return runCatching {
            require(text.length <= 1_048_576)
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(lines.isNotEmpty())
            val imported = if (lines.all { it.startsWith("vmess://", true) }) lines.map(ProfileUriCodec::decode) else ProfileJsonCodec.decode(text)
            val existing = profiles.load().associateBy { it.id }.toMutableMap()
            imported.forEach { existing[it.id] = it }
            profiles.save(existing.values.toList())
            states.updateSettings { it.copy(currentProfileId = if (imported.size == 1 || it.currentProfileId == null) imported.first().id else it.currentProfileId) }
            renderProfiles(); status.text = "已导入 ${imported.size} 个节点并选中当前节点"
            true
        }.getOrElse {
            status.text = when (it) {
                is ProfileValidationException -> "导入失败：${it.errors.joinToString { field -> field.message }}"
                is ConfigUnreadableException, is ConfigWriteException -> "导入失败：本地加密存储不可用"
                else -> "导入失败：配置格式无效"
            }; false
        }
    }

    private fun confirmDelete(profile: VmessWsProfile) {
        if (actions.vpn.pending != null || actions.connectToken != null) { status.text = "请等待连接操作结束后删除"; return }
        val activeId = (states.state.value as? TunnelRuntimeState.Connected)?.profileId ?: states.settings().currentProfileId
        val running = (states.state.value != TunnelRuntimeState.Stopped || states.settings().desiredRunning) && activeId == profile.id
        val coordinator = ProfileDeletionCoordinator(ProfileDeletionTransaction(profiles::load, profiles::save,
            { states.settings().currentProfileId }, { id -> states.updateSettings { it.copy(currentProfileId = id) } },
            health::snapshot, health::delete, health::restore))
        val dialog = UiFeedback.dialog(this).setTitle("删除节点？")
            .setMessage("只删除本机「${profile.name}」的配置和测试记录，不影响服务器。删除后无法恢复。")
            .setPositiveButton(if (running) "断开并删除" else "删除", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            UiFeedback.destructive(dialog, this)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = accepted("delete") ?: return@setOnClickListener
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                positive.isEnabled = false; positive.text = if (running) "正在断开" else "正在删除"
                dialog.setCancelable(false)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                lifecycleScope.launch {
                    try {
                        if (profileProbeJob?.isActive == true) {
                            probeGeneration++; profileProbeJob?.cancelAndJoin()
                        }
                        var result = coordinator.request(profile.id, if (running) profile.id else null, !running)
                        if (result == DeleteResult.WaitingForStop) {
                            check(requestStop())
                            withTimeout(15_000) { states.state.first { it == TunnelRuntimeState.Stopped && !states.settings().desiredRunning } }
                            result = coordinator.onState(true)
                        }
                        if (result is DeleteResult.Deleted) {
                            dialog.dismiss(); renderProfiles(); status.text = "节点已删除"
                            findViewById<Button>(R.id.import_profile).requestFocus()
                        } else dialog.setMessage("删除失败，本地加密存储不可写。节点已保留，请重试。")
                    } catch (error: TimeoutCancellationException) {
                        dialog.setMessage("断开超时，节点已保留。请确认 VPN 已停止后重试。")
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { dialog.setMessage("删除失败，节点已保留。请重试。") }
                    finally {
                        coordinator.cancel(); actions.actions.finish(token)
                        positive.isEnabled = true; positive.text = if (running) "断开并删除" else "删除"
                        dialog.setCancelable(true); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmExport(items: List<VmessWsProfile>, uri: Boolean) {
        UiFeedback.dialog(this).setTitle("导出连接凭据").setMessage("导出文件含完整连接凭据，请仅保存到可信位置。")
            .setPositiveButton("继续导出") { _, _ ->
                exportText = if (uri) items.joinToString("\n", transform = ProfileUriCodec::encode) else ProfileJsonCodec.encode(items)
                runCatching { if (uri) createUriDocument.launch("cartunnel-vmess.txt") else createDocument.launch("cartunnel-profiles.json") }
                    .onFailure { exportText = null; status.text = "系统无法创建导出文件" }
            }.setNegativeButton("取消", null).show()
    }
    private fun exportAll() {
        val all = runCatching { profiles.load() }.getOrElse { status.text = "节点无法读取"; return }
        if (all.isEmpty()) status.text = "暂无节点可导出" else confirmExport(all, false)
    }
    private fun writeExport(uri: Uri?) {
        val text = exportText; exportText = null
        if (uri == null || text == null) return
        runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("unwritable") }
            .onSuccess { status.text = "已导出，请妥善保管连接凭据" }.onFailure { status.text = "导出失败，请重试" }
    }
    private fun showStorageRecovery() {
        if (recoveryDialog?.isShowing == true) return
        recoveryDialog = UiFeedback.dialog(this).setTitle("本地节点无法读取")
            .setMessage("本地密文或系统密钥无法读取。清空将永久删除本机节点，之后可重新导入。")
            .setPositiveButton("清空并重新导入") { _, _ ->
                if (requireStopped()) runCatching { profiles.reset(); states.updateSettings { it.copy(currentProfileId = null) }; renderProfiles() }
                    .onFailure { status.text = "清空失败，请重试" }
            }.setNegativeButton("取消", null).show()
    }
    private fun openProfileEditor(id: String?) {
        if (!requireStopped()) return
        profileEditor.launch(Intent(this, ProfileEditActivity::class.java).apply { id?.let { putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, it) } })
    }
    private fun requireStopped(): Boolean {
        if (!states.settings().desiredRunning && !states.state.value.isActive() && states.state.value != TunnelRuntimeState.Stopping &&
            actions.vpn.pending == null && !actions.actions.isBusy("delete")) return true
        status.text = "请先断开连接并等待操作完成"
        return false
    }
    private fun TunnelRuntimeState.isActive() = this is TunnelRuntimeState.Connected || this is TunnelRuntimeState.Starting || this is TunnelRuntimeState.Reconnecting
}
