package com.carlink.navigation

import android.content.Context
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import com.carlink.R
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn
import com.carlink.logging.Logger

/**
 * Maps CarPlay CPManeuverType values (0-53) to AAOS Car App Library Maneuver.TYPE_* constants
 * and provides explicit maneuver icons for cluster display.
 *
 * Source: LIVI translateNavigation.ts (verified against iAP2 spec "Table 15-16").
 * Icon mapping cross-referenced with GM RouteStateMachine.mapManeuverType() from
 * decompiled DelayedWKSApp to ensure cluster icon compatibility.
 *
 * NaviTurnSide controls U-turn direction (LEFT vs RIGHT) and roundabout rotation (CW vs CCW).
 */
object ManeuverMapper {

    /**
     * Map a CPManeuverType + turnSide to a Maneuver.TYPE_* constant.
     *
     * @param cpType CPManeuverType value (0-53)
     * @param turnSide 0=right-hand driving (default), 1=left-hand driving
     * @return Maneuver type constant
     */
    fun mapManeuverType(cpType: Int, turnSide: Int = 0): Int {
        val isLeftDrive = turnSide == 1
        val mapped = when (cpType) {
            0  -> Maneuver.TYPE_STRAIGHT             // noTurn
            1  -> Maneuver.TYPE_TURN_NORMAL_LEFT     // left
            2  -> Maneuver.TYPE_TURN_NORMAL_RIGHT    // right
            3  -> Maneuver.TYPE_STRAIGHT             // straight
            4  -> if (isLeftDrive) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT // uTurn
            5  -> Maneuver.TYPE_STRAIGHT             // followRoad
            6  -> if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW // enterRoundabout
            7  -> if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_EXIT_CW else Maneuver.TYPE_ROUNDABOUT_EXIT_CCW // exitRoundabout
            8  -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT // rampOff (highway exit)
            9  -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT  // rampOn (merge onto highway)
            10 -> Maneuver.TYPE_DESTINATION           // endOfNavigation
            11 -> Maneuver.TYPE_DEPART                // proceedToRoute
            12 -> Maneuver.TYPE_DESTINATION           // arrived
            13 -> Maneuver.TYPE_KEEP_LEFT             // keepLeft
            14 -> Maneuver.TYPE_KEEP_RIGHT            // keepRight
            15 -> Maneuver.TYPE_FERRY_BOAT            // enterFerry
            16 -> Maneuver.TYPE_FERRY_BOAT            // exitFerry
            17 -> Maneuver.TYPE_FERRY_BOAT            // changeFerry
            18 -> if (isLeftDrive) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT // uTurnToRoute
            19 -> if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW // roundaboutUTurn
            20 -> Maneuver.TYPE_TURN_NORMAL_LEFT      // endOfRoadLeft
            21 -> Maneuver.TYPE_TURN_NORMAL_RIGHT     // endOfRoadRight
            22 -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT  // rampOffLeft
            23 -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT // rampOffRight
            24 -> Maneuver.TYPE_DESTINATION_LEFT      // arrivedLeft
            25 -> Maneuver.TYPE_DESTINATION_RIGHT     // arrivedRight
            26 -> if (isLeftDrive) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT // uTurnWhenPossible
            27 -> Maneuver.TYPE_DESTINATION           // endOfDirections
            in 28..46 -> {
                // Roundabout exit 1-19 (type - 27 = exit number)
                if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
                else Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
            }
            47 -> Maneuver.TYPE_TURN_SHARP_LEFT       // sharpLeft
            48 -> Maneuver.TYPE_TURN_SHARP_RIGHT      // sharpRight
            49 -> Maneuver.TYPE_TURN_SLIGHT_LEFT      // slightLeft
            50 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT     // slightRight
            51 -> Maneuver.TYPE_KEEP_RIGHT            // changeHighway (fork)
            52 -> Maneuver.TYPE_KEEP_LEFT             // changeHighwayLeft
            53 -> Maneuver.TYPE_KEEP_RIGHT            // changeHighwayRight
            else -> {
                logWarn("[NAVI] Unknown CPManeuverType=$cpType, turnSide=$turnSide — falling back to TYPE_UNKNOWN", tag = Logger.Tags.NAVI)
                Maneuver.TYPE_UNKNOWN
            }
        }

        logNavi { "[NAVI] Mapped CPManeuverType=$cpType (turnSide=$turnSide) → Maneuver.TYPE=$mapped" }
        return mapped
    }

    /**
     * Map a CPManeuverType to the appropriate drawable resource for cluster display.
     *
     * Cross-referenced with GM's RouteStateMachine.mapManeuverType() which maps
     * CINEMO CarPlay maneuver types → GM TurnType → ClusterAssetUtils → PNG assets.
     * The AOSP NavigationState.proto Maneuver.Type enum is too generic (no left/right),
     * so explicit icons via setIcon() are required for correct cluster rendering.
     *
     * @param cpType CPManeuverType value (0-53)
     * @param turnSide 0=right-hand driving (default), 1=left-hand driving
     * @return Drawable resource ID
     */
    fun getIconResource(cpType: Int, turnSide: Int = 0): Int {
        val isLeftDrive = turnSide == 1
        return when (cpType) {
            0  -> R.drawable.ic_nav_straight             // noTurn → CONTINUE_STRAIGHT
            1  -> R.drawable.ic_nav_turn_left            // left → TURN_LEFT
            2  -> R.drawable.ic_nav_turn_right           // right → TURN_RIGHT
            3  -> R.drawable.ic_nav_straight             // straight → CONTINUE_STRAIGHT
            4  -> if (isLeftDrive) R.drawable.ic_nav_uturn_right    // uTurn → U_TURN_LEFT/RIGHT
                  else R.drawable.ic_nav_uturn_left
            5  -> R.drawable.ic_nav_straight             // followRoad → CONTINUE_STRAIGHT
            6  -> if (isLeftDrive) R.drawable.ic_nav_roundabout_cw  // enterRoundabout
                  else R.drawable.ic_nav_roundabout_ccw
            7  -> if (isLeftDrive) R.drawable.ic_nav_roundabout_cw  // exitRoundabout
                  else R.drawable.ic_nav_roundabout_ccw
            8  -> R.drawable.ic_nav_off_ramp_right       // rampOff → OFF_RAMP_RIGHT
            9  -> R.drawable.ic_nav_fork_right           // rampOn → ON_RAMP_RIGHT (merge)
            10 -> R.drawable.ic_nav_destination          // endOfNavigation → DESTINATION
            11 -> R.drawable.ic_nav_depart               // proceedToRoute → DEPART
            12 -> R.drawable.ic_nav_destination          // arrived → DESTINATION
            13 -> R.drawable.ic_nav_fork_left            // keepLeft → FORK_LEFT
            14 -> R.drawable.ic_nav_fork_right           // keepRight → FORK_RIGHT
            15, 16, 17 -> R.drawable.ic_nav_ferry        // ferry variants → FERRY
            18 -> if (isLeftDrive) R.drawable.ic_nav_uturn_right    // uTurnToRoute
                  else R.drawable.ic_nav_uturn_left
            19 -> if (isLeftDrive) R.drawable.ic_nav_roundabout_cw  // roundaboutUTurn
                  else R.drawable.ic_nav_roundabout_ccw
            20 -> R.drawable.ic_nav_turn_left            // endOfRoadLeft → TURN_LEFT
            21 -> R.drawable.ic_nav_turn_right           // endOfRoadRight → TURN_RIGHT
            22 -> R.drawable.ic_nav_off_ramp_left        // rampOffLeft → OFF_RAMP_LEFT
            23 -> R.drawable.ic_nav_off_ramp_right       // rampOffRight → OFF_RAMP_RIGHT
            24 -> R.drawable.ic_nav_destination          // arrivedLeft → DESTINATION
            25 -> R.drawable.ic_nav_destination          // arrivedRight → DESTINATION
            26 -> if (isLeftDrive) R.drawable.ic_nav_uturn_right    // uTurnWhenPossible
                  else R.drawable.ic_nav_uturn_left
            27 -> R.drawable.ic_nav_destination          // endOfDirections → DESTINATION
            in 28..46 -> {                               // roundabout exit 1-19
                if (isLeftDrive) R.drawable.ic_nav_roundabout_cw
                else R.drawable.ic_nav_roundabout_ccw
            }
            47 -> R.drawable.ic_nav_sharp_left           // sharpLeft → SHARP_LEFT
            48 -> R.drawable.ic_nav_sharp_right          // sharpRight → SHARP_RIGHT
            49 -> R.drawable.ic_nav_slight_left          // slightLeft → SLIGHT_LEFT
            50 -> R.drawable.ic_nav_slight_right         // slightRight → SLIGHT_RIGHT
            51 -> R.drawable.ic_nav_fork_right           // changeHighway → PROCEED/FORK
            52 -> R.drawable.ic_nav_fork_left            // changeHighwayLeft → FORK_LEFT
            53 -> R.drawable.ic_nav_fork_right           // changeHighwayRight → FORK_RIGHT
            else -> R.drawable.ic_nav_straight           // fallback
        }
    }

    /**
     * Get roundabout exit number for types 28-46.
     *
     * @return Exit number (1-19), or null if not a roundabout exit type
     */
    fun getRoundaboutExitNumber(cpType: Int): Int? {
        val exitNumber = if (cpType in 28..46) cpType - 27 else null
        if (exitNumber != null) {
            logNavi { "[NAVI] Roundabout exit number: $exitNumber (cpType=$cpType)" }
        }
        return exitNumber
    }

    /**
     * Build a Maneuver object from navigation state with explicit icon for cluster display.
     *
     * The AOSP NavigationState.proto Maneuver.Type enum (18 generic types) loses
     * direction information (e.g., TURN has no left/right). GM's VMSClusterService
     * checks icon sources in priority order: ManeuverIcon > ManeuverByteArray > TurnType.
     * Providing an explicit icon ensures the cluster renders the correct arrow.
     *
     * @param state Current navigation state
     * @param context Context for loading drawable resources (CarContext)
     * @return Maneuver with type and explicit icon set
     */
    fun buildManeuver(state: NavigationState, context: Context): Maneuver {
        val type = mapManeuverType(state.maneuverType, state.turnSide)
        val builder = Maneuver.Builder(type)

        getRoundaboutExitNumber(state.maneuverType)?.let {
            builder.setRoundaboutExitNumber(it)
        }

        val iconRes = getIconResource(state.maneuverType, state.turnSide)
        val icon = CarIcon.Builder(
            IconCompat.createWithResource(context, iconRes),
        ).build()
        builder.setIcon(icon)

        return builder.build()
    }
}
