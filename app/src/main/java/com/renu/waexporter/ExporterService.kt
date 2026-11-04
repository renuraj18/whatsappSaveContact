package com.renu.waexporter

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shows a small floating panel while WhatsApp / WhatsApp Business is open.
 *  - "Scan chats": scrolls the chat list and saves every chat whose title is a phone number
 *    (= not saved in your contacts). Read-only, nothing is opened.
 *  - "Get names": opens each collected number's chat, reads the "~ Name" WhatsApp shows for
 *    non-contacts (chat screen, else contact-info screen), then goes back. Opening a chat marks
 *    its unread messages as read, so it asks for a second tap first.
 */
class ExporterService : AccessibilityService() {

    companion object {
        val WA_PACKAGES = setOf("com.whatsapp.w4b", "com.whatsapp")
        private const val SCROLL_DELAY = 900L
        private const val CHAT_OPEN_DELAY = 2500L
        private const val INFO_OPEN_DELAY = 1800L
        private const val BACK_DELAY = 900L
        private val BUSINESS_NAME_IDS = listOf("contact_title", "business_title", "contact_name", "profile_name")
    }

    private enum class Mode { IDLE, SCANNING, NAMING }
    private data class Row(val title: String, val number: String?, val name: String?)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: ContactStore
    private var mode = Mode.IDLE
    private var waPkg = "com.whatsapp.w4b"

    private var lastSnapshot: List<String> = emptyList()
    private var staleRounds = 0

    private val queue = ArrayDeque<String>()
    private var current: String? = null
    private var total = 0
    private var done = 0
    private var found = 0
    private var namesArmed = false
    private var groupScan = false

    private var overlay: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var scanBtn: TextView? = null
    private var nameBtn: TextView? = null
    private var groupBtn: TextView? = null

    override fun onServiceConnected() {
        store = ContactStore(this)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopAll(null)
        hideOverlay()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val activePkg = rootInActiveWindow?.packageName?.toString() ?: return
        if (activePkg in WA_PACKAGES) {
            // Keep the package chosen at scan time while a scan / naming run is in progress.
            if (mode == Mode.IDLE) waPkg = activePkg
            showOverlay()
        } else if (activePkg != packageName && mode == Mode.IDLE) {
            hideOverlay()
        }
    }

    // ---------------------------------------------------------------- scanning the chat list

    private fun startScan(group: Boolean = false) {
        store.sourcePackage = waPkg
        groupScan = group
        mode = Mode.SCANNING
        staleRounds = 0
        lastSnapshot = emptyList()
        refresh(if (group) "Scanning group members… keep the member list open" else "Scanning… keep WhatsApp on the Chats tab")
        if (group) clickViewAll()
        handler.post(::scanStep)
    }

    private fun scanStep() {
        if (mode != Mode.SCANNING) return
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() !in WA_PACKAGES) {
            stopAll("Stopped: WhatsApp was not in front"); return
        }
        val list = findChatList(root)
        if (list == null) {
            stopAll(if (groupScan) "Couldn't find the member list. Open Group info and try again." else "Couldn't find the chat list. Open the Chats tab and try again.")
            return
        }

        val rows = if (groupScan) readGroupRows(list) else readRows(list)
        val added = store.addAll(rows.mapNotNull { r -> r.number?.let { it to r.name } })
        val titles = rows.map { it.title }
        staleRounds = if (titles == lastSnapshot) staleRounds + 1 else 0
        lastSnapshot = titles
        refresh("Scanning… ${store.count()} numbers (+$added)")

        if (staleRounds >= 3) {
            stopAll("Scan done: ${store.count()} numbers. Tap Get names, or export from the app.")
            return
        }
        list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        handler.postDelayed(::scanStep, SCROLL_DELAY)
    }

    private fun readRows(list: AccessibilityNodeInfo): List<Row> {
        val out = mutableListOf<Row>()
        for (i in 0 until list.childCount) {
            val row = list.getChild(i) ?: continue
            val texts = mutableListOf<String>()
            collectTexts(row, texts)
            val titleNode = row.findAccessibilityNodeInfosByViewId("$waPkg:id/conversations_row_contact_name").firstOrNull()
            val title = PhoneUtil.clean(titleNode?.text ?: texts.firstOrNull())
            if (title.isEmpty()) continue
            val number = PhoneUtil.normalize(title)
            val name = if (number != null) texts.firstNotNullOfOrNull { PhoneUtil.pushName(it) } else null
            out += Row(title, number, name)
        }
        return out
    }

    /** Group info rows: an unsaved member shows as a bare number, often with "~ Name" beside it. */
    private fun readGroupRows(list: AccessibilityNodeInfo): List<Row> {
        val out = mutableListOf<Row>()
        for (i in 0 until list.childCount) {
            val row = list.getChild(i) ?: continue
            val texts = mutableListOf<String>()
            collectTexts(row, texts)
            if (texts.isEmpty()) continue
            val number = texts.firstNotNullOfOrNull { PhoneUtil.normalize(it) }
            val name = if (number != null) texts.firstNotNullOfOrNull { PhoneUtil.pushName(it) } else null
            out += Row(texts.joinToString("|"), number, name)
        }
        return out
    }

    /** Group info collapses long member lists behind a "View all (N more)" row; expand it if present. */
    private fun clickViewAll() {
        val root = rootInActiveWindow ?: return
        val node = root.findAccessibilityNodeInfosByText("View all").firstOrNull() ?: return
        clickableAncestor(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findChatList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$waPkg:id/conversations_row_contact_name").firstOrNull()?.let { n ->
            var p = n.parent
            while (p != null) { if (p.isScrollable) return p; p = p.parent }
        }
        // Fallback: the vertical list with the most children (ViewPager pages have only a few).
        var best: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            val cls = n.className?.toString().orEmpty()
            if (n.isScrollable && (cls.contains("RecyclerView") || cls.contains("ListView")) &&
                n.childCount > (best?.childCount ?: -1)) best = n
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        return best
    }

    // ---------------------------------------------------------------- getting names

    private fun startNaming() {
        val pending = store.all().filter { it.value.isBlank() }.keys
        if (pending.isEmpty()) { refresh("Every number already has a name."); return }
        if (!namesArmed) {
            namesArmed = true
            refresh("Get names opens each of ${pending.size} chats one by one — their unread messages will be marked as read. Tap Get names again to start.")
            return
        }
        namesArmed = false
        store.sourcePackage?.let { waPkg = it }
        queue.clear(); queue.addAll(pending)
        total = pending.size; done = 0; found = 0
        mode = Mode.NAMING
        refresh("Getting names… 0/$total — don't touch the phone")
        handler.post(::openNextChat)
    }

    private fun openNextChat() {
        if (mode != Mode.NAMING) return
        val number = queue.removeFirstOrNull()
        if (number == null) { stopAll("Names done: found $found of $total. Export from the app."); return }
        current = number
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=" + number.filter(Char::isDigit))
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(waPkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(intent) } catch (e: Exception) { stopAll("Couldn't open WhatsApp: ${e.message}"); return }
        handler.postDelayed(::readChatScreen, CHAT_OPEN_DELAY)
    }

    private fun readChatScreen() {
        if (mode != Mode.NAMING) return
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() !in WA_PACKAGES) { finishOne(null, 0); return }
        findPushName(root)?.let { finishOne(it, 1); return }

        // Not visible on the chat screen: open contact info by tapping the header.
        val header = root.findAccessibilityNodeInfosByViewId("$waPkg:id/conversation_contact").firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("$waPkg:id/conversation_contact_name").firstOrNull()
        val target = header?.let(::clickableAncestor)
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { finishOne(null, 1); return }
        handler.postDelayed(::readInfoScreen, INFO_OPEN_DELAY)
    }

    private fun readInfoScreen() {
        if (mode != Mode.NAMING) return
        val root = rootInActiveWindow
        val name = root?.let { findPushName(it) ?: findBusinessName(it) }
        finishOne(name, 2)
    }

    private fun finishOne(name: String?, backs: Int) {
        val number = current
        if (number != null && !name.isNullOrBlank()) { store.setName(number, name); found++ }
        done++
        refresh("Getting names… $done/$total (found $found)")
        goBack(backs) { handler.postDelayed(::openNextChat, BACK_DELAY) }
    }

    private fun goBack(times: Int, then: () -> Unit) {
        if (mode != Mode.NAMING) return
        if (times <= 0) { then(); return }
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ goBack(times - 1, then) }, BACK_DELAY)
    }

    private fun findPushName(root: AccessibilityNodeInfo): String? {
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        return texts.firstNotNullOfOrNull { PhoneUtil.pushName(it) }
    }

    /** Business accounts show their business name on the info screen without the "~". Best effort. */
    private fun findBusinessName(root: AccessibilityNodeInfo): String? {
        for (id in BUSINESS_NAME_IDS) {
            for (n in root.findAccessibilityNodeInfosByViewId("$waPkg:id/$id")) {
                val t = PhoneUtil.clean(n.text)
                if (t.isNotEmpty() && PhoneUtil.normalize(t) == null) return t.removePrefix("~").trim()
            }
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.let { if (it.isNotBlank()) out += it.toString() }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectTexts(it, out) }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        while (n != null) { if (n.isClickable) return n; n = n.parent }
        return null
    }

    private fun stopAll(message: String?) {
        mode = Mode.IDLE
        handler.removeCallbacksAndMessages(null)
        refresh(message)
    }

    // ---------------------------------------------------------------- floating panel

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlay != null) { refresh(null); return }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density

        fun pill(color: Int) = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(color) }
        fun button(onTap: () -> Unit) = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            background = pill(0xFF25D366.toInt())
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { onTap() }
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; maxWidth = (210 * dp).toInt()
            text = "WA Contact Exporter  ⠿ drag"
        }
        scanBtn = button { if (mode == Mode.IDLE) startScan() else stopAll("Stopped. ${store.count()} numbers saved.") }
        nameBtn = button { if (mode == Mode.IDLE) startNaming() else stopAll("Stopped. Names found: $found.") }
        groupBtn = button { if (mode == Mode.IDLE) startScan(group = true) else stopAll("Stopped. ${store.count()} numbers saved.") }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(0xEE1F2C34.toInt())
            val p = (10 * dp).toInt(); setPadding(p, p, p, p)
            addView(status)
            val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (8 * dp).toInt() }
            addView(scanBtn, gap)
            addView(nameBtn, LinearLayout.LayoutParams(gap))
            addView(groupBtn, LinearLayout.LayoutParams(gap))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - 240 * dp).toInt().coerceAtLeast(0)
            y = (140 * dp).toInt()
        }

        // Drag the panel by its status line.
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        status?.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt(); lp.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(box, lp); true
                }
                else -> true
            }
        }

        wm.addView(box, lp)
        overlay = box; params = lp
        refresh(null)
    }

    private fun hideOverlay() {
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        overlay = null; status = null; scanBtn = null; nameBtn = null; groupBtn = null
        namesArmed = false
    }

    private fun refresh(message: String?) {
        if (message != null) status?.text = message
        if (!::store.isInitialized) return
        scanBtn?.text = if (mode == Mode.SCANNING && !groupScan) "■ Stop scan" else "Scan chats (${store.count()})"
        groupBtn?.text = if (mode == Mode.SCANNING && groupScan) "■ Stop scan" else "Scan group members"
        nameBtn?.text = if (mode == Mode.NAMING) "■ Stop" else "Get names (${store.pendingNames()} left)"
        scanBtn?.alpha = if (mode == Mode.NAMING || (mode == Mode.SCANNING && groupScan)) 0.4f else 1f
        groupBtn?.alpha = if (mode == Mode.NAMING || (mode == Mode.SCANNING && !groupScan)) 0.4f else 1f
        nameBtn?.alpha = if (mode == Mode.SCANNING) 0.4f else 1f
        scanBtn?.isEnabled = mode == Mode.IDLE || (mode == Mode.SCANNING && !groupScan)
        groupBtn?.isEnabled = mode == Mode.IDLE || (mode == Mode.SCANNING && groupScan)
        nameBtn?.isEnabled = mode != Mode.SCANNING
    }
}
