package ai.fitcast.core

/**
 * 합성 출근 시나리오(약 12분). 실제 주행 기록이 아니라, 에뮬레이터·실차 기록을 받기 전까지
 * 엔진 동작을 재현 가능하게 확인하려고 만든 시간표다.
 *
 * 0∼300초 도심(신호 정지 3회, 회전 2회, 어린이보호구역 1곳)
 * 300초 내비 재탐색으로 남은 시간 2분 단축
 * 300∼560초 도시고속도로(IC 진입·분기·진출, 단속 카메라, 차선 변경)
 * 420∼540초 비(와이퍼)
 * 560∼720초 도심(회전 1회, 정지 1회) → 720초 도착
 */
object CommuteScenario {
    const val ARRIVE_SEC = 720
    private const val INITIAL_ETA_SEC = 840
    private const val REROUTE_AT_SEC = 300
    private const val REROUTE_SAVING_SEC = 120

    private data class Point(val atSec: Int, val kind: ManeuverKind)
    private data class Caution(val atSec: Int, val kind: CautionKind)

    private val maneuvers = listOf(
        Point(70, ManeuverKind.SIMPLE_TURN),
        Point(190, ManeuverKind.SIMPLE_TURN),
        Point(320, ManeuverKind.COMPLEX),   // 도시고속도로 진입
        Point(450, ManeuverKind.COMPLEX),   // 분기
        Point(560, ManeuverKind.COMPLEX),   // 진출
        Point(650, ManeuverKind.SIMPLE_TURN),
        Point(720, ManeuverKind.ARRIVE),
    )
    private val cautions = listOf(Caution(130, CautionKind.CHILD_OR_ACCIDENT), Caution(400, CautionKind.ENFORCEMENT))
    private val stops = listOf(40..70, 150..175, 250..280, 600..630) // 신호 대기(초)
    private val laneChanges = listOf(335..341, 380..385, 470..476, 520..525)
    private val turnWindows = maneuvers.filter { it.kind != ManeuverKind.ARRIVE }.map { (it.atSec - 6)..(it.atSec + 2) }

    private fun speedAt(t: Int): Double = when {
        stops.any { t in it } -> 0.0
        stops.any { t in (it.first - 8) until it.first } -> 15.0 // 감속
        t < 300 -> 40.0
        t < 320 -> 55.0
        t < 560 -> 78.0
        else -> 35.0
    }

    /** t초까지 달린 거리(m). 1초 적분. */
    private val distanceTable: DoubleArray = DoubleArray(ARRIVE_SEC + 2).also { arr ->
        for (t in 1..ARRIVE_SEC + 1) arr[t] = arr[t - 1] + speedAt(t - 1) / 3.6
    }

    fun snapshot(t: Int): DriveSnapshot {
        val remain = if (t < REROUTE_AT_SEC) INITIAL_ETA_SEC - t else ARRIVE_SEC - t
        val here = distanceTable[t]
        val nextM = maneuvers.firstOrNull { it.atSec > t }
        val caution = cautions.firstOrNull { it.atSec > t }
        val speed = speedAt(t)
        val inTurn = turnWindows.any { t in it }
        val inStopDecel = stops.any { t in (it.first - 8)..it.first }
        val camera = cautions.firstOrNull { it.kind == CautionKind.ENFORCEMENT && it.atSec > t }
        return DriveSnapshot(
            tMs = t * 1000L,
            gear = GearState.DRIVE,
            turnSignal = when {
                inTurn -> Turn.RIGHT
                laneChanges.any { t in it } -> Turn.LEFT
                else -> Turn.OFF
            },
            brake = inStopDecel || stops.any { t in it },
            steeringDeg = if (inTurn && t >= (nextM?.atSec ?: 0) - 3) 90f else 0f,
            wiperOn = t in 420..540,
            speedKmh = speed,
            speedLimitKmh = if (t in 300..560) 80 else 50,
            nextManeuverDistanceM = nextM?.let { distanceTable[it.atSec] - here },
            nextManeuver = nextM?.kind ?: ManeuverKind.STRAIGHT,
            cautionDistanceM = caution?.let { (distanceTable[it.atSec] - here).toInt() },
            caution = caution?.kind ?: CautionKind.NONE,
            cameraDistanceM = camera?.let { (distanceTable[it.atSec] - here).toInt() },
            overSpeed = false,
            remainSec = remain,
            arrived = t >= ARRIVE_SEC,
        )
    }
}
