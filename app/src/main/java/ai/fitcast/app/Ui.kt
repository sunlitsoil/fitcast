package ai.fitcast.app

import ai.fitcast.core.Load
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * 화면 색. 절제형: 중립색 위에 운전 단계 색 하나만 바뀐다.
 * 주행 중 한눈에 읽히도록 단계 색은 단계 이름·점·도착 막대의 현재 항목에만 쓴다.
 * 낮·밤은 시스템 설정(차량의 주간·야간 모드)을 따른다.
 */
data class Palette(
    val bg: Int, val surface: Int, val line: Int,
    val text: Int, val sub: Int, val faint: Int,
    val relaxed: Int, val caution: Int, val focus: Int, val parked: Int,
    val onAccent: Int,
) {
    fun of(load: Load) = when (load) {
        Load.LOW -> relaxed; Load.MEDIUM -> caution; Load.HIGH -> focus; Load.PARKED -> parked
    }

    companion object {
        val DAY = Palette(
            bg = Color.rgb(244, 245, 247), surface = Color.WHITE, line = Color.rgb(218, 222, 228),
            text = Color.rgb(22, 24, 29), sub = Color.rgb(84, 91, 104), faint = Color.rgb(150, 156, 166),
            relaxed = Color.rgb(24, 116, 82), caution = Color.rgb(154, 91, 0), focus = Color.rgb(190, 50, 40), parked = Color.rgb(64, 78, 102),
            onAccent = Color.WHITE,
        )
        val NIGHT = Palette(
            bg = Color.rgb(17, 19, 23), surface = Color.rgb(27, 30, 36), line = Color.rgb(46, 50, 58),
            text = Color.rgb(237, 239, 243), sub = Color.rgb(161, 168, 179), faint = Color.rgb(104, 111, 122),
            relaxed = Color.rgb(76, 195, 138), caution = Color.rgb(240, 176, 74), focus = Color.rgb(255, 107, 94), parked = Color.rgb(154, 166, 184),
            onAccent = Color.rgb(17, 19, 23),
        )

        fun forSystem(ctx: Context): Palette {
            val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (night == Configuration.UI_MODE_NIGHT_YES) NIGHT else DAY
        }
    }
}

/** 단계 이름. 주행 중에 읽을 말이라 짧게 쓴다. */
fun Load.title() = when (this) {
    Load.LOW -> "듣는 중"
    Load.MEDIUM -> "주의 — 읽던 문장만 마칩니다"
    Load.HIGH -> "잠시 멈춤"
    Load.PARKED -> "주차 중"
}

/**
 * 도착 맞춤 막대. 막대 전체가 도착까지 남은 시간이고, 앞에서부터 읽을 항목들의 낭독 시간을 이어 붙인다.
 * 지금 읽는 항목은 단계 색, 남은 계획은 옅은 색이다. 도착 지점에 세로 표시를 둔다.
 */
class ArrivalBar(ctx: Context, private val font: Typeface) : View(ctx) {
    var palette: Palette = Palette.DAY
    var accent: Int = Palette.DAY.relaxed
    /** 앞에서부터 항목별 낭독 예상 시간(초). 첫 값이 지금 읽는 항목. */
    var segments: List<Double> = emptyList()
    var remainSec: Int? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val barTop = h * 0.42f; val barH = h * 0.38f; val radius = barH / 2
        p.style = Paint.Style.FILL
        p.color = palette.line
        r.set(0f, barTop, w, barTop + barH); c.drawRoundRect(r, radius, radius, p)

        val remain = remainSec ?: return
        val total = segments.sum()
        val scale = w / maxOf(remain.toDouble(), total, 1.0)
        var x = 0f
        val gap = 4f
        segments.forEachIndexed { i, sec ->
            val sw = (sec * scale).toFloat()
            if (sw <= gap) { x += sw; return@forEachIndexed }
            p.color = if (i == 0) accent else palette.faint
            r.set(x, barTop, x + sw - gap, barTop + barH)
            c.drawRoundRect(r, 6f, 6f, p)
            x += sw
        }
        // 도착 지점
        val ax = (remain * scale).toFloat().coerceAtMost(w - 2f)
        p.color = palette.text
        c.drawRect(ax - 2f, barTop - 10f, ax + 2f, barTop + barH + 10f, p)
        p.typeface = font; p.textSize = h * 0.28f; p.color = palette.sub
        val label = "도착"
        val tw = p.measureText(label)
        c.drawText(label, (ax - tw).coerceAtLeast(0f), barTop - 16f, p)
    }
}
