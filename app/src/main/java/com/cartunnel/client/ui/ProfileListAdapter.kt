package com.cartunnel.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import com.cartunnel.client.R
import com.cartunnel.client.profile.ProfileHealthRecord
import com.cartunnel.client.profile.ProfileHealthStatus
import com.cartunnel.client.profile.ProfileHealthStore
import com.cartunnel.client.profile.VmessWsProfile
import com.cartunnel.client.vpn.TunnelRuntimeState
import androidx.core.view.ViewCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

class ProfileListAdapter(
    private val container: LinearLayout,
    private val health: ProfileHealthStore,
    private val onSelect: (VmessWsProfile) -> Unit,
    private val onTest: (VmessWsProfile) -> Unit,
    private val onMore: (VmessWsProfile) -> Unit,
) {
    private var lastRender: String? = null
    fun render(
        profiles: List<VmessWsProfile>,
        selectedId: String?,
        runtime: TunnelRuntimeState,
    ) {
        val key = profiles.joinToString { "${it.id}:${ProfileHealthStore.revision(it)}:${health.read(it)}" } + selectedId + runtime
        if (key == lastRender) return
        lastRender = key
        val focused = container.findFocus()
        val focusedId = focused?.id
        var focusedRow: View? = focused
        while (focusedRow != null && focusedRow.parent != container) focusedRow = focusedRow.parent as? View
        val focusedProfile = focusedRow?.tag
        container.removeAllViews()
        if (profiles.isEmpty()) {
            container.addView(TextView(container.context).apply { text = "暂无节点，请新增或导入服务端配置。" })
            return
        }
        profiles.forEach { profile ->
            val row = LayoutInflater.from(container.context).inflate(R.layout.item_profile, container, false)
            row.tag = profile.id
            ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.RadioButton"
                    info.isCheckable = true
                    info.isChecked = profile.id == selectedId
                }
            })
            row.isSelected = profile.id == selectedId
            row.findViewById<TextView>(R.id.profile_selected).text = if (row.isSelected) "已选" else ""
            row.findViewById<TextView>(R.id.profile_name).text = profile.name
            row.findViewById<TextView>(R.id.profile_server).text =
                "${profile.server}:${profile.port} · VMess / WS"
            row.findViewById<TextView>(R.id.profile_health).text = statusText(profile, selectedId, runtime)
            row.findViewById<Button>(R.id.profile_test).setOnClickListener { onTest(profile) }
            row.findViewById<Button>(R.id.profile_test).isEnabled = health.read(profile).status != ProfileHealthStatus.TESTING
            row.findViewById<Button>(R.id.profile_more).setOnClickListener { onMore(profile) }
            row.setOnClickListener { onSelect(profile) }
            container.addView(row)
            if (focusedProfile == profile.id) {
                val target = if (focusedId == View.NO_ID || focusedId == null) row else row.findViewById<View>(focusedId)
                target?.requestFocus()
            }
        }
    }

    private fun statusText(
        profile: VmessWsProfile,
        selectedId: String?,
        runtime: TunnelRuntimeState,
    ): String {
        if (selectedId == profile.id) {
            when (runtime) {
                is TunnelRuntimeState.Connected -> if (runtime.profileId == profile.id) {
                    return runtime.latencyMs?.let { "出口可用 · ${it}ms" } ?: "VPN 已启动 · 待验证"
                }
                is TunnelRuntimeState.Starting -> return "连接中"
                is TunnelRuntimeState.Reconnecting -> return "重连中"
                is TunnelRuntimeState.Error -> return "连接失败"
                TunnelRuntimeState.PermissionRequired -> return "等待 VPN 授权"
                TunnelRuntimeState.Stopping -> return "停止中"
                TunnelRuntimeState.Stopped -> Unit
            }
        }
        return healthText(health.read(profile))
    }

    fun healthText(record: ProfileHealthRecord): String = when (record.status) {
        ProfileHealthStatus.UNTESTED -> "未测试"
        ProfileHealthStatus.TESTING -> "测试中"
        ProfileHealthStatus.AVAILABLE -> "可用 · ${record.latencyMs ?: "?"}ms · ${record.checkedAt?.let(::formatTime).orEmpty()}"
        ProfileHealthStatus.FAILED -> "测试失败${record.safeErrorCode?.let { " · $it" }.orEmpty()}"
        ProfileHealthStatus.STALE -> "结果已过期"
    }

    private fun formatTime(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}
