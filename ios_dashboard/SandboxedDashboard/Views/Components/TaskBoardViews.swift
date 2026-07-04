//
//  TaskBoardViews.swift
//  SandboxedDashboard
//
//  Task board pill + sheet for boss missions running on the server-owned
//  task board (see backend api::control::board). The pill mirrors
//  WorkerPillView's grammar; the sheet lists tasks grouped by status with
//  digest previews and verdict actions.
//

import SwiftUI

// MARK: - Pill

struct TaskBoardPillView: View {
    let board: MissionBoard
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Image(systemName: "square.grid.3x1.below.line.grid.1x2")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.accent)

                Text("\(board.utilization.total)")
                    .font(.caption.weight(.semibold))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .foregroundStyle(Theme.textPrimary)

                if board.utilization.running > 0 {
                    countBadge(board.utilization.running, color: Theme.success)
                }
                if board.utilization.settled > 0 {
                    countBadge(board.utilization.settled, color: Theme.warning)
                }
                if board.utilization.failed > 0 {
                    countBadge(board.utilization.failed, color: Theme.error)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            .overlay(Capsule().stroke(Theme.border, lineWidth: 0.5))
        }
        .buttonStyle(.plain)
    }

    private func countBadge(_ count: Int, color: Color) -> some View {
        HStack(spacing: 3) {
            Circle()
                .fill(color)
                .frame(width: 5, height: 5)
            Text("\(count)")
                .font(.system(size: 10, weight: .medium).monospaced())
                .contentTransition(.numericText())
                .foregroundStyle(color)
        }
    }
}

// MARK: - Self-polling pill loader

/// Owns board fetching so the host view only needs a mission id. Renders
/// nothing for missions without a board; polls every 10s while mounted.
struct TaskBoardPillLoader: View {
    let missionId: String

    @State private var board: MissionBoard?
    @State private var showSheet = false

    private let api = APIService.shared

    var body: some View {
        ZStack {
            // Zero-size anchor keeps this view installed in the hierarchy
            // while there is no pill to show — without it, `.task` on the
            // empty conditional never fires and the board is never fetched.
            Color.clear.frame(width: 0, height: 0)
            if let board, !board.tasks.isEmpty {
                TaskBoardPillView(board: board) {
                    HapticService.lightTap()
                    showSheet = true
                }
            }
        }
        .task(id: missionId) {
            board = nil
            while !Task.isCancelled {
                do {
                    board = try await api.getMissionBoard(missionId: missionId)
                } catch {
                    #if DEBUG
                        print("[task-board] fetch failed for \(missionId): \(error)")
                    #endif
                }
                try? await Task.sleep(for: .seconds(10))
            }
        }
        .sheet(isPresented: $showSheet) {
            TaskBoardSheetView(missionId: missionId)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
                .presentationBackgroundInteraction(.enabled(upThrough: .medium))
        }
    }
}

// MARK: - Sheet

struct TaskBoardSheetView: View {
    let missionId: String

    @Environment(\.dismiss) private var dismiss
    @State private var board: MissionBoard?
    @State private var busyTaskId: String?
    @State private var errorMessage: String?
    @State private var peekWorker: Mission?

    private let api = APIService.shared

    private static let sectionOrder: [BoardTaskStatus] = [
        .settled, .running, .pending, .failed, .accepted, .cancelled,
    ]

    private func sectionTitle(_ status: BoardTaskStatus) -> String {
        switch status {
        case .settled: return "Awaiting verdict"
        case .running: return "Running"
        case .pending: return "Pending"
        case .failed: return "Failed"
        case .accepted: return "Accepted"
        case .cancelled: return "Cancelled"
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let board {
                    boardList(board)
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle("Task Board")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .task { await refresh() }
            .refreshable { await refresh() }
            .sheet(item: $peekWorker) { worker in
                WorkerPeekView(mission: worker)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
    }

    private func refresh() async {
        do {
            board = try await api.getMissionBoard(missionId: missionId)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    @ViewBuilder
    private func boardList(_ board: MissionBoard) -> some View {
        List {
            if let errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(Theme.error)
            }
            Section {
                HStack(spacing: 14) {
                    utilizationStat("Running", board.utilization.running, Theme.success)
                    utilizationStat("Verdict", board.utilization.settled, Theme.warning)
                    utilizationStat("Pending", board.utilization.pending, Theme.info)
                    utilizationStat(
                        "Done",
                        board.utilization.accepted + board.utilization.cancelled,
                        Theme.textSecondary
                    )
                    Spacer()
                    Text("cap \(board.utilization.maxParallel)")
                        .font(.system(size: 10).monospaced())
                        .foregroundStyle(Theme.textMuted)
                }
                .listRowBackground(Color.clear)
            }
            ForEach(Self.sectionOrder, id: \.self) { status in
                let tasks = board.tasks.filter { $0.status == status }
                if !tasks.isEmpty {
                    Section(sectionTitle(status)) {
                        ForEach(tasks) { task in
                            taskRow(task)
                        }
                    }
                }
            }
        }
    }

    private func utilizationStat(_ label: String, _ value: Int, _ color: Color) -> some View {
        VStack(spacing: 2) {
            Text("\(value)")
                .font(.callout.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(color)
            Text(label)
                .font(.system(size: 9, weight: .medium))
                .foregroundStyle(Theme.textMuted)
        }
    }

    @ViewBuilder
    private func taskRow(_ task: BoardTask) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                Circle()
                    .fill(task.statusColor)
                    .frame(width: 7, height: 7)
                Text(task.taskKey)
                    .font(.system(size: 11, weight: .semibold).monospaced())
                    .foregroundStyle(Theme.textSecondary)
                Text(task.statusLabel)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(task.statusColor)
                Spacer()
                if task.attempts > 1 {
                    Text("attempt \(task.attempts)")
                        .font(.system(size: 9).monospaced())
                        .foregroundStyle(Theme.textMuted)
                }
                Text(task.backend)
                    .font(.system(size: 9, weight: .medium).monospaced())
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Theme.border)
                    .clipShape(Capsule())
                    .foregroundStyle(Theme.textSecondary)
            }
            Text(task.title)
                .font(.subheadline)
                .foregroundStyle(Theme.textPrimary)
            if task.status == .pending, !task.dependsOn.isEmpty {
                Text("waits for \(task.dependsOn.joined(separator: ", "))")
                    .font(.system(size: 10).monospaced())
                    .foregroundStyle(Theme.textMuted)
            }
            if let digest = task.resultDigest, !digest.isEmpty {
                Text(digest)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(4)
            }
            HStack(spacing: 10) {
                if let workerId = task.workerMissionId {
                    Button {
                        Task {
                            if let worker = try? await api.getMission(id: workerId) {
                                peekWorker = worker
                            }
                        }
                    } label: {
                        Label("Worker", systemImage: "arrow.up.right.square")
                            .font(.caption2)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.mini)
                }
                if task.status == .settled {
                    Button {
                        Task { await verdict(task, action: "accept") }
                    } label: {
                        Label("Accept", systemImage: "checkmark")
                            .font(.caption2)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.mini)
                    .tint(Theme.success)
                    .disabled(busyTaskId == task.id)
                }
                if task.status == .pending || task.status == .running {
                    Button(role: .destructive) {
                        Task { await cancel(task) }
                    } label: {
                        Label("Cancel", systemImage: "xmark")
                            .font(.caption2)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.mini)
                    .disabled(busyTaskId == task.id)
                }
            }
        }
        .padding(.vertical, 2)
    }

    private func verdict(_ task: BoardTask, action: String) async {
        busyTaskId = task.id
        defer { busyTaskId = nil }
        do {
            try await api.boardTaskVerdict(taskId: task.id, action: action)
            await refresh()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func cancel(_ task: BoardTask) async {
        busyTaskId = task.id
        defer { busyTaskId = nil }
        do {
            try await api.cancelBoardTask(taskId: task.id)
            await refresh()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
