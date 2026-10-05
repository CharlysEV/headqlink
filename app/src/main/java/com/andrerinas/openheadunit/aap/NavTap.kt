package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType as T

/**
 * headqlink: grifo de la navegación de Android Auto (canal de cuadro de instrumentos). Deja el
 * estado en un formato sencillo para la interfaz propia del coche: destino, distancia/tiempo
 * restantes, hora de llegada, calle y próxima maniobra normalizada (tipo + ángulo).
 */
object NavTap {
    const val KIND_NONE = 0
    const val KIND_TURN = 1
    const val KIND_UTURN = 2
    const val KIND_ROUNDABOUT = 3
    const val KIND_DESTINATION = 4
    const val KIND_MERGE = 5
    const val KIND_DEPART = 6

    class Info {
        @JvmField var active = false
        @JvmField var destination: String? = null
        @JvmField var remainingMeters = -1
        @JvmField var remainingSeconds = -1L
        @JvmField var eta: String? = null
        @JvmField var road: String? = null
        @JvmField var kind = KIND_NONE
        /** Grados, negativo a la izquierda (0 recto, ±90 girar, ±180 cambio de sentido). */
        @JvmField var angle = 0
        @JvmField var roundaboutExit = -1
        @JvmField var nextRoad: String? = null
        @JvmField var stepMeters = -1
        @JvmField var stepDisplay: String? = null
        @JvmField var stepSeconds = -1L
        /** Carriles: true = recomendado. */
        @JvmField var lanes: BooleanArray? = null
        @JvmField var updatedMs = 0L
    }

    @JvmStatic
    @Volatile
    var info = Info()
        private set

    fun publish(s: AapNavigationHelper.NavigationSnapshot) {
        val i = Info()
        val status = s.clusterStatus?.payload?.status
        val state = s.navigationState?.payload
        val pos = s.currentPosition?.payload
        i.active = status == NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.ACTIVE ||
            status == NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.REROUTING ||
            (state != null && state.stepsCount > 0)
        i.destination = state?.destinationsList?.firstOrNull()?.address?.takeIf { it.isNotBlank() }
        pos?.destinationDistancesList?.firstOrNull()?.let { d ->
            if (d.hasDistance()) i.remainingMeters = d.distance.meters
            if (d.hasTimeToArrivalSeconds()) i.remainingSeconds = d.timeToArrivalSeconds
            if (d.hasEstimatedTimeAtArrival()) i.eta = d.estimatedTimeAtArrival
        }
        i.road = s.currentStreet?.payload
        pos?.stepDistance?.let { sd ->
            if (sd.hasDistance()) {
                i.stepMeters = sd.distance.meters
                i.stepDisplay = sd.distance.displayValue?.takeIf { it.isNotBlank() }
            }
            if (sd.hasTimeToStepSeconds()) i.stepSeconds = sd.timeToStepSeconds
        }
        val step = state?.stepsList?.firstOrNull()
        if (step != null && step.hasManeuver()) {
            val m = step.maneuver
            mapManeuver(i, m.type)
            if (m.hasRoundaboutExitNumber()) i.roundaboutExit = m.roundaboutExitNumber
            i.nextRoad = step.road?.name?.takeIf { it.isNotBlank() }
            if (step.lanesCount > 0) {
                i.lanes = BooleanArray(step.lanesCount) { idx ->
                    step.getLanes(idx).laneDirectionsList.any { it.isHighlighted }
                }
            }
        } else {
            // Mensajes antiguos (NextTurnDetail / NextTurnDistanceEvent).
            @Suppress("DEPRECATION")
            s.nextTurnDetail?.payload?.let { d -> mapLegacy(i, d) }
            @Suppress("DEPRECATION")
            s.nextTurnDistance?.payload?.let { e ->
                if (i.stepMeters < 0 && e.hasDistanceMeters()) i.stepMeters = e.distanceMeters
                if (i.stepSeconds < 0 && e.hasTimeToTurnSeconds()) i.stepSeconds = e.timeToTurnSeconds.toLong()
            }
        }
        i.updatedMs = SystemClock.elapsedRealtime()
        // Registro de cambios (a nivel INFO, para que salga también con el logcat filtrado): sirve
        // para saber qué manda Google Maps (¿llega la dirección del destino?). Solo si llega, no cuál:
        // el logcat va en el log exportado, que no lleva ubicaciones.
        val summary = "activa=${i.active} destino=${if (i.destination != null) "sí" else "—"} destinos=${state?.destinationsCount ?: 0} " +
            "pasos=${state?.stepsCount ?: 0} posición=${pos != null} falta=${i.remainingMeters} m"
        if (summary != lastSummary) {
            lastSummary = summary
            AppLog.i("NavTap: $summary")
        }
        info = i
    }

    private var lastSummary = ""

    private fun mapManeuver(i: Info, t: T) {
        fun turn(a: Int) { i.kind = KIND_TURN; i.angle = a }
        when (t) {
            T.KEEP_LEFT -> turn(-20); T.KEEP_RIGHT -> turn(20)
            T.TURN_SLIGHT_LEFT, T.ON_RAMP_SLIGHT_LEFT, T.OFF_RAMP_SLIGHT_LEFT -> turn(-45)
            T.TURN_SLIGHT_RIGHT, T.ON_RAMP_SLIGHT_RIGHT, T.OFF_RAMP_SLIGHT_RIGHT -> turn(45)
            T.TURN_NORMAL_LEFT, T.ON_RAMP_NORMAL_LEFT, T.OFF_RAMP_NORMAL_LEFT -> turn(-90)
            T.TURN_NORMAL_RIGHT, T.ON_RAMP_NORMAL_RIGHT, T.OFF_RAMP_NORMAL_RIGHT -> turn(90)
            T.TURN_SHARP_LEFT, T.ON_RAMP_SHARP_LEFT -> turn(-135)
            T.TURN_SHARP_RIGHT, T.ON_RAMP_SHARP_RIGHT -> turn(135)
            T.U_TURN_LEFT, T.ON_RAMP_U_TURN_LEFT -> { i.kind = KIND_UTURN; i.angle = -180 }
            T.U_TURN_RIGHT, T.ON_RAMP_U_TURN_RIGHT -> { i.kind = KIND_UTURN; i.angle = 180 }
            T.FORK_LEFT -> turn(-30); T.FORK_RIGHT -> turn(30)
            T.MERGE_LEFT -> { i.kind = KIND_MERGE; i.angle = -30 }
            T.MERGE_RIGHT, T.MERGE_SIDE_UNSPECIFIED -> { i.kind = KIND_MERGE; i.angle = 30 }
            T.ROUNDABOUT_ENTER, T.ROUNDABOUT_EXIT, T.ROUNDABOUT_ENTER_AND_EXIT_CW,
            T.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, T.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            T.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> i.kind = KIND_ROUNDABOUT
            T.DESTINATION, T.DESTINATION_STRAIGHT -> i.kind = KIND_DESTINATION
            T.DESTINATION_LEFT -> { i.kind = KIND_DESTINATION; i.angle = -90 }
            T.DESTINATION_RIGHT -> { i.kind = KIND_DESTINATION; i.angle = 90 }
            T.DEPART -> i.kind = KIND_DEPART
            else -> turn(0)
        }
    }

    private fun mapLegacy(i: Info, d: NavigationStatus.NextTurnDetail) {
        val sign = if (d.hasSide() && d.side == NavigationStatus.NextTurnDetail.Side.LEFT) -1 else 1
        i.nextRoad = d.road?.takeIf { it.isNotBlank() }
        if (d.hasTurnNumber()) i.roundaboutExit = d.turnNumber
        when (d.nextTurn) {
            NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN -> { i.kind = KIND_TURN; i.angle = 45 * sign }
            NavigationStatus.NextTurnDetail.NextEvent.TURN -> { i.kind = KIND_TURN; i.angle = 90 * sign }
            NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> { i.kind = KIND_TURN; i.angle = 135 * sign }
            NavigationStatus.NextTurnDetail.NextEvent.U_TURN -> { i.kind = KIND_UTURN; i.angle = 180 * sign }
            NavigationStatus.NextTurnDetail.NextEvent.ON_RAMP, NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP,
            NavigationStatus.NextTurnDetail.NextEvent.FORK -> { i.kind = KIND_TURN; i.angle = 30 * sign }
            NavigationStatus.NextTurnDetail.NextEvent.MERGE -> { i.kind = KIND_MERGE; i.angle = 30 * sign }
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER,
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT,
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> i.kind = KIND_ROUNDABOUT
            NavigationStatus.NextTurnDetail.NextEvent.DESTINATION -> i.kind = KIND_DESTINATION
            NavigationStatus.NextTurnDetail.NextEvent.DEPART -> i.kind = KIND_DEPART
            else -> { i.kind = KIND_TURN; i.angle = 0 }
        }
    }
}
