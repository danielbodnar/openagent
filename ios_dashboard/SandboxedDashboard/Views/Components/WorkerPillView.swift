//
//  WorkerPillView.swift
//  SandboxedDashboard
//
//  Floating pill showing worker count for boss missions.
//  Tapping opens the worker sheet.
//

import SwiftUI

struct WorkerPillView: View {
    let workers: [Mission]
    let runningWorkers: [RunningMissionInfo]
    let onTap: () -> Void

    private var buckets: WorkerBuckets {
        WorkerBuckets(workers: workers, runningWorkers: runningWorkers)
    }

    private var activeCount: Int { buckets.active.count }
    private var waitingCount: Int { buckets.waiting.count }
    private var completedCount: Int { buckets.done.count }
    private var failedCount: Int { buckets.failed.count }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Image(systemName: "person.3.fill")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.accent)

                // Headline = workers running right now, NOT the cumulative
                // total. The total counts every sub-mission that ever ran,
                // most of which have finished and are never reused, so leading
                // with it implied a far larger live fleet than exists.
                Text("\(activeCount)")
                    .font(.caption.weight(.semibold))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .foregroundStyle(activeCount > 0 ? Theme.textPrimary : Theme.textMuted)
                Text("active")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(Theme.textTertiary)

                if waitingCount > 0 {
                    HStack(spacing: 3) {
                        Circle()
                            .fill(Theme.info)
                            .frame(width: 5, height: 5)
                        Text("\(waitingCount)")
                            .font(.system(size: 10, weight: .medium).monospaced())
                            .foregroundStyle(Theme.info)
                    }
                }

                if failedCount > 0 {
                    HStack(spacing: 3) {
                        Circle()
                            .fill(Theme.error)
                            .frame(width: 5, height: 5)
                        Text("\(failedCount)")
                            .font(.system(size: 10, weight: .medium).monospaced())
                            .foregroundStyle(Theme.error)
                    }
                }

                // Cumulative count of every sub-mission ever spawned — kept
                // for reference but visually muted so it doesn't read as a
                // live fleet size.
                Text("· \(workers.count) total")
                    .font(.system(size: 10))
                    .monospacedDigit()
                    .foregroundStyle(Theme.textMuted)

                Image(systemName: "chevron.up")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(Theme.textMuted)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            .overlay(
                Capsule()
                    .fill(Theme.surfaceSheen)
                    .allowsHitTesting(false)
            )
            .overlay(
                Capsule()
                    .strokeBorder(Theme.edgeHighlight, lineWidth: 0.5)
            )
            .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
        }
        .buttonStyle(.plain)
        // Roll digits instead of snapping when worker states change.
        .animation(.snappy, value: workers.count)
        .animation(.snappy, value: activeCount)
        .animation(.snappy, value: waitingCount)
        .animation(.snappy, value: failedCount)
    }
}
