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
            DispatchQueue.main.async { self?.shown = show.boolValue }
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
    @Published private(set) var lyrics: [LyricRow] = []
    @Published private(set) var lyricsSynced = false
    @Published private(set) var queue: [QueueRow] = []
    @Published private(set) var queueIndex = 0

    private var subscriptions: [PlayerSubscription] = []

    func start() {
        guard subscriptions.isEmpty else { return }
        let player = NativePlayer.shared
        subscriptions = [
            player.observe { [weak self] snapshot in self?.now = snapshot },
            player.observeLyrics { [weak self] rows, synced in
                self?.lyrics = rows
                self?.lyricsSynced = synced.boolValue
            },
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

    deinit { stop() }
}
