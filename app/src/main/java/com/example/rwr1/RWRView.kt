package com.example.rwr1

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class RWRView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var isCircleVisible = false
    private val fixedColor = Color.GREEN
    private val blinkingColor = Color.GREEN // 闪烁的颜色
    private val outerCircleStrokeWidth = 4f // 细外圈
    private var middleCircleStrokeWidth = 100f // 粗中圈 - 现在可单独设置
    private var middleCircleRadiusFactor = 0.86f // 中圈半径相对于外圈的因子 - 现在可单独设置
    private val innerCircleStrokeWidth = 16f // 粗内圈
    private var dashLineLengthFactor = 0.7f // 控制虚线长度的因子 (相对于外圆半径)，默认全长
    private var dashLineSegmentLength = 10f // 虚线线段的长度
    private var dashLineGapLength = 10f // 虚线线段之间的间隔

    // 提供方法设置中圈半径因子
    fun setMiddleCircleRadiusFactor(factor: Float) {
        middleCircleRadiusFactor = factor.coerceIn(0f, 1f)
        invalidate()
    }

    // 提供方法设置中圈粗细
    fun setMiddleCircleStrokeWidth(width: Float) {
        middleCircleStrokeWidth = width
        invalidate()
    }

    // 提供方法设置虚线长度因子
    fun setDashLineLengthFactor(factor: Float) {
        dashLineLengthFactor = factor.coerceIn(0f, 1f)
        invalidate()
    }

    // 提供方法设置虚线线段长度
    fun setDashLineSegmentLength(length: Float) {
        dashLineSegmentLength = length
        invalidate()
    }

    // 提供方法设置虚线间隔长度
    fun setDashLineGapLength(length: Float) {
        dashLineGapLength = length
        invalidate()
    }

    // 提供一个方法来设置圆圈的可见性 (控制中圈闪烁)
    fun setCircleVisibility(visible: Boolean) {
        isCircleVisible = visible
        invalidate() // 请求重绘
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radiusOuter = min(centerX, centerY) * 1f // 最大外圈半径
        val radiusMiddle = radiusOuter * middleCircleRadiusFactor // 根据因子计算中圈半径
        val radiusInner = radiusOuter * 0.2f // 小内圈半径
        val dashLineLength = radiusOuter * dashLineLengthFactor // 虚线长度等于外圈半径乘以因子

        // 绘制外圈 (不闪烁)
        paint.color = fixedColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outerCircleStrokeWidth
        canvas.drawCircle(centerX, centerY, radiusOuter, paint)

        // 绘制闪烁的中圈 (粗细和大小可单独控制)
        paint.color = if (isCircleVisible) blinkingColor else Color.TRANSPARENT // 使用透明色实现闪烁
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = middleCircleStrokeWidth
        canvas.drawCircle(centerX, centerY, radiusMiddle, paint)

        // 绘制内圈 (小粗，不闪烁)
        paint.color = fixedColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = innerCircleStrokeWidth
        canvas.drawCircle(centerX, centerY, radiusInner, paint)

        // 绘制十字 (不闪烁，相对于内圈)
        paint.color = fixedColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outerCircleStrokeWidth
        val crossLengthInner = radiusInner * 0.7f
        canvas.drawLine(centerX - crossLengthInner, centerY, centerX + crossLengthInner, centerY, paint)
        canvas.drawLine(centerX, centerY - crossLengthInner, centerX, centerY + crossLengthInner, paint)

        // 绘制 X 形虚线 (长度和样式可自由设置)
        paint.color = fixedColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outerCircleStrokeWidth
        paint.pathEffect = DashPathEffect(floatArrayOf(dashLineSegmentLength, dashLineGapLength), 0f)
        canvas.drawLine(centerX - dashLineLength, centerY - dashLineLength, centerX + dashLineLength, centerY + dashLineLength, paint)
        canvas.drawLine(centerX - dashLineLength, centerY + dashLineLength, centerX + dashLineLength, centerY - dashLineLength, paint)
        paint.pathEffect = null // 恢复实线
    }
}