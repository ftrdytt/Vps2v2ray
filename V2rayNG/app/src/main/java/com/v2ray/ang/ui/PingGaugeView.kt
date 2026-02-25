package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator

class PingGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    private var currentPing = 0f
    private val maxPing = 500f // الحد الأقصى للعداد

    init {
        arcPaint.style = Paint.Style.STROKE
        arcPaint.strokeWidth = 30f
        arcPaint.strokeCap = Paint.Cap.ROUND

        textPaint.color = Color.WHITE
        textPaint.textSize = 40f
        textPaint.textAlign = Paint.Align.CENTER

        needlePaint.color = Color.RED
        needlePaint.style = Paint.Style.FILL
        needlePaint.strokeWidth = 8f
        needlePaint.strokeCap = Paint.Cap.ROUND
    }

    fun setPing(ping: Float) {
        val targetPing = if (ping > maxPing) maxPing else ping
        
        val animator = ValueAnimator.ofFloat(currentPing, targetPing)
        animator.duration = 800
        animator.interpolator = OvershootInterpolator(1.2f) // حركة إبرة واقعية
        animator.addUpdateListener { animation ->
            currentPing = animation.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val radius = Math.min(width, height) / 2 - 40f
        val cx = width / 2
        val cy = height / 2 + 40f

        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // رسم قوس العداد (أخضر ثم أصفر ثم أحمر)
        arcPaint.color = Color.parseColor("#4CAF50") // 0-150 أخضر
        canvas.drawArc(rectF, 135f, 90f, false, arcPaint)

        arcPaint.color = Color.parseColor("#FFC107") // 150-300 أصفر
        canvas.drawArc(rectF, 225f, 90f, false, arcPaint)

        arcPaint.color = Color.parseColor("#F44336") // 300+ أحمر
        canvas.drawArc(rectF, 315f, 90f, false, arcPaint)

        // رسم الرقم في المنتصف
        canvas.drawText("${currentPing.toInt()} ms", cx, cy + 20f, textPaint)

        // حساب زاوية الإبرة (تبدأ من 135 وتصل إلى 405)
        val angle = 135f + (currentPing / maxPing) * 270f
        val angleRad = Math.toRadians(angle.toDouble())

        // رسم الإبرة
        val needleLength = radius - 40f
        val stopX = (cx + Math.cos(angleRad) * needleLength).toFloat()
        val stopY = (cy + Math.sin(angleRad) * needleLength).toFloat()

        canvas.drawLine(cx, cy, stopX, stopY, needlePaint)
        canvas.drawCircle(cx, cy, 15f, needlePaint) // دائرة في المنتصف
    }
}
