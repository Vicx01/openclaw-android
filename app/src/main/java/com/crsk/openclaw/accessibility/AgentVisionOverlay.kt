package com.crsk.openclaw.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.crsk.openclaw.data.network.ws.ApprovalRequest
import com.crsk.openclaw.data.network.ws.RiskLevel

/**
 * Full-screen transparent overlay the USER watches: draws mark boxes + numbers and a tap
 * ripple, plus a top-anchored approval/stop banner when the agent requests confirmation
 * while another app is in the foreground. Added by the AccessibilityService via
 * TYPE_ACCESSIBILITY_OVERLAY — no SYSTEM_ALERT_WINDOW permission required.
 */
class AgentVisionOverlay(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: OverlayView? = null
    private var approvalBannerView: View? = null
    @Volatile var available: Boolean = true; private set

    private class Mark(
        val left: Float, val top: Float, val right: Float, val bottom: Float, val label: String,
    )

    private inner class OverlayView : View(service) {
        @Volatile var marks: List<Mark> = emptyList()
        @Volatile var rippleX = -1f
        @Volatile var rippleY = -1f
        @Volatile var rippleR = 0f
        private val box = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 4f; color = 0x9900E5FF.toInt()
        }
        private val badgeBg = Paint().apply {
            isAntiAlias = true; style = Paint.Style.FILL; color = 0xCC0091EA.toInt()
        }
        private val badgeTx = Paint().apply {
            isAntiAlias = true; color = Color.WHITE; textSize = 26f; isFakeBoldText = true
        }
        private val ripple = Paint().apply {
            isAntiAlias = true; style = Paint.Style.FILL; color = 0x553F51B5
        }

        override fun onDraw(canvas: Canvas) {
            for (m in marks) {
                canvas.drawRect(m.left, m.top, m.right, m.bottom, box)
                val tw = badgeTx.measureText(m.label)
                canvas.drawRect(m.left, m.top, m.left + tw + 12f, m.top + 32f, badgeBg)
                canvas.drawText(m.label, m.left + 6f, m.top + 26f, badgeTx)
            }
            if (rippleR > 0f) canvas.drawCircle(rippleX, rippleY, rippleR, ripple)
        }
    }

    private fun ensureAttached() {
        if (view != null) return
        val v = OverlayView()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(v, lp) }
            .onSuccess { view = v }
            .onFailure { available = false; Log.w(TAG, "vision overlay addView failed", it) }
    }

    fun showMarks(marks: List<ElementNode>) = main.post {
        if (!available) return@post
        ensureAttached()
        val v = view ?: return@post
        v.marks = marks.map {
            Mark(
                it.bounds.left.toFloat(), it.bounds.top.toFloat(),
                it.bounds.right.toFloat(), it.bounds.bottom.toFloat(),
                it.markId.toString(),
            )
        }
        v.invalidate()
    }

    fun flashTap(x: Int, y: Int) = main.post {
        val v = view ?: return@post
        v.rippleX = x.toFloat(); v.rippleY = y.toFloat(); v.rippleR = 36f
        v.invalidate()
        main.postDelayed({ v.rippleR = 0f; v.invalidate() }, 450L)
    }

    /**
     * Shows an interactive approval card at the top of the screen via TYPE_ACCESSIBILITY_OVERLAY
     * so the user can approve, deny, or stop sensitive actions even when another app is in
     * the foreground and SYSTEM_ALERT_WINDOW is not enabled.
     */
    fun showApprovalBanner(
        request: ApprovalRequest,
        onAllow: () -> Unit,
        onDeny: () -> Unit,
        onStop: () -> Unit,
    ) = main.post {
        removeApprovalBannerInternal()
        val dp = service.resources.displayMetrics.density
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (16 * dp).toInt()
            val padV = (12 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(0xF2141416.toInt())
                cornerRadius = 18f * dp
                setStroke((1.5f * dp).toInt(), if (request.riskLevel == RiskLevel.High) 0xFFFF453A.toInt() else 0xFFFF9F0A.toInt())
            }
        }

        val headerText = TextView(service).apply {
            val cat = request.riskCategory?.let { " · $it" }.orEmpty()
            text = "⚠ 4AIs Approval Needed$cat"
            setTextColor(if (request.riskLevel == RiskLevel.High) 0xFFFF453A.toInt() else 0xFFFF9F0A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            paint.isFakeBoldText = true
        }
        container.addView(headerText)

        val summaryView = TextView(service).apply {
            text = request.summary
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, (4 * dp).toInt(), 0, (10 * dp).toInt())
        }
        container.addView(summaryView)

        val buttonRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        fun makeBtn(label: String, bgColor: Int, onClick: () -> Unit): Button =
            Button(service).apply {
                text = label
                isAllCaps = false
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 10f * dp
                }
                setOnClickListener { onClick() }
            }

        val lpBtn = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            (36 * dp).toInt(),
        ).apply { marginStart = (8 * dp).toInt() }

        buttonRow.addView(makeBtn("■ Stop", 0xFFD32F2F.toInt(), onStop), lpBtn)
        buttonRow.addView(makeBtn("Deny", 0xFF3A3A3C.toInt(), onDeny), lpBtn)
        buttonRow.addView(makeBtn("Allow", 0xFF0A84FF.toInt(), onAllow), lpBtn)
        container.addView(buttonRow)

        val lp = WindowManager.LayoutParams(
            (service.resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * dp).toInt()
        }

        runCatching { wm.addView(container, lp) }
            .onSuccess { approvalBannerView = container }
            .onFailure { Log.w(TAG, "approval banner addView failed", it) }
    }

    fun hideApprovalBanner() = main.post {
        removeApprovalBannerInternal()
    }

    private fun removeApprovalBannerInternal() {
        approvalBannerView?.let { runCatching { wm.removeView(it) } }
        approvalBannerView = null
    }

    /** Toggle visibility without removing the window (used to keep it out of the model screenshot). */
    fun setHiddenForCapture(hidden: Boolean) = main.post {
        view?.visibility = if (hidden) View.GONE else View.VISIBLE
        approvalBannerView?.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    fun hide() = main.post {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        removeApprovalBannerInternal()
    }

    companion object { private const val TAG = "AgentVisionOverlay" }
}
