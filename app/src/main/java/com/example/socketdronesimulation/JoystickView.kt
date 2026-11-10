package com.example.socketdronesimulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 콜백 인터페이스 (X, Y 값은 -1.0 ~ 1.0 범위를 가집니다)
    var onJoystickMoveListener: ((x: Float, y: Float) -> Unit)? = null
    var onJoystickReleased: (() -> Unit)? = null

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var hatRadius = 0f

    private var hatX = 0f
    private var hatY = 0f

    private val basePaint = Paint().apply {
        color = Color.GRAY
        alpha = 100
        style = Paint.Style.FILL
    }

    private val hatPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) / 3f
        hatRadius = baseRadius / 3f
        resetHat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 외곽 베이스 원
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        // 조작 스틱 원
        canvas.drawCircle(hatX, hatY, hatRadius, hatPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val displacement = sqrt((event.x - centerX).pow(2) + (event.y - centerY).pow(2))
                if (displacement < baseRadius) {
                    hatX = event.x
                    hatY = event.y
                } else {
                    // 베이스 반경을 벗어나지 않도록 제한
                    val ratio = baseRadius / displacement
                    hatX = centerX + (event.x - centerX) * ratio
                    hatY = centerY + (event.y - centerY) * ratio
                }
                invalidate()

                // -1.0 ~ 1.0 사이 값으로 정규화
                val percentX = (hatX - centerX) / baseRadius
                val percentY = (hatY - centerY) / baseRadius
                onJoystickMoveListener?.invoke(percentX, percentY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetHat()
                invalidate()
                onJoystickReleased?.invoke()
            }
        }
        return true
    }

    fun resetJoystick() {
        resetHat()
        invalidate()
    }

    private fun resetHat() {
        hatX = centerX
        hatY = centerY
    }
}
