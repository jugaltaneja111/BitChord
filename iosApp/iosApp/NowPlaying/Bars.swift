import BitChordKit
import SwiftUI

/// What the bars show: the app's tab and theme, and the playing track, from NativePlayer.
final class BarsModel: ObservableObject {
    @Published private(set) var chrome = ChromeState(tab: 0, visible: false, dark: true)
    @Published private(set) var now: NowPlayingSnapshot?
    let titles: [String] = NativePlayer.shared.tabTitles()
    private var subscriptions: [PlayerSubscription] = []

    init() {
        let player = NativePlayer.shared
        subscriptions = [
            player.observeChrome { [weak self] state in self?.chrome = state },
            player.observe { [weak self] snapshot in self?.now = snapshot },
        ]
    }

    deinit { subscriptions.forEach { $0.close() } }
}

/// The mini player and the tabs, native, floating over the Compose pages as the Compose bars did.
/// Their height goes back to Kotlin so the last row of a page still clears them.
struct NativeBars: View {
    @StateObject private var model = BarsModel()
    /// The window's height in global points, for working out how much of its foot the bars cover.
    let windowBottom: CGFloat

    var body: some View {
        Group {
            if model.chrome.visible {
                VStack(spacing: 8) {
                    if let now = model.now {
                        MiniPlayerBar(now: now)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    TabBar(titles: model.titles, selected: Int(model.chrome.tab))
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 4)
                .frame(maxWidth: 560)
                .background(GeometryReader { geometry in
                    Color.clear.preference(key: BarsTopKey.self, value: geometry.frame(in: .global).minY)
                })
                .onPreferenceChange(BarsTopKey.self) { top in
                    NativePlayer.shared.setBarsHeight(points: Double(max(windowBottom - top, 0)))
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: model.chrome.visible)
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: model.now == nil)
        .environment(\.colorScheme, model.chrome.dark ? .dark : .light)
    }
}

private struct BarsTopKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

// MARK: - Mini player

/// The phone's mini player: the cover, the track, play and skip. A tap or a pull up opens the
/// player; a sideways drag of 72 points skips, as the Compose bar does.
private struct MiniPlayerBar: View {
    let now: NowPlayingSnapshot
    @StateObject private var artwork = ArtworkLoader()
    @State private var drag: CGSize = .zero
    private let player = NativePlayer.shared
    private static let threshold: CGFloat = 72

    var body: some View {
        HStack(spacing: 12) {
            Color.primary.opacity(0.08)
                .frame(width: 40, height: 40)
                .overlay {
                    if let image = artwork.image {
                        Image(uiImage: image).resizable().scaledToFill()
                    } else {
                        Image(systemName: "music.note").foregroundStyle(.secondary)
                    }
                }
                .clipped()
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(now.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(now.artist).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .offset(x: drag.width * 0.35)
            Button { PlayerHaptics.tap(); player.playPause() } label: {
                ZStack {
                    if now.isLoading && !now.isPlaying {
                        ProgressView()
                    } else {
                        Image(systemName: now.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 22))
                    }
                }
                .frame(width: 40, height: 40)
            }
            Button { PlayerHaptics.tap(); player.next() } label: {
                Image(systemName: "forward.fill").font(.system(size: 20)).frame(width: 40, height: 40)
            }
            .disabled(!now.hasNext)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
        .padding(.leading, 12)
        .padding(.trailing, 8)
        .padding(.vertical, 8)
        .background(.regularMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(.primary.opacity(0.08)))
        .shadow(color: .black.opacity(0.18), radius: 12, y: 4)
        .contentShape(Capsule())
        .onTapGesture { player.openPlayer() }
        .gesture(
            DragGesture(minimumDistance: 12)
                .onChanged { value in
                    // Steeper than about 34° is a pull up; flatter, a skip.
                    if abs(value.translation.width) > abs(value.translation.height) * 1.5 { drag = value.translation }
                }
                .onEnded { value in
                    let dx = value.translation.width
                    let dy = value.translation.height
                    if abs(dx) > abs(dy) * 1.5 {
                        if dx <= -Self.threshold {
                            PlayerHaptics.tap()
                            player.next()
                        } else if dx >= Self.threshold {
                            PlayerHaptics.tap()
                            player.previous()
                        }
                    } else if dy < -30 {
                        player.openPlayer()
                    }
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { drag = .zero }
                }
        )
        .onAppear { artwork.load(now.artworkUrl) }
        .onChange(of: now.artworkUrl) { artwork.load($0) }
    }
}

// MARK: - Tabs

private struct TabBar: View {
    let titles: [String]
    let selected: Int
    private static let symbols = ["house.fill", "square.grid.2x2.fill", "rectangle.stack.fill", "magnifyingglass"]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(titles.enumerated()), id: \.offset) { index, title in
                Button {
                    PlayerHaptics.select()
                    NativePlayer.shared.selectTab(index: Int32(index))
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: Self.symbols[min(index, Self.symbols.count - 1)])
                            .font(.system(size: 19, weight: .semibold))
                            .frame(height: 24)
                        Text(title).font(.system(size: 10, weight: .semibold)).lineLimit(1)
                    }
                    .foregroundStyle(index == selected ? Color.primary : Color.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 7)
                    .background {
                        if index == selected {
                            Capsule().fill(.primary.opacity(0.1)).padding(.horizontal, 4)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .background(.regularMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(.primary.opacity(0.08)))
        .shadow(color: .black.opacity(0.18), radius: 12, y: 4)
        .animation(.easeInOut(duration: 0.2), value: selected)
    }
}
