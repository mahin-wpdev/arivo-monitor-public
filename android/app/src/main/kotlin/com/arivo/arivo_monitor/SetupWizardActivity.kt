package com.arivo.arivo_monitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SetupWizardActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var instructionText: TextView
    private lateinit var continueButton: Button
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var pendingVendorReview: String? = null
    private var waitingForBackgroundSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        refresh()
    }

    override fun onResume() {
        super.onResume()

        pendingVendorReview?.let { key ->
            prefs.edit().putBoolean(key, true).apply()
            pendingVendorReview = null
        }

        if (waitingForBackgroundSettings) {
            waitingForBackgroundSettings = false
        }

        refresh()
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(30))
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Complete Arivo Setup"
            textSize = 26f
            setTextColor(Color.rgb(20, 24, 32))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Complete these settings once so Arivo can stay online in the background."
            textSize = 16f
            setTextColor(Color.rgb(88, 96, 110))
            setPadding(0, dp(10), 0, dp(22))
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(34, 42, 54))
            setLineSpacing(0f, 1.25f)
        }

        instructionText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(86, 92, 104))
            setPadding(0, dp(22), 0, dp(18))
        }

        continueButton = Button(this).apply {
            textSize = 16f
            isAllCaps = false
            setOnClickListener { runNextStep() }
        }

        val footer = TextView(this).apply {
            text = "Android protects some background settings, so Arivo cannot silently change them. This setup opens the correct page and remembers the completed steps."
            textSize = 13f
            setTextColor(Color.rgb(118, 124, 136))
            setPadding(0, dp(20), 0, 0)
        }

        content.addView(title)
        content.addView(subtitle)
        content.addView(statusText)
        content.addView(instructionText)
        content.addView(
            continueButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )
        content.addView(footer)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun refresh() {
        val vivo = isVivoDevice()
        val lines = mutableListOf<String>()

        lines += row(hasForegroundLocation(), "Location permission")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lines += row(hasNotificationPermission(), "Notification permission")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lines += row(hasBackgroundLocation(), "Background location")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            lines += row(isBatteryOptimizationIgnored(), "Battery unrestricted")
        }
        if (vivo) {
            lines += row(prefs.getBoolean(KEY_VIVO_AUTOSTART, false), "Vivo Auto-start reviewed")
            lines += row(prefs.getBoolean(KEY_VIVO_BACKGROUND_POWER, false), "Vivo background power reviewed")
        }

        statusText.text = lines.joinToString("\n")

        val next = nextStep()
        instructionText.text = next.second
        continueButton.text = next.first

        if (isSetupComplete(this)) {
            instructionText.text = "All required background settings are complete."
            continueButton.text = "Finish setup"
        }
    }

    private fun row(done: Boolean, label: String): String =
        if (done) "✓  $label" else "•  $label"

    private fun nextStep(): Pair<String, String> {
        if (!hasForegroundLocation()) {
            return "Allow location" to
                "Allow precise location so Arivo can report the device location."
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission()
        ) {
            return "Allow notifications" to
                "Allow notifications. Android requires this permission for reliable foreground monitoring."
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasBackgroundLocation()
        ) {
            return "Allow background location" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "On the page that opens, choose Permissions → Location → Allow all the time, then return to Arivo."
            } else {
                "Allow location access while Arivo is running in the background."
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !isBatteryOptimizationIgnored()
        ) {
            return "Set battery unrestricted" to
                "Approve the Android prompt so battery optimization does not stop Arivo."
        }

        if (isVivoDevice() && !prefs.getBoolean(KEY_VIVO_AUTOSTART, false)) {
            return "Open Vivo Auto-start" to
                "Turn Arivo ON in Vivo Auto-start, then return to this screen."
        }

        if (isVivoDevice() && !prefs.getBoolean(KEY_VIVO_BACKGROUND_POWER, false)) {
            return "Open Vivo power settings" to
                "Allow Arivo to use background power / high background power, then return to this screen."
        }

        return "Finish setup" to "All required background settings are complete."
    }

    private fun runNextStep() {
        if (!hasForegroundLocation()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission()
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasBackgroundLocation()
        ) {
            requestBackgroundLocation()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !isBatteryOptimizationIgnored()
        ) {
            requestBatteryExemption()
            return
        }

        if (isVivoDevice() && !prefs.getBoolean(KEY_VIVO_AUTOSTART, false)) {
            openVivoAutoStart()
            return
        }

        if (isVivoDevice() && !prefs.getBoolean(KEY_VIVO_BACKGROUND_POWER, false)) {
            openVivoBackgroundPower()
            return
        }

        finishSetup()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQUEST_BACKGROUND_LOCATION
            )
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Allow all the time")
            .setMessage(
                "Android requires this setting to be changed on the app page. " +
                    "Open Permissions → Location and select “Allow all the time”, then return."
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open settings") { _, _ ->
                waitingForBackgroundSettings = true
                openAppDetails()
            }
            .show()
    }

    private fun requestBatteryExemption() {
        try {
            prefs.edit().putBoolean("battery_optimization_prompted", true).apply()
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                openAppDetails()
            }
        }
    }

    private fun openVivoAutoStart() {
        pendingVendorReview = KEY_VIVO_AUTOSTART
        val candidates = listOf(
            explicitIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            explicitIntent(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            ),
            explicitIntent(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
        )

        if (!openFirstAvailable(candidates)) {
            openAppDetails()
        }
    }

    private fun openVivoBackgroundPower() {
        pendingVendorReview = KEY_VIVO_BACKGROUND_POWER
        val candidates = listOf(
            explicitIntent(
                "com.vivo.abe",
                "com.vivo.abe.ui.power.PowerConsumptionActivity"
            ),
            explicitIntent(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.PowerSaveManagerActivity"
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )

        if (!openFirstAvailable(candidates)) {
            openAppDetails()
        }
    }

    private fun explicitIntent(packageName: String, className: String): Intent =
        Intent().apply {
            component = ComponentName(packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun openFirstAvailable(candidates: List<Intent>): Boolean {
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
                // Try the next Vivo/Funtouch OS variant.
            }
        }
        return false
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun finishSetup() {
        prefs.edit().putBoolean(KEY_SETUP_FINISHED, true).apply()
        startMonitorService()
        setResult(RESULT_OK)
        finish()
    }

    private fun startMonitorService() {
        val service = Intent(this, MonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service)
            } else {
                startService(service)
            }
        } catch (_: Exception) {
            // MainActivity/BootReceiver will retry when Android allows foreground startup.
        }
    }

    private fun hasForegroundLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    companion object {
        private const val PREFS = "arivo_local_setup"
        private const val KEY_SETUP_FINISHED = "setup_wizard_finished"
        private const val KEY_VIVO_AUTOSTART = "vivo_autostart_reviewed"
        private const val KEY_VIVO_BACKGROUND_POWER = "vivo_background_power_reviewed"
        private const val REQUEST_LOCATION = 3101
        private const val REQUEST_NOTIFICATIONS = 3102
        private const val REQUEST_BACKGROUND_LOCATION = 3103

        fun isSetupComplete(context: Context): Boolean {
            val foregroundLocation =
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED

            val notifications =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED

            val backgroundLocation =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED

            val battery =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) true
                else {
                    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    power.isIgnoringBatteryOptimizations(context.packageName)
                }

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
            val brand = (Build.BRAND ?: "").lowercase()
            val vivo = manufacturer.contains("vivo") || brand.contains("vivo") ||
                manufacturer.contains("iqoo") || brand.contains("iqoo")
            val vendorReady = !vivo ||
                (prefs.getBoolean(KEY_VIVO_AUTOSTART, false) &&
                    prefs.getBoolean(KEY_VIVO_BACKGROUND_POWER, false))

            return foregroundLocation && notifications && backgroundLocation &&
                battery && vendorReady
        }

        fun shouldShow(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return !prefs.getBoolean(KEY_SETUP_FINISHED, false) || !isSetupComplete(context)
        }
    }

    private fun isVivoDevice(): Boolean {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        return manufacturer.contains("vivo") || brand.contains("vivo") ||
            manufacturer.contains("iqoo") || brand.contains("iqoo")
    }
}
