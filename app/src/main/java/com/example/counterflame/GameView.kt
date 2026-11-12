package com.example.counterflame

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

data class WeaponDef(
    val id: String,
    val name: String,
    val damage: Float,
    val fireRate: Float,
    val magSize: Int,
    val reloadTime: Float,
    val range: Float,
    val sprite: Int,
    val recoilKick: Float
)

class GameView(ctx: Context) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 40f; typeface = Typeface.DEFAULT_BOLD
    }
    private val skyPaint = Paint()
    private val floorPaint = Paint()
    private var lastTime = 0L

    // Оружие
    private val weapons = listOf(
        WeaponDef("pistol", "Glock", 20f, 0.16f, 20, 1.2f, 20f, 0, 0.04f),
        WeaponDef("rifle",  "AK-47", 34f, 0.11f, 30, 1.8f, 26f, 1, 0.07f),
        WeaponDef("sniper", "AWP",   95f, 1.10f, 10, 2.6f, 40f, 2, 0.14f)
    )
    private var currentWeapon = weapons[1] // AK-47 стартовое
    private var ammoMag = currentWeapon.magSize
    private var reloading = 0f
    private var weaponKick = 0f

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
    private var health = 100
    private var score = 0
    private var muzzle = 0f
    private var hurt = 0f
    private var shootCd = 0f

    private val fov = Math.toRadians(70.0).toFloat()
    private val tanHalf = tan(fov / 2f)
    private val moveSpeed = 3.2f

    // Враги
    private class Enemy(var x: Float, var y: Float) {
        var hp = 100f
        var alive = true
        var cd = 1.5f + Random.nextFloat() * 1.5f
        var respawn = 0f
    }
    private val enemies = ArrayList<Enemy>()

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
    private var fireCx = 0f; private var fireCy = 0f
    private val fireR = 95f
    private var firing = false

    // Кнопки оружия
    private var weaponBtnR = 55f
    private var weaponBtnY = 90f
    private var weaponBtnXs = floatArrayOf(0f, 0f, 0f)

    init {
        repeat(5) {
            val e = Enemy(8f, 8f)
            placeEnemy(e)
            enemies.add(e)
        }
    }

    private fun placeEnemy(e: Enemy) {
        var tries = 0
        while (tries++ < 300) {
            val x = 1 + Random.nextFloat() * (mw - 2)
            val y = 1 + Random.nextFloat() * (mh - 2)
            if (wallAt(x.toInt(), y.toInt()) != 0) continue
            if (hypot(x - px, y - py) < 4.5f) continue
            e.x = x; e.y = y; return
        }
        e.x = 8f; e.y = 8f
    }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw; h = nh
        focal = (w / 2f) / tanHalf
        zbuf = FloatArray(w)
        fireCx = w - 160f; fireCy = h - 150f
        weaponBtnY = 80f
        weaponBtnR = 55f
        weaponBtnXs = floatArrayOf(w * 0.42f, w * 0.52f, w * 0.62f)
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

    private fun lineOfSight(tx: Float, ty: Float): Boolean {
        val dx = tx - px; val dy = ty - py
        val d = hypot(dx, dy)
        if (d < 0.001f) return true
        return castRay(px, py, dx / d, dy / d, 40f) >= d - 0.15f
    }

    private fun switchWeapon(idx: Int) {
        if (idx !in weapons.indices) return
        if (weapons[idx].id == currentWeapon.id) return
        currentWeapon = weapons[idx]
        ammoMag = currentWeapon.magSize
        reloading = 0f
        weaponKick = 0.3f
    }

    private fun reload() {
        if (reloading > 0f) return
        if (ammoMag >= currentWeapon.magSize) return
        reloading = currentWeapon.reloadTime
    }

    private fun shoot() {
        if (shootCd > 0f || reloading > 0f) return
        if (ammoMag <= 0) { reload(); return }
        ammoMag--
        shootCd = currentWeapon.fireRate
        muzzle = 1f
        weaponKick = 1f

        var best: Enemy? = null
        var bestD = Float.MAX_VALUE
        for (e in enemies) {
            if (!e.alive) continue
            val dx = e.x - px; val dy = e.y - py
            val d = hypot(dx, dy)
            if (d > currentWeapon.range) continue
            var rel = atan2(dy, dx) - pa
            while (rel > Math.PI.toFloat()) rel -= (2 * Math.PI).toFloat()
            while (rel < -Math.PI.toFloat()) rel += (2 * Math.PI).toFloat()
            if (abs(rel) > 0.12f) continue
            if (d < bestD && lineOfSight(e.x, e.y)) { bestD = d; best = e }
        }
        best?.let {
            it.hp -= currentWeapon.damage
            if (it.hp <= 0f) {
                it.alive = false
                it.respawn = 2.5f
                score++
            }
        }
    }

    private fun update(dt: Float) {
        if (reloading > 0f) {
            reloading -= dt
            if (reloading <= 0f) { reloading = 0f; ammoMag = currentWeapon.magSize }
        }
        muzzle = max(0f, muzzle - dt * 6f)
        hurt = max(0f, hurt - dt * 1.5f)
        shootCd = max(0f, shootCd - dt)
        weaponKick = max(0f, weaponKick - dt * 4f)
        if (firing) shoot()

        val fwd = -joyDy
        val str = joyDx
        val sp = moveSpeed * dt
        val nx = px + (cos(pa) * fwd - sin(pa) * str) * sp
        val ny = py + (sin(pa) * fwd + cos(pa) * str) * sp
        if (wallAt(nx.toInt(), py.toInt()) == 0) px = nx
        if (wallAt(px.toInt(), ny.toInt()) == 0) py = ny

        for (e in enemies) {
            if (!e.alive) {
                e.respawn -= dt
                if (e.respawn <= 0f) {
                    placeEnemy(e); e.alive = true; e.hp = 100f; e.cd = 1.5f
                }
                continue
            }
            val ex = px - e.x; val ey = py - e.y
            val d = hypot(ex, ey).coerceAtLeast(0.001f)
            if (d > 1.5f) {
                val s = 1.5f * dt
                val mx = e.x + ex / d * s
                val my = e.y + ey / d * s
                if (wallAt(mx.toInt(), e.y.toInt()) == 0) e.x = mx
                if (wallAt(e.x.toInt(), my.toInt()) == 0) e.y = my
            }
            e.cd -= dt
            if (e.cd <= 0f && d < 9f) {
                e.cd = 1.2f + Random.nextFloat() * 1.6f
                if (lineOfSight(e.x, e.y)) {
                    health -= 7
                    hurt = 1f
                    if (health <= 0) {
                        health = 100
                        score = max(0, score - 2)
                        px = 2.5f; py = 2.5f
                    }
                }
            }
        }
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

        val alive = enemies.filter { it.alive }
            .sortedByDescending { (it.x - px) * (it.x - px) + (it.y - py) * (it.y - py) }
        for (e in alive) drawEnemy(c, e)

        drawWeaponInHands(c)

        if (muzzle > 0f) {
            val r = 40f + 40f * currentWeapon.sprite
            paint.color = Color.argb((muzzle * 200).toInt(), 255, 220, 120)
            c.drawCircle(w * 0.72f, h * 0.88f, r * muzzle, paint)
        }

        paint.color = Color.WHITE
        paint.strokeWidth = 4f
        c.drawLine(w / 2f - 28, h / 2f, w / 2f - 9, h / 2f, paint)
        c.drawLine(w / 2f + 9, h / 2f, w / 2f + 28, h / 2f, paint)
        c.drawLine(w / 2f, h / 2f - 28, w / 2f, h / 2f - 9, paint)
        c.drawLine(w / 2f, h / 2f + 9, w / 2f, h / 2f + 28, paint)

        if (joyId != -1) {
            paint.color = Color.argb(90, 255, 255, 255)
            c.drawCircle(joyBx, joyBy, joyR, paint)
            paint.color = Color.argb(190, 255, 255, 255)
            c.drawCircle(joyBx + joyDx * joyR, joyBy + joyDy * joyR, joyR * 0.38f, paint)
        }

        paint.color = Color.argb(if (firing) 210 else 140, 220, 40, 40)
        c.drawCircle(fireCx, fireCy, fireR, paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 34f; text.color = Color.WHITE
        c.drawText("FIRE", fireCx, fireCy + 12f, text)

        // Кнопки выбора оружия
        for (i in weapons.indices) {
            val selected = weapons[i].id == currentWeapon.id
            paint.color = if (selected) Color.rgb(255, 200, 60)
                          else Color.argb(150, 40, 40, 60)
            c.drawCircle(weaponBtnXs[i], weaponBtnY, weaponBtnR, paint)
            text.textAlign = Paint.Align.CENTER
            text.textSize = 22f
            text.color = if (selected) Color.BLACK else Color.WHITE
            c.drawText(weapons[i].name, weaponBtnXs[i], weaponBtnY + 8f, text)
        }

        // HUD
        text.textAlign = Paint.Align.LEFT
        text.textSize = 44f; text.color = Color.WHITE
        c.drawText("HP $health", 40f, h - 40f, text)
        text.textAlign = Paint.Align.RIGHT
        val ammoLabel = if (reloading > 0f) "RELOAD…"
                        else "$ammoMag / ${currentWeapon.magSize}"
        c.drawText(ammoLabel, w - 40f, h - 40f, text)
        text.textAlign = Paint.Align.CENTER
        c.drawText("SCORE $score", w / 2f, 60f, text)

        if (hurt > 0f) {
            paint.color = Color.argb((hurt * 110).toInt(), 200, 0, 0)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }

    private fun drawWeaponInHands(c: Canvas) {
        val cx = w * 0.72f
        val cy = h * 0.92f
        val bob = sin(System.nanoTime() / 100_000_000.0).toFloat() * 6f *
                  hypot(joyDx.toDouble(), joyDy.toDouble()).toFloat()
        val kick = weaponKick * 25f * currentWeapon.recoilKick / 0.05f
        val y = cy + bob + kick

        val metal = Color.rgb(60, 60, 70)
        val dark  = Color.rgb(30, 30, 35)

        when (currentWeapon.sprite) {
            0 -> { // пистолет
                paint.color = dark
                rect.set(cx - 35f, y - 30f, cx + 35f, y + 40f)
                c.drawRoundRect(rect, 6f, 6f, paint)
                paint.color = metal
                rect.set(cx - 15f, y - 55f, cx + 15f, y - 30f)
                c.drawRoundRect(rect, 4f, 4f, paint)
                paint.color = Color.rgb(20, 20, 20)
                rect.set(cx - 25f, y + 10f, cx + 5f, y + 60f)
                c.drawRoundRect(rect, 4f, 4f, paint)
            }
            1 -> { // AK-47
                paint.color = dark
                rect.set(cx - 110f, y - 25f, cx + 90f, y + 15f)
                c.drawRoundRect(rect, 4f, 4f, paint)
                paint.color = Color.rgb(110, 60, 30) // дерево
                rect.set(cx - 80f, y + 10f, cx - 20f, y + 55f)
                c.drawRoundRect(rect, 3f, 3f, paint)
                rect.set(cx + 40f, y + 10f, cx + 100f, y + 40f)
                c.drawRoundRect(rect, 3f, 3f, paint)
                paint.color = metal
                rect.set(cx + 90f, y - 10f, cx + 150f, y + 5f)
                c.drawRect(rect, paint) // ствол
                paint.color = dark
                rect.set(cx - 10f, y + 5f, cx + 30f, y + 60f)
                c.drawRoundRect(rect, 3f, 3f, paint) // магазин
                paint.color = metal
                rect.set(cx - 20f, y - 45f, cx + 20f, y - 20f)
                c.drawRect(rect, paint) // прицельная планка
            }
            2 -> { // AWP
                paint.color = dark
                rect.set(cx - 150f, y - 22f, cx + 130f, y + 15f)
                c.drawRoundRect(rect, 4f, 4f, paint)
                paint.color = Color.rgb(40, 80, 50) // зелёный приклад
                rect.set(cx - 150f, y - 15f, cx - 60f, y + 25f)
                c.drawRoundRect(rect, 4f, 4f, paint)
                paint.color = dark
                rect.set(cx + 130f, y - 8f, cx + 200f, y + 5f)
                c.drawRect(rect, paint) // длинный ствол
                // оптика
                paint.color = Color.rgb(20, 20, 20)
                rect.set(cx - 40f, y - 55f, cx + 40f, y - 25f)
                c.drawRoundRect(rect, 6f, 6f, paint)
                paint.color = Color.rgb(80, 140, 200)
                c.drawCircle(cx - 30f, y - 40f, 6f, paint)
                // сошки
                paint.color = metal
                c.drawLine(cx + 100f, y + 5f, cx + 80f, y + 50f, paint)
                c.drawLine(cx + 100f, y + 5f, cx + 120f, y + 50f, paint)
            }
        }
    }

    private fun drawEnemy(c: Canvas, e: Enemy) {
        val dx = e.x - px; val dy = e.y - py
        val d = hypot(dx, dy)
        if (d < 0.25f) return
        var rel = atan2(dy, dx) - pa
        while (rel > Math.PI.toFloat()) rel -= (2 * Math.PI).toFloat()
        while (rel < -Math.PI.toFloat()) rel += (2 * Math.PI).toFloat()
        if (abs(rel) > fov / 2f + 0.6f) return

        val sx = w / 2f + tan(rel) * focal
        val col = sx.toInt().coerceIn(0, w - 1)
        if (d > zbuf[col] + 0.25f) return

        val feet = h / 2f + focal / (2f * d)
        val sh = focal * 0.95f / d
        val sw = sh * 0.42f
        val top = feet - sh

        paint.color = Color.rgb(188, 46, 46)
        rect.set(sx - sw / 2f, top + sh * 0.28f, sx + sw / 2f, feet)
        c.drawRoundRect(rect, sw * 0.2f, sw * 0.2f, paint)

        paint.color = Color.rgb(226, 190, 158)
        c.drawCircle(sx, top + sh * 0.15f, sw * 0.30f, paint)

        val by = top - sh * 0.14f
        paint.color = Color.argb(180, 0, 0, 0)
        c.drawRect(sx - sw / 2f, by, sx + sw / 2f, by + sh * 0.06f, paint)
        paint.color = Color.rgb(80, 220, 100)
        c.drawRect(
            sx - sw / 2f, by,
            sx - sw / 2f + sw * (e.hp / 100f).coerceIn(0f, 1f),
            by + sh * 0.06f, paint
        )
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val x = ev.getX(i); val y = ev.getY(i)
                val id = ev.getPointerId(i)

                // проверка кнопок оружия
                for (wi in weapons.indices) {
                    if (hypot(x - weaponBtnXs[wi], y - weaponBtnY) < weaponBtnR * 1.2f) {
                        switchWeapon(wi)
                        return true
                    }
                }

                if (hypot(x - fireCx, y - fireCy) < fireR * 1.35f && !firing) {
                    firing = true
                    shoot()
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
                if (id == ev.getPointerId(0)) firing = false
            }
            MotionEvent.ACTION_CANCEL -> {
                joyId = -1; lookId = -1
                joyDx = 0f; joyDy = 0f
                firing = false
            }
        }
        return true
    }
}
