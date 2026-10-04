package com.nikita.sleepcycle.engine

import java.time.Duration

/** Rejects a nonsensical [EngineConfig] (non-positive durations, an empty picker). One of the only two throw sites. */
internal fun validateConfig(config: EngineConfig) {
    require(config.cycleLength > Duration.ZERO) { "cycleLength must be positive" }
    require(config.napLength > Duration.ZERO) { "napLength must be positive" }
    require(config.fallAsleepEstimate >= Duration.ZERO) { "fallAsleepEstimate must not be negative" }
    require(config.minAwakening > Duration.ZERO) { "minAwakening must be positive" }
    require(config.minAlarmLead >= Duration.ZERO) { "minAlarmLead must not be negative" }
    require(config.nearAlarmSyncWindow >= Duration.ZERO) { "nearAlarmSyncWindow must not be negative" }
    require(config.frequentSyncDelay > Duration.ZERO) { "frequentSyncDelay must be positive" }
    require(config.normalSyncDelay > Duration.ZERO) { "normalSyncDelay must be positive" }
    require(config.outOfBedDelay > Duration.ZERO) { "outOfBedDelay must be positive" }
    require(config.ringAutoStopAfter > Duration.ZERO) { "ringAutoStopAfter must be positive" }
    require(config.preNudgeCheckLead > Duration.ZERO) { "preNudgeCheckLead must be positive" }
    require(config.owedCycleForgiveness >= Duration.ZERO && config.owedCycleForgiveness < config.cycleLength) {
        "owedCycleForgiveness must be at least zero and shorter than a cycle"
    }
    // F1's outOfBedDelay > ringAutoStopAfter rule is gone (owner spec, 2026-10-02): since P3 the nudge is
    // measured from the ring's END, so it can no longer land while the ring is still sounding, and the Settings
    // screen offers nudges from 5 min, shorter than the 9 min auto-stop.
    // H7.3: enforced, not coincidental - see EngineConfig.preNudgeCheckLead's own doc for why this must hold.
    require(config.outOfBedDelay > config.preNudgeCheckLead) {
        "outOfBedDelay must be strictly greater than preNudgeCheckLead, or the pre-nudge check would land at " +
            "or before the nudge was even armed"
    }
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
