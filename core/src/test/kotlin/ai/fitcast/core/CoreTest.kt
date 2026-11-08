package ai.fitcast.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadEstimatorTest {
    private val est = LoadEstimator()

    @Test fun parkIsParked() = assertEquals(Load.PARKED, est.estimate(DriveSnapshot(0, gear = GearState.PARK)).load)

    @Test fun turnSignalIsHigh() = assertEquals(Load.HIGH, est.estimate(DriveSnapshot(0, turnSignal = Turn.LEFT)).load)

    @Test fun complexManeuverNearIsHigh() {
        val s = DriveSnapshot(0, speedKmh = 70.0, nextManeuverDistanceM = 500.0, nextManeuver = ManeuverKind.COMPLEX)
        assertEquals(Load.HIGH, est.estimate(s).load)
    }

    @Test fun simpleTurnFarIsMediumThenLow() {
        assertEquals(Load.MEDIUM, est.estimate(DriveSnapshot(0, nextManeuverDistanceM = 700.0, nextManeuver = ManeuverKind.SIMPLE_TURN)).load)
        assertEquals(Load.LOW, est.estimate(DriveSnapshot(0, nextManeuverDistanceM = 2000.0, nextManeuver = ManeuverKind.SIMPLE_TURN)).load)
    }

    @Test fun rainWidensThreshold() {
        val dry = DriveSnapshot(0, nextManeuverDistanceM = 400.0, nextManeuver = ManeuverKind.SIMPLE_TURN)
        assertEquals(Load.MEDIUM, est.estimate(dry).load)
        assertEquals(Load.HIGH, est.estimate(dry.copy(wiperOn = true)).load)
    }

    @Test fun secondsUntilHigh() {
        // 72km/h = 20m/s, 회전 900m 앞, HIGH 기준 300m → 30초
        val s = DriveSnapshot(0, speedKmh = 72.0, nextManeuverDistanceM = 900.0, nextManeuver = ManeuverKind.SIMPLE_TURN)
        assertEquals(30.0, est.secondsUntilHigh(s)!!, 0.01)
    }
}

class PlannerTest {
    private val clock = SpeechClock()
    private val planner = Planner(clock)
    private val items = SampleBriefing.items

    @Test fun bigBudgetReadsEverything() {
        val plan = planner.plan(items, 10_000.0)
        assertTrue(plan.entries.all { it.depth == Depth.FULL })
        assertEquals(items.size, plan.entries.size)
    }

    @Test fun planNeverExceedsBudget() {
        for (b in listOf(0.0, 20.0, 60.0, 120.0, 240.0, 400.0)) {
            val plan = planner.plan(items, b)
            assertTrue(plan.usedSec <= b + 1e-9, "budget $b used ${plan.usedSec}")
        }
    }

    @Test fun smallBudgetKeepsTopPriorityFirst() {
        val plan = planner.plan(items, 40.0)
        assertTrue(plan.entries.isNotEmpty())
        assertEquals(5, plan.entries.first().item.priority)
    }

    @Test fun budgetShrinksWithLowerPlayableRatio() {
        assertTrue(planner.budget(600, 0.4) < planner.budget(600, 0.8))
    }
}

class EngineTest {
    @Test fun fitcastNeverSpeaksDuringHighLoad() {
        val r = runScenario("fit", SampleBriefing.items) { FitCastEngine(SampleBriefing.items, it) }
        assertEquals(0, r.speechInHighSec)
    }

    @Test fun plainSpeaksDuringHighLoad() {
        val items = SampleBriefing.items
        assertTrue(runScenario("plain", items) { PlainPlayer(items, it) }.speechInHighSec > 0)
    }

    @Test fun fitcastBeatsSafeBaseline() {
        val items = SampleBriefing.items
        val safe = runScenario("safe", items) { PlainPlayer(items, it, pauseOnHigh = true) }
        val fit = runScenario("fit", items) { FitCastEngine(items, it) }
        assertEquals(0, safe.speechInHighSec)
        assertTrue(fit.interruptions < safe.interruptions, "fit ${fit.interruptions} safe ${safe.interruptions}")
        // 끊김을 줄이면서 우선순위 가중 가치는 비슷하게(5% 이내) 유지하고, 더 많은 항목을 다룬다
        assertTrue(fit.value(items) >= 0.95 * safe.value(items), "fit ${fit.value(items)} safe ${safe.value(items)}")
        val covered = { r: RunResult -> items.count { (r.heard[it.id] ?: 0) > 0 } }
        assertTrue(covered(fit) >= covered(safe), "fit ${covered(fit)} safe ${covered(safe)}")
    }

    @Test fun randomRoutesKeepSafetyAndFewerCuts() {
        val items = SampleBriefing.items
        val rs = robustness(items, 1L..60L)
        // 어느 경로에서도 운전 부담 큰 순간에는 말하지 않는다
        assertTrue(rs.all { it.third.speechInHighSec == 0 })
        // 끊김은 평균적으로 단순 멈춤의 절반 아래, 가중 청취 가치는 평균 3% 안
        val cuts = { pick: (Triple<RunResult, RunResult, RunResult>) -> RunResult -> rs.sumOf { pick(it).interruptions }.toDouble() / rs.size }
        assertTrue(cuts { it.third } < 0.6 * cuts { it.second }, "C ${cuts { it.third }} B ${cuts { it.second }}")
        val v = { pick: (Triple<RunResult, RunResult, RunResult>) -> RunResult -> rs.sumOf { pick(it).value(items) } / rs.size }
        assertTrue(v { it.third } >= 0.97 * v { it.second }, "C ${v { it.third }} B ${v { it.second }}")
    }

    @Test fun audioFocusLossPausesAndResumesSameSentence() {
        val spoken = mutableListOf<String>()
        var stops = 0
        val sink = object : SpeechSink {
            override fun speak(utteranceId: String, text: String) { spoken += utteranceId }
            override fun stop() { stops++ }
        }
        val engine = FitCastEngine(SampleBriefing.items, sink)
        engine.onTick(DriveSnapshot(0, speedKmh = 30.0, remainSec = 900))
        val first = spoken.single()
        engine.audioHold = "내비 안내 음성"
        engine.onTick(DriveSnapshot(500, speedKmh = 30.0, remainSec = 900))
        assertEquals(1, stops)
        engine.onTick(DriveSnapshot(4_000, speedKmh = 30.0, remainSec = 896))
        assertEquals(1, spoken.size) // 포커스를 되찾기 전에는 다시 말하지 않는다
        engine.audioHold = null
        engine.onTick(DriveSnapshot(7_000, speedKmh = 30.0, remainSec = 893))
        assertEquals(first, spoken.last())
    }

    @Test fun interruptedSentenceIsRepeated() {
        val clock = SpeechClock()
        val spoken = mutableListOf<String>()
        val sink = object : SpeechSink {
            override fun speak(utteranceId: String, text: String) { spoken += utteranceId }
            override fun stop() {}
        }
        val engine = FitCastEngine(SampleBriefing.items, sink, clock = clock)
        engine.onTick(DriveSnapshot(0, speedKmh = 30.0, remainSec = 900))
        val first = spoken.single()
        engine.onTick(DriveSnapshot(1_000, speedKmh = 30.0, turnSignal = Turn.LEFT, remainSec = 899))
        engine.onTick(DriveSnapshot(5_000, speedKmh = 30.0, remainSec = 895))
        assertEquals(first, spoken.last())
    }
}

class VoiceCommandTest {
    @Test fun parse() {
        assertEquals(VoiceCommand.NEXT, VoiceCommand.parse("다음 거"))
        assertEquals(VoiceCommand.MORE, VoiceCommand.parse("자세히 알려 줘"))
        assertEquals(VoiceCommand.SAVE, VoiceCommand.parse("이거 나중에 볼래"))
        assertEquals(null, VoiceCommand.parse("에어컨 켜 줘"))
    }
}
