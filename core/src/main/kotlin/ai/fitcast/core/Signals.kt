package ai.fitcast.core

/** 차량·내비에서 들어오는 신호를 한 시점으로 모은 값. 앱에서는 Pleos SDK 콜백이 이 값을 갱신한다. */
enum class Turn { OFF, LEFT, RIGHT }

enum class GearState { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 다음 안내 지점의 성격. IC 진출입·회전교차로·유턴·분기·톨게이트는 복잡 구간으로 본다. */
enum class ManeuverKind { STRAIGHT, SIMPLE_TURN, COMPLEX, ARRIVE }

/** 주의 구간 성격. Pleos NaviHelper CautionPointType을 묶은 것. */
enum class CautionKind { NONE, CHILD_OR_ACCIDENT, ROAD_HAZARD, ENFORCEMENT }

data class DriveSnapshot(
    val tMs: Long,
    val gear: GearState = GearState.DRIVE,
    val turnSignal: Turn = Turn.OFF,
    val brake: Boolean = false,
    val steeringDeg: Float = 0f,
    val wiperOn: Boolean = false,
    /** FusedLocation 연속 좌표에서 계산한 속도. 모르면 null. */
    val speedKmh: Double? = null,
    val speedLimitKmh: Int? = null,
    val nextManeuverDistanceM: Double? = null,
    val nextManeuver: ManeuverKind = ManeuverKind.STRAIGHT,
    val cautionDistanceM: Int? = null,
    val caution: CautionKind = CautionKind.NONE,
    val cameraDistanceM: Int? = null,
    val overSpeed: Boolean = false,
    /** 목적지까지 남은 시간(초). NaviHelper DrivingInfo.destination.duration. */
    val remainSec: Int? = null,
    val arrived: Boolean = false,
)

/** 주행 부담 단계. PARKED는 화면 글까지 허용되는 정차·주차 상태. */
enum class Load { PARKED, LOW, MEDIUM, HIGH }

data class LoadVerdict(val load: Load, val reasons: List<String>)
