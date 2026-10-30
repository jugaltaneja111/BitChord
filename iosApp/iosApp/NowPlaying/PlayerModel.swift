import BitChordKit
import SwiftUI

/// Whether the native player is up. The Kotlin side opens it from the mini player and closes it
/// before it opens a page, through the presenter registered at launch.
final class NativePlayerPresenter: ObservableObject {
    @Published var shown = false

    /// Posted with a panel's name ("cover", "lyrics", "queue") for the open screen to show.
    static let panelRequested = Notification.Name("BitChordNativePlayerPanel")
    /// The last one asked for, for a screen that opens after it was posted.
    static var requestedPanel: String?

    init() {
        NativePlayer.shared.setPresenter { [weak self] show in
            DispatchQueue.main.async {
                print("native player: asked to \(show.boolValue ? "open" : "close")")
                self?.shown = show.boolValue
            }
        }
        NativePlayer.shared.setPanelHandler { name in
            DispatchQueue.main.async {
                Self.requestedPanel = name
                NotificationCenter.default.post(name: Self.panelRequested, object: name)
            }
        }
    }
}

/// What the screen shows, fed from [NativePlayer] while the screen is up and stopped when it goes.
final class PlayerModel: ObservableObject {
    @Published private(set) var now: NowPlayingSnapshot?
    @Published private(set) var lyrics: LyricsView?
    @Published private(set) var canvas: CanvasClip?
    @Published private(set) var queue: [QueueRow] = []
    @Published private(set) var queueIndex = 0

    private var subscriptions: [PlayerSubscription] = []

    func start() {
        guard subscriptions.isEmpty else { return }
        let player = NativePlayer.shared
        subscriptions = [
            player.observe { [weak self] snapshot in self?.now = snapshot },
            player.observeLyrics { [weak self] view in self?.lyrics = view },
            player.observeCanvas { [weak self] clip in self?.canvas = clip },
            player.observeQueue { [weak self] rows, index in
                self?.queue = rows
                self?.queueIndex = index.intValue
            },
        ]
    }

    func stop() {
        subscriptions.forEach { $0.close() }
        subscriptions = []
    }

    var positionMs: Int64 { NativePlayer.shared.positionMs() }
    /// Where the lyrics are: the clock less the listener's timing shift.
    var lyricsPositionMs: Int64 { positionMs - Int64(lyrics?.offsetMs ?? 0) }

    deinit { stop() }
}

/// The lyrics providers, watched only while their sheet is up.
final class LyricsProvidersModel: ObservableObject {
    @Published private(set) var rows: [LyricsProviderRow] = []
    private var subscription: PlayerSubscription?

    func start() {
        guard subscription == nil else { return }
        subscription = NativePlayer.shared.observeLyricsProviders { [weak self] rows in self?.rows = rows }
    }

    func stop() {
        subscription?.close()
        subscription = nil
    }

    deinit { stop() }
}
