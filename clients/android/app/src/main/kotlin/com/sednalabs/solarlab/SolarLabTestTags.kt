package com.sednalabs.solarlab

/**
 * Stable test hooks for shell-level Android instrumentation tests.
 */
object SolarLabTestTags {
    const val SHELL_COLUMN = "shell.column"
    const val TITLE = "shell.title"
    const val STATUS_LINE = "shell.status"
    const val RENDER_PANEL = "shell.render_panel"
    const val DETAIL_LINE = "shell.detail"
    const val SESSION_HANDLE = "shell.session_handle"
    const val RENDER_PACKET_SUMMARY = "shell.render_packet_summary"

    const val FOCUS_BODY_FIELD = "shell.focus_body_field"
    const val FOCUS_BODY_SET_BUTTON = "shell.focus_body_set"
    const val FOCUS_SELECTION_BUTTON = "shell.focus_selection"
    const val CHECKPOINT_ID_FIELD = "shell.checkpoint_id_field"
    const val CREATE_CHECKPOINT_BUTTON = "shell.create_checkpoint"
    const val BRANCH_FROM_CHECKPOINT_FIELD = "shell.branch_from_checkpoint"
    const val BRANCH_NAME_FIELD = "shell.branch_name_field"
    const val CREATE_BRANCH_FROM_CHECKPOINT_BUTTON = "shell.create_branch_from_checkpoint"
    const val SPAWN_BODY_ID_FIELD = "shell.spawn_body_id_field"
    const val SPAWN_BODY_MASS_FIELD = "shell.spawn_body_mass_field"
    const val SPAWN_BODY_RADIUS_FIELD = "shell.spawn_body_radius_field"
    const val SPAWN_BODY_BUTTON = "shell.spawn_body"
    const val SET_BODY_KINEMATICS_BODY_ID_FIELD = "shell.set_body_kinematics_body_id_field"
    const val SET_BODY_KINEMATICS_POSITION_X_FIELD = "shell.set_body_kinematics_position_x_field"
    const val SET_BODY_KINEMATICS_POSITION_Y_FIELD = "shell.set_body_kinematics_position_y_field"
    const val SET_BODY_KINEMATICS_POSITION_Z_FIELD = "shell.set_body_kinematics_position_z_field"
    const val SET_BODY_KINEMATICS_VELOCITY_X_FIELD = "shell.set_body_kinematics_velocity_x_field"
    const val SET_BODY_KINEMATICS_VELOCITY_Y_FIELD = "shell.set_body_kinematics_velocity_y_field"
    const val SET_BODY_KINEMATICS_VELOCITY_Z_FIELD = "shell.set_body_kinematics_velocity_z_field"
    const val SET_BODY_KINEMATICS_BUTTON = "shell.set_body_kinematics"
    const val REMOVE_BODY_ID_FIELD = "shell.remove_body_id_field"
    const val REMOVE_BODY_BUTTON = "shell.remove_body"

    const val METADATA_FOCUS_TARGET = "shell.meta_focus_target"
    const val METADATA_OBSERVER_MODE = "shell.meta_observer_mode"
    const val METADATA_ACTIVE_BRANCH = "shell.meta_active_branch"
    const val METADATA_ACTIVE_CHECKPOINT = "shell.meta_active_checkpoint"
    const val METADATA_PROVENANCE = "shell.meta_provenance"
    const val METADATA_LIGHTS = "shell.meta_lights"
}
