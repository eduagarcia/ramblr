package com.trevornk.ramblr

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * The list behind [PauseNearApps]: the apps Ramblr turns its accessibility service off for.
 * Built like [ExclusionListActivity] (exact package names, no installed-app picker, for the same
 * package-visibility reason), plus the two things the feature needs from outside the app and
 * whether it has them.
 */
class PauseNearAppsActivity : BaseSettingsActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyLabel: TextView
    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = "Pause near apps"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        root.addView(TextView(this).apply {
            text = "While one of the apps below is in front, Ramblr turns its accessibility service " +
                "off completely, the same as \"Turn Ramblr off\", and turns it back on when you leave " +
                "the app. For banking apps that warn about, or refuse to run next to, an accessibility " +
                "service they do not know. A notification shows while Ramblr is paused; tapping it " +
                "turns Ramblr back on at once. The floating icon and dictation are gone in these apps."
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        statusLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(20))
        }
        root.addView(statusLabel)

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        val packageInput = EditText(this).apply {
            hint = "Exact package name, e.g. com.example.bank"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addButton = MaterialButton(this).apply {
            text = "Add"
            setOnClickListener {
                val pkg = packageInput.text?.toString()?.trim().orEmpty()
                if (pkg.isBlank()) {
                    toast("Enter a package name first")
                } else if (!pkg.contains('.') || pkg.any { it.isWhitespace() }) {
                    toast("Doesn't look like a package name (e.g. com.example.app)")
                } else if (pkg == packageName) {
                    toast("Ramblr can't pause for itself")
                } else {
                    PauseNearAppsStore.setListed(this@PauseNearAppsActivity, pkg, listed = true)
                    packageInput.setText("")
                    refresh()
                }
            }
        }
        addRow.addView(packageInput)
        addRow.addView(addButton)
        root.addView(addRow)

        emptyLabel = TextView(this).apply {
            text = "No apps listed yet"
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), dp(8), dp(24), dp(16))
        }
        root.addView(emptyLabel)

        listContainer = vertical(0)
        root.addView(listContainer)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        statusLabel.text = statusText(
            canWrite = InvocationSecureSettings.canWrite(this),
            usageAccess = PauseNearApps.hasUsageAccess(this),
            packageName = packageName,
        )
        listContainer.removeAllViews()
        val listed = PauseNearAppsStore.packages(this).sorted()
        emptyLabel.visibility = if (listed.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        for (pkg in listed) listContainer.addView(buildRow(pkg))
    }

    private fun buildRow(pkg: String): android.view.View {
        val removeBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "🗑"
            textSize = 16f
            setOnClickListener {
                PauseNearAppsStore.setListed(this@PauseNearAppsActivity, pkg, listed = false)
                refresh()
            }
        }
        return settingsRow(pkg, "Ramblr is off while this app is in front", removeBtn)
    }

    companion object {
        /** What the feature needs from outside the app, and what happens without each. */
        fun statusText(canWrite: Boolean, usageAccess: Boolean, packageName: String): String = when {
            !canWrite ->
                "Not active yet. Ramblr may not turn its own accessibility service back on, so it never " +
                    "pauses. Grant it once from a computer:\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
            !usageAccess ->
                "Active, but Ramblr cannot see when you leave an app, so it stays off until you tap its " +
                    "notification. Turn on usage access for Ramblr in the system settings, or from a computer:\n" +
                    "adb shell appops set $packageName GET_USAGE_STATS allow"
            else -> "Active: Ramblr pauses in the apps below and comes back when you leave them."
        }
    }
}
