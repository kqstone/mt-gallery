package com.kqstone.mtphotos.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 为地图 Marker 生成自定义 Bitmap 图标。
 * 包含圆角缩略图 + 数量角标 + 底部三角指针。
 */
object MapClusterRenderer {

    private const val MARKER_SIZE_DP = 52
    private const val BADGE_SIZE_DP = 20
    private const val POINTER_HEIGHT_DP = 8
    private const val CORNER_RADIUS_DP = 8
    private const val BADGE_TEXT_SIZE_SP = 10

    /**
     * 生成带缩略图的 Marker Bitmap。
     * 如果缩略图加载失败，生成带颜色的默认 Marker。
     */
    suspend fun createMarkerBitmap(
        context: Context,
        thumbUrl: String?,
        count: Int,
        imageLoader: ImageLoader? = null
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val markerSize = (MARKER_SIZE_DP * density).toInt()
        val badgeSize = (BADGE_SIZE_DP * density).toInt()
        val pointerHeight = (POINTER_HEIGHT_DP * density).toInt()
        val cornerRadius = CORNER_RADIUS_DP * density
        val totalHeight = markerSize + pointerHeight
        val totalWidth = markerSize

        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 绘制阴影底层
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(50, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val shadowOffset = 2 * density
        canvas.drawRoundRect(
            RectF(shadowOffset, shadowOffset, totalWidth.toFloat(), markerSize.toFloat()),
            cornerRadius, cornerRadius, shadowPaint
        )

        // 绘制白色边框底层
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, totalWidth.toFloat(), markerSize.toFloat()),
            cornerRadius, cornerRadius, borderPaint
        )

        // 尝试加载缩略图
        var thumbBitmap: Bitmap? = null
        if (thumbUrl != null) {
            try {
                val loader = imageLoader ?: ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(thumbUrl)
                    .size(markerSize)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    thumbBitmap = (result.drawable as? BitmapDrawable)?.bitmap
                }
            } catch (_: Exception) { /* 降级到默认样式 */ }
        }

        val innerPadding = 3 * density
        val innerRect = RectF(
            innerPadding, innerPadding,
            totalWidth - innerPadding, markerSize - innerPadding
        )
        val innerRadius = cornerRadius - innerPadding

        if (thumbBitmap != null) {
            // 绘制缩略图（裁剪为圆角矩形）
            canvas.save()
            val clipPath = Path().apply {
                addRoundRect(innerRect, innerRadius, innerRadius, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            canvas.drawBitmap(
                thumbBitmap,
                null,
                innerRect,
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            canvas.restore()
        } else {
            // 绘制默认渐变背景
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#7C8EF5")
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(innerRect, innerRadius, innerRadius, bgPaint)

            // 绘制图标占位
            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 20 * density
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(
                "📷",
                totalWidth / 2f,
                markerSize / 2f + 7 * density,
                iconPaint
            )
        }

        // 绘制底部三角指针
        val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val pointerPath = Path().apply {
            moveTo(totalWidth / 2f - 6 * density, markerSize.toFloat() - 1)
            lineTo(totalWidth / 2f, totalHeight.toFloat())
            lineTo(totalWidth / 2f + 6 * density, markerSize.toFloat() - 1)
            close()
        }
        canvas.drawPath(pointerPath, pointerPaint)

        // 绘制数量角标（仅在 count > 1 时显示）
        if (count > 1) {
            val badgeCx = totalWidth - badgeSize / 2f
            val badgeCy = badgeSize / 2f

            // 角标背景
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF4444")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(badgeCx, badgeCy, badgeSize / 2f, badgeBgPaint)

            // 角标文字
            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = BADGE_TEXT_SIZE_SP * density
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val displayText = if (count > 99) "99+" else count.toString()
            val textY = badgeCy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(displayText, badgeCx, textY, badgeTextPaint)
        }

        return bitmap
    }

    /**
     * 生成纯数量标记的简易 Marker（不含缩略图），用于大范围低缩放级别。
     */
    fun createSimpleMarkerBitmap(context: Context, count: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (40 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 背景圆
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7C8EF5")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        // 白色边框
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1.5f * density, borderPaint)

        // 数字
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13 * density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val displayText = when {
            count >= 1000 -> "${count / 1000}k"
            count > 99 -> "99+"
            else -> count.toString()
        }
        val textY = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(displayText, size / 2f, textY, textPaint)

        return bitmap
    }

    /**
     * 生成圆形缩略图的 Marker，数量角标位于右上角 45 度。
     */
    suspend fun createCircularThumbMarkerBitmap(
        context: Context,
        thumbUrl: String?,
        count: Int,
        imageLoader: ImageLoader? = null
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val thumbRadiusDp = 22f
        val badgeRadiusDp = 10f
        val borderWidthDp = 2f

        val thumbRadius = thumbRadiusDp * density
        val badgeRadius = badgeRadiusDp * density
        val borderWidth = borderWidthDp * density

        // 计算包含角标在内的画布总尺寸
        // 中心为 (thumbRadius, thumbRadius+badgeRadius/2) 确保上方有空间
        // 为确保图片和角标都能画下，我们直接定义画布尺寸
        // 角标中心在 45度角：cx + r*cos(45), cy - r*sin(45)
        // cos(45) = sin(45) ≈ 0.707
        
        // 留出边距
        val canvasPadding = badgeRadius
        val totalSize = ((thumbRadius + canvasPadding) * 2).toInt()
        val center = totalSize / 2f

        val bitmap = Bitmap.createBitmap(totalSize, totalSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 背景阴影
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(50, 0, 0, 0)
            setShadowLayer(4f * density, 0f, 2f * density, Color.argb(50, 0, 0, 0))
        }
        canvas.drawCircle(center, center, thumbRadius, shadowPaint)

        // 绘制白色边框
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, thumbRadius, borderPaint)

        // 尝试加载缩略图
        var thumbBitmap: Bitmap? = null
        if (thumbUrl != null) {
            try {
                val loader = imageLoader ?: ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(thumbUrl)
                    .size((thumbRadius * 2).toInt())
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    thumbBitmap = (result.drawable as? BitmapDrawable)?.bitmap
                }
            } catch (_: Exception) { /* 忽略 */ }
        }

        val innerRadius = thumbRadius - borderWidth

        if (thumbBitmap != null) {
            // 绘制缩略图（裁剪为圆形）
            canvas.save()
            val clipPath = Path().apply {
                addCircle(center, center, innerRadius, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            // 计算缩放比例，保持长宽比填满
            val scale = maxOf(
                (innerRadius * 2) / thumbBitmap.width,
                (innerRadius * 2) / thumbBitmap.height
            )
            val matrix = android.graphics.Matrix()
            matrix.postScale(scale, scale)
            matrix.postTranslate(
                center - (thumbBitmap.width * scale) / 2f,
                center - (thumbBitmap.height * scale) / 2f
            )
            canvas.drawBitmap(thumbBitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            // 绘制默认渐变背景
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#7C8EF5")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(center, center, innerRadius, bgPaint)

            // 绘制图标占位
            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 20 * density
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(
                "📷",
                center,
                center + 7 * density,
                iconPaint
            )
        }

        // 绘制右上角 45度 的角标
        if (count > 0) { // 显示所有数量（或者只显示 >1，根据需要。通常相册聚合需要显示数量）
            val angle = Math.toRadians(45.0)
            val badgeCx = center + (thumbRadius * Math.cos(angle)).toFloat()
            val badgeCy = center - (thumbRadius * Math.sin(angle)).toFloat()

            // 角标白底边框
            val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(badgeCx, badgeCy, badgeRadius + 1.5f * density, badgeBorderPaint)

            // 角标背景
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF4444") // 红色或者蓝色，看需求，相册常红
                style = Paint.Style.FILL
            }
            canvas.drawCircle(badgeCx, badgeCy, badgeRadius, badgeBgPaint)

            // 角标文字
            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 10 * density
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val displayText = when {
                count >= 1000 -> "${count / 1000}k"
                count > 99 -> "99+"
                else -> count.toString()
            }
            val textY = badgeCy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(displayText, badgeCx, textY, badgeTextPaint)
        }

        return bitmap
    }
}
