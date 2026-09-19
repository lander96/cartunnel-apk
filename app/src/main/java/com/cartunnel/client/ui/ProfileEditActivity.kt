package com.cartunnel.client.ui

import android.app.Activity
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.core.widget.doAfterTextChanged
import com.cartunnel.client.App
import com.cartunnel.client.R
import com.cartunnel.client.profile.*
import com.cartunnel.client.diag.RedactingLog
import java.util.UUID

class ProfileEditDraft : ViewModel() {
    val actions = SingleFlightGate()
    val newId = UUID.randomUUID().toString()
    var initial: List<String>? = null
    var values: List<String>? = null
}

class ProfileEditActivity : AppCompatActivity() {
    companion object { const val EXTRA_PROFILE_ID = "profile_id" }
    private val app get() = application as App
    private lateinit var draft: ProfileEditDraft
    private val actions get() = draft.actions
    private lateinit var fields: Map<String, EditText>
    private lateinit var errorSummary: TextView
    private lateinit var save: Button
    private var original: VmessWsProfile? = null
    private var initial: List<String> = emptyList()
    private var discardVisible = false
    private val newId get() = draft.newId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = ViewModelProvider(this)[ProfileEditDraft::class.java]
        setContentView(R.layout.activity_profile_edit)
        UiFeedback.applyInsets(this)
        fields = mapOf("name" to R.id.profile_name, "server" to R.id.profile_server, "port" to R.id.profile_port,
            "uuid" to R.id.profile_uuid, "wsHost" to R.id.profile_ws_host, "wsPath" to R.id.profile_ws_path)
            .mapValues { findViewById(it.value) }
        errorSummary = findViewById(R.id.profile_error_summary)
        save = findViewById(R.id.profile_save)
        save.setOnClickListener { saveProfile() }
        findViewById<Button>(R.id.profile_cancel).setOnClickListener { leave() }
        findViewById<CheckBox>(R.id.profile_show_password).setOnCheckedChangeListener { view, checked ->
            fields.getValue("uuid").apply {
                transformationMethod = if (checked) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                setSelection(text.length)
            }
            view.text = if (checked) "隐藏 UUID" else "显示 UUID"
        }
        val id = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (id != null) {
            original = runCatching { app.profiles.load().firstOrNull { it.id == id } }.getOrNull()
            if (original == null) { pageError("节点不可读取或已不存在，请返回后重试。"); save.isEnabled = false }
        }
        findViewById<TextView>(R.id.profile_title).text = if (id == null) "新增节点" else "编辑节点"
        val values = original?.let { listOf(it.name, it.server, it.port.toString(), it.uuid, it.wsHost, it.wsPath) }
            ?: listOf("", "", "818", "", "", "/")
        initial = draft.initial ?: values.also { draft.initial = it }
        fields.values.zip(draft.values ?: values).forEach { (field, value) ->
            field.isSaveEnabled = false
            field.setText(value)
        }
        fields.values.forEach { field -> field.doAfterTextChanged { draft.values = fields.values.map { it.text.toString() } } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    private fun leave() {
        if (fields.values.map { it.text.toString() } == initial) { finish(); return }
        if (discardVisible) return
        discardVisible = true
        UiFeedback.dialog(this).setTitle("放弃未保存的修改？").setMessage("已输入的修改尚未保存。")
            .setPositiveButton("放弃修改") { _, _ -> finish() }.setNegativeButton("继续编辑", null)
            .create().also { dialog ->
                dialog.setOnDismissListener { discardVisible = false }
                dialog.setOnShowListener { UiFeedback.destructive(dialog, this) }
                dialog.show()
            }
    }

    private fun saveProfile() {
        val token = actions.begin("save")
        RedactingLog.write("UI_ACTION action=save result=${if (token == null) "ignored" else "accepted"}")
        if (token == null) return
        save.isEnabled = false; save.text = "正在保存"
        try {
            fields.values.forEach { it.error = null }
            errorSummary.visibility = View.INVISIBLE
            if (app.tunnelStates.settings().desiredRunning) { pageError("请先断开连接，再修改节点。"); return }
            fun text(key: String) = fields.getValue(key).text.toString().trim()
            val profile = VmessWsProfile(id = original?.id ?: newId, name = text("name"), server = text("server"),
                port = text("port").toIntOrNull() ?: -1, uuid = text("uuid").lowercase(), wsHost = text("wsHost"), wsPath = text("wsPath"))
            val errors = ProfileValidator.validate(profile)
            if (errors.isNotEmpty()) {
                errors.forEach { fields[it.field]?.error = it.message }
                pageError("请修正标记的 ${errors.size} 个字段。")
                fields[errors.first().field]?.requestFocus()
                return
            }
            app.profiles.upsert(profile)
            if (app.tunnelStates.settings().currentProfileId == null) app.tunnelStates.updateSettings { it.copy(currentProfileId = profile.id) }
            setResult(Activity.RESULT_OK); finish()
        } catch (error: Exception) {
            RedactingLog.write("UI_SAVE_FAILED type=${error.javaClass.simpleName}")
            pageError("节点保存失败，本地加密存储不可写。请重试。")
        } finally { actions.finish(token); save.isEnabled = true; save.text = "保存节点" }
    }

    private fun pageError(message: String) { errorSummary.text = message; errorSummary.visibility = View.VISIBLE }
}
