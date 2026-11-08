package ai.fitcast.core

import kotlin.math.max

/** 문장을 읽는 데 걸리는 시간 추정. 실기기에서는 TTS onStart∼onDone 실측으로 보정한다. */
class SpeechClock(
    val charsPerSec: Double = 5.5,
    val pauseSec: Double = 0.4,
) {
    fun seconds(sentence: String): Double = sentence.count { !it.isWhitespace() } / charsPerSec + pauseSec
    fun seconds(sentences: List<String>): Double = sentences.sumOf { seconds(it) }
}

data class PlanEntry(val item: BriefItem, val depth: Depth) {
    fun sentences(): List<String> = item.sentences.take(depth.sentenceCount(item.sentences.size))
}

data class Plan(val entries: List<PlanEntry>, val budgetSec: Double, val usedSec: Double)

/**
 * 도착 시각에 맞춰 무엇을 얼마나 읽을지 정한다.
 *
 * 예산 = 남은 시간 × 들을 수 있는 비율 − 도착 여유.
 * 들을 수 있는 비율은 지금까지 주행에서 운전 부담이 낮았던 시간의 비율로 계속 갱신한다.
 * 우선순위×깊이 가중치의 가치를 초당 가치가 큰 순서로 한 단계씩 올리는 탐욕 배정이다.
 */
class Planner(private val clock: SpeechClock, private val arrivalMarginSec: Double = 30.0) {

    fun budget(remainSec: Int, playableRatio: Double): Double =
        max(0.0, remainSec * playableRatio - arrivalMarginSec)

    fun plan(items: List<BriefItem>, budgetSec: Double): Plan {
        val depth = items.associate { it.id to Depth.SKIP }.toMutableMap()
        var used = 0.0

        fun cost(item: BriefItem, d: Depth) = clock.seconds(item.sentences.take(d.sentenceCount(item.sentences.size)))

        while (true) {
            var best: Pair<BriefItem, Depth>? = null
            var bestRate = 0.0
            for (item in items) {
                val cur = depth.getValue(item.id)
                val nxt = cur.next() ?: continue
                val dCost = cost(item, nxt) - cost(item, cur)
                if (used + dCost > budgetSec) continue
                val dValue = item.priority * (nxt.weight - cur.weight)
                // 헤드라인을 먼저 고루 깔도록 첫 단계에 가산점을 준다
                val rate = dValue / max(dCost, 0.1) * (if (cur == Depth.SKIP) 2.0 else 1.0)
                if (rate > bestRate) { bestRate = rate; best = item to nxt }
            }
            val (item, nxt) = best ?: break
            used += cost(item, nxt) - cost(item, depth.getValue(item.id))
            depth[item.id] = nxt
        }

        val entries = items
            .filter { depth.getValue(it.id) != Depth.SKIP }
            .sortedWith(compareByDescending<BriefItem> { it.priority }.thenBy { items.indexOf(it) })
            .map { PlanEntry(it, depth.getValue(it.id)) }
        return Plan(entries, budgetSec, used)
    }
}
