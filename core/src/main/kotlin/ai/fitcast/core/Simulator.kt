package ai.fitcast.core

import java.io.File

/** 시간 계산으로 음성 출력을 흉내 낸다. 문장 길이 / 말하기 속도만큼 지나면 끝난 것으로 본다. */
class SimulatedSpeech(private val clock: SpeechClock) : SpeechSink {
    var now = 0L
    var currentId: String? = null; private set
    var currentText: String? = null; private set
    private var endMs = 0L
    var interruptions = 0; private set
    val spoken = mutableListOf<Pair<Long, String>>()

    override fun speak(utteranceId: String, text: String) {
        currentId = utteranceId; currentText = text
        endMs = now + (clock.seconds(text) * 1000).toLong()
        spoken += now to text
    }

    override fun stop() {
        if (currentId != null) interruptions++
        currentId = null; currentText = null
    }

    /** 끝난 발화가 있으면 그 id를 돌려준다. */
    fun poll(): String? {
        val id = currentId ?: return null
        if (now >= endMs) { currentId = null; currentText = null; return id }
        return null
    }
}

data class RunResult(
    val name: String,
    val speechSec: Int,
    val speechInHighSec: Int,
    val interruptions: Int,
    val heard: Map<String, Int>,
    val lastSpeechEndSec: Int?,
    val events: List<EngineEvent>,
) {
    fun value(items: List<BriefItem>): Double = items.sumOf { item ->
        val h = heard[item.id] ?: 0
        val w = when {
            h >= item.sentences.size -> Depth.FULL.weight
            h >= Depth.SUMMARY.sentenceCount(item.sentences.size) -> Depth.SUMMARY.weight
            h >= 1 -> Depth.HEADLINE.weight
            else -> 0.0
        }
        item.priority * w
    }
}

fun runScenario(name: String, items: List<BriefItem>, route: Route = CommuteScenario, make: (SpeechSink) -> BriefingPlayer): RunResult {
    val clock = SpeechClock()
    val speech = SimulatedSpeech(clock)
    val player = make(speech)
    val estimator = LoadEstimator()
    var speechSec = 0
    var highSec = 0
    var lastEnd: Int? = null
    for (t in 0..route.arriveSec) {
        speech.now = t * 1000L
        speech.poll()?.let { player.onSpeechDone(it); lastEnd = t }
        val s = route.snapshot(t)
        player.onTick(s)
        if (speech.currentId != null && !s.arrived) {
            speechSec++
            if (estimator.estimate(s).load == Load.HIGH) highSec++
        }
    }
    return RunResult(name, speechSec, highSec, speech.interruptions, player.heardSentences(), lastEnd, player.events)
}

private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

fun main(args: Array<String>) {
    val items = SampleBriefing.items
    val runs = listOf(
        runScenario("A. 일반 재생", items) { PlainPlayer(items, it) },
        runScenario("B. 위험 순간만 멈춤", items) { PlainPlayer(items, it, pauseOnHigh = true) },
        runScenario("C. 핏캐스트", items) { FitCastEngine(items, it) },
    )
    val fit = runs.last()
    val clock = SpeechClock()
    val totalSec = items.sumOf { clock.seconds(it.sentences) }
    val top = items.filter { it.priority == 5 }

    val sb = StringBuilder()
    sb.appendLine("# 핏캐스트 주행 시뮬레이션 결과")
    sb.appendLine()
    sb.appendLine("합성 출근 시나리오 ${CommuteScenario.arriveSec / 60}분(5분 시점 재탐색으로 2분 단축, 7∼9분 우천, 도시고속도로 IC 3곳, 신호 대기 4회). 브리핑 ${items.size}개 항목, 전부 읽으면 약 ${totalSec.toInt()}초.")
    sb.appendLine("실제 주행 기록·실제 TTS가 아니라 1초 단위 시간 계산 모의 실행이다. 말하기 속도 ${clock.charsPerSec}자/초 가정. 세 방식 모두 같은 부담 추정 규칙으로 채점했고, A·B는 우선순위 순서로 읽는다.")
    sb.appendLine()
    sb.appendLine("우선순위 가중 청취 가치 = 항목마다 (우선순위 1∼5) × (끝까지 들은 문장으로 정한 깊이 가중치)의 합. 가중치는 전체 문장 1.0, 절반 이상 0.8, 첫 문장만 0.5, 못 들음 0이다. 18개 항목의 우선순위 합이 ${items.sumOf { it.priority }}이라 최대값도 ${items.sumOf { it.priority }}이다. 중간에 끊긴 문장은 들은 것으로 세지 않는다.")
    sb.appendLine()
    sb.appendLine("| 지표 | " + runs.joinToString(" | ") { it.name } + " |")
    sb.appendLine("|---|" + runs.joinToString("|") { "---" } + "|")
    fun row(label: String, f: (RunResult) -> String) = sb.appendLine("| $label | " + runs.joinToString(" | ") { f(it) } + " |")
    row("운전 부담 큰 순간(HIGH)에 말한 시간") { "${it.speechInHighSec}초" }
    row("말하던 문장을 중간에 끊은 횟수") { "${it.interruptions}회" }
    row("전체 발화 시간") { "${it.speechSec}초" }
    row("한 문장이라도 들은 항목") { r -> "${items.count { (r.heard[it.id] ?: 0) > 0 }}/${items.size}" }
    row("우선순위 5 항목 끝까지 들음") { r -> "${top.count { (r.heard[it.id] ?: 0) >= it.sentences.size }}/${top.size}" }
    row("우선순위 가중 청취 가치(최대 ${items.sumOf { it.priority }})") { "%.1f".format(it.value(items)) }
    row("마지막 문장이 끝난 시각(도착 ${fmt(CommuteScenario.arriveSec * 1000L)})") { r -> r.lastSpeechEndSec?.let { fmt(it * 1000L) } ?: "-" }
    sb.appendLine()
    sb.appendLine("- A는 끊김이 없지만 HIGH 순간에도 계속 말한다. 끊김 0은 안전해서가 아니라 멈추지 않아서다.")
    sb.appendLine("- B는 HIGH에서 멈추지만, 곧 끊길 문장도 시작하고 분량을 조절하지 않는다.")
    sb.appendLine("- C는 끝까지 못 읽을 문장은 시작하지 않고, 남은 시간에 맞춰 항목별 분량(헤드라인·요약·전체)을 다시 배정한다.")
    sb.appendLine()
    appendRobustness(sb, items)
    sb.appendLine()
    sb.appendLine("## 핏캐스트 판단 기록")
    sb.appendLine()
    sb.appendLine("| 시각 | 종류 | 내용 |")
    sb.appendLine("|---|---|---|")
    for (e in fit.events) sb.appendLine("| ${fmt(e.tMs)} | ${e.kind} | ${e.detail.replace("|", "/")} |")

    print(sb)
    val out = args.firstOrNull()
    if (out != null) { File(out).parentFile?.mkdirs(); File(out).writeText(sb.toString()) }
}

/** 기준값을 위 한 경로에 맞춰 고르지 않았는지 보려고, 무작위 경로 여러 개에서 같은 비교를 돌린다. */
fun robustness(items: List<BriefItem>, seeds: LongRange): List<Triple<RunResult, RunResult, RunResult>> = seeds.map { seed ->
    val route = Commute.random(seed)
    Triple(
        runScenario("A", items, route) { PlainPlayer(items, it) },
        runScenario("B", items, route) { PlainPlayer(items, it, pauseOnHigh = true) },
        runScenario("C", items, route) { FitCastEngine(items, it) },
    )
}

private fun appendRobustness(sb: StringBuilder, items: List<BriefItem>) {
    val seeds = 1L..300L
    val rs = robustness(items, seeds)
    val n = rs.size
    fun mean(f: (RunResult) -> Double, pick: (Triple<RunResult, RunResult, RunResult>) -> RunResult) = rs.sumOf { f(pick(it)) } / n
    val picks = listOf<(Triple<RunResult, RunResult, RunResult>) -> RunResult>({ it.first }, { it.second }, { it.third })
    val shortRoutes = rs.filter { Commute.random(seeds.first + rs.indexOf(it)).arriveSec <= 15 * 60 }
    sb.appendLine("## 무작위 경로 ${n}개 비교")
    sb.appendLine()
    sb.appendLine("위 12분 경로 하나에 기준값을 맞춘 것이 아닌지 보려고, 길이 8∼30분·신호 대기·차로 변경·IC·주의 구간·재탐색(±1∼3분)·비를 seed ${seeds.first}∼${seeds.last}로 무작위로 만든 경로에서 같은 비교를 했다. 평균값이다.")
    sb.appendLine()
    sb.appendLine("| 지표(평균) | A. 일반 재생 | B. 위험 순간만 멈춤 | C. 핏캐스트 |")
    sb.appendLine("|---|---|---|---|")
    fun row(label: String, f: (RunResult) -> Double, fmt: String) =
        sb.appendLine("| $label | " + picks.joinToString(" | ") { fmt.format(mean(f, it)) } + " |")
    row("운전 부담 큰 순간에 말한 시간", { it.speechInHighSec.toDouble() }, "%.1f초")
    row("문장을 중간에 끊은 횟수", { it.interruptions.toDouble() }, "%.1f회")
    row("우선순위 가중 청취 가치(최대 ${items.sumOf { it.priority }})", { it.value(items) }, "%.1f")
    val cWins = rs.count { it.third.value(items) >= it.second.value(items) }
    val cFewer = rs.count { it.third.interruptions < it.second.interruptions }
    sb.appendLine()
    sb.appendLine("- C의 끊김이 B보다 적은 경로: ${cFewer}/${n}. C의 가중 청취 가치가 B 이상인 경로: ${cWins}/${n}.")
    if (shortRoutes.isNotEmpty()) {
        val sw = shortRoutes.count { it.third.value(items) >= it.second.value(items) }
        val gain = shortRoutes.sumOf { it.third.value(items) - it.second.value(items) } / shortRoutes.size
        sb.appendLine("- 15분 이하 짧은 경로 ${shortRoutes.size}개만 보면, C가 B 이상인 경로 ${sw}개, 가중 청취 가치 차이 평균 %+.1f.".format(gain))
    }
}
