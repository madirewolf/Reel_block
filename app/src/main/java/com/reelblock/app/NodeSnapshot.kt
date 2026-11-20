package com.reelblock.app

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A plain-data snapshot of an AccessibilityNodeInfo subtree.
 *
 * AccessibilityNodeInfo is awkward to test against — it requires the
 * full Android accessibility framework. By copying the parts we care
 * about into a POJO we can unit-test the detector with hand-crafted
 * trees on the JVM without any mocking.
 */
data class NodeSnapshot(
    val viewIdResourceName: String?,
    val contentDescription: String?,
    val text: String?,
    val className: String?,
    val isSelected: Boolean,
    val isScrollable: Boolean,
    val children: List<NodeSnapshot>,
    /**
     * False for views laid out but not on screen. Instagram keeps every
     * main tab's page (Reels, Messages, Profile, ...) in its tab pager
     * at all times; only the current one is visible. Reading the hidden
     * pages made Home look like a reel viewer and pulled reel authors
     * from the hidden Reels page.
     */
    val isVisibleToUser: Boolean = true,
) {
    /** Depth-first iteration over this node and its on-screen
     *  descendants. Hidden subtrees are skipped. */
    fun flatten(): Sequence<NodeSnapshot> = sequence {
        yield(this@NodeSnapshot)
        children.forEach { child ->
            if (child.isVisibleToUser) yieldAll(child.flatten())
        }
    }

    companion object {
        /** Used when the framework hands us a null root. */
        val EMPTY = NodeSnapshot(
            viewIdResourceName = null,
            contentDescription = null,
            text = null,
            className = null,
            isSelected = false,
            isScrollable = false,
            children = emptyList(),
        )
    }
}

/**
 * Convert a live AccessibilityNodeInfo tree into a pure-Kotlin snapshot.
 *
 * The tree is copied eagerly because AccessibilityNodeInfo objects can
 * be recycled out from under us by the system at any point after the
 * event callback returns. The maxDepth guard prevents runaway traversal
 * on pathological hierarchies (Instagram's UI is rich but not this rich).
 */
fun AccessibilityNodeInfo?.toSnapshot(maxDepth: Int = 32): NodeSnapshot? {
    if (this == null) return null
    return copyNode(this, 0, maxDepth)
}

private fun copyNode(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int): NodeSnapshot {
    val children = if (depth >= maxDepth) {
        emptyList()
    } else {
        buildList(node.childCount) {
            for (i in 0 until node.childCount) {
                val child = try {
                    fetchChild(node, i)
                } catch (_: Throwable) {
                    null
                } ?: continue
                // Off-screen subtrees (hidden tab pages, the screen under
                // a full-screen viewer) are skipped: they are never what
                // the user is looking at, and copying them is most of the
                // cost of a tree walk.
                if (!child.isVisibleToUser) continue
                add(copyNode(child, depth + 1, maxDepth))
            }
        }
    }
    return NodeSnapshot(
        viewIdResourceName = node.viewIdResourceName,
        contentDescription = node.contentDescription?.toString(),
        text = node.text?.toString(),
        className = node.className?.toString(),
        isSelected = node.isSelected,
        isScrollable = node.isScrollable,
        children = children,
        isVisibleToUser = node.isVisibleToUser,
    )
}

/**
 * Prefetch flags for tree walks. Every getChild is an IPC to Instagram's
 * UI thread. By default the system's batch prefetch is interruptible:
 * our very next getChild cancels it, so each node cost its own round
 * trip. On the Messages screen, where Instagram's UI thread is busy, that
 * made one walk take ~1.8 s and held up blocking behind it. Asking for
 * an uninterruptible batch brings up to 50 nodes back per IPC.
 */
internal val WALK_PREFETCH: Int =
    AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
        AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

private fun fetchChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        node.getChild(index, WALK_PREFETCH)
    } else {
        node.getChild(index)
    }
