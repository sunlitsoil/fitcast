package ai.fitcast.core

import kotlin.math.abs

/**
 * 운전 부담 추정기.
 *
 * 규칙 기반이다. 판단 이유를 그대로 남겨서, 왜 멈췄는지 사람이 확인할 수 있게 한다.
 * 기준값은 시제품용 초기값이며 실차·에뮬레이터 주행 기록으로 조정해야 한다.
 */
class LoadEstimator(
    private val maneuverHighM: Double = 300.0,
    private val complexManeuverHighM: Double = 600.0,
    private val maneuverMediumM: Double = 900.0,
    private val cautionHighM: Int = 300,
    private val cameraHighM: Int = 300,
    private val steeringHighDeg: Float = 45f,
    /** 와이퍼가 켜지면(비·눈) 거리 기준을 이만큼 늘린다. */
    private val weatherFactor: Double = 1.5,
) {
    fun estimate(s: DriveSnapshot): LoadVerdict {
        if (s.gear == GearState.PARK) return LoadVerdict(Load.PARKED, listOf("P단 주차"))
        // 신호 대기: 멈춰 서서 브레이크를 밟고 있고 방향지시등이 꺼져 있으면 말하기 좋은 틈으로 본다
        val stopped = (s.speedKmh ?: Double.MAX_VALUE) < 3 && s.brake
        if (stopped && s.turnSignal == Turn.OFF && s.gear != GearState.REVERSE && !s.overSpeed) {
            return LoadVerdict(Load.LOW, listOf("정차(신호 대기)"))
        }

        val high = mutableListOf<String>()
        val medium = mutableListOf<String>()
        val w = if (s.wiperOn) weatherFactor else 1.0

        if (s.gear == GearState.REVERSE) high += "후진"
        if (s.turnSignal != Turn.OFF) high += "방향지시등 ${if (s.turnSignal == Turn.LEFT) "좌" else "우"}"
        if (abs(s.steeringDeg) >= steeringHighDeg) high += "조향각 ${abs(s.steeringDeg).toInt()}°"

        s.nextManeuverDistanceM?.let { d ->
            if (s.nextManeuver != ManeuverKind.STRAIGHT) {
                val highM = (if (s.nextManeuver == ManeuverKind.COMPLEX) complexManeuverHighM else maneuverHighM) * w
                when {
                    d <= highM -> high += "안내 지점 ${d.toInt()}m 앞(${s.nextManeuver.label()})"
                    d <= maneuverMediumM * w -> medium += "안내 지점 ${d.toInt()}m 앞"
                }
            }
        }
        s.cautionDistanceM?.let { d ->
            if (s.caution != CautionKind.NONE && d <= cautionHighM * w) high += "주의 구간 ${d}m 앞(${s.caution.label()})"
        }
        s.cameraDistanceM?.let { d ->
            if (d <= cameraHighM * w && s.overSpeed) high += "단속 카메라 ${d}m 앞·과속"
            else if (d <= cameraHighM * w) medium += "단속 카메라 ${d}m 앞"
        }
        if (s.overSpeed) high += "제한속도 초과"
        val v = s.speedKmh
        if (s.brake && v != null && v >= 40) high += "고속 제동(${v.toInt()}km/h)"
        if (s.wiperOn) medium += "와이퍼 작동(우천 추정)"

        return when {
            high.isNotEmpty() -> LoadVerdict(Load.HIGH, high + medium)
            medium.isNotEmpty() -> LoadVerdict(Load.MEDIUM, medium)
            else -> LoadVerdict(Load.LOW, listOf(if (v != null && v < 5) "정차·서행" else "평이한 주행"))
        }
    }

    /**
     * 지금 속도로 가면 몇 초 뒤 HIGH 구간에 들어가는지. 문장을 시작하기 전에
     * "끝까지 못 읽을 문장은 시작하지 않는다"는 판단에 쓴다. 속도를 모르면 null.
     */
    fun secondsUntilHigh(s: DriveSnapshot): Double? {
        val v = s.speedKmh ?: return null
        // 신호 대기 중에는 언제 출발할지 알 수 없어 예측하지 않는다. 출발 후 HIGH가 되면 멈추고 그 문장을 다시 읽는다
        if (v < 3) return null
        val mps = v / 3.6
        val w = if (s.wiperOn) weatherFactor else 1.0
        val gaps = mutableListOf<Double>()
        s.nextManeuverDistanceM?.let { d ->
            if (s.nextManeuver != ManeuverKind.STRAIGHT) {
                val th = (if (s.nextManeuver == ManeuverKind.COMPLEX) complexManeuverHighM else maneuverHighM) * w
                gaps += d - th
            }
        }
        s.cautionDistanceM?.let { d -> if (s.caution != CautionKind.NONE) gaps += d - cautionHighM * w }
        if (gaps.isEmpty()) return null
        // 이미 기준 거리 안(예: 회전 직전 신호 대기)이면 출발하는 즉시 HIGH다
        return maxOf(0.0, gaps.min()) / mps
    }

    private fun ManeuverKind.label() = when (this) {
        ManeuverKind.COMPLEX -> "IC·분기·회전교차로"
        ManeuverKind.SIMPLE_TURN -> "회전"
        ManeuverKind.ARRIVE -> "도착"
        ManeuverKind.STRAIGHT -> "직진"
    }

    private fun CautionKind.label() = when (this) {
        CautionKind.CHILD_OR_ACCIDENT -> "어린이보호·사고다발"
        CautionKind.ROAD_HAZARD -> "도로 위험"
        CautionKind.ENFORCEMENT -> "단속"
        CautionKind.NONE -> ""
    }
}
