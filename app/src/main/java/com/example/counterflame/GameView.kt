package com.example.counterflame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.sin

class GameView(ctx: Context) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 60f
        textAlign = Paint.Align.CENTER
    }
    private var lastTime = 0L
    private var t = 0f
    private var counter = 0
    private var w = 1
    private var h = 1
    private var btnX = 0f
    private var btnY = 0f
    private var btnR = 120f

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw; h = nh
        btnX = w / 2f; btnY = h * 0.75f
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastTime == 0L) 0f else (now - lastTime) / 1_000_000_000f
        lastTime = now
        t += dt

        // фон
        paint.color = Color.rgb(20, 24, 40)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // «огонь» — пульсирующий круг
        val pulse = (sin(t * 3.0).toFloat() + 1f) / 2f
        paint.color = Color.rgb(255, (100 + pulse * 100).toInt(), 0)
        canvas.drawCircle(w / 2f, h / 2f, 100f + pulse * 30f, paint)

        // заголовок
        text.textSize = 70f
        text.color = Color.WHITE
        canvas.drawText("CounterFlame", w / 2f, h * 0.20f, text)

        // счётчик
        text.textSize = 90f
        text.color = Color.rgb(255, 220, 60)
        canvas.drawText("$counter", w / 2f, h / 2f + 30f, text)

        // кнопка
        paint.color = Color.rgb(40, 140, 220)
        canvas.drawCircle(btnX, btnY, btnR, paint)
        text.textSize = 40f
        text.color = Color.WHITE
        canvas.drawText("+1", btnX, btnY + 14f, text)

        // подсказка
        text.textSize = 28f
        text.color = Color.GRAY
        canvas.drawText("Нажми кнопку — увидишь счёт", w / 2f, h - 60f, text)

        invalidate()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (hypot(ev.x - btnX, ev.y - btnY) < btnR * 1.3f) {
                counter++
                return true
            }
        }
        return true
    }
}
