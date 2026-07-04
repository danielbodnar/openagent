//
//  WorkerBuckets.swift
//  SandboxedDashboard
//
//  Single source of truth for grouping a boss mission's workers by state.
//  The floating pill and the worker sheet previously used different filters
//  (the pill excluded awaiting_user, the sheet included it), so the numbers
//  visibly disagreed — "1 active" on the pill, "6 active" in the sheet.
//

import Foundation

struct WorkerBuckets {
    let active: [Mission]
    let waiting: [Mission]
    let done: [Mission]
    let failed: [Mission]

    init(workers: [Mission], runningWorkers: [RunningMissionInfo]) {
        let runningIds = Set(runningWorkers.filter(\.isRunning).map(\.missionId))
        var active: [Mission] = []
        var waiting: [Mission] = []
        var done: [Mission] = []
        var failed: [Mission] = []

        for mission in workers {
            if runningIds.contains(mission.id) {
                active.append(mission)
                continue
            }
            switch mission.status {
            case .active, .pending:
                active.append(mission)
            case .awaitingUser:
                waiting.append(mission)
            case .completed, .acknowledged:
                done.append(mission)
            case .failed, .notFeasible, .interrupted, .blocked:
                failed.append(mission)
            default:
                waiting.append(mission)
            }
        }

        self.active = active
        self.waiting = waiting
        self.done = done
        self.failed = failed
    }
}
