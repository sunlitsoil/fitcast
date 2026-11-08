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
 *
 * [Commute.random]은 같은 구조에서 길이·신호 대기·차로 변경·재탐색·비를 무작위로 바꾼 경로를 만든다.
 * 기준값을 한 경로에 맞춰 고르지 않았는지 확인하는 데 쓴다.
 */
/** 시뮬레이터가 1초마다 읽는 주행 경로. */
interface Route {
    val arriveSec: Int
    fun snapshot(t: Int): DriveSnapshot
}

class Commute(
    override val arriveSec: Int,
    private val initialEtaSec: Int,
    private val rerouteAtSec: Int,
    private val maneuvers: List<Pair<Int, ManeuverKind>>,
    private val cautions: List<Pair<Int, CautionKind>>,
    private val stops: List<IntRange>,
    private val laneChanges: List<IntRange>,
    private val rain: IntRange?,
    private val highway: IntRange?,
    private val speedAfterHighway: Double = 40.0,
) : Route {
    private val turnWindows = maneuvers.filter { it.second != ManeuverKind.ARRIVE }.map { (it.first - 6)..(it.first + 2) }

    private fun speedAt(t: Int): Double = when {
        stops.any { t in it } -> 0.0
        stops.any { t in (it.first - 8) until it.first } -> 15.0 // 감속
        highway != null && t in highway -> 78.0
        highway != null && t in (highway.first - 20) until highway.first -> 55.0
        highway != null && t > highway.last -> speedAfterHighway
        else -> 40.0
    }

    /** t초까지 달린 거리(m). 1초 적분. */
    private val distanceTable: DoubleArray = DoubleArray(arriveSec + 2).also { arr ->
        for (t in 1..arriveSec + 1) arr[t] = arr[t - 1] + speedAt(t - 1) / 3.6
    }

    override fun snapshot(t: Int): DriveSnapshot {
        val remain = if (t < rerouteAtSec) initialEtaSec - t else arriveSec - t
        val here = distanceTable[t]
        val nextM = maneuvers.firstOrNull { it.first > t }
        val caution = cautions.firstOrNull { it.first > t }
        val inTurn = turnWindows.any { t in it }
        val inStopDecel = stops.any { t in (it.first - 8)..it.first }
        val camera = cautions.firstOrNull { it.second == CautionKind.ENFORCEMENT && it.first > t }
        return DriveSnapshot(
            tMs = t * 1000L,
            gear = GearState.DRIVE,
            turnSignal = when {
                inTurn -> Turn.RIGHT
                laneChanges.any { t in it } -> Turn.LEFT
                else -> Turn.OFF
            },
            brake = inStopDecel || stops.any { t in it },
            steeringDeg = if (inTurn && t >= (nextM?.first ?: 0) - 3) 90f else 0f,
            wiperOn = rain != null && t in rain,
            speedKmh = speedAt(t),
            speedLimitKmh = if (highway != null && t in highway) 80 else 50,
            nextManeuverDistanceM = nextM?.let { distanceTable[it.first] - here },
            nextManeuver = nextM?.second ?: ManeuverKind.STRAIGHT,
            cautionDistanceM = caution?.let { (distanceTable[it.first] - here).toInt() },
            caution = caution?.second ?: CautionKind.NONE,
            cameraDistanceM = camera?.let { (distanceTable[it.first] - here).toInt() },
            overSpeed = false,
            remainSec = remain,
            arrived = t >= arriveSec,
        )
    }

    companion object {
        /**
         * 무작위 출근 경로. 길이 8∼30분, 앞쪽 도심·가운데 도시고속도로(경로의 절반 확률)·뒤쪽 도심.
         * 회전·IC·신호 대기·차로 변경·주의 구간·재탐색(±1∼3분)·비를 seed로 정한다.
         */
        fun random(seed: Long): Commute {
            val r = kotlin.random.Random(seed)
            val arrive = r.nextInt(8 * 60, 30 * 60 + 1)
            val hw = if (r.nextBoolean()) (arrive * 0.3).toInt()..(arrive * 0.75).toInt() else null
            val maneuvers = mutableListOf<Pair<Int, ManeuverKind>>()
            var t = r.nextInt(40, 120)
            while (t < arrive - 40) {
                val onHw = hw != null && t in hw
                maneuvers += t to if (onHw || r.nextInt(5) == 0) ManeuverKind.COMPLEX else ManeuverKind.SIMPLE_TURN
                t += if (onHw) r.nextInt(90, 200) else r.nextInt(60, 180)
            }
            maneuvers += arrive to ManeuverKind.ARRIVE
            val stops = mutableListOf<IntRange>()
            t = r.nextInt(30, 90)
            while (t < arrive - 40) {
                if (hw == null || t !in (hw.first - 30)..hw.last) {
                    val len = r.nextInt(15, 50)
                    if (maneuvers.none { it.first in (t - 10)..(t + len + 10) }) stops += t..(t + len)
                }
                t += r.nextInt(70, 200)
            }
            val lanes = (0 until arrive / 90).map { r.nextInt(30, arrive - 20) }.sorted()
                .filter { lc -> stops.none { lc in (it.first - 10)..(it.last + 5) } }.map { it..(it + r.nextInt(4, 8)) }
            val cautions = (0 until r.nextInt(0, 4)).map {
                r.nextInt(60, arrive - 30) to listOf(CautionKind.CHILD_OR_ACCIDENT, CautionKind.ENFORCEMENT, CautionKind.ROAD_HAZARD)[r.nextInt(3)]
            }.sortedBy { it.first }
            val reroute = if (r.nextInt(3) == 0) null else r.nextInt(arrive / 4, arrive / 2)
            val etaShift = if (reroute == null) 0 else listOf(-1, 1)[r.nextInt(2)] * r.nextInt(60, 181)
            val rain = if (r.nextInt(3) == 0) r.nextInt(0, arrive / 2).let { it..(it + r.nextInt(60, arrive / 2)) } else null
            return Commute(
                arriveSec = arrive, initialEtaSec = arrive - etaShift, rerouteAtSec = reroute ?: 0,
                maneuvers = maneuvers, cautions = cautions, stops = stops, laneChanges = lanes, rain = rain, highway = hw,
            )
        }
    }
}

/** 문서·테스트에 쓰는 고정 12분 출근 경로(위 설명). */
val CommuteScenario: Commute = Commute(
    arriveSec = 720,
    initialEtaSec = 840,
    rerouteAtSec = 300,
    maneuvers = listOf(
        70 to ManeuverKind.SIMPLE_TURN,
        190 to ManeuverKind.SIMPLE_TURN,
        320 to ManeuverKind.COMPLEX,   // 도시고속도로 진입
        450 to ManeuverKind.COMPLEX,   // 분기
        560 to ManeuverKind.COMPLEX,   // 진출
        650 to ManeuverKind.SIMPLE_TURN,
        720 to ManeuverKind.ARRIVE,
    ),
    cautions = listOf(130 to CautionKind.CHILD_OR_ACCIDENT, 400 to CautionKind.ENFORCEMENT),
    stops = listOf(40..70, 150..175, 250..280, 600..630), // 신호 대기(초)
    laneChanges = listOf(335..341, 380..385, 470..476, 520..525),
    rain = 420..540,
    highway = 320 until 560,
    speedAfterHighway = 35.0,
)

/**
 * 시연 영상용 5분 경로. 2분에 재탐색으로 도착이 1분 30초 당겨지고, 4∼4분 40초에 비가 온다.
 * 앱의 「시연 주행」 버튼이 이 경로를 실제 시간으로 흘려보내고, 실제 차량 신호(콘솔에서 바꾼 방향지시등·기어·와이퍼)를 그 위에 덮어쓴다.
 */
val DemoRoute: Commute = Commute(
    arriveSec = 300,
    initialEtaSec = 390,
    rerouteAtSec = 120,
    maneuvers = listOf(
        45 to ManeuverKind.SIMPLE_TURN,
        105 to ManeuverKind.COMPLEX,
        175 to ManeuverKind.COMPLEX,
        250 to ManeuverKind.SIMPLE_TURN,
        300 to ManeuverKind.ARRIVE,
    ),
    cautions = listOf(215 to CautionKind.CHILD_OR_ACCIDENT),
    stops = listOf(12..32, 262..280),
    laneChanges = listOf(140..145),
    rain = 240..280,
    highway = 105 until 175,
)
