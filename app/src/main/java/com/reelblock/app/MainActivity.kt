package com.reelblock.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.button.MaterialButton

/**
 * The only settings screen. Top to bottom:
 *  - Status: on, paused, or not running yet.
 *  - Setup, three numbered steps: accessibility (required),
 *    notifications, battery. Each turns into a filled check when done.
 *  - Options: three switches, each stored in [SettingsStore] and picked
 *    up by the service live.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    private lateinit var statusMark: View
    private lateinit var statusText: TextView
    private lateinit var steps: List<Step>
    private lateinit var switchBlocking: SwitchCompat
    private lateinit var switchDmReels: SwitchCompat
    private lateinit var switchFeedReels: SwitchCompat
    private lateinit var rowDm: View
    private lateinit var rowFeed: View

    private class Step(
        val number: Int,
        val mark: TextView,
        val body: TextView,
        val bodyText: Int,
        val button: MaterialButton,
        val isDone: () -> Boolean,
    )

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Denied for good: the system dialog no longer shows, so send
            // the user to this app's notification settings instead.
            if (!notificationsAllowed()) openNotificationSettings()
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore(this)

        statusMark = findViewById(R.id.status_mark)
        statusText = findViewById(R.id.status_text)

        steps = listOf(
            step(1, R.id.step_1_mark, R.id.step_1_body, R.string.step_1_body, R.id.step_1_button) {
                ServiceStatus.isAccessibilityServiceEnabled(this)
            },
            step(2, R.id.step_2_mark, R.id.step_2_body, R.string.step_2_body, R.id.step_2_button) {
                notificationsAllowed()
            },
            step(3, R.id.step_3_mark, R.id.step_3_body, R.string.step_3_body, R.id.step_3_button) {
                batteryUnrestricted()
            },
        )
        steps[0].button.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        steps[1].button.setOnClickListener { askForNotifications() }
        steps[2].button.setOnClickListener { askForBatteryExemption() }

        switchBlocking = findViewById(R.id.switch_blocking)
        switchDmReels = findViewById(R.id.switch_dm_reels)
        switchFeedReels = findViewById(R.id.switch_feed_reels)
        rowDm = findViewById(R.id.row_dm)
        rowFeed = findViewById(R.id.row_feed)

        bindSwitch(R.id.row_block, switchBlocking, settings.blockingEnabled) {
            settings.blockingEnabled = it
        }
        bindSwitch(R.id.row_dm, switchDmReels, settings.allowDmReels) {
            settings.allowDmReels = it
        }
        bindSwitch(R.id.row_feed, switchFeedReels, settings.hideFeedReels) {
            settings.hideFeedReels = it
        }

        findViewById<View>(R.id.row_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Steps are usually finished in system screens; re-check on return.
        refresh()
    }

    private fun step(
        number: Int,
        markId: Int,
        bodyId: Int,
        bodyText: Int,
        buttonId: Int,
        isDone: () -> Boolean,
    ) = Step(number, findViewById(markId), findViewById(bodyId), bodyText, findViewById(buttonId), isDone)

    /** The whole row toggles its switch, so the tap target is large. */
    private fun bindSwitch(rowId: Int, switch: SwitchCompat, initial: Boolean, save: (Boolean) -> Unit) {
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, checked ->
            save(checked)
            refresh()
        }
        findViewById<View>(rowId).setOnClickListener {
            if (switch.isEnabled) switch.toggle()
        }
    }

    private fun refresh() {
        steps.forEach { s ->
            val done = s.isDone()
            s.mark.text = if (done) getString(R.string.step_done_mark) else s.number.toString()
            s.mark.setBackgroundResource(if (done) R.drawable.bg_step_done else R.drawable.bg_step_todo)
            s.mark.setTextColor(getColor(if (done) R.color.rb_on_primary else R.color.rb_on_surface))
            s.body.setText(if (done) R.string.step_done else s.bodyText)
            s.button.visibility = if (done) View.GONE else View.VISIBLE
        }

        val running = steps[0].isDone()
        val blocking = settings.blockingEnabled
        when {
            !running -> status(R.color.rb_status_setup, R.string.status_setup)
            !blocking -> status(R.color.rb_status_off, R.string.status_paused)
            else -> status(R.color.rb_status_on, R.string.status_on)
        }

        // The other two options only mean something while blocking is on.
        listOf(rowDm to switchDmReels, rowFeed to switchFeedReels).forEach { (row, sw) ->
            sw.isEnabled = blocking
            row.alpha = if (blocking) 1f else 0.4f
        }
    }

    private fun status(color: Int, text: Int) {
        statusMark.setBackgroundColor(getColor(color))
        statusText.setText(text)
    }

    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try { startActivity(intent) } catch (_: Throwable) { /* no settings screen */ }
    }

    private fun batteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun askForBatteryExemption() {
        // The per-app system dialog; if an OEM lacks it, the full list.
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(direct)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) { /* nothing to open */ }
        }
    }
}
