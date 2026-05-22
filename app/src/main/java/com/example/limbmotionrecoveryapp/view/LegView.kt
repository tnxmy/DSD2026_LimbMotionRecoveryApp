package com.example.limbmotionrecoveryapp.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * A custom View that renders anatomical leg visualization for rehabilitation exercise tracking.
 * It supports two legs drawn simultaneously with independent animation states, three display modes
 * (horizontal/supine, squat, stepping), configurable colors per leg, and visibility toggles.
 *
 * Architecture:
 * - Each leg maintains its own LegState (target angle, rendered angle, pending queue, animator).
 * - Geometry is computed per mode in calculatePoints(), then drawn via drawLeg().
 * - The right leg is rendered by applying a Canvas scale transform for axis-flip symmetry.
 */
class LegView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class LegSide { LEFT, RIGHT }

    enum class AngleMode { DEGREES, RADIANS }

    /** Supported display modes that change the geometric constraints of leg rendering. */
    enum class DisplayMode { HORIZONTAL, SQUAT, STEPPING }

    private var side: LegSide = LegSide.LEFT

    private var angleMode: AngleMode = AngleMode.RADIANS

    // -------------------------------------------------------------------------
    // Per-leg independent state: pending angle queue, active animator, and current rendered angle.
    // This allows the left and right legs to animate independently with their own timing.
    // -------------------------------------------------------------------------
    private class LegState {
        var targetAngle: Float = 180f
        var renderedAngle: Float = 180f
        val pendingAngles = ArrayDeque<Float>()
        var currentAnimator: ValueAnimator? = null
    }

    private val leftState = LegState()
    private val rightState = LegState()

    // -------------------------------------------------------------------------
    // Visibility and display mode state
    // -------------------------------------------------------------------------
    private var showLeft = true
    private var showRight = true
    private var displayMode = DisplayMode.HORIZONTAL

    /** Switch the input unit to degrees. When enabled, setLeftAngle/setRightAngle expect degrees. */
    fun setAngleModeDegrees() {
        angleMode = AngleMode.DEGREES
    }

    /** Switch the input unit to radians. Values will be auto-converted to degrees internally. */
    fun setAngleModeRadians() {
        angleMode = AngleMode.RADIANS
    }

    /**
     * Enqueue a new target angle for the left leg.
     * The value is clamped to [0, 180] and queued for adaptive animation.
     */
    fun setLeftAngle(value: Float) = setAngleInternal(leftState, value)

    /**
     * Enqueue a new target angle for the right leg.
     * The value is clamped to [0, 180] and queued for adaptive animation.
     */
    fun setRightAngle(value: Float) = setAngleInternal(rightState, value)

    /** Internal helper to convert input to degrees, clamp, queue, and start animation if idle. */
    private fun setAngleInternal(state: LegState, value: Float) {
        val degrees = when (angleMode) {
            AngleMode.DEGREES -> value
            AngleMode.RADIANS -> Math.toDegrees(value.toDouble()).toFloat()
        }
        val clamped = degrees.coerceIn(0f, 180f)
        state.pendingAngles.addLast(clamped)

        if (state.currentAnimator?.isRunning != true) {
            consumeNext(state)
        }
    }

    /** Legacy setter retained for compatibility; updates the internal side flag and redraws. */
    fun setSide(side: LegSide) {
        this.side = side
        invalidate()
    }

    // -------------------------------------------------------------------------
    // Visibility, display mode, and color setters
    // -------------------------------------------------------------------------

    /** Toggle visibility of the left leg. When hidden, it is not drawn. */
    fun setShowLeft(show: Boolean) {
        showLeft = show
        invalidate()
    }

    /** Toggle visibility of the right leg. When hidden, it is not drawn. */
    fun setShowRight(show: Boolean) {
        showRight = show
        invalidate()
    }

    /** Change the display mode (HORIZONTAL, SQUAT, STEPPING) and trigger a redraw. */
    fun setDisplayMode(mode: DisplayMode) {
        displayMode = mode
        invalidate()
    }

    /**
     * Replace the left leg limb gradient colors.
     * @param colors IntArray of at least 3 colors for the LinearGradient.
     */
    fun setLeftLimbColors(colors: IntArray) {
        require(colors.size >= 3) { "Need at least 3 colors for limb gradient" }
        leftLimbColors = colors.copyOf(3)
        invalidate()
    }

    /**
     * Replace the right leg limb gradient colors.
     * @param colors IntArray of at least 3 colors for the LinearGradient.
     */
    fun setRightLimbColors(colors: IntArray) {
        require(colors.size >= 3) { "Need at least 3 colors for limb gradient" }
        rightLimbColors = colors.copyOf(3)
        invalidate()
    }

    /**
     * Replace the left knee cap gradient colors.
     * @param colors IntArray of at least 2 colors for the RadialGradient.
     */
    fun setLeftCapColors(colors: IntArray) {
        require(colors.size >= 2) { "Need at least 2 colors for cap gradient" }
        leftCapColors = colors.copyOf(2)
        invalidate()
    }

    /**
     * Replace the right knee cap gradient colors.
     * @param colors IntArray of at least 2 colors for the RadialGradient.
     */
    fun setRightCapColors(colors: IntArray) {
        require(colors.size >= 2) { "Need at least 2 colors for cap gradient" }
        rightCapColors = colors.copyOf(2)
        invalidate()
    }

    // -------------------------------------------------------------------------
    // Geometry dimensions and paint objects
    // -------------------------------------------------------------------------
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

    private var leftLimbColors = intArrayOf(
        Color.parseColor("#87CEEB"),
        Color.parseColor("#4A90D9"),
        Color.parseColor("#1A3A5C")
    )

    private var rightLimbColors = intArrayOf(
        Color.parseColor("#FFB6C1"),
        Color.parseColor("#D94A4A"),
        Color.parseColor("#5C1A1A")
    )

    private var leftCapColors = intArrayOf(
        Color.parseColor("#A8D8FF"),
        Color.parseColor("#2C5F8A")
    )

    private var rightCapColors = intArrayOf(
        Color.parseColor("#FFD8D8"),
        Color.parseColor("#8A2C2C")
    )

    /**
     * Compute scaled geometry based on the view size.
     * We preserve a design aspect ratio and scale limb lengths and stroke widths proportionally.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val designThigh = 160f
        val designShin = 160f
        val maxReach = designThigh + designShin  // 320f

        // Because of the scale(-1,1) transform, both legs are drawn on the right half.
        // Reserve 20px margin so the ankle never touches the view edge.
        val scaleFromWidth = (w.toFloat() / 2f - 20f) / (maxReach + 10f)

        // Vertical safety margins for each display mode:
        // HORIZONTAL: baseY = h*0.55, knee bulges upward by ~designThigh*s.
        val scaleFromHeightHorizontal = (h.toFloat() * 0.55f - 10f) / designThigh

        // SQUAT: baseY = h*0.75, hip can rise by maxReach*s.
        val scaleFromHeightSquat = (h.toFloat() * 0.75f - 10f) / maxReach

        // STEPPING: baseY = h*0.30, ankle can drop by maxReach*s. Must stay within h - 10.
        val scaleFromHeightStepping = (h.toFloat() * 0.70f - 10f) / maxReach

        val s = minOf(scaleFromWidth, scaleFromHeightHorizontal, scaleFromHeightSquat, scaleFromHeightStepping, 2.5f)
            .coerceAtLeast(0.1f)

        thighLength = designThigh * s
        shinLength = designShin * s
        baseLimbWidth = 24f * s
        textPaint.textSize = 28f * s

        limbPaint.setShadowLayer(8f * s, 2f * s, 4f * s, Color.parseColor("#40000000"))
        capPaint.setShadowLayer(6f * s, 0f, 2f * s, Color.parseColor("#40000000"))
    }

    // -------------------------------------------------------------------------
    // Adaptive animation core (independent per leg)
    // -------------------------------------------------------------------------

    /**
     * Consume the next pending angle for the given leg state.
     * Implements backlog protection: if the queue grows too large, intermediate frames are dropped
     * so the animation always catches up to the latest sensor value.
     */
    private fun consumeNext(state: LegState) {
        if (state.pendingAngles.isEmpty()) return

        // Backlog protection: drop intermediate frames when queue is too large, keep only the latest.
        if (state.pendingAngles.size >= 10) {
            val latest = state.pendingAngles.removeLast()
            state.pendingAngles.clear()
            state.pendingAngles.addLast(latest)
        }

        val target = state.pendingAngles.removeFirst()
        val backlog = state.pendingAngles.size

        val durationMs = (120L - backlog * 10L).coerceAtLeast(20L)
        val interpolator = LinearInterpolator()

        state.currentAnimator?.cancel()
        state.currentAnimator = ValueAnimator.ofFloat(state.renderedAngle, target).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener {
                state.renderedAngle = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    consumeNext(state)
                }
            })
            start()
        }
    }

    // -------------------------------------------------------------------------
    // Geometry calculation for the three display modes
    // -------------------------------------------------------------------------

    /** Simple data class holding hip, knee, and ankle coordinates for a single leg. */
    private data class LegPoints(val hip: PointF, val knee: PointF, val ankle: PointF)

    /**
     * Compute hip/knee/ankle positions for a leg based on the current DisplayMode.
     *
     * HORIZONTAL: hip is fixed, ankle stays on the same horizontal line as hip, knee bulges upward.
     * SQUAT:     ankle is fixed, hip moves vertically above ankle, knee bulges sideways.
     * STEPPING:  hip is fixed, shin is always vertical downward.
     *
     * Both legs use the same base direction (dir = -1) because the right leg is mirrored
     * via Canvas transform in onDraw().
     */
    private fun calculatePoints(
        side: LegSide,
        baseX: Float,
        baseY: Float,
        angleRad: Double,
        cosTheta: Float
    ): LegPoints {
        val dir = -1f
        val L1 = thighLength
        val L2 = shinLength

        return when (displayMode) {
            DisplayMode.HORIZONTAL -> {
                val hip = PointF(baseX, baseY)
                val d = kotlin.math.sqrt((L1 * L1 + L2 * L2 - 2 * L1 * L2 * cosTheta).toDouble()).toFloat()
                val safeD = if (d < 0.001f) 0.001f else d
                val ankle = PointF(baseX + safeD * dir, baseY)

                val a = (L1 * L1 - L2 * L2 + safeD * safeD) / (2 * safeD)
                val h = kotlin.math.sqrt(maxOf(0.0, (L1 * L1 - a * a).toDouble())).toFloat()
                val knee = PointF(baseX + a * dir, baseY - h)

                LegPoints(hip, knee, ankle)
            }
            DisplayMode.SQUAT -> {
                val ankle = PointF(baseX, baseY)
                val d = kotlin.math.sqrt((L1 * L1 + L2 * L2 - 2 * L1 * L2 * cosTheta).toDouble()).toFloat()
                val safeD = if (d < 0.001f) 0.001f else d
                val hip = PointF(baseX, baseY - safeD)

                val a = (L1 * L1 - L2 * L2 + safeD * safeD) / (2 * safeD)
                val h = kotlin.math.sqrt(maxOf(0.0, (L1 * L1 - a * a).toDouble())).toFloat()
                val knee = PointF(baseX + h * dir, baseY - safeD + a)

                LegPoints(hip, knee, ankle)
            }
            DisplayMode.STEPPING -> {
                val hip = PointF(baseX, baseY)
                val sinTheta = kotlin.math.sin(angleRad).toFloat()
                val knee = PointF(
                    baseX + L1 * sinTheta * dir,
                    baseY - L1 * cosTheta
                )
                val ankle = PointF(knee.x, knee.y + L2)

                LegPoints(hip, knee, ankle)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Draw a single leg (thigh + shin + knee cap)
    // -------------------------------------------------------------------------

    /**
     * Render one leg onto the Canvas using the provided joint positions.
     * This draws the thigh path, shin path, and the knee joint cap with gradients.
     */
    private fun drawLeg(canvas: Canvas, hip: PointF, knee: PointF, ankle: PointF, side: LegSide) {
        val limbColors = if (side == LegSide.LEFT) leftLimbColors else rightLimbColors
        val capColors = if (side == LegSide.LEFT) leftCapColors else rightCapColors

        updateLimbShader(hip.x, hip.y, knee.x, knee.y, hipWidth, kneeWidth, limbColors)
        canvas.drawPath(
            buildThighPath(hip.x, hip.y, knee.x, knee.y, hipWidth, kneeWidth),
            limbPaint
        )

        updateLimbShader(knee.x, knee.y, ankle.x, ankle.y, kneeWidth, ankleWidth, limbColors)
        canvas.drawPath(
            buildShinPath(knee.x, knee.y, ankle.x, ankle.y, kneeWidth, ankleWidth, calfWidth),
            limbPaint
        )

        updateCapShader(knee.x, knee.y, capColors)
        val capRect = RectF(
            knee.x - capWidth / 2f,
            knee.y - capHeight / 2f,
            knee.x + capWidth / 2f,
            knee.y + capHeight / 2f
        )
        canvas.drawOval(capRect, capPaint)
    }

    // -------------------------------------------------------------------------
    // Main onDraw: compute base position, then render left and/or right leg
    // -------------------------------------------------------------------------

    /**
     * Main draw entry point.
     * 1. Computes the base anchor point (baseX, baseY) depending on DisplayMode.
     * 2. Applies per-mode offsets so the left leg does not fully overlap the right leg.
     * 3. Draws the left leg normally.
     * 4. Draws the right leg under a Canvas scale(-1, 1) transform for horizontal-axis symmetry.
     * 5. Renders the current left-leg angle text at the top.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val baseX = width / 2f
        val baseY = when (displayMode) {
            DisplayMode.HORIZONTAL -> height * 0.55f
            DisplayMode.SQUAT -> height * 0.75f
            DisplayMode.STEPPING -> height * 0.30f
        }

        // Different horizontal offsets for each display mode to avoid perfect overlap.
        val leftOffsetX = when (displayMode) {
            DisplayMode.HORIZONTAL -> baseLimbWidth * 0f
            DisplayMode.SQUAT      -> baseLimbWidth * 0.35f
            DisplayMode.STEPPING   -> baseLimbWidth * 0.35f
        }
        val leftOffsetY = when (displayMode) {
            DisplayMode.HORIZONTAL -> baseLimbWidth * 0.35f
            DisplayMode.SQUAT      -> baseLimbWidth * 0f
            DisplayMode.STEPPING   -> baseLimbWidth * 0f
        }

        // Apply a horizontal flip so the right leg mirrors the left leg around the vertical center line.
        canvas.save()
        canvas.scale(-1f, 1f, baseX, baseY)

        if (showLeft) {
            val angleRad = Math.toRadians(leftState.renderedAngle.toDouble())
            val cosTheta = kotlin.math.cos(angleRad).toFloat()
            val pts = calculatePoints(LegSide.LEFT, baseX - leftOffsetX, baseY - leftOffsetY, angleRad, cosTheta)
            drawLeg(canvas, pts.hip, pts.knee, pts.ankle, LegSide.LEFT)
        }

        if (showRight) {
            val angleRad = Math.toRadians(rightState.renderedAngle.toDouble())
            val cosTheta = kotlin.math.cos(angleRad).toFloat()
            val pts = calculatePoints(LegSide.RIGHT, baseX, baseY, angleRad, cosTheta)
            drawLeg(canvas, pts.hip, pts.knee, pts.ankle, LegSide.RIGHT)
        }

        canvas.restore()
    }

    // -------------------------------------------------------------------------
    // Path builders for thigh and shin shapes
    // -------------------------------------------------------------------------

    /**
     * Build a Path representing the thigh segment between hip and knee.
     * Uses cubic Bezier curves to create an organic, slightly bulging limb shape.
     */
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

    /**
     * Build a Path representing the shin segment between knee and ankle.
     * Uses multiple cubic Bezier curves to form a calf bulge and tapered ankle.
     */
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

    /**
     * Update the limb Paint shader to a LinearGradient perpendicular to the bone direction,
     * giving the limb a 3D cylindrical shading effect.
     */
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

    /**
     * Update the knee cap Paint shader to a RadialGradient simulating a spherical highlight.
     */
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