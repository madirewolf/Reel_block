package com.reelblock.app

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
) {
    /** Depth-first iteration including this node. */
    fun flatten(): Sequence<NodeSnapshot> = sequence {
        yield(this@NodeSnapshot)
        children.forEach { yieldAll(it.flatten()) }
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
                    node.getChild(i)
                } catch (_: Throwable) {
                    null
                } ?: continue
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
    )
}
