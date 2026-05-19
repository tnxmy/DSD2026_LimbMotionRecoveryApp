package com.example.limbmotionrecoveryapp.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

class LegView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class LegSide { LEFT, RIGHT }

    enum class AngleMode { DEGREES, RADIANS }

    private var side: LegSide = LegSide.LEFT

    // Target angle from external sensor; queued for animation consumption
    private var targetAngle: Float = 90f

    // Currently rendered angle during animation interpolation
    private var renderedAngle: Float = 90f

    private var angleMode: AngleMode = AngleMode.DEGREES

    // Pending angle queue for adaptive animation
    private val pendingAngles = ArrayDeque<Float>()

    // Active animator reference for cancellation
    private var currentAnimator: ValueAnimator? = null

    /** Set input unit to degrees. No conversion applied in setAngle(). */
    fun setAngleModeDegrees() {
        angleMode = AngleMode.DEGREES
    }

    /** Set input unit to radians. setAngle() will auto-convert to degrees. */
    fun setAngleModeRadians() {
        angleMode = AngleMode.RADIANS
    }

    /** Accepts angle in current mode (degrees or radians), stores as degrees internally. */
    fun setAngle(value: Float) {
        val degrees = when (angleMode) {
            AngleMode.DEGREES -> value
            AngleMode.RADIANS -> Math.toDegrees(value.toDouble()).toFloat()
        }
        val clamped = degrees.coerceIn(0f, 180f)
        pendingAngles.addLast(clamped)

        if (currentAnimator?.isRunning != true) {
            consumeNext()
        }
    }

    fun setSide(side: LegSide) {
        this.side = side
        invalidate()
    }

    private var baseLimbWidth = 24f
    private var thighLength = 160f
    private var shinLength = 160f

    private val hipWidth get() = baseLimbWidth * 1.5f
    private val kneeWidth get() = baseLimbWidth * 1.2f
    private val calfWidth get() = baseLimbWidth * 1.3f
    private val ankleWidth get() = baseLimbWidth * 0.8f
    private val capWidth get() = baseLimbWidth * 1.45f
    private val capHeight get() = baseLimbWidth * 1.3f

    private val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textAlign = Paint.Align.CENTER
    }

    private val leftLimbColors = intArrayOf(
        Color.parseColor("#87CEEB"),
        Color.parseColor("#4A90D9"),
        Color.parseColor("#1A3A5C")
    )

    private val rightLimbColors = intArrayOf(
        Color.parseColor("#FFB6C1"),
        Color.parseColor("#D94A4A"),
        Color.parseColor("#5C1A1A")
    )

    private val leftCapColors = intArrayOf(
        Color.parseColor("#A8D8FF"),
        Color.parseColor("#2C5F8A")
    )

    private val rightCapColors = intArrayOf(
        Color.parseColor("#FFD8D8"),
        Color.parseColor("#8A2C2C")
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val designLength = 360f
        val designThickness = 55f
        val lengthScale = (w.toFloat() * 0.85f) / designLength
        val thicknessScale = (h.toFloat() * 0.6f) / designThickness
        val s = minOf(lengthScale, thicknessScale, 2.5f)

        thighLength = 160f * s
        shinLength = 160f * s
        baseLimbWidth = 24f * s
        textPaint.textSize = 28f * s

        limbPaint.setShadowLayer(8f * s, 2f * s, 4f * s, Color.parseColor("#40000000"))
        capPaint.setShadowLayer(6f * s, 0f, 2f * s, Color.parseColor("#40000000"))
    }

    // -------------------------------------------------------------------------
    // Adaptive animation core
    // -------------------------------------------------------------------------
    private fun consumeNext() {
        if (pendingAngles.isEmpty()) return

        // Backlog protection: if queue grows too large, drop intermediate frames
        if (pendingAngles.size >= 5) {
            val latest = pendingAngles.removeLast()
            pendingAngles.clear()
            pendingAngles.addLast(latest)
        }

        val target = pendingAngles.removeFirst()
        val backlog = pendingAngles.size

        // Dynamic duration: base 110ms, minus 10ms per queued item, floor at 60ms
        val durationMs = (110L - backlog * 10L).coerceAtLeast(60L)

        // Interpolator: smooth when catching up, linear when under pressure
        val interpolator = if (backlog >= 3) LinearInterpolator() else AccelerateDecelerateInterpolator()

        currentAnimator?.cancel()
        currentAnimator = ValueAnimator.ofFloat(renderedAngle, target).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener {
                renderedAngle = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    consumeNext()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f + baseLimbWidth * 1.5f

        canvas.save()
        canvas.rotate(90f, cx, cy)

        if (side == LegSide.RIGHT) {
            canvas.save()
            canvas.rotate(180f, cx, cy)
            canvas.scale(-1f, 1f, cx, cy)
        }

        val limbColors = if (side == LegSide.LEFT) leftLimbColors else rightLimbColors
        val capColors = if (side == LegSide.LEFT) leftCapColors else rightCapColors

        val hipX = cx
        val hipY = cy - thighLength
        updateLimbShader(hipX, hipY, cx, cy, hipWidth, kneeWidth, limbColors)
        canvas.drawPath(
            buildThighPath(hipX, hipY, cx, cy, hipWidth, kneeWidth),
            limbPaint
        )

        // Use renderedAngle (animated) instead of raw targetAngle
        val shinRad = Math.toRadians((90f - renderedAngle).toDouble())
        val shinDirX = kotlin.math.cos(shinRad).toFloat()
        val shinDirY = kotlin.math.sin(shinRad).toFloat()
        val ankleX = cx + shinDirX * shinLength
        val ankleY = cy + shinDirY * shinLength
        updateLimbShader(cx, cy, ankleX, ankleY, kneeWidth, ankleWidth, limbColors)
        canvas.drawPath(
            buildShinPath(cx, cy, ankleX, ankleY, kneeWidth, ankleWidth, calfWidth),
            limbPaint
        )

        updateCapShader(cx, cy, capColors)
        val capRect = RectF(
            cx - capWidth / 2f,
            cy - capHeight / 2f,
            cx + capWidth / 2f,
            cy + capHeight / 2f
        )
        canvas.drawOval(capRect, capPaint)

        if (side == LegSide.RIGHT) {
            canvas.restore()
        }
        canvas.restore()

        canvas.drawText("${renderedAngle.toInt()}°", cx, cy - baseLimbWidth * 2.5f, textPaint)
    }

    private fun buildThighPath(
        hipX: Float, hipY: Float,
        kneeX: Float, kneeY: Float,
        hipWidth: Float,
        kneeWidth: Float
    ): Path {
        val path = Path()
        val dx = kneeX - hipX
        val dy = kneeY - hipY
        val len = kotlin.math.hypot(dx, dy)
        if (len < 0.001f) return path

        val dirX = dx / len
        val dirY = dy / len
        val perpX = -dirY
        val perpY = dirX

        val hipHalf = hipWidth / 2f
        val kneeHalf = kneeWidth / 2f
        val bulge = len * 0.08f

        val hipOuterX = hipX + perpX * hipHalf
        val hipOuterY = hipY + perpY * hipHalf
        path.moveTo(hipOuterX, hipOuterY)

        val cp1x = hipX + dirX * len * 0.35f + perpX * (hipHalf + bulge)
        val cp1y = hipY + dirY * len * 0.35f + perpY * (hipHalf + bulge)
        val cp2x = hipX + dirX * len * 0.75f + perpX * (kneeHalf + bulge * 0.6f)
        val cp2y = hipY + dirY * len * 0.75f + perpY * (kneeHalf + bulge * 0.6f)

        val kneeOuterX = kneeX + perpX * kneeHalf
        val kneeOuterY = kneeY + perpY * kneeHalf
        path.cubicTo(cp1x, cp1y, cp2x, cp2y, kneeOuterX, kneeOuterY)

        val kneeInnerX = kneeX - perpX * kneeHalf
        val kneeInnerY = kneeY - perpY * kneeHalf
        path.lineTo(kneeInnerX, kneeInnerY)

        val cp3x = kneeX - dirX * len * 0.3f - perpX * (kneeHalf + bulge * 0.3f)
        val cp3y = kneeY - dirY * len * 0.3f - perpY * (kneeHalf + bulge * 0.3f)
        val cp4x = hipX + dirX * len * 0.7f - perpX * (hipHalf * 0.85f)
        val cp4y = hipY + dirY * len * 0.7f - perpY * (hipHalf * 0.85f)
        val hipInnerX = hipX - perpX * hipHalf
        val hipInnerY = hipY - perpY * hipHalf
        path.cubicTo(cp3x, cp3y, cp4x, cp4y, hipInnerX, hipInnerY)

        val hipRect = RectF(hipX - hipHalf, hipY - hipHalf, hipX + hipHalf, hipY + hipHalf)
        path.arcTo(hipRect, Math.toDegrees(kotlin.math.atan2(dirY.toDouble(), dirX.toDouble())).toFloat() + 90f, 180f, false)

        path.close()
        return path
    }

    private fun buildShinPath(
        kneeX: Float, kneeY: Float,
        ankleX: Float, ankleY: Float,
        kneeWidth: Float,
        ankleWidth: Float,
        calfWidth: Float
    ): Path {
        val path = Path()
        val dx = ankleX - kneeX
        val dy = ankleY - kneeY
        val len = kotlin.math.hypot(dx, dy)
        if (len < 0.001f) return path

        val dirX = dx / len
        val dirY = dy / len
        val perpX = -dirY
        val perpY = dirX

        val kneeHalf = kneeWidth / 2f
        val ankleHalf = ankleWidth / 2f
        val calfHalf = calfWidth / 2f
        val midPos = 0.4f
        val midX = kneeX + dirX * len * midPos
        val midY = kneeY + dirY * len * midPos
        val bulge = len * 0.06f

        val kneeOuterX = kneeX + perpX * kneeHalf
        val kneeOuterY = kneeY + perpY * kneeHalf
        path.moveTo(kneeOuterX, kneeOuterY)

        val cp1x = kneeX + dirX * len * 0.2f + perpX * (kneeHalf + bulge * 1.2f)
        val cp1y = kneeY + dirY * len * 0.2f + perpY * (kneeHalf + bulge * 1.2f)
        val cp2x = midX + perpX * (calfHalf + bulge * 0.8f)
        val cp2y = midY + perpY * (calfHalf + bulge * 0.8f)
        val midOuterX = midX + perpX * calfHalf
        val midOuterY = midY + perpY * calfHalf
        path.cubicTo(cp1x, cp1y, cp2x, cp2y, midOuterX, midOuterY)

        val cp3x = midX + dirX * len * 0.25f + perpX * (calfHalf + bulge * 0.4f)
        val cp3y = midY + dirY * len * 0.25f + perpY * (calfHalf + bulge * 0.4f)
        val cp4x = ankleX - dirX * len * 0.2f + perpX * (ankleHalf + bulge * 0.5f)
        val cp4y = ankleY - dirY * len * 0.2f + perpY * (ankleHalf + bulge * 0.5f)

        val ankleOuterX = ankleX + perpX * ankleHalf
        val ankleOuterY = ankleY + perpY * ankleHalf
        path.cubicTo(cp3x, cp3y, cp4x, cp4y, ankleOuterX, ankleOuterY)

        val ankleInnerX = ankleX - perpX * ankleHalf
        val ankleInnerY = ankleY - perpY * ankleHalf
        path.lineTo(ankleInnerX, ankleInnerY)

        val cp5x = ankleX - dirX * len * 0.2f - perpX * (ankleHalf + bulge * 0.3f)
        val cp5y = ankleY - dirY * len * 0.2f - perpY * (ankleHalf + bulge * 0.3f)
        val cp6x = midX + dirX * len * 0.25f - perpX * (calfHalf * 0.75f)
        val cp6y = midY + dirY * len * 0.25f - perpY * (calfHalf * 0.75f)
        val midInnerX = midX - perpX * calfHalf
        val midInnerY = midY - perpY * calfHalf
        path.cubicTo(cp5x, cp5y, cp6x, cp6y, midInnerX, midInnerY)

        val cp7x = midX - dirX * len * 0.2f - perpX * (calfHalf * 0.65f)
        val cp7y = midY - dirY * len * 0.2f - perpY * (calfHalf * 0.65f)
        val cp8x = kneeX + dirX * len * 0.2f - perpX * (kneeHalf * 0.85f)
        val cp8y = kneeY + dirY * len * 0.2f - perpY * (kneeHalf * 0.85f)
        val kneeInnerX = kneeX - perpX * kneeHalf
        val kneeInnerY = kneeY - perpY * kneeHalf
        path.cubicTo(cp7x, cp7y, cp8x, cp8y, kneeInnerX, kneeInnerY)

        val kneeRect = RectF(kneeX - kneeHalf, kneeY - kneeHalf, kneeX + kneeHalf, kneeY + kneeHalf)
        path.arcTo(kneeRect, Math.toDegrees(kotlin.math.atan2(dirY.toDouble(), dirX.toDouble())).toFloat() + 90f, 180f, false)

        path.close()
        return path
    }

    private fun updateLimbShader(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        startWidth: Float,
        endWidth: Float,
        colors: IntArray
    ) {
        val dx = endX - startX
        val dy = endY - startY
        val len = kotlin.math.hypot(dx, dy)
        if (len < 0.001f) return

        val dirX = dx / len
        val dirY = dy / len
        val perpX = -dirY
        val perpY = dirX
        val half = maxOf(startWidth, endWidth) / 2f

        val midX = (startX + endX) / 2f
        val midY = (startY + endY) / 2f
        val x0 = midX + perpX * half
        val y0 = midY + perpY * half
        val x1 = midX - perpX * half
        val y1 = midY - perpY * half

        limbPaint.shader = LinearGradient(
            x0, y0, x1, y1,
            colors,
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun updateCapShader(cx: Float, cy: Float, colors: IntArray) {
        val highlightX = cx - capWidth * 0.15f
        val highlightY = cy - capHeight * 0.15f
        val radius = maxOf(capWidth, capHeight) * 0.6f
        capPaint.shader = RadialGradient(
            highlightX, highlightY,
            radius,
            colors[0], colors[1],
            Shader.TileMode.CLAMP
        )
    }
}