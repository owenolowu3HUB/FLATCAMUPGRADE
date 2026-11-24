package com.flatcam.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Big round shutter / record button. */
class RecordButton(c: Context) : View(c) {
    var recording = false
        set(v) { field = v; invalidate() }
    var photo = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 4f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - ring.strokeWidth
        cv.drawCircle(cx, cy, r, ring)
        if (recording) {
            fill.color = Color.parseColor("#FF3B30")
            val s = r * 0.48f
            cv.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * 0.3f, s * 0.3f, fill)
        } else {
            fill.color = if (photo) Color.WHITE else Color.parseColor("#FF3B30")
            cv.drawCircle(cx, cy, r * 0.78f, fill)
        }
    }
}

/** Faces, tracked-subject brackets and the tap ripple. Everything is in view pixels. */
class OverlayView(c: Context) : View(c) {
    val faceRects = ArrayList<RectF>()
    var primary = -1
    var target: RectF? = null
    var targetState = 0 // 0 none, 1 face lock, 2 focus lock, 3 lost, 4 focusing
    var meter: RectF? = null
    /** Focus marks (A/B/C) placed on subjects; x,y in view pixels. */
    class MarkPt(val label: String, val x: Float, val y: Float, val active: Boolean)
    val marks = ArrayList<MarkPt>()
    /** Gamepad cursor in view pixels, null = hidden. */
    var cursor: PointF? = null
    /** Sparse focus-peaking highlights in view pixels. */
    var focusPeakingPoints = FloatArray(0)
    var showFocusPeaking = false
    var showLevelMeter = false
    var levelRoll = 0f
    var levelPitch = 0f

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * d; color = Color.WHITE }
    private val peak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(220, 90, 255, 120)
    }
    private var rx = 0f
    private var ry = 0f
    private var ripple = 0f
    private var anim: ValueAnimator? = null

    fun ripple(x: Float, y: Float) {
        rx = x; ry = y
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            addUpdateListener { ripple = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun brackets(cv: Canvas, r: RectF) {
        val l = min(r.width(), r.height()) * 0.28f
        cv.drawLine(r.left, r.top, r.left + l, r.top, p); cv.drawLine(r.left, r.top, r.left, r.top + l, p)
        cv.drawLine(r.right, r.top, r.right - l, r.top, p); cv.drawLine(r.right, r.top, r.right, r.top + l, p)
        cv.drawLine(r.left, r.bottom, r.left + l, r.bottom, p); cv.drawLine(r.left, r.bottom, r.left, r.bottom - l, p)
        cv.drawLine(r.right, r.bottom, r.right - l, r.bottom, p); cv.drawLine(r.right, r.bottom, r.right, r.bottom - l, p)
    }

    override fun onDraw(cv: Canvas) {
        if (showFocusPeaking) {
            var i = 0
            while (i + 1 < focusPeakingPoints.size) {
                cv.drawCircle(focusPeakingPoints[i], focusPeakingPoints[i + 1], 1.35f * d, peak)
                i += 2
            }
        }
        if (showLevelMeter) drawBalanceMeter(cv)
        for ((i, r) in faceRects.withIndex()) {
            val isPrimary = i == primary && targetState == 0
            p.color = if (isPrimary) Color.parseColor("#FFC107") else Color.argb(150, 255, 255, 255)
            brackets(cv, r)
        }
        target?.let { t ->
            p.color = when (targetState) {
                3 -> Color.parseColor("#FF3B30")
                4 -> Color.parseColor("#FFC107")
                else -> Color.parseColor("#4CD964")
            }
            brackets(cv, t)
            txt.color = p.color
            val label = when (targetState) { 1 -> "FACE LOCK"; 2 -> "FOCUS LOCK"; 4 -> "FOCUSING"; else -> "LOST" }
            cv.drawText(label, t.left, max(t.top - 5f * d, 12f * d), txt)
        }
        meter?.let { m ->
            p.color = Color.parseColor("#4DA3FF")
            cv.drawCircle(m.centerX(), m.centerY(), max(m.width(), m.height()) / 2f, p)
            cv.drawLine(m.centerX() - 6f * d, m.centerY(), m.centerX() + 6f * d, m.centerY(), p)
            cv.drawLine(m.centerX(), m.centerY() - 6f * d, m.centerX(), m.centerY() + 6f * d, p)
            txt.color = p.color
            cv.drawText("METER", m.left, max(m.top - 5f * d, 12f * d), txt)
        }
        for (m in marks) {
            p.color = if (m.active) Color.parseColor("#4CD964") else Color.parseColor("#FFB300")
            cv.drawCircle(m.x, m.y, 13f * d, p)
            txt.color = p.color
            val w = txt.measureText(m.label)
            cv.drawText(m.label, m.x - w / 2f, m.y + 4f * d, txt)
        }
        cursor?.let { c ->
            p.color = Color.WHITE
            cv.drawCircle(c.x, c.y, 16f * d, p)
            cv.drawLine(c.x - 24f * d, c.y, c.x - 8f * d, c.y, p); cv.drawLine(c.x + 8f * d, c.y, c.x + 24f * d, c.y, p)
            cv.drawLine(c.x, c.y - 24f * d, c.x, c.y - 8f * d, p); cv.drawLine(c.x, c.y + 8f * d, c.x, c.y + 24f * d, p)
        }
        if (ripple > 0f && ripple < 1f) {
            p.color = Color.argb(((1f - ripple) * 220).toInt(), 255, 255, 255)
            cv.drawCircle(rx, ry, (22f + 36f * ripple) * d, p)
        }
    }

    private fun drawBalanceMeter(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = 25f * d
        val scale = 1.35f * d
        val bubbleX = cx + (levelRoll.coerceIn(-12f, 12f) * scale)
        val bubbleY = cy + (levelPitch.coerceIn(-12f, 12f) * scale)
        val isLevel = kotlin.math.abs(levelRoll) < 1.5f && kotlin.math.abs(levelPitch) < 1.5f
        p.color = if (isLevel) Color.argb(190, 76, 217, 100) else Color.argb(175, 255, 255, 255)
        p.strokeWidth = 1.3f * d
        cv.drawCircle(cx, cy, radius, p)
        cv.drawLine(cx - radius, cy, cx + radius, cy, p)
        cv.drawLine(cx, cy - radius, cx, cy + radius, p)
        cv.drawCircle(cx, cy, 2.2f * d, p)
        p.style = Paint.Style.FILL
        cv.drawCircle(bubbleX, bubbleY, 4.5f * d, p)
        p.style = Paint.Style.STROKE
        txt.color = p.color
        txt.textSize = 9f * d
        txt.textAlign = Paint.Align.CENTER
        cv.drawText(if (isLevel) "LEVEL" else "LEVEL  ${levelRoll.toInt()}°  ${levelPitch.toInt()}°", cx, cy - radius - 8f * d, txt)
        txt.textAlign = Paint.Align.LEFT
    }
}

/** Black masks around the selected composition frame. Camera controls are layered above this view. */
class AspectMaskView(c: Context) : View(c) {
    var aspectRatio: Float? = null
        set(v) { field = v; invalidate() }
    var barOpacity = 0.88f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val mask = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(115, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density
    }

    fun contentRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        val selected = aspectRatio ?: return RectF(0f, 0f, w, h)
        if (w <= 0f || h <= 0f) return RectF(0f, 0f, w, h)
        val ratio = if (w >= h) selected else 1f / selected
        if (kotlin.math.abs(w / h - ratio) < 0.035f) return RectF(0f, 0f, w, h)
        val fw: Float
        val fh: Float
        if (w / h > ratio) { fh = h; fw = h * ratio }
        else { fw = w; fh = w / ratio }
        return RectF((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f)
    }

    override fun onDraw(cv: Canvas) {
        val rect = contentRect()
        val w = width.toFloat()
        val h = height.toFloat()
        mask.color = Color.argb((barOpacity * 255).toInt(), 0, 0, 0)
        if (rect.top > 0f) cv.drawRect(0f, 0f, w, rect.top, mask)
        if (rect.bottom < h) cv.drawRect(0f, rect.bottom, w, h, mask)
        if (rect.left > 0f) cv.drawRect(0f, rect.top, rect.left, rect.bottom, mask)
        if (rect.right < w) cv.drawRect(rect.right, rect.top, w, rect.bottom, mask)
        if (aspectRatio != null && (rect.left > 0f || rect.top > 0f || rect.right < w || rect.bottom < h)) {
            cv.drawRect(rect, frame)
        }
    }
}

/** Live luma histogram with clipping / crushed-shadow warnings. */
class HistogramView(c: Context) : View(c) {
    private var bins = IntArray(48)
    private var clip = 0f
    private var crush = 0f
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    fun update(b: IntArray, clipFrac: Float, crushFrac: Float) {
        bins = b; clip = clipFrac; crush = crushFrac; invalidate()
    }

    override fun onDraw(cv: Canvas) {
        cv.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6f * d, 6f * d, bg)
        val mx = max(1, bins.maxOrNull() ?: 1)
        val bw = width.toFloat() / bins.size
        for (i in bins.indices) {
            val h = height * 0.9f * (bins[i].toFloat() / mx)
            bar.color = when {
                i >= bins.size - 2 && clip > 0.01f -> Color.parseColor("#FF3B30")
                i <= 1 && crush > 0.05f -> Color.parseColor("#4DA3FF")
                else -> Color.argb(190, 255, 255, 255)
            }
            cv.drawRect(i * bw, height - h, (i + 1) * bw - 1f, height.toFloat(), bar)
        }
    }
}

/** Rule-of-thirds grid. */
class ThirdsGrid(c: Context) : View(c) {
    private val p = Paint().apply { color = Color.argb(90, 255, 255, 255); strokeWidth = 1f }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1..2) {
            cv.drawLine(w * i / 3f, 0f, w * i / 3f, h, p)
            cv.drawLine(0f, h * i / 3f, w, h * i / 3f, p)
        }
    }
}


/** Audio level meter (peak) shown while recording. */
class VuView(c: Context) : View(c) {
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        cv.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 3f * d, 3f * d, bg)
        bar.color = when {
            level > 0.93f -> Color.parseColor("#FF3B30")
            level > 0.75f -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#4CD964")
        }
        cv.drawRoundRect(0f, 0f, width * level, height.toFloat(), 3f * d, 3f * d, bar)
    }
}
