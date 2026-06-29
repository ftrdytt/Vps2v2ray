package com.v2ray.ang.util // تأكد من تغيير المسار إذا وضعته في مجلد آخر

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.abs

object AvatarGenerator {
    // 🎨 قائمة ألوان متناسقة تشبه ألوان جوجل وتيليجرام 🎨
    private val AVATAR_COLORS = arrayOf(
        "#F44336", "#E91E63", "#9C27B0", "#673AB7",
        "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4",
        "#009688", "#4CAF50", "#8BC34A", "#FF9800", 
        "#FF5722", "#795548", "#607D8B", "#E53935"
    )

    fun generateAvatar(name: String, id: String, size: Int = 150): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. اختيار لون ثابت للمستخدم بناءً على الـ ID الخاص به
        val hash = id.hashCode()
        val colorIndex = abs(hash % AVATAR_COLORS.size)
        val bgColor = Color.parseColor(AVATAR_COLORS[colorIndex])

        // 2. رسم الدائرة الخلفية باللون المختار
        val bgPaint = Paint().apply {
            color = bgColor
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, bgPaint)

        // 3. استخراج أول حرف من اسم المستخدم (عربي أو إنجليزي)
        val firstLetter = if (name.isNotBlank() && name != "مجهول" && name != "مجهول الهوية") {
            name.trim().take(1).uppercase()
        } else {
            "?"
        }

        // 4. إعداد خط النص ليكون أبيض، بولد، وبحجم متناسق
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = size / 2.2f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // 5. حساب الأبعاد لرسم الحرف في المنتصف تماماً
        val bounds = Rect()
        textPaint.getTextBounds(firstLetter, 0, firstLetter.length, bounds)
        val yOffset = bounds.height() / 2f - bounds.bottom

        canvas.drawText(firstLetter, radius, radius + yOffset, textPaint)

        return bitmap
    }
}
