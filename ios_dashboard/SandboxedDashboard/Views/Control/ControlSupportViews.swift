//
//  ControlSupportViews.swift
//  SandboxedDashboard
//
//  Extracted from ControlView.swift (mechanical split, no behavior change)
//

import SwiftUI

// MARK: - Scroll Offset Preference Key

struct ScrollOffsetPreferenceKey: PreferenceKey {
    nonisolated(unsafe) static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

// MARK: - Conditionally Lazy VStack

/// VStack for small content, LazyVStack past a threshold. See
/// `ControlView.lazyConversationThreshold` for why laziness is opt-in here.
struct ConditionallyLazyVStack<Content: View>: View {
    let isLazy: Bool
    var spacing: CGFloat? = nil
    @ViewBuilder let content: () -> Content

    var body: some View {
        if isLazy {
            LazyVStack(spacing: spacing, content: content)
        } else {
            VStack(spacing: spacing, content: content)
        }
    }
}

// MARK: - Conversation Rows

struct ConversationRowsView: View {
    let groupedItems: [GroupedChatItem]
    let copiedMessageId: String?
    @Binding var expandedToolGroups: Set<String>
    let onCopy: (ChatMessage) -> Void
    let onRetry: (ChatMessage) -> Void

    var body: some View {
        ForEach(groupedItems) { item in
            switch item {
            case .single(let message):
                MessageBubble(
                    message: message,
                    isCopied: copiedMessageId == message.id,
                    onCopy: { onCopy(message) },
                    onRetry: message.sendState.isFailed ? { onRetry(message) } : nil
                )
                .modifier(ControlBodyRenderProbe(name: "MessageBubble"))
                .id(message.id)
            case .toolGroup(let groupId, let tools):
                ToolGroupView(
                    groupId: groupId,
                    tools: tools,
                    expandedGroups: $expandedToolGroups
                )
                .modifier(ControlBodyRenderProbe(name: "ToolGroupView"))
                .id(item.id)
            }
        }
    }
}

// MARK: - Message Bubble

/// Concrete struct holding the main content stack, parameterised on the
/// dynamic bits ControlView needs to inject. Pulling this out of
/// `ControlView` lets SwiftUI's type-checker resolve it independently of
/// the toolbar + sheet + onChange chain on the parent body.
struct MainContentStack<Banner: View, Pill: View, Messages: View, Worker: View, Input: View>: View {
    let showBanner: Bool
    let bannerView: Banner
    let showStaleCachePill: Bool
    let staleCachePill: Pill
    let messagesView: Messages
    let workerPill: Worker
    let inputView: Input

    var body: some View {
        VStack(spacing: 0) {
            if showBanner {
                bannerView
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            if showStaleCachePill {
                staleCachePill
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            ZStack(alignment: .bottom) {
                messagesView
                workerPill
            }
            inputView
        }
        .animation(.easeInOut(duration: 0.2), value: showBanner)
        .animation(.easeInOut(duration: 0.2), value: showStaleCachePill)
    }
}

/// Renders the connection-state banner. Extracted to a separate struct so
/// `ControlView.body` doesn't grow past the Swift type-checker's complexity
/// budget.
struct ConnectionBannerView: View {
    let state: ConnectionState

    var body: some View {
        // Degraded is a softer signal than disconnect/reconnect; use the
        // standard textSecondary tone rather than warning so users on a
        // marginal cell don't get a red flag for every minor slowdown.
        let tint: Color = state.isDegraded ? Theme.textSecondary : Theme.warning
        return HStack(spacing: 8) {
            Image(systemName: state.icon)
                .font(.system(size: 11, weight: .semibold))
                .symbolEffect(.pulse, options: state.isDegraded ? .nonRepeating : .repeating)
            Text(state.label)
                .font(.caption.weight(.medium))
            Spacer()
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .background(tint.opacity(0.12))
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(tint.opacity(0.25))
                .frame(height: 0.5)
        }
    }
}
