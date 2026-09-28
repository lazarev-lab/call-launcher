package com.grandpalauncher

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.PendingIntent
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoAnswerService : AccessibilityService() {
    private var lastAnswerAttempt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName != WHATSAPP_PACKAGE && packageName != SYSTEM_UI_PACKAGE) return
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        migrateAutoAnswerPreference(prefs)
        if (enabledContactNames(prefs).isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastAnswerAttempt < 2500) return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val notification = event.parcelableData as? Notification
            Log.i(TAG, "Notification event from $packageName, category=${notification?.category}")
            val caller = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            if (!isAutoAnswerEnabledForCaller(prefs, caller)) return
            val answerAction = notification?.actions?.firstOrNull { action ->
                val label = action.title?.toString()?.trim()?.lowercase().orEmpty()
                ANSWER_LABELS.any { label == it || label.startsWith("$it ") }
            }
            if (notification?.category == Notification.CATEGORY_CALL && answerAction != null) {
                lastAnswerAttempt = now
                try {
                    answerAction.actionIntent.send()
                    Log.i(TAG, "Incoming WhatsApp call answered from notification action")
                } catch (error: PendingIntent.CanceledException) {
                    Log.e(TAG, "WhatsApp answer action was canceled", error)
                }
                return
            }
        }
        val answer = if (packageName == SYSTEM_UI_PACKAGE) {
            windows.asSequence().mapNotNull { it.root }
                .firstNotNullOfOrNull { root ->
                    if (containsIncomingCallText(root) && containsEnabledCaller(root, prefs)) findAnswerButton(root)
                    else null
                }
        } else {
            rootInActiveWindow?.let { root ->
                if (containsEnabledCaller(root, prefs)) findAnswerButton(root) else null
            }
        } ?: return
        lastAnswerAttempt = now
        if (clickNodeOrParent(answer)) {
            Log.i(TAG, "Incoming WhatsApp call answered")
        }
    }

    private fun enabledContactNames(prefs: SharedPreferences): List<String> =
        (0..3).mapNotNull { slot ->
            if (!prefs.getBoolean("auto_answer_$slot", false)) return@mapNotNull null
            (prefs.getString("whatsapp_name_$slot", null) ?: prefs.getString("name_$slot", null))
                ?.trim()?.takeIf(String::isNotEmpty)
        }

    private fun isAutoAnswerEnabledForCaller(prefs: SharedPreferences, caller: String?): Boolean {
        val normalizedCaller = caller?.trim() ?: return false
        return enabledContactNames(prefs).any { it.equals(normalizedCaller, ignoreCase = true) }
    }

    private fun containsEnabledCaller(root: AccessibilityNodeInfo, prefs: SharedPreferences): Boolean {
        val names = enabledContactNames(prefs)
        if (names.isEmpty()) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = listOfNotNull(node.text, node.contentDescription).joinToString(" ").trim()
            if (names.any { label.equals(it, ignoreCase = true) }) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return false
    }

    private fun migrateAutoAnswerPreference(prefs: SharedPreferences) {
        if (prefs.getBoolean("auto_answer_per_tile_migrated", false)) return
        val wasEnabled = prefs.getBoolean("auto_answer", false)
        prefs.edit()
            .putBoolean("auto_answer_0", wasEnabled)
            .putBoolean("auto_answer_per_tile_migrated", true)
            .remove("auto_answer")
            .apply()
    }

    private fun containsIncomingCallText(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = listOfNotNull(node.text, node.contentDescription).joinToString(" ").lowercase()
            if (INCOMING_CALL_LABELS.any(label::contains)) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return false
    }

    private fun clickNodeOrParent(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        repeat(4) {
            val current = node ?: return false
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = current.parent
        }
        return start.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findAnswerButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        ANSWER_VIEW_IDS.forEach { id ->
            root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isVisibleToUser && it.isEnabled }?.let { return it }
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = listOfNotNull(node.text, node.contentDescription).joinToString(" ").lowercase()
            if (node.isVisibleToUser && node.isEnabled && node.isClickable &&
                ANSWER_LABELS.any { label == it || label.startsWith("$it ") }) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "CallLauncherAutoAnswer"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private val ANSWER_VIEW_IDS = listOf(
            "com.whatsapp:id/accept_incoming_call_view",
            "com.whatsapp:id/accept_incoming_call_container",
            "com.whatsapp:id/answer_call_tap_variant",
            "com.whatsapp:id/answer_call_view_id",
            "com.whatsapp:id/answer_call_slide_variant",
            "com.whatsapp:id/accept_call_swipe_up_hint_view"
        )
        private val ANSWER_LABELS = listOf(
            "ответить", "принять", "ответить на звонок", "answer", "accept", "answer call"
        )
        private val INCOMING_CALL_LABELS = listOf(
            "входящий видеозвонок", "входящий аудиозвонок", "входящий звонок",
            "incoming video call", "incoming audio call", "incoming voice call", "incoming call"
        )
    }
}
