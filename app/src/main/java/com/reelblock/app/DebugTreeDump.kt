package com.reelblock.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug builds only: writes every on-screen window's accessibility tree,
 * exactly as this service sees it, to the app's private files dir, plus
 * what [ReelDetector] makes of the Instagram tree. Used to check the
 * detector's view-ID markers against a live Instagram build without
 * relying on `uiautomator dump`, which fails while video plays and
 * hides not-important views.
 *
 * Trigger:  adb shell am broadcast -a com.reelblock.app.DEBUG_DUMP --es label reels_tab
 * Fetch:    adb exec-out run-as com.reelblock.app.debug cat files/dumps/<name>.json
 *
 * Dumps contain whatever is on screen (usernames, message text). They
 * stay in app-private storage and must never be committed.
 */
object DebugTreeDump {

    const val ACTION = "com.reelblock.app.DEBUG_DUMP"
    private const val TAG = "ReelBlock/Dump"

    fun dump(service: AccessibilityService, label: String): File? = try {
        val out = JSONObject()
        out.put("label", label)
        out.put("timestamp", System.currentTimeMillis())
        val windowsJson = JSONArray()
        val windows = try { service.windows } catch (_: Throwable) { emptyList() }
        for (w in windows) {
            val root = try { w.root } catch (_: Throwable) { null }
            val wj = JSONObject()
            wj.put("type", windowType(w.type))
            wj.put("title", w.title?.toString())
            wj.put("layer", w.layer)
            wj.put("active", w.isActive)
            wj.put("package", root?.packageName?.toString())
            if (root != null) {
                val snap = root.toSnapshot()
                if (snap != null) {
                    wj.put("detectedScreen", ReelDetector.detectScreen(snap).name)
                    wj.put("selectedBottomTab", ReelDetector.extractSelectedBottomTab(snap))
                    wj.put("signature", ReelDetector.extractReelSignature(snap))
                }
                wj.put("tree", node(root, 0))
            }
            windowsJson.put(wj)
        }
        out.put("windows", windowsJson)
        val active = try { service.rootInActiveWindow } catch (_: Throwable) { null }
        out.put("activeRootPackage", active?.packageName?.toString())
        val dir = File(service.filesDir, "dumps").apply { mkdirs() }
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "dump-$safeLabel-${System.currentTimeMillis()}.json")
        file.writeText(out.toString(1))
        Log.i(TAG, "wrote ${file.name}")
        file
    } catch (t: Throwable) {
        Log.w(TAG, "dump failed", t)
        null
    }

    private fun node(n: AccessibilityNodeInfo, depth: Int): JSONObject {
        val o = JSONObject()
        n.viewIdResourceName?.let { o.put("id", it) }
        n.className?.let { o.put("cls", it.toString()) }
        n.contentDescription?.let { o.put("desc", it.toString()) }
        n.text?.let { o.put("text", it.toString()) }
        if (n.isSelected) o.put("selected", true)
        if (n.isClickable) o.put("clickable", true)
        if (n.isScrollable) o.put("scrollable", true)
        if (!n.isVisibleToUser) o.put("invisible", true)
        val r = Rect().also { n.getBoundsInScreen(it) }
        o.put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
        if (depth < 40 && n.childCount > 0) {
            val kids = JSONArray()
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue
                kids.put(node(c, depth + 1))
            }
            o.put("children", kids)
        }
        return o
    }

    private fun windowType(t: Int): String = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11yOverlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
        else -> "type$t"
    }
}
