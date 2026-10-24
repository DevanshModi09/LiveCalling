package com.vcd.calling

import com.vcd.calling.detection.StreamingDetector
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Standalone calling APK. No detector or inference runtime is installed. */
open class LiveCallingActivity : Activity() {
    private var call: WebRtcCallView? = null
    private var detector: StreamingDetector? = null
    private lateinit var address: EditText
    private lateinit var message: TextView
    private var pendingUrl: String? = null
    private val bg = Color.rgb(242, 241, 236)
    private val ink = Color.rgb(31, 42, 36)
    private val soft = Color.rgb(94, 105, 98)
    private val accent = Color.rgb(244, 89, 43)
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun shape(color: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(14).toFloat()
        if (border) setStroke(dp(1), Color.rgb(218, 217, 210))
    }
    private fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(8))
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        showSetup()
        intent.getStringExtra("server_url")?.let { address.setText(it) }
        intent.getStringExtra("room_url")?.let { address.setText(it) }
        readInvitation(intent)
    }
    private fun readInvitation(source: Intent?) {
        if (source?.action != Intent.ACTION_SEND) return
        val value = source.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = Regex("https://[^\\s]+").find(value)?.value ?: return
        address.setText(url); message.text = "Invitation loaded. Tap Open calling room."
    }
    private fun showSetup() {
        call?.close(); call = null
        detector?.close(); detector = null
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        scroll.addView(column)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        column.addView(text("✺  Voice Check", 25f, bold = true))
        column.addView(View(this).apply { setBackgroundColor(Color.rgb(218, 217, 210)) }, LinearLayout.LayoutParams(-1, dp(1)))
        column.addView(text("IN-APP AUDIO CALLS", 12f, soft).apply { letterSpacing = .18f; gravity = Gravity.CENTER; setPadding(0, dp(32), 0, dp(12)) })
        column.addView(text("A closer connection.\nFor every voice.", 32f, bold = true).apply { gravity = Gravity.CENTER })
        column.addView(text("Create a room, share a link, and talk together.", 16f, soft).apply { gravity = Gravity.CENTER })
        column.addView(object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f; val cy = height / 2f
                val radius = minOf(width, height) * .46f
                paint.style = Paint.Style.FILL; paint.color = Color.rgb(248, 247, 243)
                canvas.drawCircle(cx, cy, radius, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat()
                paint.color = Color.rgb(218, 217, 210); canvas.drawCircle(cx, cy, radius, paint)
                paint.strokeWidth = dp(2).toFloat(); paint.strokeCap = Paint.Cap.ROUND
                repeat(60) { i ->
                    val angle = Math.toRadians(i * 6.0 - 90)
                    paint.color = if (i in 3..5) accent else Color.rgb(200, 199, 191)
                    canvas.drawLine(cx + kotlin.math.cos(angle).toFloat() * radius * .84f,
                        cy + kotlin.math.sin(angle).toFloat() * radius * .84f,
                        cx + kotlin.math.cos(angle).toFloat() * radius * .92f,
                        cy + kotlin.math.sin(angle).toFloat() * radius * .92f, paint)
                }
                paint.color = ink; paint.strokeWidth = dp(4).toFloat()
                val heights = listOf(7, 12, 22, 32, 44, 32, 22, 12, 7)
                heights.forEachIndexed { i, h ->
                    val x = cx + dp((i - 4) * 8)
                    canvas.drawLine(x, cy - dp(14) - dp(h) / 2f, x, cy - dp(14) + dp(h) / 2f, paint)
                }
                paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
                paint.textSize = dp(15).toFloat(); paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("Ready to connect", cx, cy + dp(42), paint)
            }
        }.apply { contentDescription = "Ready to connect" }, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        column.addView(text("YOUR CALLING SERVER", 12f, soft).apply { letterSpacing = .15f })
        address = EditText(this).apply {
            hint = "https://call.yourdomain.com/"; textSize = 15f; setTextColor(ink)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true; background = shape(Color.WHITE, true); setPadding(dp(16), dp(16), dp(16), dp(16))
            setText(getPreferences(MODE_PRIVATE).getString("server", ""))
        }
        column.addView(address, LinearLayout.LayoutParams(-1, -2))
        column.addView(text("Use the HTTPS address of your LiveCalling server, or paste a room invitation link.", 13f, soft))
        val button = Button(this).apply {
            text = "Open calling room  →"; isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE); background = shape(accent)
            setOnClickListener { openRoom() }
        }
        column.addView(button, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(16) })
        message = text("", 13f, soft); column.addView(message)
        column.addView(text("Model checks are not connected", 14f, soft).apply { gravity = Gravity.CENTER })
        column.addView(text("Internet required · Both participants join the same room.\nKeep this screen open during your call.", 12f, soft).apply { gravity = Gravity.CENTER })
        setContentView(scroll)
    }
    private fun openRoom() {
        val value = address.text.toString().trim()
        val uri = Uri.parse(value)
        if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null) {
            message.text = "Enter a valid HTTPS server or room link."; return
        }
        pendingUrl = value
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        launchCall(value)
    }
    private fun launchCall(url: String) {
        getPreferences(MODE_PRIVATE).edit().putString("server", Uri.parse(url).buildUpon().clearQuery().fragment(null).build().toString()).apply()
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); fitsSystemWindows = true
        }
        frame.addView(Button(this).apply {
            text = "‹  Server settings / leave call"; isAllCaps = false; setTextColor(ink); setBackgroundColor(bg)
            setOnClickListener { showSetup() }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        val stateLine = text("Opening secure calling room…", 12f, soft).apply {
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        frame.addView(stateLine)
        val evidenceLine = text("Voice checks: no model connected", 12f, soft).apply {
            setPadding(dp(16), dp(2), dp(16), dp(2))
        }
        frame.addView(evidenceLine)
        detector = StreamingDetector(onUpdate = { update ->
            runOnUiThread {
                evidenceLine.text = String.format(java.util.Locale.US,
                    "%.1f s received · No model connected", update.audioTimeS)
            }
        })
        call = WebRtcCallView(this, url,
            onRemotePcm16k = { samples -> detector?.push(samples) },
            onState = { state -> stateLine.text = state })
        frame.addView(call!!.view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(frame)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) pendingUrl?.let(::launchCall)
        else message.text = "Microphone permission is needed to call. Tap again to retry."
    }
    @Deprecated("Legacy Activity back handling")
    override fun onBackPressed() { if (call != null) showSetup() else super.onBackPressed() }
    override fun onDestroy() { call?.close(); call = null; detector?.close(); detector = null; super.onDestroy() }
}
