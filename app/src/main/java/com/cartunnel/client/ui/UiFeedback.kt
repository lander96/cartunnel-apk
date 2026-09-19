package com.cartunnel.client.ui

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.cartunnel.client.R
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object UiFeedback {
    private var lastMessage = ""
    private var lastAt = 0L
    fun dialog(context: Context) = AlertDialog.Builder(context)
    fun applyInsets(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val left = root.paddingLeft; val top = root.paddingTop
        val right = root.paddingRight; val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view: View, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
    fun notice(context: Context, message: String) {
        val now = SystemClock.elapsedRealtime()
        if (message == lastMessage && now - lastAt < 2000) return
        lastMessage = message; lastAt = now
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    fun destructive(dialog: AlertDialog, context: Context) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(context.getColor(R.color.danger))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }
}
