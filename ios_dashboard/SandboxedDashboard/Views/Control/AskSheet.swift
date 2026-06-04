import SwiftUI

/// Ask — the iOS surface for the non-interrupting sidecar co-pilot.
///
/// Presented as a bottom sheet (medium/large detents) over the mission. It runs
/// in its own lane: it never touches the mission's queue or the working agent.
/// Threads/messages live in a separate backend store and are rendered here with
/// a distinct cyan "co-pilot" identity.
struct AskSheet: View {
    let missionId: String
    /// Drop an Ask answer into the real mission composer (optional bridge).
    var onSendToAgent: ((String) -> Void)? = nil
    let onDismiss: () -> Void

    @State private var threads: [AskThread] = []
    @State private var threadId: String?
    @State private var messages: [AskMessage] = []
    @State private var input: String = ""
    @State private var isLoading = false
    @State private var errorText: String?
    // Id of the assistant bubble currently being streamed into (nil between segments).
    @State private var streamId: String?
    // Bumped on send / thread switch so a stale post-stream sync can be skipped.
    @State private var streamGen = 0

    private let api = APIService.shared
    private let copilot = Color.cyan

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                conversation
                composer
            }
            .background(Theme.backgroundSecondary)
            .navigationTitle("Ask")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
            // Re-run when the mission changes while the sheet stays open: a
            // bare `.task` would keep the old mission's threads (and let a
            // superseded stream mutate the view). `id:` restarts it per mission.
            .task(id: missionId) {
                streamGen += 1
                isLoading = false
                streamId = nil
                threadId = nil
                messages = []
                threads = []
                errorText = nil
                await loadThreads()
            }
        }
    }

    // MARK: - Conversation

    private var conversation: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if messages.isEmpty && !isLoading {
                        emptyState
                    }
                    ForEach(messages) { message in
                        AskBubble(
                            message: message,
                            copilot: copilot,
                            onSendToAgent: onSendToAgent,
                            onRetry: message.sendState.isFailed
                                ? { Task { await retry(message) } } : nil
                        )
                        .id(message.id)
                    }
                    if isLoading {
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.small)
                            Text("thinking…")
                                .font(.caption)
                                .foregroundStyle(copilot.opacity(0.8))
                        }
                        .id("loading")
                    }
                    if let errorText {
                        Text(errorText)
                            .font(.caption)
                            .foregroundStyle(Theme.error)
                            .padding(8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Theme.error.opacity(0.1))
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
                .padding(16)
            }
            .onChange(of: messages.count) { _, _ in
                if let last = messages.last {
                    withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
            .onChange(of: isLoading) { _, loading in
                if loading { withAnimation { proxy.scrollTo("loading", anchor: .bottom) } }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "sparkles")
                .font(.system(size: 22))
                .foregroundStyle(copilot.opacity(0.5))
            Text("Ask about this mission — what it's doing, why, or inspect the workspace. The working agent is never interrupted.")
                .font(.footnote)
                .foregroundStyle(Theme.textMuted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }

    // MARK: - Composer

    private var composer: some View {
        HStack(spacing: 8) {
            TextField("Ask the co-pilot…", text: $input, axis: .vertical)
                .lineLimit(1...4)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Theme.card)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .onSubmit { Task { await send() } }

            Button {
                Task { await send() }
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isLoading ? Theme.textMuted : copilot)
            }
            .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isLoading)
        }
        .padding(12)
        .background(.ultraThinMaterial)
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button("Done") { onDismiss() }
                .foregroundStyle(copilot)
        }
        ToolbarItem(placement: .topBarTrailing) {
            HStack(spacing: 14) {
                Menu {
                    Button {
                        newThread()
                    } label: {
                        Label("New thread", systemImage: "plus")
                    }
                    if !threads.isEmpty {
                        Divider()
                        ForEach(threads) { thread in
                            Button {
                                Task { await selectThread(thread.id) }
                            } label: {
                                Label(thread.displayTitle, systemImage: thread.id == threadId ? "checkmark" : "bubble.left")
                            }
                        }
                    }
                } label: {
                    Image(systemName: "bubble.left.and.bubble.right")
                }

                Button(role: .destructive) {
                    Task { await clearThread() }
                } label: {
                    Image(systemName: "trash")
                }
                .disabled(threadId == nil)
            }
            .foregroundStyle(copilot)
        }
    }

    // MARK: - Actions

    private func loadThreads() async {
        do {
            let fetched = try await api.listAskThreads(missionId: missionId)
            threads = fetched
            if let first = fetched.first {
                await selectThread(first.id)
            }
        } catch {
            // Non-fatal — just start with an empty thread.
        }
    }

    private func selectThread(_ id: String) async {
        streamGen += 1
        let gen = streamGen
        isLoading = false
        streamId = nil
        threadId = id
        do {
            let detail = try await api.getAskThread(missionId: missionId, threadId: id)
            // A later switch / send may have superseded this fetch.
            if gen == streamGen { messages = detail.messages }
        } catch {
            if gen == streamGen { messages = [] }
        }
    }

    private func newThread() {
        streamGen += 1
        isLoading = false
        streamId = nil
        threadId = nil
        messages = []
        input = ""
        errorText = nil
    }

    private func send() async {
        let content = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !content.isEmpty, !isLoading else { return }
        input = ""
        let userId = "u-\(UUID().uuidString)"
        messages.append(
            AskMessage(
                id: userId,
                threadId: threadId ?? "",
                seq: messages.count + 1,
                role: "user",
                content: content,
                toolName: nil,
                toolCallId: nil,
                createdAt: isoNow(),
                sendState: .pending
            )
        )
        await runTurn(userMessageId: userId, content: content)
    }

    /// Re-run a co-pilot turn for a user message whose send failed. Reuses the
    /// existing bubble (no duplicate) with the same rollback semantics.
    private func retry(_ message: AskMessage) async {
        guard message.isUser, message.sendState.isFailed, !isLoading else { return }
        await runTurn(userMessageId: message.id, content: message.content)
    }

    /// Drives one streamed co-pilot turn. The user bubble is **preserved**
    /// across failures (flipped to `.failed` with a tap-to-retry) instead of
    /// being rolled back — mirroring the main mission composer so a dropped
    /// message never silently vanishes. Only this turn's streamed
    /// assistant/tool bubbles roll back on failure.
    private func runTurn(userMessageId: String, content: String) async {
        errorText = nil
        isLoading = true
        streamId = nil
        streamGen += 1
        let gen = streamGen
        if let i = messages.firstIndex(where: { $0.id == userMessageId }) {
            messages[i].sendState = .pending
        }
        // Roll-back boundary: keep everything up to and including the user
        // bubble; drop streamed bubbles appended during this turn on failure.
        let baseCount =
            messages.firstIndex(where: { $0.id == userMessageId }).map { $0 + 1 }
            ?? messages.count

        var failure: String?
        do {
            for try await ev in api.askStream(
                missionId: missionId,
                content: content,
                threadId: threadId
            ) {
                // A newer send / thread switch superseded this turn.
                if gen != streamGen { return }
                // First event back means the backend accepted the message.
                if let i = messages.firstIndex(where: { $0.id == userMessageId }),
                    messages[i].sendState.isPending
                {
                    messages[i].sendState = .sent
                }
                handleStreamEvent(ev)
            }
        } catch {
            if gen == streamGen { failure = error.localizedDescription }
        }

        // Superseded — a newer turn owns the list and the loading flag now.
        if gen != streamGen { return }

        if let failure {
            // Preserve the user bubble (failed + retry); drop streamed bubbles.
            if messages.count > baseCount {
                messages = Array(messages.prefix(baseCount))
            }
            if let i = messages.firstIndex(where: { $0.id == userMessageId }) {
                messages[i].sendState = .failed(reason: failure)
            }
        } else if let i = messages.firstIndex(where: { $0.id == userMessageId }) {
            messages[i].sendState = .sent
        }
        isLoading = false
        streamId = nil
    }

    private func isoNow() -> String {
        ISO8601DateFormatter().string(from: Date())
    }

    private func handleStreamEvent(_ ev: AskStreamEvent) {
        switch ev.type {
        case "delta":
            guard let text = ev.content else { return }
            if let id = streamId,
                let idx = messages.firstIndex(where: { $0.id == id })
            {
                let m = messages[idx]
                messages[idx] = AskMessage(
                    id: m.id,
                    threadId: m.threadId,
                    seq: m.seq,
                    role: m.role,
                    content: m.content + text,
                    toolName: nil,
                    toolCallId: nil,
                    createdAt: m.createdAt
                )
            } else {
                let id = "a-\(UUID().uuidString)"
                streamId = id
                messages.append(
                    AskMessage(
                        id: id,
                        threadId: threadId ?? "",
                        seq: messages.count + 1,
                        role: "assistant",
                        content: text,
                        toolName: nil,
                        toolCallId: nil,
                        createdAt: isoNow()
                    )
                )
            }
        case "tool_call":
            streamId = nil
            messages.append(
                AskMessage(
                    id: "tc-\(ev.toolCallId ?? UUID().uuidString)",
                    threadId: threadId ?? "",
                    seq: messages.count + 1,
                    role: "tool_call",
                    content: ev.args ?? "",
                    toolName: ev.name,
                    toolCallId: ev.toolCallId,
                    createdAt: isoNow()
                )
            )
        case "tool_result":
            messages.append(
                AskMessage(
                    id: "tr-\(ev.toolCallId ?? UUID().uuidString)",
                    threadId: threadId ?? "",
                    seq: messages.count + 1,
                    role: "tool_result",
                    content: ev.result ?? "",
                    toolName: ev.name,
                    toolCallId: ev.toolCallId,
                    createdAt: isoNow()
                )
            )
        case "done":
            streamId = nil
            let tid = ev.threadId
            if let tid { threadId = tid }
            let gen = streamGen
            Task {
                if let refreshed = try? await api.listAskThreads(missionId: missionId),
                    gen == streamGen
                {
                    threads = refreshed
                }
                // Reconcile the streamed bubbles with the canonical persisted
                // messages, unless a newer send / thread switch superseded this.
                if let tid,
                    let detail = try? await api.getAskThread(missionId: missionId, threadId: tid),
                    gen == streamGen, threadId == tid
                {
                    messages = detail.messages
                }
            }
        // "error" events are surfaced via the throw path in askStream, so they
        // flow through send()'s catch (gen-guarded rollback + restore).
        default:
            break
        }
    }

    private func clearThread() async {
        guard let id = threadId else { return }
        try? await api.deleteAskThread(missionId: missionId, threadId: id)
        if let refreshed = try? await api.listAskThreads(missionId: missionId) {
            threads = refreshed
        }
        newThread()
    }
}

// MARK: - Bubble

private struct AskBubble: View {
    let message: AskMessage
    let copilot: Color
    var onSendToAgent: ((String) -> Void)?
    var onRetry: (() -> Void)? = nil

    var body: some View {
        if message.isUser {
            HStack {
                Spacer(minLength: 40)
                VStack(alignment: .trailing, spacing: 4) {
                    Text(message.content)
                        .font(.subheadline)
                        .foregroundStyle(Theme.textPrimary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(
                            message.sendState.isFailed
                                ? Theme.error.opacity(0.18) : Theme.card
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .stroke(
                                    message.sendState.isFailed ? Theme.error : Color.clear,
                                    lineWidth: 1
                                )
                        )
                        // Dim + spinner while awaiting backend ack; this is the
                        // co-pilot peer of the main composer's pending bubble.
                        .opacity(message.sendState.isPending ? 0.55 : 1)
                        .overlay(alignment: .bottomTrailing) {
                            if message.sendState.isPending {
                                ProgressView()
                                    .controlSize(.mini)
                                    .padding(6)
                            } else if message.sendState.isFailed {
                                Image(systemName: "exclamationmark.circle.fill")
                                    .font(.caption)
                                    .foregroundStyle(Theme.error)
                                    .padding(6)
                            }
                        }
                        .animation(.easeOut(duration: 0.15), value: message.sendState.isPending)
                        .animation(.easeOut(duration: 0.15), value: message.sendState.isFailed)
                    // Inline "Not sent · Tap to retry" — preserves the message
                    // on screen instead of dropping it on a failed turn.
                    if message.sendState.isFailed {
                        Button { onRetry?() } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "arrow.clockwise.circle.fill")
                                    .font(.caption2)
                                Text("Not sent · Tap to retry")
                                    .font(.caption2.weight(.medium))
                            }
                            .foregroundStyle(Theme.error)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        } else if message.isTool {
            HStack(alignment: .top, spacing: 6) {
                Image(systemName: "terminal")
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.textMuted)
                Text(toolSummary)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Theme.textMuted)
                    .lineLimit(3)
            }
            .padding(.leading, 24)
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            // assistant
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.system(size: 13))
                    .foregroundStyle(copilot)
                    .padding(.top, 2)
                VStack(alignment: .leading, spacing: 6) {
                    Text(message.content)
                        .font(.subheadline)
                        .foregroundStyle(Theme.textPrimary)
                        .textSelection(.enabled)
                    if let onSendToAgent {
                        Button {
                            onSendToAgent(message.content)
                        } label: {
                            Label("Send to agent", systemImage: "arrow.uturn.left")
                                .font(.caption2)
                                .foregroundStyle(copilot.opacity(0.8))
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(copilot.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
    }

    private var toolSummary: String {
        let label = message.toolName.map { "\($0) → " } ?? "↳ "
        let body: String
        if message.isToolCall, let data = message.content.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            body = (obj["command"] as? String) ?? (obj["path"] as? String) ?? message.content
        } else {
            body = message.content
        }
        let trimmed = body.count > 200 ? String(body.prefix(200)) + "…" : body
        return label + trimmed
    }
}
