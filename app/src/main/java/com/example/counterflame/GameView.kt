package com.example.counterflame

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class GameView(ctx: Context) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 40f
    }
    private val skyPaint = Paint()
    private val floorPaint = Paint()
    private var lastTime = 0L

    // Карта
    private val map = arrayOf(
        "1111111111111111",
        "1..............1",
        "1..22....22....1",
        "1..2......2....1",
        "1..............1",
        "1....3333......1",
        "1....3..3......1",
        "1....3..3......1",
        "1....3333......1",
        "1..............1",
        "1..2......2....1",
        "1..22....22....1",
        "1..............1",
        "1..............1",
        "1..............1",
        "1111111111111111"
    )
    private val mw = map[0].length
    private val mh = map.size

    private fun wallAt(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= mw || y >= mh) return 1
        val c = map[y][x]
        return if (c == '.') 0 else (c - '0')
    }

    // Игрок
    private var px = 2.5f
    private var py = 2.5f
    private var pa = 0.4f

    private val fov = Math.toRadians(70.0).toFloat()
    private val tanHalf = tan(fov / 2f)
    private val moveSpeed = 3.2f

    // Экран
    private var w = 1; private var h = 1
    private var focal = 1f
    private var zbuf = FloatArray(1)
    private val info = IntArray(2)

    // Ввод
    private var joyId = -1
    private var joyBx = 0f; private var joyBy = 0f
    private var joyDx = 0f; private var joyDy = 0f
    private val joyR = 120f
    private var lookId = -1
    private var lookPrevX = 0f

    // Кнопка огня
    private var fireCx = 0f; private var fireCy = 0f
    private val fireR = 95f
    private var muzzle = 0f

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw; h = nh
        focal = (w / 2f) / tanHalf
        zbuf = FloatArray(w)
        fireCx = w - 160f; fireCy = h - 150f
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, h / 2f,
            Color.rgb(18, 22, 38), Color.rgb(78, 96, 138), Shader.TileMode.CLAMP)
        floorPaint.shader = LinearGradient(
            0f, h / 2f, 0f, h.toFloat(),
            Color.rgb(46, 46, 52), Color.rgb(10, 10, 12), Shader.TileMode.CLAMP)
    }

    private fun castRay(ox: Float, oy: Float, dx: Float, dy: Float, maxD: Float): Float {
        var mapX = ox.toInt(); var mapY = oy.toInt()
        val ddx = if (dx == 0f) 1e30f else abs(1f / dx)
        val ddy = if (dy == 0f) 1e30f else abs(1f / dy)
        val stepX: Int; val stepY: Int
        var sdx: Float; var sdy: Float
        if (dx < 0) { stepX = -1; sdx = (ox - mapX) * ddx }
        else { stepX = 1; sdx = (mapX + 1f - ox) * ddx }
        if (dy < 0) { stepY = -1; sdy = (oy - mapY) * ddy }
        else { stepY = 1; sdy = (mapY + 1f - oy) * ddy }
        var side = 0; var t = 0f; var guard = 0
        while (guard++ < 512) {
            if (sdx < sdy) { t = sdx; sdx += ddx; mapX += stepX; side = 0 }
            else { t = sdy; sdy += ddy; mapY += stepY; side = 1 }
            if (t > maxD) break
            val tile = wallAt(mapX, mapY)
            if (tile != 0) { info[0] = side; info[1] = tile; return t }
        }
        info[0] = -1; info[1] = 1; return maxD
    }

    private fun update(dt: Float) {
        muzzle = max(0f, muzzle - dt * 6f)

        val fwd = -joyDy
        val str = joyDx
        val sp = moveSpeed * dt
        val nx = px + (cos(pa) * fwd - sin(pa) * str) * sp
        val ny = py + (sin(pa) * fwd + cos(pa) * str) * sp
        if (wallAt(nx.toInt(), py.toInt()) == 0) px = nx
        if (wallAt(px.toInt(), ny.toInt()) == 0) py = ny
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        var dt = if (lastTime == 0L) 0f else (now - lastTime) / 1_000_000_000f
        lastTime = now
        dt = dt.coerceAtMost(0.05f)
        update(dt)
        render(canvas)
        invalidate()
    }

    private fun render(c: Canvas) {
        c.drawRect(0f, 0f, w.toFloat(), h / 2f, skyPaint)
        c.drawRect(0f, h / 2f, w.toFloat(), h.toFloat(), floorPaint)

        val dirX = cos(pa); val dirY = sin(pa)
        val planeX = -dirY * tanHalf; val planeY = dirX * tanHalf

        for (x in 0 until w) {
            val camX = 2f * x / w - 1f
            val rx = dirX + planeX * camX
            val ry = dirY + planeY * camX
            var t = castRay(px, py, rx, ry, 40f)
            if (t < 0.05f) t = 0.05f
            zbuf[x] = t

            val lineH = focal / t
            val top = h / 2f - lineH / 2f
            val bot = h / 2f + lineH / 2f

            val base = when (info[1]) {
                2 -> Color.rgb(126, 74, 52)
                3 -> Color.rgb(72, 104, 72)
                else -> Color.rgb(92, 92, 104)
            }
            val shade = if (info[0] == 1) 0.72f else 1f
            val fog = (1f - t / 22f).coerceIn(0.12f, 1f)
            paint.color = Color.rgb(
                (Color.red(base) * shade * fog).toInt().coerceIn(0, 255),
                (Color.green(base) * shade * fog).toInt().coerceIn(0, 255),
                (Color.blue(base) * shade * fog).toInt().coerceIn(0, 255)
            )
            c.drawRect(x.toFloat(), top, x + 1f, bot, paint)
        }

        // Вспышка выстрела
        if (muzzle > 0f) {
            paint.color = Color.argb((muzzle * 200).toInt(), 255, 220, 120)
            c.drawCircle(w * 0.72f, h * 0.92f, 60f * muzzle, paint)
        }

        // Прицел
        paint.color = Color.WHITE
        paint.strokeWidth = 4f
        c.drawLine(w / 2f - 28, h / 2f, w / 2f - 9, h / 2f, paint)
        c.drawLine(w / 2f + 9, h / 2f, w / 2f + 28, h / 2f, paint)
        c.drawLine(w / 2f, h / 2f - 28, w / 2f, h / 2f - 9, paint)
        c.drawLine(w / 2f, h / 2f + 9, w / 2f, h / 2f + 28, paint)

        // Джойстик
        if (joyId != -1) {
            paint.color = Color.argb(90, 255, 255, 255)
            c.drawCircle(joyBx, joyBy, joyR, paint)
            paint.color = Color.argb(190, 255, 255, 255)
            c.drawCircle(joyBx + joyDx * joyR, joyBy + joyDy * joyR, joyR * 0.38f, paint)
        }

        // Кнопка огня
        paint.color = Color.argb(140, 220, 40, 40)
        c.drawCircle(fireCx, fireCy, fireR, paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 34f
        c.drawText("FIRE", fireCx, fireCy + 12f, text)

        text.textAlign = Paint.Align.LEFT
        text.textSize = 28f
        text.color = Color.WHITE
        c.drawText("3D SHOOTER — стены", 30f, 50f, text)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val x = ev.getX(i); val y = ev.getY(i)
                val id = ev.getPointerId(i)
                if (hypot(x - fireCx, y - fireCy) < fireR * 1.35f) {
                    muzzle = 1f
                } else if (x < w * 0.55f && y > h * 0.35f && joyId == -1) {
                    joyId = id; joyBx = x; joyBy = y; joyDx = 0f; joyDy = 0f
                } else if (lookId == -1) {
                    lookId = id; lookPrevX = x
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    val x = ev.getX(i); val y = ev.getY(i)
                    if (id == joyId) {
                        var dx = x - joyBx; var dy = y - joyBy
                        val len = hypot(dx, dy)
                        if (len > joyR) { dx = dx / len * joyR; dy = dy / len * joyR }
                        joyDx = dx / joyR; joyDy = dy / joyR
                    } else if (id == lookId) {
                        pa += (x - lookPrevX) * 0.005f
                        lookPrevX = x
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                if (id == joyId) { joyId = -1; joyDx = 0f; joyDy = 0f }
                if (id == lookId) lookId = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                joyId = -1; lookId = -1
                joyDx = 0f; joyDy = 0f
            }
        }
        return true
    }
}
