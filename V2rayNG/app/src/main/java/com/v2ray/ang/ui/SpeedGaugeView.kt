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

class SpeedGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    private var currentSpeed = 0f
    private val maxSpeed = 100f // أقصى سرعة بالكيج 100 ميجابت (Mbps)

    private var currentAnimator: ValueAnimator? = null

    init {
        arcPaint.style = Paint.Style.STROKE
        arcPaint.strokeWidth = 30f
        arcPaint.strokeCap = Paint.Cap.ROUND

        textPaint.color = Color.WHITE
        textPaint.textSize = 40f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true

        needlePaint.color = Color.parseColor("#03A9F4") // إبرة زرقاء رياضية
        needlePaint.style = Paint.Style.FILL
        needlePaint.strokeWidth = 10f
        needlePaint.strokeCap = Paint.Cap.ROUND
        
        centerPaint.color = Color.WHITE
        centerPaint.style = Paint.Style.FILL
    }

    fun setSpeed(speedMbps: Float) {
        // تجاهل التحديث إذا كان التغيير طفيفاً جداً لتقليل الارتجاف المزعج
        if (Math.abs(currentSpeed - speedMbps) < 0.2f) return

        currentAnimator?.cancel()

        var targetSpeed = speedMbps
        if (targetSpeed < 0f) targetSpeed = 0f
        if (targetSpeed > maxSpeed) targetSpeed = maxSpeed

        // السحر هنا: تسريع استجابة الإبرة لتصبح كالمحرك الرياضي
        currentAnimator = ValueAnimator.ofFloat(currentSpeed, targetSpeed).apply {
            duration = 150 // استجابة لحظية في أجزاء من الثانية!
            interpolator = OvershootInterpolator(1.2f) // تأثير ارتداد خفيف وممتع للإبرة
            addUpdateListener { animation ->
                currentSpeed = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val radius = Math.min(width, height) / 2 - 35f
        val cx = width / 2
        val cy = height / 2 + 35f

        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 0-25 Mbps أحمر (بطيء)
        arcPaint.color = Color.parseColor("#F44336")
        canvas.drawArc(rectF, 135f, 67.5f, false, arcPaint)

        // 25-60 Mbps أصفر (متوسط)
        arcPaint.color = Color.parseColor("#FFC107")
        canvas.drawArc(rectF, 202.5f, 94.5f, false, arcPaint)

        // 60-100+ Mbps أخضر (سريع)
        arcPaint.color = Color.parseColor("#4CAF50")
        canvas.drawArc(rectF, 297f, 108f, false, arcPaint)

        // رسم الرقم مع كسر عشري واحد ليكون أكثر دقة وحيوية (مثلاً 15.4 Mbps)
        canvas.drawText(String.format(Locale.US, "%.1f Mbps", currentSpeed), cx, cy + 30f, textPaint)

        val angle = 135f + (currentSpeed / maxSpeed) * 270f
        val angleRad = Math.toRadians(angle.toDouble())

        val needleLength = radius - 30f
        val stopX = (cx + Math.cos(angleRad) * needleLength).toFloat()
        val stopY = (cy + Math.sin(angleRad) * needleLength).toFloat()

        canvas.drawLine(cx, cy, stopX, stopY, needlePaint)
        canvas.drawCircle(cx, cy, 18f, centerPaint)
    }
}
