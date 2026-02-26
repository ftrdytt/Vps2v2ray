package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class PingGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    private var currentPing = 0f
    private val maxPing = 500f // أقصى حد لعداد البنق (500ms)

    private var currentAnimator: ValueAnimator? = null 

    init {
        arcPaint.style = Paint.Style.STROKE
        arcPaint.strokeWidth = 35f
        arcPaint.strokeCap = Paint.Cap.ROUND

        textPaint.color = Color.WHITE
        textPaint.textSize = 45f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true

        needlePaint.color = Color.RED
        needlePaint.style = Paint.Style.FILL
        needlePaint.strokeWidth = 10f
        needlePaint.strokeCap = Paint.Cap.ROUND
        
        centerPaint.color = Color.WHITE
        centerPaint.style = Paint.Style.FILL
    }

    fun setPing(ping: Float) {
        if (Math.abs(currentPing - ping) < 1f) return

        currentAnimator?.cancel()

        var targetPing = ping
        if (targetPing < 0f) targetPing = 0f
        if (targetPing > maxPing) targetPing = maxPing

        // حركة سلسة للإبرة تستغرق 800 ملي ثانية
        currentAnimator = ValueAnimator.ofFloat(currentPing, targetPing).apply {
            duration = 800 
            interpolator = DecelerateInterpolator() 
            addUpdateListener { animation ->
                currentPing = animation.animatedValue as Float
                invalidate() 
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val radius = Math.min(width, height) / 2 - 40f
        val cx = width / 2
        val cy = height / 2 + 40f

        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 0-150 أخضر (بنق ممتاز)
        arcPaint.color = Color.parseColor("#4CAF50") 
        canvas.drawArc(rectF, 135f, 90f, false, arcPaint)

        // 150-300 أصفر (بنق متوسط)
        arcPaint.color = Color.parseColor("#FFC107") 
        canvas.drawArc(rectF, 225f, 90f, false, arcPaint)

        // 300-500 أحمر (بنق ضعيف)
        arcPaint.color = Color.parseColor("#F44336") 
        canvas.drawArc(rectF, 315f, 90f, false, arcPaint)

        // كتابة البنق بالملي ثانية
        canvas.drawText("${currentPing.toInt()} ms", cx, cy + 30f, textPaint)

        val angle = 135f + (currentPing / maxPing) * 270f
        val angleRad = Math.toRadians(angle.toDouble())

        val needleLength = radius - 35f
        val stopX = (cx + Math.cos(angleRad) * needleLength).toFloat()
        val stopY = (cy + Math.sin(angleRad) * needleLength).toFloat()

        canvas.drawLine(cx, cy, stopX, stopY, needlePaint)
        canvas.drawCircle(cx, cy, 18f, centerPaint) 
    }
}
