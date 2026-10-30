import BitChordKit
import SwiftUI

/// The full player, native: the cover, the track, its controls, and lyrics or the queue in place of
/// the cover. Everything it shows comes from [PlayerModel]; everything it does goes to NativePlayer.
///
/// The position is read four times a second while the screen is up, and the screen is otherwise
/// still: the backdrop is drawn once a track, and nothing redraws while the music just plays.
struct NowPlayingView: View {
    @StateObject private var model = PlayerModel()
    @StateObject private var artwork = ArtworkLoader()
    @State private var panel: Panel = .cover
    @State private var scrubbing: Double?
    @State private var pipelineOpen = false
    @State private var menuOpen = false
    @State private var timingOpen = false
    @State private var providersOpen = false
    /// How far the cover follows a sideways drag, damped: a skip hint, not a position.
    @State private var swipe: CGFloat = 0
    @State private var canvasReady = false

    private let player = NativePlayer.shared

    enum Panel { case cover, lyrics, queue }

    var body: some View {
        presentations(lifecycle(screen))
    }

    private var screen: some View {
        ZStack {
            PlayerBackdrop(image: artwork.image, palette: artwork.palette)
            if let clip = model.canvas, clip.fullScreen {
                FullScreenCanvas(clip: clip, playing: model.now?.isPlaying ?? false, ready: $canvasReady)
            }
            if let now = model.now {
                VStack(spacing: 0) {
                    header(now)
                    Group {
                        switch panel {
                        case .cover: cover(now)
                        case .lyrics: LyricsPanel(model: model, onSeek: seek, onTiming: { timingOpen = true }, onProviders: { providersOpen = true })
                        case .queue: QueuePanel(model: model, now: now)
                        }
                    }
                    .frame(maxHeight: .infinity)
                    .transition(.opacity)
                    if panel != .cover { compactTitle(now).padding(.top, 12) }
                    scrubber(now).padding(.top, 18)
                    controls(now).padding(.top, 14)
                    volume.padding(.top, 18)
                    bottomBar.padding(.top, 14).padding(.bottom, 6)
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 26)
                .foregroundStyle(.white)
            }
        }
    }

    // Split from the body so the compiler type-checks each part on its own.
    private func lifecycle<V: View>(_ content: V) -> some View {
        content
        .preferredColorScheme(.dark)
        .onAppear {
            // The simulator tour reads this to know the native screen came up.
            print("native player: shown")
            model.start()
            if let name = NativePlayerPresenter.requestedPanel { show(name) }
        }
        .onReceive(NotificationCenter.default.publisher(for: NativePlayerPresenter.panelRequested)) { note in
            if let name = note.object as? String { show(name) }
        }
        .onDisappear { model.stop() }
        .onChange(of: model.now?.artworkUrl) { artwork.load($0) }
        .onChange(of: model.canvas?.url) { _ in canvasReady = false }
    }

    private func presentations<V: View>(_ content: V) -> some View {
        content
        .fullScreenCover(isPresented: $pipelineOpen) {
            AudioPipelineSheet { pipelineOpen = false }
                .ignoresSafeArea()
                .clearBackground()
        }
        // The Compose song menu over the screen, which stays up under it; the menu slides itself in.
        .fullScreenCover(isPresented: $menuOpen, onDismiss: {
            if player.takeLyricsTimingRequest() { timingOpen = true }
        }) {
            MenuSheet { withoutAnimation { menuOpen = false } }
                .ignoresSafeArea()
                .clearBackground()
        }
        .sheet(isPresented: $timingOpen) {
            LyricsTimingSheet(offsetMs: Int(model.lyrics?.offsetMs ?? 0))
                .preferredColorScheme(.dark)
        }
        .sheet(isPresented: $providersOpen) {
            LyricsProvidersSheet().preferredColorScheme(.dark)
        }
    }

    // MARK: Parts

    private func header(_ now: NowPlayingSnapshot) -> some View {
        Group {
            if let from = now.playingFrom, !from.isEmpty {
                Button { player.openPlaybackSource() } label: {
                    Text(from)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.white.opacity(0.6))
                        .lineLimit(1)
                }
                .disabled(!now.canOpenSource)
            } else {
                Text(" ").font(.footnote)
            }
        }
        .padding(.top, 22)
        .padding(.bottom, 8)
    }

    private func cover(_ now: NowPlayingSnapshot) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 8)
            ZStack(alignment: .bottom) {
                // The square first and the cover inside it: a 16:9 video thumbnail filled on its own
                // asks for its full width, and the whole screen widened to give it that.
                Color.white.opacity(0.08)
                    .aspectRatio(1, contentMode: .fit)
                    .overlay {
                        if let image = artwork.image {
                            Image(uiImage: image).resizable().scaledToFill()
                        } else {
                            Image(systemName: "music.note").font(.system(size: 60)).foregroundStyle(.white.opacity(0.3))
                        }
                    }
                    .overlay {
                        // A square clip plays in the cover's place, over the still until its first frame.
                        if let clip = model.canvas, !clip.fullScreen {
                            CanvasVideo(clip: clip, playing: now.isPlaying, ready: $canvasReady)
                                .opacity(canvasReady ? 1 : 0)
                                .animation(Animation.easeInOut(duration: 0.4), value: canvasReady)
                        }
                    }
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .shadow(color: .black.opacity(0.4), radius: 24, y: 12)
                    .scaleEffect(now.isPlaying ? 1 : 0.84)
                    .animation(.spring(response: 0.45, dampingFraction: 0.75), value: now.isPlaying)
                    // A tall clip behind the screen is the cover now; the square only keeps its place.
                    .opacity(model.canvas?.fullScreen == true && canvasReady ? 0 : 1)
                if let line = now.qualityLine {
                    Text(line)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.white.opacity(0.75))
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(.black.opacity(0.35), in: Capsule())
                        .padding(.bottom, 10)
                        .opacity(now.isPlaying ? 1 : 0)
                }
            }
            .offset(x: swipe)
            Spacer(minLength: 20)
            fullTitle(now)
        }
        .contentShape(Rectangle())
        // Sideways skips, as on the phone; with the sheet's own pull-down left to work alongside it.
        .simultaneousGesture(skipSwipe)
    }

    private var skipSwipe: some Gesture {
        DragGesture(minimumDistance: 16)
            .onChanged { drag in
                guard abs(drag.translation.width) > abs(drag.translation.height) else { return }
                swipe = drag.translation.width * 0.35
            }
            .onEnded { drag in
                let dx = drag.translation.width
                if abs(dx) > abs(drag.translation.height) {
                    if dx <= -Self.swipeThreshold {
                        PlayerHaptics.tap()
                        player.next()
                    } else if dx >= Self.swipeThreshold {
                        PlayerHaptics.tap()
                        player.previous()
                    }
                }
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { swipe = 0 }
            }
    }

    private static let swipeThreshold: CGFloat = 72

    private func fullTitle(_ now: NowPlayingSnapshot) -> some View {
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Button { player.openAlbum() } label: {
                    Text(now.title).font(.title3.weight(.bold)).lineLimit(1)
                }
                .disabled(!now.canOpenAlbum)
                Button { player.openArtist() } label: {
                    Text(now.artist).font(.title3).foregroundStyle(.white.opacity(0.6)).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .buttonStyle(.plain)
            likeAndMenu(now)
        }
    }

    private func compactTitle(_ now: NowPlayingSnapshot) -> some View {
        HStack(spacing: 12) {
            if let image = artwork.image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
                    .frame(width: 48, height: 48)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(now.title).font(.headline).lineLimit(1)
                Text(now.artist).font(.subheadline).foregroundStyle(.white.opacity(0.6)).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            likeAndMenu(now)
        }
    }

    private func likeAndMenu(_ now: NowPlayingSnapshot) -> some View {
        HStack(spacing: 10) {
            CircleButton(systemName: now.liked ? "heart.fill" : "heart") {
                PlayerHaptics.tap()
                player.toggleLike()
            }
            CircleButton(systemName: "ellipsis") {
                player.openMenu()
                withoutAnimation { menuOpen = true }
            }
        }
    }

    private func scrubber(_ now: NowPlayingSnapshot) -> some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { _ in
            let duration = max(Double(now.durationMs), 1)
            let position = scrubbing ?? min(Double(model.positionMs), duration)
            VStack(spacing: 6) {
                Slider(
                    value: Binding(get: { position }, set: { scrubbing = $0 }),
                    in: 0...duration
                ) { editing in
                    if !editing, let target = scrubbing {
                        seek(Int64(target))
                        scrubbing = nil
                    }
                }
                .tint(.white.opacity(0.85))
                .disabled(now.durationMs <= 0 || now.controlsLocked)
                ZStack {
                    HStack {
                        Text(Self.time(position))
                        Spacer()
                        Text("-" + Self.time(duration - position))
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.55))
                    if let badge = now.badge {
                        Button { pipelineOpen = true } label: {
                            QualityBadge(text: badge, lossless: now.badgeLossless, pulsing: now.badgePulsing)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private func controls(_ now: NowPlayingSnapshot) -> some View {
        HStack {
            Spacer()
            Button { PlayerHaptics.tap(); player.previous() } label: {
                Image(systemName: "backward.fill").font(.system(size: 34))
            }
            .disabled(now.controlsLocked)
            Spacer()
            Button { PlayerHaptics.tap(); player.playPause() } label: {
                ZStack {
                    if now.isLoading && !now.isPlaying {
                        ProgressView().tint(.white).scaleEffect(1.6)
                    } else {
                        Image(systemName: now.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 46))
                    }
                }
                .frame(width: 64, height: 64)
            }
            Spacer()
            Button { PlayerHaptics.tap(); player.next() } label: {
                Image(systemName: "forward.fill").font(.system(size: 34))
            }
            .disabled(!now.hasNext || now.controlsLocked)
            Spacer()
        }
        .buttonStyle(.plain)
    }

    private var volume: some View {
        HStack(spacing: 10) {
            Image(systemName: "speaker.fill").font(.caption).foregroundStyle(.white.opacity(0.55))
            SystemVolumeSlider().frame(height: 22)
            Image(systemName: "speaker.wave.3.fill").font(.caption).foregroundStyle(.white.opacity(0.55))
        }
    }

    private var bottomBar: some View {
        HStack {
            Spacer()
            PanelButton(systemName: "quote.bubble", on: panel == .lyrics) { toggle(.lyrics) }
            Spacer()
            RoutePicker().frame(width: 40, height: 40)
            Spacer()
            PanelButton(systemName: "list.bullet", on: panel == .queue) { toggle(.queue) }
            Spacer()
        }
    }

    // MARK: Actions

    private func toggle(_ target: Panel) {
        PlayerHaptics.select()
        withAnimation(.easeInOut(duration: 0.25)) { panel = panel == target ? .cover : target }
    }

    private func seek(_ ms: Int64) { player.seekTo(positionMs: ms) }

    private func withoutAnimation(_ change: () -> Void) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction, change)
    }

    /// The simulator tour's asks: a panel by name, or "menu" for the song menu over the cover.
    private func show(_ name: String) {
        NativePlayerPresenter.requestedPanel = nil
        if name == "menu" {
            panel = .cover
            player.openMenu()
            withoutAnimation { menuOpen = true }
        } else {
            panel = Self.panel(named: name)
        }
    }

    private static func panel(named name: String) -> Panel {
        name == "lyrics" ? .lyrics : name == "queue" ? .queue : .cover
    }

    static func time(_ ms: Double) -> String {
        let total = Int(max(ms, 0) / 1000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

// MARK: - Canvas

/// Spotify's tall clips run behind the whole screen, darkened under the controls.
private struct FullScreenCanvas: View {
    let clip: CanvasClip
    let playing: Bool
    @Binding var ready: Bool

    var body: some View {
        CanvasVideo(clip: clip, playing: playing, ready: $ready)
            .ignoresSafeArea()
            .overlay {
                LinearGradient(colors: [.black.opacity(0.25), .clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
            }
            .opacity(ready ? 1 : 0)
            .animation(Animation.easeInOut(duration: 0.4), value: ready)
    }
}

// MARK: - Lyrics

private struct LyricsPanel: View {
    @ObservedObject var model: PlayerModel
    let onSeek: (Int64) -> Void
    let onTiming: () -> Void
    let onProviders: () -> Void
    private let player = NativePlayer.shared

    var body: some View {
        VStack(spacing: 6) {
            if let view = model.lyrics, !view.rows.isEmpty { controls(view) }
            content
        }
    }

    @ViewBuilder private var content: some View {
        if let view = model.lyrics, !view.rows.isEmpty {
            let offset = Int64(view.offsetMs)
            if !view.synced {
                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(Array(view.rows.enumerated()), id: \.offset) { _, row in
                            LyricLineView(row: row, active: true)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 24)
                }
            } else {
                TimelineView(.periodic(from: .now, by: 0.25)) { _ in
                    SyncedLyrics(rows: view.rows, positionMs: model.lyricsPositionMs) { onSeek(max($0 + offset, 0)) }
                }
            }
        } else if model.lyrics?.loading ?? true {
            ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            VStack(spacing: 12) {
                Text("No lyrics for this song").font(.headline).foregroundStyle(.white.opacity(0.5))
                Button("Try another provider", action: onProviders)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.8))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    /// The provider, then translate, romanise and timing: what the Compose panel's lyric controls hold.
    private func controls(_ view: LyricsView) -> some View {
        VStack(spacing: 4) {
            HStack(spacing: 8) {
                Button(action: onProviders) {
                    HStack(spacing: 4) {
                        Text(view.sourceName ?? "Lyrics").lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption2.weight(.bold))
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.6))
                }
                .buttonStyle(.plain)
                Spacer()
                if view.working { ProgressView().tint(.white).scaleEffect(0.7) }
                ToggleChip(systemName: "character.bubble", on: view.mode == 1) { player.toggleTranslation() }
                ToggleChip(systemName: "textformat.abc", on: view.mode == 2) { player.toggleRomanization() }
                if view.synced { ToggleChip(systemName: "timer", on: view.offsetMs != 0, action: onTiming) }
            }
            if let note = view.note {
                Text(note)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.55))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.top, 4)
    }
}

/// One lyric, with its translation or romanisation in small type under it when one is on.
private struct LyricLineView: View {
    let row: LyricRow
    let active: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(row.text.isEmpty ? "♪" : row.text).font(.title2.weight(.bold))
            if let sub = row.subText {
                Text(sub).font(.body.weight(.medium)).foregroundStyle(.white.opacity(0.75))
            }
        }
        .foregroundStyle(.white.opacity(active ? 1 : 0.35))
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct SyncedLyrics: View {
    let rows: [LyricRow]
    let positionMs: Int64
    let onSeek: (Int64) -> Void

    private var current: Int? {
        rows.lastIndex { $0.timeMs <= positionMs }
    }

    var body: some View {
        let active = current
        ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 18) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                        LyricLineView(row: row, active: index == active)
                            .contentShape(Rectangle())
                            .onTapGesture { onSeek(row.timeMs) }
                            .id(index)
                    }
                }
                .padding(.vertical, 120)
            }
            .onChange(of: active) { line in
                guard let line else { return }
                withAnimation(.easeInOut(duration: 0.4)) { proxy.scrollTo(line, anchor: .center) }
            }
            .onAppear { if let active { proxy.scrollTo(active, anchor: .center) } }
        }
        .mask(LinearGradient(stops: [
            .init(color: .clear, location: 0), .init(color: .black, location: 0.12),
            .init(color: .black, location: 0.88), .init(color: .clear, location: 1),
        ], startPoint: .top, endPoint: .bottom))
    }
}

// MARK: - Queue

private struct QueuePanel: View {
    @ObservedObject var model: PlayerModel
    let now: NowPlayingSnapshot
    private let player = NativePlayer.shared

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Text("Up next").font(.headline)
                Spacer()
                ToggleChip(systemName: "shuffle", on: now.shuffle) { player.toggleShuffle() }
                ToggleChip(systemName: now.repeatMode == 1 ? "repeat.1" : "repeat", on: now.repeatMode != 0) { player.cycleRepeat() }
                ToggleChip(systemName: "infinity", on: now.autoplay) { player.toggleAutoplay() }
            }
            .padding(.top, 8)
            ScrollViewReader { proxy in
                List {
                    ForEach(Array(model.queue.enumerated()), id: \.offset) { index, row in
                        QueueRowView(row: row, current: index == model.queueIndex)
                            .contentShape(Rectangle())
                            .onTapGesture { player.jumpTo(index: Int32(index)) }
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets(top: 6, leading: 4, bottom: 6, trailing: 4))
                            .listRowSeparator(.hidden)
                            .id(index)
                    }
                    .onMove { from, to in
                        guard let source = from.first else { return }
                        player.move(from: Int32(source), to: Int32(to > source ? to - 1 : to))
                    }
                    .onDelete { offsets in offsets.sorted(by: >).forEach { player.removeAt(index: Int32($0)) } }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .onAppear { proxy.scrollTo(model.queueIndex, anchor: .top) }
            }
        }
    }
}

private struct QueueRowView: View {
    let row: QueueRow
    let current: Bool

    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: row.artworkUrl.flatMap { $0.hasPrefix("/") ? URL(fileURLWithPath: $0) : URL(string: $0) }) { image in
                image.resizable().aspectRatio(contentMode: .fill)
            } placeholder: {
                Color.white.opacity(0.08)
            }
            .frame(width: 44, height: 44)
            .clipped()
            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(.body.weight(current ? .semibold : .regular)).lineLimit(1)
                Text(row.artist).font(.subheadline).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
            }
            Spacer()
            if current {
                Image(systemName: "waveform").foregroundStyle(.white.opacity(0.8))
            }
        }
        .foregroundStyle(.white)
    }
}

// MARK: - Small pieces

private struct QualityBadge: View {
    let text: String
    let lossless: Bool
    let pulsing: Bool
    @State private var dim = false

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: lossless ? "waveform" : "headphones").font(.caption2.weight(.bold))
            Text(text).font(.caption.weight(.semibold))
        }
        .foregroundStyle(.white.opacity(lossless ? 0.9 : 0.6))
        .opacity(pulsing && dim ? 0.4 : 1)
        .onAppear { if pulsing { withAnimation(.easeInOut(duration: 0.9).repeatForever()) { dim = true } } }
        .onChange(of: pulsing) { if !$0 { dim = false } }
    }
}

private struct CircleButton: View {
    let systemName: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 34, height: 34)
                .background(.white.opacity(0.14), in: Circle())
        }
        .buttonStyle(.plain)
    }
}

private struct PanelButton: View {
    let systemName: String
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 19, weight: .medium))
                .frame(width: 40, height: 40)
                .foregroundStyle(on ? Color.black : Color.white.opacity(0.7))
                .background(on ? Color.white.opacity(0.9) : Color.clear, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct ToggleChip: View {
    let systemName: String
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button { PlayerHaptics.select(); action() } label: {
            Image(systemName: systemName)
                .font(.system(size: 14, weight: .semibold))
                .frame(width: 40, height: 30)
                .foregroundStyle(on ? Color.black : Color.white.opacity(0.8))
                .background(on ? Color.white.opacity(0.9) : Color.white.opacity(0.12), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

enum PlayerHaptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func select() { UISelectionFeedbackGenerator().selectionChanged() }
}

private extension View {
    /// See-through behind the Compose dialog, which draws its own scrim (iOS 16.4 and later).
    @ViewBuilder func clearBackground() -> some View {
        if #available(iOS 16.4, *) { presentationBackground(.clear) } else { self }
    }
}
