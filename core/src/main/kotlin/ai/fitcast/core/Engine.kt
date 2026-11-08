package ai.fitcast.core

/** 음성 출력. 앱에서는 Pleos TextToSpeech SDK, 시뮬레이터에서는 시간 계산으로 대신한다. */
interface SpeechSink {
    fun speak(utteranceId: String, text: String)
    fun stop()
}

enum class VoiceCommand { NEXT, MORE, SAVE;
    companion object {
        /** STT 결과 문장에서 명령어를 찾는다. 못 찾으면 null. */
        fun parse(text: String): VoiceCommand? = when {
            listOf("다음", "넘겨", "건너").any { it in text } -> NEXT
            listOf("자세히", "더 들려", "전체").any { it in text } -> MORE
            listOf("저장", "나중에").any { it in text } -> SAVE
            else -> null
        }
    }
}

data class EngineEvent(val tMs: Long, val kind: String, val detail: String)

/** 브리핑 재생기 공통 계약. 시뮬레이터가 FitCast와 비교 기준(일반 재생)을 같은 조건으로 돌린다. */
interface BriefingPlayer {
    val events: List<EngineEvent>
    fun onTick(s: DriveSnapshot)
    fun onSpeechDone(utteranceId: String)
    /** 끝까지 들은 문장 수(항목별). */
    fun heardSentences(): Map<String, Int>
    /** 도착 후 화면으로 넘길 못 들은 항목. */
    fun leftovers(): List<BriefItem>
}

/**
 * 핏캐스트 엔진.
 * - HIGH: 읽던 문장을 바로 멈추고, 나중에 그 문장을 처음부터 다시 읽는다.
 * - MEDIUM: 읽던 문장은 마치되 새 문장은 시작하지 않는다.
 * - LOW·PARKED: 다음 문장을 읽는다. 단 곧 HIGH 구간에 들어가 끝까지 못 읽을 문장은 시작하지 않는다.
 * - 남은 시간이 바뀌면(재탐색·정체) 아직 시작하지 않은 항목의 분량을 다시 배정한다.
 */
class FitCastEngine(
    private val items: List<BriefItem>,
    private val sink: SpeechSink,
    private val estimator: LoadEstimator = LoadEstimator(),
    private val clock: SpeechClock = SpeechClock(),
    private val planner: Planner = Planner(clock),
    private val resumeHoldMs: Long = 2_500,
    private val initialPlayableRatio: Double = 0.6,
    private val fallbackRemainSec: Int = 900,
) : BriefingPlayer {
    override val events = mutableListOf<EngineEvent>()

    private val heard = mutableMapOf<String, Int>()
    private val done = mutableSetOf<String>()
    private var queue: ArrayDeque<PlanEntry> = ArrayDeque()
    private var current: PlanEntry? = null
    private var nextIdx = 0
    private var speaking: String? = null
    private var lastHighMs = Long.MIN_VALUE / 2
    private var lastPlanRemain: Int? = null
    private var lastTickMs: Long? = null
    private var drivingMs = 0L
    private var playableMs = 0L
    private var arrived = false
    private var lastLoad: Load? = null
    var lastVerdict: LoadVerdict? = null
        private set

    /** 오디오 포커스를 잠시 잃은 이유(예: 「내비 안내 음성」). null이면 포커스를 갖고 있다. 앱의 AudioFocus 콜백이 넣는다. */
    var audioHold: String? = null

    val playableRatio: Double
        get() = if (drivingMs < 60_000) initialPlayableRatio
        else (0.5 * initialPlayableRatio + 0.5 * playableMs.toDouble() / drivingMs)

    override fun onTick(s: DriveSnapshot) {
        // 도착 뒤에도 화면이 주차 여부를 따라가도록 판단만 갱신한다(말은 하지 않는다)
        if (arrived) { lastVerdict = estimator.estimate(s); return }
        val dt = lastTickMs?.let { s.tMs - it } ?: 0L
        lastTickMs = s.tMs

        if (s.arrived) {
            arrived = true
            if (speaking != null) { sink.stop(); speaking = null }
            log(s.tMs, "도착", "끝까지 못 들은 ${leftovers().size}개 항목을 주차 후 화면 목록으로 넘김")
            return
        }

        val est = estimator.estimate(s)
        // 내비 안내·Gleo 호출로 오디오 포커스를 잠시 잃으면 음량을 낮춰 겹쳐 말하지 않고, 운전 부담이 큰 순간과 똑같이 멈춘다
        val hold = audioHold
        val v = if (hold != null && est.load != Load.PARKED) LoadVerdict(Load.HIGH, listOf(hold) + est.reasons) else est
        lastVerdict = v
        if (v.load != lastLoad) {
            log(s.tMs, "부담 ${v.load}", v.reasons.joinToString(", "))
            lastLoad = v.load
        }
        if (v.load != Load.PARKED) {
            drivingMs += dt
            if (v.load == Load.LOW) playableMs += dt
            if (v.load == Load.MEDIUM) playableMs += (dt * 0.7).toLong()
        }

        maybeReplan(s)

        when (v.load) {
            Load.HIGH -> {
                lastHighMs = s.tMs
                if (speaking != null) {
                    sink.stop()
                    log(s.tMs, "멈춤", "${speaking} — ${v.reasons.first()}")
                    speaking = null // nextIdx를 올리지 않았으므로 같은 문장을 다시 읽는다
                }
            }
            // MEDIUM: 읽던 문장은 마저 읽고, 새 문장은 HIGH 구간 전에 끝낼 수 있을 때만 시작
            Load.MEDIUM -> if (speaking == null && s.tMs - lastHighMs >= resumeHoldMs) startNext(s, marginSec = 1.0, needForecast = true)
            Load.LOW, Load.PARKED -> {
                if (speaking == null && s.tMs - lastHighMs >= resumeHoldMs) startNext(s, marginSec = 1.0, needForecast = false)
            }
        }
    }

    override fun onSpeechDone(utteranceId: String) {
        if (utteranceId != speaking) return
        speaking = null
        val entry = current ?: return
        heard[entry.item.id] = (heard[entry.item.id] ?: 0) + 1
        nextIdx++
    }

    private fun maybeReplan(s: DriveSnapshot) {
        // 내비 경로가 없으면 한 번만 기본 시간(15분)으로 계획하고, 경로가 생기면 그때 다시 맞춘다
        val remain = s.remainSec ?: if (lastPlanRemain == null) fallbackRemainSec else return
        val prev = lastPlanRemain
        val drift = if (prev == null) Int.MAX_VALUE else {
            // 시간이 흐른 만큼 줄어드는 건 정상이므로, 예상 감소분과의 차이만 본다
            val elapsed = ((s.tMs - planAtMs) / 1000).toInt()
            kotlin.math.abs((prev - elapsed) - remain)
        }
        if (drift < 45 && s.tMs - planAtMs < 120_000) return
        val pending = items.filter { it.id !in done && it.id != current?.item?.id }
        val curLeft = current?.let { clock.seconds(it.sentences().drop(nextIdx)) } ?: 0.0
        val budget = planner.budget(remain, playableRatio) - curLeft
        val plan = planner.plan(pending, budget)
        queue = ArrayDeque(plan.entries)
        planAtMs = s.tMs
        lastPlanRemain = remain
        log(
            s.tMs, if (prev == null) "계획" else "재계획",
            "남은 ${remain / 60}분 ${remain % 60}초, 들을 수 있는 비율 ${"%.2f".format(playableRatio)}, 예산 ${budget.toInt()}초 → " +
                plan.entries.joinToString(" / ") { "${it.item.title}:${it.depth.label()}" }
        )
        // 현재 항목도 예산이 모자라면 문장 경계에서 줄인다
        current?.let { cur ->
            if (budget < 0 && cur.depth != Depth.HEADLINE) {
                val shorter = PlanEntry(cur.item, Depth.HEADLINE)
                if (shorter.sentences().size > nextIdx) {
                    current = shorter
                    log(s.tMs, "줄임", "${cur.item.title} → 헤드라인")
                }
            }
        }
    }

    private var planAtMs = 0L

    private fun startNext(s: DriveSnapshot, marginSec: Double, needForecast: Boolean) {
        var entry = current
        if (entry == null || nextIdx >= entry.sentences().size) {
            entry?.let { done += it.item.id }
            entry = queue.removeFirstOrNull() ?: run { current = null; return }
            current = entry
            nextIdx = 0
            log(s.tMs, "항목 시작", "${entry.item.title} (${entry.depth.label()})")
        }
        val sentence = entry.sentences()[nextIdx]
        val need = clock.seconds(sentence)
        val untilHigh = estimator.secondsUntilHigh(s)
        if (needForecast && untilHigh == null && s.nextManeuverDistanceM != null) return // 예측 불가면 보류
        if (untilHigh != null && untilHigh < need + marginSec) return // 끝까지 못 읽을 문장은 미룬다
        val id = "${entry.item.id}#$nextIdx"
        speaking = id
        sink.speak(id, sentence)
    }

    override fun heardSentences(): Map<String, Int> = heard.toMap()

    override fun leftovers(): List<BriefItem> = items.filter { (heard[it.id] ?: 0) < it.sentences.size }

    /** 말로 하는 명령. 화면을 보지 않고 「다음」「자세히」「저장」만으로 조작한다. */
    fun command(c: VoiceCommand, tMs: Long) {
        val cur = current ?: return
        when (c) {
            VoiceCommand.NEXT -> {
                if (speaking != null) { sink.stop(); speaking = null }
                done += cur.item.id
                current = null
                log(tMs, "명령", "다음 — ${cur.item.title} 건너뜀")
            }
            VoiceCommand.MORE -> {
                current = PlanEntry(cur.item, Depth.FULL)
                log(tMs, "명령", "자세히 — ${cur.item.title} 전체로")
            }
            VoiceCommand.SAVE -> {
                savedForLater += cur.item
                log(tMs, "명령", "저장 — ${cur.item.title} 주차 후 보기에 추가")
            }
        }
    }

    val savedForLater = mutableListOf<BriefItem>()
    /** 화면 표시용: 지금 항목 다음에 읽을 계획. */
    val upcoming: List<PlanEntry> get() = queue.toList()
    val currentDepth: Depth? get() = current?.depth
    /** 멈춘 뒤 다시 읽을 문장(아직 시작하지 않은 다음 문장). */
    val pendingSentence: String? get() = current?.sentences()?.getOrNull(nextIdx)
    /** 지금 항목에서 몇 번째 문장(0부터)과 전체 문장 수. 화면 표시용. */
    val sentencePosition: Pair<Int, Int>? get() = current?.let { nextIdx to it.sentences().size }
    val currentTitle: String? get() = current?.item?.title
    val currentEntry: PlanEntry? get() = current

    private fun log(t: Long, kind: String, detail: String) { events += EngineEvent(t, kind, detail) }
}

fun Depth.label() = when (this) {
    Depth.SKIP -> "생략"; Depth.HEADLINE -> "헤드라인"; Depth.SUMMARY -> "요약"; Depth.FULL -> "전체"
}

/**
 * 비교 기준. 우선순위 순서로 전부 읽는다.
 * pauseOnHigh=false: 지금의 일반 음성 재생(주행 상황을 보지 않음)
 * pauseOnHigh=true: 같은 부담 추정기로 HIGH일 때만 멈추는 단순 안전 재생(분량 계획·예측 없음)
 */
class PlainPlayer(
    private val items: List<BriefItem>,
    private val sink: SpeechSink,
    private val pauseOnHigh: Boolean = false,
    private val estimator: LoadEstimator = LoadEstimator(),
) : BriefingPlayer {
    override val events = mutableListOf<EngineEvent>()
    private val ordered = items.sortedByDescending { it.priority }
    private val flat = ordered.flatMap { item -> item.sentences.indices.map { item to it } }
    private var idx = 0
    private var speaking: String? = null
    private val heard = mutableMapOf<String, Int>()
    private var arrived = false

    override fun onTick(s: DriveSnapshot) {
        if (arrived) return
        if (s.arrived) {
            arrived = true
            if (speaking != null) { sink.stop(); speaking = null }
            return
        }
        if (pauseOnHigh) {
            val load = estimator.estimate(s).load
            if (load == Load.HIGH) {
                if (speaking != null) { sink.stop(); speaking = null }
                return
            }
        }
        if (speaking == null && idx < flat.size) {
            val (item, i) = flat[idx]
            speaking = "${item.id}#$i"
            sink.speak(speaking!!, item.sentences[i])
        }
    }

    override fun onSpeechDone(utteranceId: String) {
        if (utteranceId != speaking) return
        val (item, _) = flat[idx]
        heard[item.id] = (heard[item.id] ?: 0) + 1
        idx++
        speaking = null
    }

    override fun heardSentences(): Map<String, Int> = heard.toMap()
    override fun leftovers(): List<BriefItem> = items.filter { (heard[it.id] ?: 0) < it.sentences.size }
}
