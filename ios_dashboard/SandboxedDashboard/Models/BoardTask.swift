//
//  BoardTask.swift
//  SandboxedDashboard
//
//  Mission task board: server-scheduled worker tasks owned by a boss mission.
//  Mirrors the backend's BoardTask / BoardUtilization payloads
//  (GET /api/control/missions/:id/board).
//

import Foundation
import SwiftUI

enum BoardTaskStatus: String, Codable, CaseIterable {
    case pending
    case running
    case settled
    case accepted
    case failed
    case cancelled

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = BoardTaskStatus(rawValue: raw) ?? .pending
    }
}

enum BoardTaskOutcome: String, Codable {
    case success
    case blocked
    case failed

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = BoardTaskOutcome(rawValue: raw) ?? .failed
    }
}

struct BoardTask: Codable, Identifiable, Hashable {
    let id: String
    let bossMissionId: String
    let taskKey: String
    let title: String
    let prompt: String
    let backend: String
    let modelOverride: String?
    let modelEffort: String?
    let workingDirectory: String?
    let dependsOn: [String]
    let status: BoardTaskStatus
    let outcome: BoardTaskOutcome?
    let workerMissionId: String?
    let attempts: Int
    let resultDigest: String?
    let notes: String?
    let createdAt: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case id, title, prompt, backend, status, outcome, attempts, notes
        case bossMissionId = "boss_mission_id"
        case taskKey = "task_key"
        case modelOverride = "model_override"
        case modelEffort = "model_effort"
        case workingDirectory = "working_directory"
        case dependsOn = "depends_on"
        case workerMissionId = "worker_mission_id"
        case resultDigest = "result_digest"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }

    var statusColor: Color {
        switch status {
        case .running: return Theme.success
        case .settled: return outcome == .success ? Theme.warning : Theme.error
        case .accepted: return Theme.textMuted
        case .failed: return Theme.error
        case .cancelled: return Theme.textMuted
        case .pending: return Theme.info
        }
    }

    var statusLabel: String {
        switch status {
        case .settled:
            switch outcome {
            case .blocked: return "blocked"
            case .failed: return "failed"
            default: return "needs verdict"
            }
        default:
            return status.rawValue
        }
    }
}

struct BoardUtilization: Codable, Hashable {
    let pending: Int
    let running: Int
    let settled: Int
    let accepted: Int
    let failed: Int
    let cancelled: Int
    let total: Int
    let maxParallel: Int

    enum CodingKeys: String, CodingKey {
        case pending, running, settled, accepted, failed, cancelled, total
        case maxParallel = "max_parallel"
    }
}

struct MissionBoard: Codable, Hashable {
    let tasks: [BoardTask]
    let utilization: BoardUtilization
}
