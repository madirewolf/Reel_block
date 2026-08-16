package com.reelblock.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single-screen dashboard. Its job is to:
 *  - Explain what Reel Block does.
 *  - Report which OS-level permissions/services are still missing.
 *  - Open the relevant system settings screen with a tap.
 *  - Let the user pause blocking without touching system settings.
 *  - Link to the live detection log.
 *  - Surface a "service liveness" heartbeat so the user can tell at a
 *    glance whether an OEM task-killer killed the accessibility service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    private lateinit var chipOverlay: TextView
    private lateinit var chipAccessibility: TextView
    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnLog: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var livenessText: TextView
    private lateinit var btnBattery: MaterialButton
    private lateinit var btnBatteryGuide: MaterialButton
    private lateinit var switchBlocking: MaterialSwitch
    private lateinit var switchDmReels: MaterialSwitch

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result is informational */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore(this)

        chipOverlay = findViewById(R.id.chip_overlay)
        chipAccessibility = findViewById(R.id.chip_accessibility)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnLog = findViewById(R.id.btn_view_log)
        statusText = findViewById(R.id.status_text)
        livenessText = findViewById(R.id.liveness_text)
        btnBattery = findViewById(R.id.btn_battery)
        btnBatteryGuide = findViewById(R.id.btn_battery_guide)
        switchBlocking = findViewById(R.id.switch_blocking)
        switchDmReels = findViewById(R.id.switch_dm_reels)

        btnOverlay.setOnClickListener { openOverlaySettings() }
        btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        btnLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        btnBattery.setOnClickListener { openBatteryOptimizationSettings() }
        btnBatteryGuide.setOnClickListener { openDontKillMyApp() }

        switchBlocking.isChecked = settings.blockingEnabled
        switchBlocking.setOnCheckedChangeListener { _, isChecked ->
            settings.blockingEnabled = isChecked
            refresh()
        }

        switchDmReels.isChecked = settings.allowDmReels
        switchDmReels.setOnCheckedChangeListener { _, isChecked ->
            settings.allowDmReels = isChecked
        }

        // On API 33+, the live status notification from the service only
        // renders if the user grants POST_NOTIFICATIONS. Ask once — if
        // denied the app still functions, just without the live readout.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Re-check status on a light heartbeat while this screen is
        // visible — users often bounce out to the Settings app and back,
        // and the liveness timer needs to tick in real time so the user
        // can immediately see whether the service is alive after a
        // reboot / OEM kill.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val hasOverlay = ServiceStatus.canDrawOverlays(this)
        val hasAccessibility = ServiceStatus.isAccessibilityServiceEnabled(this)

        setChip(chipOverlay, hasOverlay,
            okText = R.string.card_overlay_granted,
            warnText = R.string.card_overlay_missing)
        setChip(chipAccessibility, hasAccessibility,
            okText = R.string.card_accessibility_enabled,
            warnText = R.string.card_accessibility_disabled)

        btnOverlay.visibility = if (hasOverlay) View.GONE else View.VISIBLE
        btnAccessibility.visibility = if (hasAccessibility) View.GONE else View.VISIBLE

        val blockingEnabled = settings.blockingEnabled
        switchBlocking.isEnabled = hasOverlay && hasAccessibility

        statusText.setText(
            when {
                !hasOverlay || !hasAccessibility -> R.string.card_status_paused
                !blockingEnabled -> R.string.card_status_disabled_by_user
                else -> R.string.card_status_active
            }
        )

        refreshLiveness()
    }

    private fun refreshLiveness() {
        val lastAt = DetectionLog.lastEntryAt()
        if (lastAt == null) {
            livenessText.setText(R.string.card_liveness_never)
            return
        }
        val ago = (System.currentTimeMillis() - lastAt).coerceAtLeast(0)
        livenessText.text = getString(R.string.card_liveness_body, formatAgo(ago))
    }

    private fun formatAgo(ms: Long): String {
        val s = ms / 1000
        if (s < 60) return "${s}s"
        val m = s / 60
        if (m < 60) return "${m}m ${s % 60}s"
        val h = m / 60
        return "${h}h ${m % 60}m"
    }

    private fun setChip(chip: TextView, ok: Boolean, okText: Int, warnText: Int) {
        chip.setText(if (ok) okText else warnText)
        chip.setBackgroundResource(if (ok) R.drawable.bg_chip_ok else R.drawable.bg_chip_warn)
        chip.setTextColor(getColor(if (ok) R.color.rb_chip_ok_fg else R.color.rb_chip_warn_fg))
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openBatteryOptimizationSettings() {
        // Prefer the per-app request dialog; if the OEM doesn't support
        // it, fall back to the system battery-optimization list.
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try {
            startActivity(direct)
        } catch (_: Throwable) {
            try { startActivity(fallback) } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun openDontKillMyApp() {
        // The dontkillmyapp.com project maintains the best OEM-specific
        // instructions for keeping background services alive. Linking
        // there is the most honest thing we can do — we can't monkey
        // patch Samsung's battery manager for the user.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com")))
        } catch (_: Throwable) { /* ignore */ }
    }

    companion object {
        private const val POLL_MS = 1000L
    }
}
