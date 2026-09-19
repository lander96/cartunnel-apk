package com.cartunnel.client.ui

import android.content.ClipData
import android.os.Bundle
import android.graphics.Typeface
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cartunnel.client.App
import com.cartunnel.client.BuildConfig
import com.cartunnel.client.R
import com.cartunnel.client.diag.RedactingLog
import com.cartunnel.client.vpn.TunnelStateStore
import com.cartunnel.client.vpn.TunnelRuntimeState

class SettingsActivity : AppCompatActivity() {
    private lateinit var states: TunnelStateStore
    private lateinit var autoConnect: CheckBox
    private lateinit var autoReconnect: CheckBox
    private lateinit var networkReconnect: CheckBox
    private lateinit var wakeReconnect: CheckBox
    private lateinit var bypassLan: CheckBox
    private val actions = SingleFlightGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        UiFeedback.applyInsets(this)
        states = (application as App).tunnelStates
        val current = states.settings()
        autoConnect = findViewById(R.id.setting_auto_connect)
        autoReconnect = findViewById(R.id.setting_auto_reconnect)
        networkReconnect = findViewById(R.id.setting_network_reconnect)
        wakeReconnect = findViewById(R.id.setting_wake_reconnect)
        bypassLan = findViewById(R.id.setting_bypass_lan)
        findViewById<TextView>(R.id.setting_version).text = "CarTunnel ${BuildConfig.VERSION_NAME}\nXray v26.3.27 · HEV 2.17.1"
        autoConnect.isChecked = current.autoConnect
        autoReconnect.isChecked = current.autoReconnect
        networkReconnect.isChecked = current.reconnectOnNetworkChange
        wakeReconnect.isChecked = current.reconnectOnWake
        bypassLan.isChecked = current.bypassLan
        bypassLan.isEnabled = !current.desiredRunning

        findViewById<Button>(R.id.setting_save).setOnClickListener {
            val token = actions.begin("save") ?: return@setOnClickListener
            val save = findViewById<Button>(R.id.setting_save)
            save.isEnabled = false
            save.text = "正在保存"
            runCatching { states.updateSettings {
                it.copy(
                    autoConnect = autoConnect.isChecked,
                    autoReconnect = autoReconnect.isChecked,
                    reconnectOnNetworkChange = networkReconnect.isChecked,
                    reconnectOnWake = wakeReconnect.isChecked,
                    bypassLan = if (it.desiredRunning) it.bypassLan else bypassLan.isChecked,
                )
            } }.onSuccess { finish() }.onFailure {
                findViewById<TextView>(R.id.setting_error).text = "设置保存失败，请重试"
            }
            actions.finish(token)
            save.isEnabled = true
            save.text = "保存设置"
        }
        findViewById<Button>(R.id.setting_licenses).setOnClickListener { showLicenseFiles() }
        findViewById<Button>(R.id.setting_view_diagnostics).setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.setting_clear_logs).setOnClickListener { RedactingLog.clear(); UiFeedback.notice(this, "日志已清除") }
    }

    private fun copyDiagnostics(text: String): Boolean = runCatching {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(
            ClipData.newPlainText("CarTunnel diagnostics", text),
        )
        UiFeedback.notice(this, "已复制")
        true
    }.getOrElse { UiFeedback.notice(this, "复制失败，请重试"); false }

    private fun diagnosticsText(): String {
        val state = states.state.value
        val safeState = if (state is TunnelRuntimeState.Error) {
            "Error code=${state.code}"
        } else {
            state.javaClass.simpleName
        }
        val recent = RedactingLog.dump().lines().takeLast(80).asReversed().joinToString("\n").ifBlank { "暂无日志" }
        return "CarTunnel ${BuildConfig.VERSION_NAME}\n状态=$safeState\n最新日志在上：\n$recent"
    }

    private fun showDiagnostics() {
        val diagnostic = diagnosticsText()
        val textView = TextView(this).apply {
            text = diagnostic
            typeface = Typeface.MONOSPACE
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.type_mono))
            val padding = resources.getDimensionPixelSize(R.dimen.card_padding)
            setPadding(padding, padding, padding, padding)
        }
        val dialog = UiFeedback.dialog(this)
            .setTitle("脱敏诊断")
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("复制诊断", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = actions.begin("copy") ?: return@setOnClickListener
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = if (copyDiagnostics(diagnostic)) "已复制" else "重试复制"
                actions.finish(token)
            }
        }
        dialog.show()
    }

    private fun showLicenseFiles() {
        val files = assets.list("LICENSES")?.filter(String::isNotBlank).orEmpty().sorted()
        if (files.isEmpty()) return
        UiFeedback.dialog(this)
            .setTitle("选择许可证文件")
            .setItems(files.toTypedArray()) { _, index -> showLicenseFile(files[index]) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showLicenseFile(fileName: String) {
        val content = runCatching { assets.open("LICENSES/$fileName").bufferedReader().use { it.readText() } }
            .getOrElse { "许可证文本不可读取" }
        val textView = TextView(this).apply {
            text = content
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.type_body))
            val padding = resources.getDimensionPixelSize(R.dimen.card_padding)
            setPadding(padding, padding, padding, padding)
        }
        UiFeedback.dialog(this)
            .setTitle(fileName)
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("关闭", null)
            .show()
    }
}
