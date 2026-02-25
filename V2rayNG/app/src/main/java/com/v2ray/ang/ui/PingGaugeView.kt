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
    private val maxPing = 500f // الحد الأقصى للعداد (فوق 500 سيقفل العداد)
    
    // هذا المتغير هو السلاح السري لمنع الإبرة من التجميد
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
        // 1. إيقاف أي حركة سابقة فوراً حتى لا تتجمد الإبرة
        currentAnimator?.cancel()

        // 2. ضبط حدود البنق (ألا ينزل تحت الصفر ولا يعبر 500)
        var targetPing = ping
        if (targetPing < 0f) targetPing = 0f
        if (targetPing > maxPing) targetPing = maxPing

        // 3. تحريك الإبرة بمرونة إلى الرقم الجديد
        currentAnimator = ValueAnimator.ofFloat(currentPing, targetPing).apply {
            duration = 350 // سرعة استجابة الإبرة (أجزاء من الثانية)
            interpolator = DecelerateInterpolator() // حركة انسيابية كالسيارة
            addUpdateListener { animation ->
                currentPing = animation.animatedValue as Float
                invalidate() // إعادة رسم العداد بالزاوية الجديدة
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

        // رسم قوس العداد (أخضر ثم أصفر ثم أحمر)
        arcPaint.color = Color.parseColor("#4CAF50") // 0-150 أخضر
        canvas.drawArc(rectF, 135f, 90f, false, arcPaint)

        arcPaint.color = Color.parseColor("#FFC107") // 150-300 أصفر
        canvas.drawArc(rectF, 225f, 90f, false, arcPaint)

        arcPaint.color = Color.parseColor("#F44336") // 300+ أحمر
        canvas.drawArc(rectF, 315f, 90f, false, arcPaint)

        // رسم الرقم في المنتصف أسفل الإبرة
        canvas.drawText("${currentPing.toInt()} ms", cx, cy + 30f, textPaint)

        // حساب زاوية الإبرة (تبدأ من 135 وتصل إلى 405)
        val angle = 135f + (currentPing / maxPing) * 270f
        val angleRad = Math.toRadians(angle.toDouble())

        // رسم الإبرة
        val needleLength = radius - 35f
        val stopX = (cx + Math.cos(angleRad) * needleLength).toFloat()
        val stopY = (cy + Math.sin(angleRad) * needleLength).toFloat()

        canvas.drawLine(cx, cy, stopX, stopY, needlePaint)
        
        // رسم الدائرة البيضاء الصغيرة في مركز الإبرة
        canvas.drawCircle(cx, cy, 18f, centerPaint) 
    }
}
