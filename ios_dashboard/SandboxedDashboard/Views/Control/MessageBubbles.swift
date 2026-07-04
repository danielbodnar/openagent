//
//  MessageBubbles.swift
//  SandboxedDashboard
//
//  Extracted from ControlView.swift (mechanical split, no behavior change)
//

import SwiftUI

struct MessageBubble: View {
    let message: ChatMessage
    var isCopied: Bool = false
    var onCopy: (() -> Void)?
    var onRetry: (() -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            if message.isUser {
                Spacer(minLength: 60)
                userBubble
            } else if message.isThinking {
                ThinkingBubble(message: message)
                Spacer(minLength: 60)
            } else if message.isPhase {
                PhaseBubble(message: message)
                Spacer(minLength: 60)
            } else if message.isToolCall {
                ToolCallBubble(message: message)
                Spacer(minLength: 60)
            } else if message.isToolUI {
                toolUIBubble
                Spacer(minLength: 40)
            } else {
                // Assistant messages now use full width
                assistantBubble
            }
        }
    }
    
    @ViewBuilder
    private var toolUIBubble: some View {
        if let toolUI = message.toolUI {
            ToolUIView(content: toolUI)
        }
    }
    
    private var userBubble: some View {
        HStack(alignment: .top, spacing: 8) {
            // Copy button
            if !message.content.isEmpty {
                CopyButton(isCopied: isCopied, onCopy: onCopy)
            }

            VStack(alignment: .trailing, spacing: 4) {
                Text(message.content)
                    .font(.body)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(bubbleBackground)
                    .clipShape(
                        .rect(
                            topLeadingRadius: 20,
                            bottomLeadingRadius: 20,
                            bottomTrailingRadius: 6,
                            topTrailingRadius: 20
                        )
                    )
                    .overlay(
                        UnevenRoundedRectangle(
                            topLeadingRadius: 20,
                            bottomLeadingRadius: 20,
                            bottomTrailingRadius: 6,
                            topTrailingRadius: 20
                        )
                        .fill(Theme.surfaceSheen)
                        .allowsHitTesting(false)
                    )
                    .overlay(
                        UnevenRoundedRectangle(
                            topLeadingRadius: 20,
                            bottomLeadingRadius: 20,
                            bottomTrailingRadius: 6,
                            topTrailingRadius: 20
                        )
                        .strokeBorder(
                            message.sendState.isFailed ? AnyShapeStyle(Theme.error) : AnyShapeStyle(Color.white.opacity(0.10)),
                            lineWidth: message.sendState.isFailed ? 1 : 0.5
                        )
                    )
                    // While the message is awaiting server ack, dim the bubble
                    // and overlay a small spinner so the user has unambiguous
                    // feedback that the send is in flight. (UX audit item #11.)
                    .opacity(message.sendState.isPending ? 0.55 : 1)
                    .overlay(alignment: .bottomTrailing) {
                        if message.sendState.isPending {
                            ProgressView()
                                .controlSize(.mini)
                                .tint(.white)
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
                    .contextMenu {
                        Button {
                            onCopy?()
                        } label: {
                            Label("Copy", systemImage: "doc.on.doc")
                        }
                        if message.sendState.isFailed, onRetry != nil {
                            Button {
                                onRetry?()
                            } label: {
                                Label("Retry send", systemImage: "arrow.clockwise")
                            }
                        }
                    }

                // Inline "Send failed — Tap to retry" affordance directly under
                // the failed bubble. Mirrors iMessage's "Not Delivered" pattern
                // so users get an unmistakable signal and a one-tap recovery.
                if message.sendState.isFailed, let reason = message.sendState.failureReason {
                    Button(action: { onRetry?() }) {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.clockwise.circle.fill")
                                .font(.caption2)
                            Text("Not sent · Tap to retry")
                                .font(.caption2.weight(.medium))
                        }
                        .foregroundStyle(Theme.error)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Retry send. Failed: \(reason)")
                } else {
                    // Timestamp
                    Text(message.timestamp, style: .time)
                        .font(.caption2)
                        .monospacedDigit()
                        .foregroundStyle(Theme.textMuted)
                }
            }
        }
    }

    /// Background color/material for the user bubble. Failed sends render with
    /// a dimmed error tint so the row is unmistakably distinct from a normal
    /// (sent) bubble, in case the user is glancing rather than reading.
    private var bubbleBackground: AnyShapeStyle {
        if message.sendState.isFailed {
            return AnyShapeStyle(Theme.error.opacity(0.55))
        }
        // Subtle top-light gradient instead of a flat fill — same quiet
        // depth treatment the web dashboard uses on raised surfaces.
        return AnyShapeStyle(
            LinearGradient(
                colors: [Theme.accentLight.opacity(0.95), Theme.accent],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private var assistantBubble: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 8) {
                // Status header for assistant messages
                if case .assistant(let success, _, _, _, _) = message.type {
                    HStack(spacing: 6) {
                        Image(systemName: success ? "checkmark.circle.fill" : "xmark.circle.fill")
                            .font(.caption2)
                            .foregroundStyle(success ? Theme.success : Theme.error)

                        if let model = message.displayModel {
                            Text(model)
                                .font(.caption2.monospaced())
                                .foregroundStyle(Theme.textTertiary)
                        }

                        if let cost = message.costFormatted {
                            Text("•")
                                .foregroundStyle(Theme.textMuted)
                            // Cost + source as one calm chip: "$4.22 actual" — the
                            // ALL-CAPS pill version of "ACTUAL" was visually shouting
                            // louder than the cost itself.
                            HStack(spacing: 4) {
                                Text(cost)
                                    .font(.caption2.monospaced())
                                    .foregroundStyle(message.costIsEstimated ? Theme.textSecondary : Theme.success)
                                if let badge = message.costSourceLabel {
                                    Text(badge.lowercased())
                                        .font(.caption2)
                                        .foregroundStyle(Theme.textMuted)
                                }
                            }
                        }

                        Text("•")
                            .foregroundStyle(Theme.textMuted)
                        Text(message.timestamp, style: .time)
                            .font(.caption2)
                            .foregroundStyle(Theme.textMuted)
                    }
                }

                MarkdownView(message.content)
                    .modifier(ControlBodyRenderProbe(name: "MarkdownView"))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(.ultraThinMaterial)
                    .clipShape(
                        .rect(
                            topLeadingRadius: 20,
                            bottomLeadingRadius: 6,
                            bottomTrailingRadius: 20,
                            topTrailingRadius: 20
                        )
                    )
                    .overlay(
                        // Match the bubble's actual clip shape — the previous
                        // uniform 20pt stroke drifted off the 6pt corner.
                        UnevenRoundedRectangle(
                            topLeadingRadius: 20,
                            bottomLeadingRadius: 6,
                            bottomTrailingRadius: 20,
                            topTrailingRadius: 20
                        )
                        .strokeBorder(Theme.edgeHighlight, lineWidth: 0.5)
                    )
                    .contextMenu {
                        Button {
                            onCopy?()
                        } label: {
                            Label("Copy", systemImage: "doc.on.doc")
                        }
                    }

                // Render shared files
                if let files = message.sharedFiles, !files.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(files) { file in
                            SharedFileCardView(file: file)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            // Copy button
            if !message.content.isEmpty {
                CopyButton(isCopied: isCopied, onCopy: onCopy)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Shared File Card View

struct SharedFileCardView: View {
    let file: SharedFile
    @Environment(\.openURL) private var openURL
    @State private var imageData: Data?
    @State private var isLoadingImage = false
    @State private var imageLoadFailed = false

    private var fullURL: URL? {
        // If URL is relative, prepend the base URL
        if file.url.hasPrefix("/") {
            let baseURL = APIService.shared.baseURL
            return URL(string: baseURL + file.url)
        }
        return URL(string: file.url)
    }

    var body: some View {
        if file.isImage {
            imageCard
        } else {
            downloadCard
        }
    }

    private var imageCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Image preview with authentication support. The shimmer
            // skeleton matches the inline rich-image placeholder so the
            // chat feels consistent while either type loads.
            Group {
                if let url = fullURL,
                   let data = imageData,
                   let uiImage = ImageMemoryCache.shared.cachedImage(for: url) ?? UIImage(data: data) {
                    Image(uiImage: uiImage)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: .infinity, maxHeight: 300)
                } else if imageLoadFailed {
                    Image(systemName: "photo")
                        .font(.title)
                        .foregroundStyle(Theme.textMuted)
                        .frame(maxWidth: .infinity, minHeight: 80)
                        .background(Theme.backgroundSecondary)
                } else {
                    ShimmerSkeleton(cornerRadius: 12, height: 200)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .task {
                await loadImage()
            }

            // File info bar
            HStack(spacing: 6) {
                Image(systemName: file.kind.iconName)
                    .font(.caption2)
                    .foregroundStyle(Theme.textMuted)

                Text(file.name)
                    .font(.caption2)
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)

                Spacer()

                if let size = file.formattedSize {
                    Text(size)
                        .font(.caption2)
                        .foregroundStyle(Theme.textMuted)
                }

                Button {
                    if let url = fullURL {
                        openURL(url)
                    }
                } label: {
                    Image(systemName: "arrow.up.right.square")
                        .font(.caption2)
                        .foregroundStyle(Theme.accent)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Theme.backgroundSecondary)
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(Theme.border, lineWidth: 0.5)
        )
    }

    private var downloadCard: some View {
        Button {
            if let url = fullURL {
                openURL(url)
            }
        } label: {
            HStack(spacing: 12) {
                // File type icon
                Image(systemName: file.kind.iconName)
                    .font(.title3)
                    .foregroundStyle(Theme.accent)
                    .frame(width: 40, height: 40)
                    .background(Theme.accent.opacity(0.1))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

                // File info
                VStack(alignment: .leading, spacing: 2) {
                    Text(file.name)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)

                    HStack(spacing: 4) {
                        Text(file.contentType)
                            .font(.caption2)
                            .foregroundStyle(Theme.textMuted)
                            .lineLimit(1)

                        if let size = file.formattedSize {
                            Text("•")
                                .foregroundStyle(Theme.textMuted)
                            Text(size)
                                .font(.caption2)
                                .foregroundStyle(Theme.textMuted)
                        }
                    }
                }

                Spacer()

                // Download indicator
                Image(systemName: "arrow.down.circle")
                    .font(.title3)
                    .foregroundStyle(Theme.textMuted)
            }
            .padding(12)
            .background(.ultraThinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Theme.border, lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
    }

    private func loadImage() async {
        guard let url = fullURL, !isLoadingImage else {
            // If URL is nil (malformed), mark as failed to prevent infinite loading
            if fullURL == nil {
                await MainActor.run {
                    self.imageLoadFailed = true
                    self.isLoadingImage = false
                }
            }
            return
        }

        isLoadingImage = true
        imageLoadFailed = false

        if ImageMemoryCache.shared.cachedImage(for: url) != nil {
            imageData = Data()
            isLoadingImage = false
            return
        }

        do {
            var request = URLRequest(url: url)
            // Bound the per-image fetch to the same window as JSON requests so
            // a stalled image host can't leave the cell spinning behind the
            // 60s URLSession default.
            request.timeoutInterval = APIService.requestTimeout

            // Add authentication token if available
            if let token = APIService.shared.authToken {
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }

            let (data, response) = try await URLSession.shared.data(for: request)

            // Check response status
            if let httpResponse = response as? HTTPURLResponse {
                if httpResponse.statusCode == 200 {
                    // Validate/downsample before storing row state.
                    if await ImageMemoryCache.shared.image(from: data, url: url) != nil {
                        await MainActor.run {
                            self.imageData = data
                        }
                    } else {
                        // Data is not a valid image
                        await MainActor.run {
                            self.imageLoadFailed = true
                        }
                    }
                } else {
                    await MainActor.run {
                        self.imageLoadFailed = true
                    }
                }
            } else {
                // Non-HTTP response (or failed cast) shouldn't leave the spinner running
                await MainActor.run {
                    self.imageLoadFailed = true
                }
            }
        } catch {
            print("Failed to load image: \(error)")
            await MainActor.run {
                self.imageLoadFailed = true
            }
        }

        await MainActor.run {
            isLoadingImage = false
        }
    }
}

// MARK: - Copy Button

struct CopyButton: View {
    let isCopied: Bool
    let onCopy: (() -> Void)?

    var body: some View {
        Button {
            onCopy?()
        } label: {
            Image(systemName: isCopied ? "checkmark" : "doc.on.doc")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(isCopied ? Theme.success : Theme.textSecondary)
                .frame(width: 28, height: 28)
                .background(Theme.backgroundSecondary)
                .clipShape(Circle())
                .overlay(
                    Circle().stroke(Theme.border, lineWidth: 0.5)
                )
        }
        .accessibilityLabel(isCopied ? "Copied" : "Copy message")
    }
}

// MARK: - Phase Bubble

struct PhaseBubble: View {
    let message: ChatMessage
    
    var body: some View {
        if case .phase(let phase, let detail, let agent) = message.type {
            let agentPhase = AgentPhase(rawValue: phase)
            
            HStack(spacing: 12) {
                // Icon with pulse animation
                Image(systemName: agentPhase?.icon ?? "gear")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(Theme.accent)
                    .symbolEffect(.pulse, options: .repeating)
                    .frame(width: 32, height: 32)
                    .background(Theme.accent.opacity(0.1))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(agentPhase?.label ?? phase.replacingOccurrences(of: "_", with: " ").capitalized)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(Theme.accent)

                        if let agent = agent {
                            Text(agent)
                                .font(.caption2.monospaced())
                                .foregroundStyle(Theme.textMuted)
                                .lineLimit(1)
                                .truncationMode(.tail)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Theme.backgroundTertiary)
                                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                        }

                        Text("•")
                            .foregroundStyle(Theme.textMuted)
                            .font(.caption2)
                        Text(message.timestamp, style: .time)
                            .font(.caption2)
                            .foregroundStyle(Theme.textMuted)
                    }

                    if let detail = detail {
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(Theme.textTertiary)
                    }
                }
                
                Spacer()
                
                // Spinner
                ProgressView()
                    .progressViewStyle(.circular)
                    .scaleEffect(0.7)
                    .tint(Theme.accent.opacity(0.5))
            }
            .padding(12)
            .background(.ultraThinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Theme.accent.opacity(0.15), lineWidth: 1)
            )
            .transition(.opacity.combined(with: .scale(scale: 0.95)))
        }
    }
}

// MARK: - Tool Call Bubble (Enhanced)

struct ToolCallBubble: View {
    let message: ChatMessage
    @State private var isExpanded = false
    @State private var elapsedSeconds: Int = 0
    @State private var timerTask: Task<Void, Never>?

    private var toolData: ToolCallData? {
        message.toolData
    }

    private var isRunning: Bool {
        toolData?.state == .running
    }

    private var stateColor: Color {
        guard let state = toolData?.state else {
            return message.isActiveToolCall ? Theme.warning : Theme.textMuted
        }
        switch state {
        case .running: return Theme.warning
        case .success: return Theme.success
        case .error: return Theme.error
        case .cancelled: return Theme.warning
        }
    }

    private var stateIcon: String {
        guard let state = toolData?.state else {
            return message.isActiveToolCall ? "circle.fill" : "checkmark.circle.fill"
        }
        switch state {
        case .running: return "circle.fill"
        case .success: return "checkmark.circle.fill"
        case .error: return "xmark.circle.fill"
        case .cancelled: return "xmark.circle.fill"
        }
    }

    var body: some View {
        if let name = message.toolCallName {
            VStack(alignment: .leading, spacing: 0) {
                // Compact header button
                Button {
                    withAnimation(.spring(duration: 0.25)) {
                        isExpanded.toggle()
                    }
                    HapticService.selectionChanged()
                } label: {
                    HStack(spacing: 6) {
                        // Tool icon
                        Image(systemName: toolIcon(for: name))
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(stateColor)
                            .frame(width: 18, height: 18)
                            .background(stateColor.opacity(0.15))
                            .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))

                        // Tool name
                        Text(name)
                            .font(.caption.monospaced())
                            .foregroundStyle(Theme.accent)
                            .lineLimit(1)

                        // Args preview
                        if let preview = toolData?.argsPreview, !preview.isEmpty {
                            Text("(\(preview))")
                                .font(.caption2)
                                .foregroundStyle(Theme.textMuted)
                                .lineLimit(1)
                                .truncationMode(.tail)
                        }

                        Spacer()

                        // Duration
                        if let data = toolData {
                            Text(isRunning ? "\(formattedElapsed)..." : data.durationFormatted)
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(Theme.textMuted)
                        }

                        // State indicator
                        if isRunning {
                            ProgressView()
                                .progressViewStyle(.circular)
                                .scaleEffect(0.5)
                                .tint(stateColor)
                        } else {
                            Image(systemName: stateIcon)
                                .font(.system(size: 12))
                                .foregroundStyle(stateColor)
                        }

                        // Chevron
                        Image(systemName: "chevron.right")
                            .font(.system(size: 9, weight: .medium))
                            .foregroundStyle(Theme.textMuted)
                            .rotationEffect(.degrees(isExpanded ? 90 : 0))
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(stateColor.opacity(0.05))
                    .clipShape(Capsule())
                    .overlay(
                        Capsule()
                            .stroke(stateColor.opacity(0.2), lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)

                // Expandable content
                if isExpanded {
                    VStack(alignment: .leading, spacing: 10) {
                        // Arguments section
                        if let data = toolData, !data.args.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Arguments")
                                    .font(.caption2)
                                    .fontWeight(.medium)
                                    .foregroundStyle(Theme.textMuted)
                                    .textCase(.uppercase)

                                ScrollView(.horizontal, showsIndicators: false) {
                                    Text(data.argsString)
                                        .font(.caption.monospaced())
                                        .foregroundStyle(Theme.textSecondary)
                                        .padding(8)
                                        .background(Theme.backgroundTertiary.opacity(0.5))
                                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                                }
                                .frame(maxHeight: 120)
                            }
                        }

                        // Result section
                        if let data = toolData, let resultStr = data.resultString {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(data.isErrorResult ? "Error" : "Result")
                                    .font(.caption2)
                                    .fontWeight(.medium)
                                    .foregroundStyle(data.isErrorResult ? Theme.error : Theme.success)
                                    .textCase(.uppercase)

                                ScrollView(.horizontal, showsIndicators: false) {
                                    Text(resultStr)
                                        .font(.caption.monospaced())
                                        .foregroundStyle(data.isErrorResult ? Theme.error : Theme.textSecondary)
                                        .padding(8)
                                        .background((data.isErrorResult ? Theme.error : Theme.backgroundTertiary).opacity(0.1))
                                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                                }
                                .frame(maxHeight: 120)
                            }
                        }

                        // Still running indicator
                        if isRunning {
                            HStack(spacing: 6) {
                                ProgressView()
                                    .progressViewStyle(.circular)
                                    .scaleEffect(0.5)
                                    .tint(Theme.warning)
                                Text("Running for \(formattedElapsed)...")
                                    .font(.caption2)
                                    .foregroundStyle(Theme.warning)
                            }
                        }
                    }
                    .padding(.top, 8)
                    .padding(.horizontal, 4)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            .animation(.spring(duration: 0.25), value: isExpanded)
            .onAppear {
                if isRunning {
                    startTimer()
                }
            }
            .onDisappear {
                timerTask?.cancel()
            }
            .onChange(of: isRunning) { _, running in
                if running {
                    startTimer()
                } else {
                    timerTask?.cancel()
                }
            }
        }
    }

    private var formattedElapsed: String {
        formatDurationString(elapsedSeconds)
    }

    private func startTimer() {
        timerTask?.cancel()
        elapsedSeconds = Int(toolData?.duration ?? 0)
        timerTask = Task { @MainActor in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if !Task.isCancelled {
                    elapsedSeconds = Int(toolData?.duration ?? 0)
                }
            }
        }
    }

    private func toolIcon(for name: String) -> String {
        let lower = name.lowercased()
        if lower.contains("bash") || lower.contains("shell") || lower.contains("terminal") || lower.contains("exec") {
            return "terminal"
        } else if lower.contains("read") || lower.contains("file") || lower.contains("write") {
            return "doc.text"
        } else if lower.contains("search") || lower.contains("grep") || lower.contains("find") || lower.contains("glob") {
            return "magnifyingglass"
        } else if lower.contains("browser") || lower.contains("web") || lower.contains("http") || lower.contains("fetch") {
            return "globe"
        } else if lower.contains("edit") || lower.contains("patch") || lower.contains("notebook") {
            return "chevron.left.forwardslash.chevron.right"
        } else if lower.contains("task") || lower.contains("agent") || lower.contains("subagent") {
            return "person.2"
        } else if lower.contains("desktop") || lower.contains("screenshot") {
            return "display"
        } else if lower.contains("todo") {
            return "checklist"
        } else {
            return "wrench"
        }
    }
}

// MARK: - Thinking Bubble

struct InlineThinkingSurface: View {
    let message: ChatMessage
    let onOpenTimeline: () -> Void

    var body: some View {
        Button(action: onOpenTimeline) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Image(systemName: "brain")
                        .font(.caption)
                        .foregroundStyle(message.thinkingDone ? Theme.textMuted : Theme.accent)
                        .symbolEffect(.pulse, options: message.thinkingDone ? .nonRepeating : .repeating)

                    Text(message.thinkingDone ? "Latest thought" : "Thinking")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(message.thinkingDone ? Theme.textSecondary : Theme.accent)

                    Spacer()

                    Image(systemName: "chevron.up.forward")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Theme.textMuted)
                }

                (Text(message.content) + (message.thinkingDone ? Text("") : Text(" ▍").foregroundColor(Theme.accent)))
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(message.thinkingDone ? 2 : 4)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(10)
            .background(Theme.backgroundSecondary.opacity(0.96))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(message.thinkingDone ? Theme.border : Theme.accent.opacity(0.35), lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
    }
}

struct ThinkingBubble: View {
    let message: ChatMessage
    @State private var isExpanded: Bool = true
    @State private var elapsedSeconds: Int = 0
    @State private var timerTask: Task<Void, Never>?
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Compact header button
            Button {
                withAnimation(.spring(duration: 0.25)) {
                    isExpanded.toggle()
                }
                HapticService.selectionChanged()
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "brain")
                        .font(.caption)
                        .foregroundStyle(Theme.accent)
                        .symbolEffect(.pulse, options: message.thinkingDone ? .nonRepeating : .repeating)

                    Text(message.thinkingDone ? "Thought for \(formattedDuration)" : "Thinking for \(formattedDuration)")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)

                    Text("•")
                        .foregroundStyle(Theme.textMuted)
                        .font(.caption2)
                    Text(message.timestamp, style: .time)
                        .font(.caption2)
                        .monospacedDigit()
                        .foregroundStyle(Theme.textMuted)

                    Spacer()

                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(Theme.textMuted)
                        .rotationEffect(.degrees(isExpanded ? 90 : 0))
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Theme.accent.opacity(0.1))
                .clipShape(Capsule())
            }
            
            // Expandable content
            if isExpanded && !message.content.isEmpty {
                ScrollView {
                    // Inline a blinking caret while streaming so the user can
                    // distinguish in-flight tokens from a settled thought.
                    // Without this, a paused stream looks identical to a
                    // completed one.
                    (Text(message.content) + (message.thinkingDone ? Text("") : Text(" ▍").foregroundColor(Theme.accent)))
                        .font(.caption)
                        .foregroundStyle(Theme.textTertiary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 300) // Allow scrolling for long thinking content
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.white.opacity(0.02))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(Theme.border, lineWidth: 0.5)
                )
                .transition(.opacity.combined(with: .scale(scale: 0.95, anchor: .top)))
            } else if isExpanded && message.content.isEmpty {
                Text("Processing...")
                    .font(.caption)
                    .italic()
                    .foregroundStyle(Theme.textMuted)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
            }
        }
        .onAppear {
            startTimer()
        }
        .onDisappear {
            timerTask?.cancel()
            timerTask = nil
        }
        .onChange(of: message.thinkingDone) { _, done in
            if done {
                timerTask?.cancel()
                timerTask = nil

                if let startTime = message.thinkingStartTime {
                    elapsedSeconds = Int(Date().timeIntervalSince(startTime))
                }
            }

        }
    }
    
    private var formattedDuration: String {
        formatDurationString(elapsedSeconds)
    }
    
    private func startTimer() {
        timerTask?.cancel()
        timerTask = nil

        guard !message.thinkingDone else {
            // Calculate elapsed from start time
            if let startTime = message.thinkingStartTime {
                elapsedSeconds = Int(Date().timeIntervalSince(startTime))
            }
            return
        }

        // Update every second while thinking
        timerTask = Task { @MainActor in
            while !Task.isCancelled {
                if let startTime = message.thinkingStartTime {
                    elapsedSeconds = Int(Date().timeIntervalSince(startTime))
                } else {
                    elapsedSeconds += 1
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }
}


// MARK: - Thoughts Sheet

struct ThoughtsSheet: View {
    let messages: [ChatMessage]
    @Environment(\.dismiss) private var dismiss

    /// All thinking messages
    private var thinkingMessages: [ChatMessage] {
        messages.filter { $0.isThinking }
    }

    /// Stable, chronological thought rows. Completed rows are deduplicated,
    /// but they do not move between separate active/completed sections when
    /// streaming finishes.
    private var visibleThoughts: [ChatMessage] {
        var seen = Set<String>()
        return thinkingMessages.filter { msg in
            let trimmed = msg.content.trimmingCharacters(in: .whitespacesAndNewlines)
            guard msg.thinkingDone else { return true }
            guard !trimmed.isEmpty else { return false }
            guard !seen.contains(trimmed) else { return false }
            seen.insert(trimmed)
            return true
        }
    }

    private var hasActiveThinking: Bool {
        visibleThoughts.contains { !$0.thinkingDone }
    }

    /// Count aligned with what is actually rendered in the sheet.
    private var visibleThoughtCount: Int {
        visibleThoughts.count
    }

    private var hasVisibleThoughts: Bool {
        visibleThoughtCount > 0
    }

    var body: some View {
        NavigationStack {
            Group {
                if !hasVisibleThoughts {
                    ContentUnavailableView(
                        "No Thoughts Yet",
                        systemImage: "brain",
                        description: Text("Agent thoughts will appear here during execution.")
                    )
                } else {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: 14) {
                                ForEach(Array(visibleThoughts.enumerated()), id: \.element.id) { index, msg in
                                    ThoughtTimelineRow(
                                        message: msg,
                                        emphasize: !msg.thinkingDone,
                                        isLatest: index == visibleThoughts.count - 1
                                    )
                                    .id(msg.id)
                                    .accessibilityIdentifier(index == visibleThoughts.count - 1 ? "thought-latest" : "thought-row")
                                }
                                Color.clear
                                    .frame(height: 1)
                                    .id("thoughts-bottom")
                                    .accessibilityIdentifier("thoughts-bottom")
                            }
                            .padding()
                        }
                        .accessibilityIdentifier("thoughts-timeline")
                        .onAppear {
                            scrollToLatestThought(proxy)
                        }
                        .onChange(of: visibleThoughtCount) { _, _ in
                            scrollToLatestThought(proxy)
                        }
                        .onChange(of: hasActiveThinking) { _, _ in
                            scrollToLatestThought(proxy)
                        }
                    }
                }
            }
            .navigationTitle(hasActiveThinking ? "Thinking" : "Thoughts")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    HStack(spacing: 4) {
                        if hasActiveThinking {
                            Image(systemName: "brain")
                                .font(.caption)
                                .foregroundStyle(Theme.accent)
                                .symbolEffect(.pulse, options: .repeating)
                        }
                        Text("\(visibleThoughtCount)")
                            .font(.subheadline.monospacedDigit())
                            .foregroundStyle(Theme.textMuted)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func scrollToLatestThought(_ proxy: ScrollViewProxy) {
        guard hasVisibleThoughts else { return }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 80_000_000)
            withAnimation(.snappy(duration: 0.2)) {
                proxy.scrollTo("thoughts-bottom", anchor: .bottom)
            }
        }
    }
}

struct ThoughtTimelineRow: View {
    let message: ChatMessage
    let emphasize: Bool
    let isLatest: Bool
    @State private var isExpanded: Bool
    @State private var elapsedSeconds: Int = 0
    @State private var timerTask: Task<Void, Never>?

    init(message: ChatMessage, emphasize: Bool, isLatest: Bool) {
        self.message = message
        self.emphasize = emphasize
        self.isLatest = isLatest
        _isExpanded = State(initialValue: emphasize || isLatest)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(spacing: 0) {
                Circle()
                    .fill(emphasize ? Theme.accent : Theme.textMuted)
                    .frame(width: 8, height: 8)
                Rectangle()
                    .fill(Theme.border)
                    .frame(width: 1)
            }

            VStack(alignment: .leading, spacing: 6) {
                Button {
                    withAnimation(.spring(duration: 0.2)) {
                        isExpanded.toggle()
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "brain")
                            .font(.caption)
                            .foregroundStyle(message.thinkingDone ? Theme.textMuted : Theme.accent)
                            .symbolEffect(.pulse, options: message.thinkingDone ? .nonRepeating : .repeating)

                        Text(message.thinkingDone ? "Thought for \(formatDurationString(elapsedSeconds))" : "Thinking for \(formatDurationString(elapsedSeconds))")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Theme.textSecondary)

                        Spacer()

                        Image(systemName: "chevron.right")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(Theme.textMuted)
                            .rotationEffect(.degrees(isExpanded ? 90 : 0))
                    }
                }

                if isExpanded && !message.content.isEmpty {
                    Text(message.content)
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(10)
            .background(Theme.backgroundSecondary.opacity(emphasize ? 1 : 0.8))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .onAppear {
            startTimer()
        }
        .onDisappear {
            timerTask?.cancel()
            timerTask = nil
        }
        .onChange(of: message.thinkingDone) { _, done in
            if done {
                timerTask?.cancel()
                timerTask = nil
                if let startTime = message.thinkingStartTime {
                    elapsedSeconds = Int(Date().timeIntervalSince(startTime))
                }
            }
        }
        .onChange(of: isLatest) { _, latest in
            if latest {
                withAnimation(.spring(duration: 0.2)) {
                    isExpanded = true
                }
            }
        }
    }

    private func startTimer() {
        timerTask?.cancel()
        timerTask = nil

        guard !message.thinkingDone else {
            if let startTime = message.thinkingStartTime {
                elapsedSeconds = Int(Date().timeIntervalSince(startTime))
            }
            return
        }

        timerTask = Task { @MainActor in
            while !Task.isCancelled {
                if let startTime = message.thinkingStartTime {
                    elapsedSeconds = Int(Date().timeIntervalSince(startTime))
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

}

// MARK: - Flow Layout

struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let result = FlowResult(in: proposal.width ?? 0, spacing: spacing, subviews: subviews)
        return result.size
    }
    
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = FlowResult(in: bounds.width, spacing: spacing, subviews: subviews)
        for (index, subview) in subviews.enumerated() {
            subview.place(at: CGPoint(x: bounds.minX + result.positions[index].x,
                                       y: bounds.minY + result.positions[index].y),
                          proposal: .unspecified)
        }
    }
    
    struct FlowResult {
        var size: CGSize = .zero
        var positions: [CGPoint] = []
        
        init(in maxWidth: CGFloat, spacing: CGFloat, subviews: Subviews) {
            var x: CGFloat = 0
            var y: CGFloat = 0
            var rowHeight: CGFloat = 0
            
            for subview in subviews {
                let size = subview.sizeThatFits(.unspecified)
                
                if x + size.width > maxWidth && x > 0 {
                    x = 0
                    y += rowHeight + spacing
                    rowHeight = 0
                }
                
                positions.append(CGPoint(x: x, y: y))
                rowHeight = max(rowHeight, size.height)
                x += size.width + spacing
                self.size.width = max(self.size.width, x)
            }
            
            self.size.height = y + rowHeight
        }
    }
}

// MARK: - Grouped Chat Item

/// Represents either a single message or a group of consecutive tool calls
enum GroupedChatItem: Identifiable {
    case single(ChatMessage)
    case toolGroup(groupId: String, tools: [ChatMessage])

    var id: String {
        switch self {
        case .single(let message):
            return message.id
        case .toolGroup(let groupId, _):
            return "group-\(groupId)"
        }
    }
}

// MARK: - Tool Group View

/// Displays a group of tool calls with expand/collapse functionality
struct ToolGroupView: View {
    let groupId: String
    let tools: [ChatMessage]
    @Binding var expandedGroups: Set<String>

    private var isExpanded: Bool {
        expandedGroups.contains(groupId)
    }

    private var hiddenCount: Int {
        tools.count - 1
    }

    private var lastTool: ChatMessage? {
        tools.last
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Expand/collapse button
            if hiddenCount > 0 {
                Button {
                    withAnimation(.spring(duration: 0.25)) {
                        if isExpanded {
                            expandedGroups.remove(groupId)
                        } else {
                            expandedGroups.insert(groupId)
                        }
                    }
                    HapticService.selectionChanged()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(Theme.textMuted)

                        Text(isExpanded ? "Hide \(hiddenCount) previous tool\(hiddenCount > 1 ? "s" : "")" : "Show \(hiddenCount) previous tool\(hiddenCount > 1 ? "s" : "")")
                            .font(.caption2)
                            .foregroundStyle(Theme.textMuted)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Theme.backgroundSecondary.opacity(0.5))
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }

            // Show all tools if expanded, otherwise just the last one
            if isExpanded {
                ForEach(tools) { tool in
                    ToolCallBubble(message: tool)
                }
            } else if let last = lastTool {
                ToolCallBubble(message: last)
            }
        }
    }
}

// MARK: - Mission Switcher Sheet

