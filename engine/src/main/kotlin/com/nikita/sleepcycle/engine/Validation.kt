package com.nikita.sleepcycle.engine

import java.time.Duration

/** Rejects a nonsensical [EngineConfig] (non-positive durations, an empty picker). One of the only two throw sites. */
internal fun validateConfig(config: EngineConfig) {
    require(config.cycleLength > Duration.ZERO) { "cycleLength must be positive" }
    require(config.napLength > Duration.ZERO) { "napLength must be positive" }
    require(config.fallAsleepEstimate >= Duration.ZERO) { "fallAsleepEstimate must not be negative" }
    require(config.phoneBackupOffset >= Duration.ZERO) { "phoneBackupOffset must not be negative" }
    require(config.minAwakening > Duration.ZERO) { "minAwakening must be positive" }
    require(config.minAlarmLead >= Duration.ZERO) { "minAlarmLead must not be negative" }
    require(config.nearAlarmSyncWindow >= Duration.ZERO) { "nearAlarmSyncWindow must not be negative" }
    require(config.frequentSyncDelay > Duration.ZERO) { "frequentSyncDelay must be positive" }
    require(config.normalSyncDelay > Duration.ZERO) { "normalSyncDelay must be positive" }
    require(config.maxOverdueDuration > Duration.ZERO) { "maxOverdueDuration must be positive" }
    require(config.allowedCycleCounts.isNotEmpty()) { "allowedCycleCounts must not be empty" }
    require(config.allowedCycleCounts.all { it > 0 }) { "allowedCycleCounts must all be positive" }
}

/**
 * Rejects a picker value outside [EngineConfig.allowedCycleCounts]. The other throw site. A deadline already in
 * the past is not an error here: [chooseMode] turns it into [AlarmMode.FINISHED] instead.
 */
internal fun validateSettings(settings: NightSettings, config: EngineConfig) {
    require(settings.pickedCycles in config.allowedCycleCounts) {
        "pickedCycles must be one of ${config.allowedCycleCounts}"
    }
}
