package com.reelblock.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Live view of the most recent detection events.
 *
 * Share button: writes the JSON to the public Downloads folder and
 * copies it to the clipboard. That's it — no share sheet, no
 * FileProvider, no MIME-type negotiation. The share-intent approach
 * was fighting Samsung OneUI for three revisions straight; a file on
 * disk plus a clipboard copy is the simplest thing that works.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var adapter: LogAdapter
    private lateinit var emptyState: View

    private val refreshListener: () -> Unit = {
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationContentDescription(R.string.nav_up)
        toolbar.setNavigationOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = LogAdapter()
        list.adapter = adapter

        emptyState = findViewById(R.id.empty_state)

        findViewById<MaterialButton>(R.id.btn_clear).setOnClickListener {
            DetectionLog.clear()
        }
        findViewById<MaterialButton>(R.id.btn_share).setOnClickListener {
            exportLog()
        }
    }

    override fun onStart() {
        super.onStart()
        DetectionLog.addListener(refreshListener)
        refresh()
    }

    override fun onStop() {
        DetectionLog.removeListener(refreshListener)
        super.onStop()
    }

    private fun refresh() {
        val entries = DetectionLog.snapshot()
        adapter.submit(entries)
        emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Serialize the log, save to Downloads, copy to clipboard. One
     * button, one job. Tell the user exactly where the file landed so
     * they don't have to guess.
     */
    private fun exportLog() {
        val entries = DetectionLog.snapshot()
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.detection_log_export_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val json = serializeLog(entries)
        val filename = "reelblock-log-${System.currentTimeMillis()}.json"

        val savedPath = writeToDownloads(filename, json)
        copyToClipboard(json)

        val message = if (savedPath != null) {
            getString(R.string.detection_log_saved_to, savedPath)
        } else {
            getString(R.string.detection_log_clipboard_only)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun serializeLog(entries: List<DetectionLog.Entry>): String {
        val packageInfo = loadPackageInfo()
        val meta = JSONObject().apply {
            put("appVersion", packageInfo?.versionName ?: "unknown")
            put("appVersionCode", packageInfo?.let { packageInfoToVersionCode(it) } ?: "unknown")
            put("deviceManufacturer", Build.MANUFACTURER)
            put("deviceModel", Build.MODEL)
            put("androidSdk", Build.VERSION.SDK_INT)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("entryCount", entries.size)
        }

        val entriesJson = JSONArray()
        entries.forEach { entry ->
            entriesJson.put(JSONObject().apply {
                put("timestampEpochMs", entry.timestamp)
                put("timestamp", DetectionLog.formatTime(entry.timestamp))
                put("screen", entry.screen.name)
                put("action", entry.action.name)
                put("mode", entry.mode.name)
                put("modeBefore", entry.modeBefore?.name ?: JSONObject.NULL)
                put("previousScreenBefore", entry.previousScreenBefore?.name ?: JSONObject.NULL)
                put("previousScreenAfter", entry.previousScreenAfter?.name ?: JSONObject.NULL)
                put("signature", entry.signature ?: JSONObject.NULL)
                put("note", entry.note ?: JSONObject.NULL)
                put("diagnostics", entry.diagnostics ?: JSONObject.NULL)
                put("eventType", entry.eventType ?: JSONObject.NULL)
                put("eventClassName", entry.eventClassName ?: JSONObject.NULL)
                put("packageName", entry.packageName ?: JSONObject.NULL)
                put("treeSource", entry.treeSource ?: JSONObject.NULL)
                put("selectedBottomTab", entry.selectedBottomTab ?: JSONObject.NULL)
                put("lastNonReelsBottomTab", entry.lastNonReelsBottomTab ?: JSONObject.NULL)
                put("blockRoute", entry.blockRoute ?: JSONObject.NULL)
                put("blockSucceeded", entry.blockSucceeded ?: JSONObject.NULL)
                put("rootPackageName", entry.rootPackageName ?: JSONObject.NULL)
                put("sourcePackageName", entry.sourcePackageName ?: JSONObject.NULL)
                put("nodeCount", entry.nodeCount ?: JSONObject.NULL)
                put("treeDepth", entry.treeDepth ?: JSONObject.NULL)
                put("interestingIds", JSONArray().apply {
                    entry.interestingIds.forEach { put(it) }
                })
                put("sampledDescriptions", JSONArray().apply {
                    entry.sampledDescriptions.forEach { put(it) }
                })
                put("handleLatencyMs", entry.handleLatencyMs ?: JSONObject.NULL)
                put("fastPath", entry.fastPath)
            })
        }

        return JSONObject().apply {
            put("meta", meta)
            put("entries", entriesJson)
        }.toString(2)
    }

    /**
     * Write the serialized JSON to the public Downloads folder.
     *
     * On Android 10+ we use the MediaStore.Downloads collection —
     * scoped-storage-compliant, no WRITE_EXTERNAL_STORAGE permission
     * required. On older devices we fall back to the legacy public
     * downloads path.
     *
     * Returns a user-visible "where the file is" path on success,
     * null if the write failed (caller falls back to clipboard-only
     * messaging).
     */
    private fun writeToDownloads(filename: String, content: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray())
                } ?: return null
                "Downloads/$filename"
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, filename)
                file.writeText(content)
                file.absolutePath
            }
        } catch (t: Throwable) {
            android.util.Log.e("ReelBlock/LogActivity", "Write to Downloads failed", t)
            null
        }
    }

    private fun copyToClipboard(content: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Reel It In log", content))
        } catch (t: Throwable) {
            android.util.Log.w("ReelBlock/LogActivity", "Clipboard copy failed", t)
        }
    }

    private fun loadPackageInfo(): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun packageInfoToVersionCode(info: PackageInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
    }

    private class LogAdapter : RecyclerView.Adapter<LogVH>() {
        private val items = mutableListOf<DetectionLog.Entry>()

        fun submit(next: List<DetectionLog.Entry>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false)
            return LogVH(view)
        }

        override fun onBindViewHolder(holder: LogVH, position: Int) = holder.bind(items[position])

        override fun getItemCount(): Int = items.size
    }

    private class LogVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val time: TextView = itemView.findViewById(R.id.log_time)
        private val action: TextView = itemView.findViewById(R.id.log_action)
        private val mode: TextView = itemView.findViewById(R.id.log_mode)
        private val screen: TextView = itemView.findViewById(R.id.log_screen)
        private val sig: TextView = itemView.findViewById(R.id.log_sig)
        private val diagnosticsView: TextView = itemView.findViewById(R.id.log_diag)

        fun bind(entry: DetectionLog.Entry) {
            val ctx = itemView.context
            time.text = DetectionLog.formatTime(entry.timestamp)
            screen.text = "${entry.screen.name}${entry.note?.let { "  •  $it" } ?: ""}"
            mode.text = "mode=" + entry.mode.name
            val sigText = entry.signature ?: "—"
            sig.text = "sig=" + sigText
            if (entry.diagnostics.isNullOrBlank()) {
                diagnosticsView.visibility = View.GONE
            } else {
                diagnosticsView.visibility = View.VISIBLE
                diagnosticsView.text = entry.diagnostics
            }

            action.text = entry.action.name
            when (entry.action) {
                SessionStateMachine.Action.BLOCK_REEL -> {
                    action.setBackgroundResource(R.drawable.bg_chip_warn)
                    action.setTextColor(ctx.getColor(R.color.rb_chip_warn_fg))
                }
                SessionStateMachine.Action.ALLOW_REEL -> {
                    action.setBackgroundResource(R.drawable.bg_chip_ok)
                    action.setTextColor(ctx.getColor(R.color.rb_chip_ok_fg))
                }
                SessionStateMachine.Action.NONE -> {
                    action.setBackgroundResource(R.drawable.bg_chip_warn)
                    action.setTextColor(ctx.getColor(R.color.rb_on_surface_muted))
                }
            }
        }
    }
}
