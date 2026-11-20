package com.reelblock.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * The only settings screen. Top to bottom:
 *  - Status: on, paused, off, or not running yet.
 *  - Setup, three numbered steps: accessibility (required),
 *    notifications, battery. Each turns into a filled check when done.
 *  - Options: the master switch and the notification.
 *  - Block reels in: one switch per place in Instagram.
 *  - Block screen: a live preview, colors, patterns and a photo.
 * Everything is stored in [SettingsStore] and picked up by the service live.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    private lateinit var statusMark: View
    private lateinit var statusText: TextView
    private lateinit var steps: List<Step>
    private lateinit var switchBlocking: SwitchCompat
    /** The per-place rows, greyed out while blocking is off. */
    private lateinit var placeRows: List<Pair<View, SwitchCompat>>
    private lateinit var placesFootnote: View

    private lateinit var preview: View
    private lateinit var swatches: List<View>
    private lateinit var styleChips: List<Pair<String, TextView>>
    private lateinit var changePhoto: TextView
    private var previewPhoto: Bitmap? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refresh() }

    private class Step(
        val number: Int,
        val mark: TextView,
        val body: TextView,
        val bodyText: Int,
        val button: MaterialButton,
        val isDone: () -> Boolean,
    )

    /** The notification's Pause button, and the pause ending, change
     *  the status while this screen is open. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            SettingsStore.KEY_PAUSED_UNTIL, SettingsStore.KEY_BLOCKING_ENABLED -> refresh()
            in SettingsStore.COVER_KEYS -> refreshCover()
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Denied for good: the system dialog no longer shows, so send
            // the user to this app's notification settings instead.
            if (!notificationsAllowed()) openNotificationSettings()
            refresh()
        }

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            Thread {
                val photo = savePhoto(uri)
                runOnUiThread {
                    if (photo == null) {
                        Toast.makeText(this, R.string.cover_photo_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        previewPhoto = photo
                        settings.coverStyle = CoverStyle.PHOTO
                        settings.coverPhotoVersion = System.currentTimeMillis()
                        refreshCover()
                    }
                }
            }.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Android 15+ draws the app edge to edge; keep content clear of the bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_scroll)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

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
        steps[0].button.setOnClickListener { showAccessibilityDisclosure() }
        steps[1].button.setOnClickListener { askForNotifications() }
        steps[2].button.setOnClickListener { askForBatteryExemption() }

        switchBlocking = findViewById(R.id.switch_blocking)
        bindSwitch(R.id.row_block, switchBlocking, settings.blockingEnabled) {
            settings.blockingEnabled = it
            // Turning blocking on by hand also ends a pause.
            if (it) settings.pausedUntil = 0L
        }
        bindSwitch(R.id.row_notification, findViewById(R.id.switch_notification), settings.showNotification) {
            settings.showNotification = it
        }

        placeRows = listOf(
            place(R.id.row_reels, R.id.switch_reels, settings.blockReelsTab) { settings.blockReelsTab = it },
            place(R.id.row_home, R.id.switch_home, settings.blockHomeReels) { settings.blockHomeReels = it },
            place(R.id.row_explore, R.id.switch_explore, settings.blockExploreReels) { settings.blockExploreReels = it },
            place(R.id.row_profile, R.id.switch_profile, settings.blockOwnProfileReels) { settings.blockOwnProfileReels = it },
            // The DMs switch blocks; the stored setting allows.
            place(R.id.row_dm, R.id.switch_dm, !settings.allowDmReels) { settings.allowDmReels = !it },
        )
        placesFootnote = findViewById(R.id.places_footnote)

        setUpCoverPicker()

        findViewById<View>(R.id.row_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        settings.registerListener(prefsListener)
    }

    override fun onResume() {
        super.onResume()
        // Steps are usually finished in system screens; re-check on return.
        switchBlocking.isChecked = settings.blockingEnabled
        refresh()
    }

    override fun onStop() {
        settings.unregisterListener(prefsListener)
        handler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    private fun step(
        number: Int,
        markId: Int,
        bodyId: Int,
        bodyText: Int,
        buttonId: Int,
        isDone: () -> Boolean,
    ) = Step(number, findViewById(markId), findViewById(bodyId), bodyText, findViewById(buttonId), isDone)

    private fun place(rowId: Int, switchId: Int, initial: Boolean, save: (Boolean) -> Unit): Pair<View, SwitchCompat> {
        val sw = findViewById<SwitchCompat>(switchId)
        bindSwitch(rowId, sw, initial, save)
        return findViewById<View>(rowId) to sw
    }

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
        val pausedFor = settings.pausedUntil - System.currentTimeMillis()
        handler.removeCallbacks(refreshRunnable)
        when {
            !running -> status(R.color.rb_status_setup, R.string.status_setup)
            !blocking -> status(R.color.rb_status_off, R.string.status_paused)
            pausedFor > 0 -> {
                status(R.color.rb_status_off, R.string.status_paused_minute)
                handler.postDelayed(refreshRunnable, pausedFor + 100)
            }
            else -> status(R.color.rb_status_on, R.string.status_on)
        }

        // Where to block only means something while blocking is on.
        placeRows.forEach { (row, sw) ->
            sw.isEnabled = blocking
            row.alpha = if (blocking) 1f else 0.4f
        }
        placesFootnote.alpha = if (blocking) 1f else 0.4f
    }

    private fun status(color: Int, text: Int) {
        statusMark.setBackgroundColor(getColor(color))
        statusText.setText(text)
    }

    // ---- Block screen picker ----

    private fun setUpCoverPicker() {
        preview = findViewById(R.id.cover_preview)
        changePhoto = findViewById(R.id.cover_change_photo)
        changePhoto.setOnClickListener { launchPhotoPicker() }

        val swatchNames = intArrayOf(
            R.string.swatch_default, R.string.swatch_slate, R.string.swatch_plum,
            R.string.swatch_pine, R.string.swatch_clay, R.string.swatch_paper,
        )
        val swatchRow = findViewById<LinearLayout>(R.id.swatch_row)
        swatches = CoverLook.SWATCHES.mapIndexed { i, color ->
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    if (i > 0) marginStart = dp(10)
                }
                contentDescription = getString(swatchNames.getOrElse(i) { R.string.swatch_default })
                setOnClickListener {
                    settings.coverColor = color
                    // A color is a look of its own; leave the photo.
                    if (settings.coverStyle == CoverStyle.PHOTO) settings.coverStyle = CoverStyle.SOLID
                }
                swatchRow.addView(this)
            }
        }

        val styleRow = findViewById<LinearLayout>(R.id.style_row)
        val styles = listOf(
            CoverStyle.SOLID to R.string.cover_style_solid,
            CoverStyle.DOTS to R.string.cover_style_dots,
            CoverStyle.STRIPES to R.string.cover_style_stripes,
            CoverStyle.GRID to R.string.cover_style_grid,
            CoverStyle.PHOTO to R.string.cover_style_photo,
        )
        styleChips = styles.mapIndexed { i, (style, label) ->
            val chip = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                }
                gravity = Gravity.CENTER
                setText(label)
                textSize = 13f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setOnClickListener { pickStyle(style) }
            }
            styleRow.addView(chip)
            style to chip
        }

        if (settings.coverStyle == CoverStyle.PHOTO) loadPreviewPhoto()
        refreshCover()
    }

    private fun pickStyle(style: String) {
        if (style != CoverStyle.PHOTO) {
            settings.coverStyle = style
            return
        }
        if (CoverLook.hasPhoto(this)) {
            if (previewPhoto == null) loadPreviewPhoto()
            settings.coverStyle = CoverStyle.PHOTO
        } else {
            launchPhotoPicker()
        }
    }

    private fun launchPhotoPicker() {
        try {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.cover_photo_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPreviewPhoto() {
        Thread {
            val photo = CoverLook.loadPhoto(this)
            runOnUiThread {
                previewPhoto = photo
                refreshCover()
            }
        }.start()
    }

    private fun refreshCover() {
        val style = settings.coverStyle
        val color = settings.coverColor
        CoverLook.apply(
            this, preview,
            preview.findViewById<ImageView>(R.id.blocking_photo),
            preview.findViewById<TextView>(R.id.blocking_title),
            style, color, previewPhoto,
        )

        val onSurface = getColor(R.color.rb_on_surface)
        val line = getColor(R.color.rb_line)
        swatches.forEachIndexed { i, v ->
            val c = CoverLook.SWATCHES[i]
            val selected = c == color && style != CoverStyle.PHOTO
            v.background = GradientDrawable().apply {
                setColor(CoverLook.baseColor(this@MainActivity, c))
                setStroke(if (selected) dp(2) else dp(1), if (selected) onSurface else line)
            }
            v.isSelected = selected
        }
        styleChips.forEach { (s, chip) ->
            val selected = s == style
            chip.background = GradientDrawable().apply {
                setColor(if (selected) onSurface else 0)
                setStroke(dp(1), if (selected) onSurface else line)
            }
            chip.setTextColor(if (selected) getColor(R.color.rb_bg) else onSurface)
            chip.isSelected = selected
        }
        changePhoto.visibility = if (style == CoverStyle.PHOTO) View.VISIBLE else View.GONE
    }

    /**
     * Copies the picked photo into the app's own storage, turned upright
     * and no larger than [PHOTO_MAX_SIDE]. Runs off the main thread.
     */
    private fun savePhoto(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_SIDE) sample *= 2
            val decoded = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val rotation = contentResolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
            val scale = minOf(1f, PHOTO_MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
            val upright = if (scale < 1f || rotation != 0) {
                val m = Matrix().apply {
                    postScale(scale, scale)
                    if (rotation != 0) postRotate(rotation.toFloat())
                }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            } else {
                decoded
            }
            val tmp = File(filesDir, CoverStyle.PHOTO_FILE + ".tmp")
            FileOutputStream(tmp).use { upright.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(CoverLook.photoFile(this))) return null
            upright
        } catch (_: Throwable) {
            null
        }
    }

    private fun exifRotation(stream: InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Throwable) {
        0
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---- Setup steps ----

    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    /** Says what the service reads and why, and only opens Accessibility settings on Agree. */
    private fun showAccessibilityDisclosure() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_body)
            .setPositiveButton(R.string.disclosure_agree) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.disclosure_decline, null)
            .show()
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

    private companion object {
        /** Longest side of a saved block-screen photo, in pixels. */
        const val PHOTO_MAX_SIDE = 1600
    }
}
