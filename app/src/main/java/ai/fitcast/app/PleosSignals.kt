package ai.fitcast.app

import ai.fitcast.core.CautionKind
import ai.fitcast.core.DriveSnapshot
import ai.fitcast.core.GearState
import ai.fitcast.core.ManeuverKind
import ai.fitcast.core.Turn
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.util.Log
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pleos Connect SDK 콜백을 모아 core의 DriveSnapshot을 만든다.
 * 차량 제어는 하지 않고 조회만 한다.
 */
class PleosSignals(context: Context) {
    private val tag = "FitCast"
    private val vehicle = Vehicle(context)
    private val navi = NaviHelper(context)
    private val location = FusedLocation(context)

    @Volatile private var gear = GearState.UNKNOWN
    @Volatile private var turn = Turn.OFF
    @Volatile private var brake = false
    @Volatile private var steering = 0f
    @Volatile private var wiper = false
    @Volatile private var speedKmh: Double? = null
    @Volatile private var speedLimit: Int? = null
    @Volatile private var maneuverM: Double? = null
    @Volatile private var maneuver = ManeuverKind.STRAIGHT
    @Volatile private var cautionM: Int? = null
    @Volatile private var caution = CautionKind.NONE
    @Volatile private var cameraM: Int? = null
    @Volatile private var overSpeed = false
    @Volatile private var remainSec: Int? = null
    @Volatile private var arrived = false
    private var lastFix: LocationData? = null

    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            turn = when (value) { TurnSignalValue.LEFT -> Turn.LEFT; TurnSignalValue.RIGHT -> Turn.RIGHT; else -> Turn.OFF }
        }
        override fun onFailed(e: Exception) = warn("turn", e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) { brake = pressed == true }
        override fun onFailed(e: Exception) = warn("brake", e)
    }
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(g: VehicleGear?) {
            gear = when (g) {
                VehicleGear.GEAR_PARK -> GearState.PARK
                VehicleGear.GEAR_REVERSE -> GearState.REVERSE
                VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
                VehicleGear.GEAR_UNKNOWN, null -> GearState.UNKNOWN
                else -> GearState.DRIVE
            }
        }
        override fun onFailed(e: Exception) = warn("gear", e)
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(deg: Float?) { steering = deg ?: 0f }
        override fun onFailed(e: Exception) = warn("steering", e)
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) { wiper = state == WiperState.ON }
        override fun onFailed(e: Exception) = warn("wiper", e)
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onTBTInfo(list: List<TBTInfo>) {
            val next = list.minByOrNull { it.distance } ?: return
            maneuverM = next.distance
            maneuver = next.type.toManeuver()
        }
        override fun onCautionPointInfo(list: List<CautionPoint>) {
            val near = list.minByOrNull { it.remainDistance }
            cautionM = near?.remainDistance
            caution = near?.type?.toCaution() ?: CautionKind.NONE
        }
        override fun onCameraAlert(list: List<CameraAlert>) {
            val near = list.minByOrNull { it.remainDistance }
            cameraM = near?.remainDistance
            overSpeed = list.any { it.isOverSpeed || it.isOverSectionAvrSpeed }
        }
        override fun onCurrentRoadSpeedLimit(limit: Int) { speedLimit = limit }
        // DrivingState.duration 단위는 문서에 명시가 없어 초로 가정했다. 에뮬레이터에서 확인 필요
        override fun onDrivingInfo(info: DrivingInfo) { remainSec = info.destination?.duration }
        override fun onDestinationArrived(info: DestinationArrivedInfo) { arrived = true }
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) { arrived = false }
    }

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(fix: LocationData?) {
            if (fix == null) return
            lastFix?.let { prev ->
                val dt = (fix.timestampMillis - prev.timestampMillis) / 1000.0
                if (dt in 0.2..10.0) speedKmh = haversineM(prev, fix) / dt * 3.6
            }
            lastFix = fix
        }
        override fun onError(e: Exception) = warn("location", e)
    }

    fun start() {
        vehicle.initialize()
        vehicle.getTurnSignal().registerTurnSignalState(turnListener)
        vehicle.getBrake().registerBrakePedalStatus(brakeListener)
        vehicle.getGear().registerGearSelection(gearListener)
        vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
        vehicle.getWiper().registerWiperState(front, wiperListener)

        navi.initialize({ navi.addListener(naviListener) }, { warn("navi init", it) })

        // 위치 권한이 아직 없으면 속도 없이 돌고, 권한을 받은 뒤 다시 시작할 때 연결한다
        runCatching {
            if (location.isSupported()) {
                location.initialize()
                location.registerFusedLocationCallback(locationListener)
            }
        }.onFailure { warn("location", it) }
    }

    fun stop() {
        runCatching {
            vehicle.getTurnSignal().unregisterTurnSignalState(turnListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
            vehicle.getWiper().unregisterWiperState(front, wiperListener)
            vehicle.release()
        }
        runCatching { navi.removeListener(naviListener); navi.release() }
        runCatching { location.unregisterFusedLocationCallback(locationListener); location.release() }
    }

    fun snapshot(nowMs: Long) = DriveSnapshot(
        tMs = nowMs, gear = gear, turnSignal = turn, brake = brake, steeringDeg = steering, wiperOn = wiper,
        speedKmh = speedKmh, speedLimitKmh = speedLimit, nextManeuverDistanceM = maneuverM, nextManeuver = maneuver,
        cautionDistanceM = cautionM, caution = caution, cameraDistanceM = cameraM, overSpeed = overSpeed,
        remainSec = remainSec, arrived = arrived,
    )

    private fun warn(what: String, e: Throwable) { Log.w(tag, "$what 실패: ${e.message}") }

    private fun haversineM(a: LocationData, b: LocationData): Double {
        val r = 6_371_000.0
        val la1 = a.latitude * PI / 180; val la2 = b.latitude * PI / 180
        val dLa = la2 - la1; val dLo = (b.longitude - a.longitude) * PI / 180
        val h = sin(dLa / 2).let { it * it } + cos(la1) * cos(la2) * sin(dLo / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }
}

/** 안내 종류를 단순/복잡으로 묶는다. IC·회전교차로·유턴·분기·톨게이트·지하차도·고가 진입은 복잡. */
internal fun TurnType.toManeuver(): ManeuverKind = when {
    this == TurnType.TURN_STRAIGHT || this == TurnType.TURN_UNKNOWN -> ManeuverKind.STRAIGHT
    this == TurnType.TURN_ARRIVE -> ManeuverKind.ARRIVE
    this == TurnType.TURN_LEFT || this == TurnType.TURN_RIGHT ||
        this == TurnType.TURN_KEEP_L || this == TurnType.TURN_KEEP_R -> ManeuverKind.SIMPLE_TURN
    else -> ManeuverKind.COMPLEX
}

internal fun CautionPointType.toCaution(): CautionKind = when (this) {
    CautionPointType.SCHOOL_ZONE, CautionPointType.ACCIDENT_PRONE_AREA, CautionPointType.WILDLIFE_CROSSING,
    CautionPointType.RAILROAD_CROSSING -> CautionKind.CHILD_OR_ACCIDENT
    CautionPointType.FOG_AREA, CautionPointType.FALLING_ROCK_AREA, CautionPointType.DOWNHILL_CAUTION_AREA,
    CautionPointType.NARROW_ROAD, CautionPointType.HIGHWAY_CONSTRUCTION, CautionPointType.SLIPPERY_ROAD_ALERT,
    CautionPointType.ROAD_DAMAGE_ALERT, CautionPointType.WRONG_WAY_DRIVING_ALERT -> CautionKind.ROAD_HAZARD
    CautionPointType.TRAFFIC_LIGHT_VIOLATION_ENFORCEMENT, CautionPointType.BUS_LANE_VIOLATION_ENFORCEMENT,
    CautionPointType.CUT_IN_VIOLATION_ENFORCEMENT, CautionPointType.BLOCK_INTERSECTION_ENFORCEMENT,
    CautionPointType.LANE_CHANGE_CAMERA_ALERT -> CautionKind.ENFORCEMENT
    else -> CautionKind.NONE
}
