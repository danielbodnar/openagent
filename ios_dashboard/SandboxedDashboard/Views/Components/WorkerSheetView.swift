//
//  WorkerSheetView.swift
//  SandboxedDashboard
//
//  Half-sheet listing worker missions for a boss mission.
//  Each worker shows status, title, and can be tapped to peek.
//

import SwiftUI

struct WorkerSheetView: View {
    let workers: [Mission]
    let runningWorkers: [RunningMissionInfo]
    @State private var peekingWorker: Mission?
    // Completed workers are terminal and never reused, so they start collapsed
    // to keep the focus on what's still in flight.
    @State private var showCompleted = false

    private var buckets: WorkerBuckets {
        WorkerBuckets(workers: workers, runningWorkers: runningWorkers)
    }

    private var activeWorkers: [Mission] { buckets.active }
    private var waitingWorkers: [Mission] { buckets.waiting }
    private var completedWorkers: [Mission] { buckets.done }
    private var failedWorkers: [Mission] { buckets.failed }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Summary cards
                    summaryRow

                    if !workers.isEmpty {
                        Text("\(workers.count) sub-mission\(workers.count == 1 ? "" : "s") spawned in total. Completed ones have finished and are not reused.")
                            .font(.caption2)
                            .foregroundStyle(Theme.textMuted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    // Active workers
                    if !activeWorkers.isEmpty {
                        workerSection("Running", icon: "bolt.fill", tint: Theme.accent, missions: activeWorkers)
                    }

                    // Waiting on user input
                    if !waitingWorkers.isEmpty {
                        workerSection("Waiting", icon: "hourglass", tint: Theme.info, missions: waitingWorkers)
                    }

                    // Failed workers (actionable — surfaced before completed)
                    if !failedWorkers.isEmpty {
                        workerSection("Failed", icon: "xmark.circle.fill", tint: Theme.error, missions: failedWorkers)
                    }

                    // Completed workers — collapsed by default (terminal noise).
                    if !completedWorkers.isEmpty {
                        collapsibleCompletedSection
                    }

                    if workers.isEmpty {
                        emptyState
                    }
                }
                .padding(16)
            }
            .background(Theme.backgroundPrimary)
            .navigationTitle("Workers")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(item: $peekingWorker) { worker in
                WorkerPeekView(mission: worker)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
                    .presentationBackgroundInteraction(.enabled(upThrough: .medium))
            }
        }
    }

    // MARK: - Summary

    private var summaryRow: some View {
        HStack(spacing: 10) {
            summaryCard(
                count: activeWorkers.count,
                label: "Active",
                tint: Theme.accent
            )
            summaryCard(
                count: waitingWorkers.count,
                label: "Waiting",
                tint: Theme.info
            )
            summaryCard(
                count: failedWorkers.count,
                label: "Failed",
                tint: Theme.error
            )
            summaryCard(
                count: completedWorkers.count,
                label: "Done",
                tint: Theme.success
            )
        }
    }

    private func summaryCard(count: Int, label: String, tint: Color) -> some View {
        VStack(spacing: 4) {
            Text("\(count)")
                .font(.title2.weight(.semibold).monospacedDigit())
                .foregroundStyle(count > 0 ? tint : Theme.textMuted)
            Text(label)
                .font(.caption2.weight(.medium))
                .foregroundStyle(Theme.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(count > 0 ? tint.opacity(0.08) : Color.white.opacity(0.03))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Theme.surfaceSheen)
                .allowsHitTesting(false)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(count > 0 ? tint.opacity(0.18) : Theme.borderSubtle, lineWidth: 0.5)
        )
    }

    // MARK: - Sections

    private var collapsibleCompletedSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                HapticService.lightTap()
                withAnimation(.snappy) { showCompleted.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(Theme.success)
                    Text("Completed")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.textSecondary)
                    Text("(\(completedWorkers.count))")
                        .font(.caption)
                        .foregroundStyle(Theme.textMuted)
                    Spacer()
                    Image(systemName: showCompleted ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Theme.textMuted)
                }
            }
            .buttonStyle(.plain)

            if showCompleted {
                ForEach(completedWorkers) { mission in
                    workerRow(mission)
                }
            }
        }
    }

    private func workerSection(_ title: String, icon: String, tint: Color, missions: [Mission]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(tint)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.textSecondary)
                Text("(\(missions.count))")
                    .font(.caption)
                    .foregroundStyle(Theme.textMuted)
            }

            ForEach(missions) { mission in
                workerRow(mission)
            }
        }
    }

    private func workerRow(_ mission: Mission) -> some View {
        let runningInfo = runningWorkers.first { $0.missionId == mission.id }

        return Button {
            HapticService.lightTap()
            peekingWorker = mission
        } label: {
            HStack(spacing: 10) {
                // Status indicator
                workerStatusDot(mission: mission, runningInfo: runningInfo)

                // Content
                VStack(alignment: .leading, spacing: 3) {
                    Text(mission.displayTitle)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)

                    HStack(spacing: 8) {
                        if let backend = mission.backend {
                            Text(backend)
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(Theme.textMuted)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.white.opacity(0.06))
                                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                        }

                        if let activity = runningInfo?.currentActivity {
                            Text(activity)
                                .font(.caption2)
                                .foregroundStyle(Theme.textTertiary)
                                .lineLimit(1)
                        }
                    }
                }

                Spacer()

                // Chevron
                Image(systemName: "eye")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textMuted)
            }
            .padding(12)
            .background(Color.white.opacity(0.03))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Theme.borderSubtle, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func workerStatusDot(mission: Mission, runningInfo: RunningMissionInfo?) -> some View {
        let color: Color = {
            if let info = runningInfo, info.isRunning {
                return info.isStalled ? Theme.warning : Theme.accent
            }
            switch mission.status {
            case .completed, .acknowledged: return Theme.success
            case .awaitingUser: return Theme.info
            case .failed, .notFeasible, .interrupted, .blocked: return Theme.error
            default: return Theme.textMuted
            }
        }()

        let isRunning = runningInfo?.isRunning ?? false

        return Circle()
            .fill(color)
            .frame(width: 8, height: 8)
            .overlay {
                if isRunning {
                    Circle()
                        .stroke(color.opacity(0.4), lineWidth: 2)
                        .frame(width: 14, height: 14)
                }
            }
    }

    // MARK: - Empty

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "person.3")
                .font(.system(size: 28))
                .foregroundStyle(Theme.textMuted)
            Text("No workers yet")
                .font(.subheadline)
                .foregroundStyle(Theme.textTertiary)
            Text("Workers will appear here when the boss agent delegates tasks")
                .font(.caption)
                .foregroundStyle(Theme.textMuted)
                .multilineTextAlignment(.center)
        }
        .padding(.vertical, 32)
    }
}
