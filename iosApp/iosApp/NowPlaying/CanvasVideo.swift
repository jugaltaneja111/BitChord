import AVFoundation
import SwiftUI
import UIKit

/// A track's Canvas: the looping clip some releases ship in place of a still cover. Muted, so it
/// never touches the music's audio session; looped by an AVPlayerLooper; drawn only once its first
/// frame is ready, so the still cover underneath never flashes black; and tried once more at the
/// clip's fallback address if the first fails, as IosCanvasVideo does for the Compose player.
struct CanvasVideo: UIViewRepresentable {
    let clip: CanvasClip
    let playing: Bool
    @Binding var ready: Bool

    func makeUIView(context: Context) -> CanvasPlayerView {
        let view = CanvasPlayerView()
        view.onReady = { ready = $0 }
        view.load(clip)
        return view
    }

    func updateUIView(_ view: CanvasPlayerView, context: Context) {
        view.onReady = { ready = $0 }
        if view.clipUrl != clip.url { view.load(clip) }
        view.setPlaying(playing)
    }

    static func dismantleUIView(_ view: CanvasPlayerView, coordinator: ()) {
        view.stop()
    }
}

final class CanvasPlayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    private var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }

    var onReady: ((Bool) -> Void)?
    private(set) var clipUrl: String?
    private var player: AVQueuePlayer?
    private var looper: AVPlayerLooper?
    private var readyWatch: NSKeyValueObservation?
    private var statusWatch: NSKeyValueObservation?
    private var triedFallback = false
    private var playing = true

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        playerLayer.videoGravity = .resizeAspectFill
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func load(_ clip: CanvasClip) {
        clipUrl = clip.url
        triedFallback = false
        start(clip.url, fallback: clip.fallbackUrl)
    }

    private func start(_ address: String, fallback: String?) {
        stop()
        guard let url = URL(string: address) else { return }
        let item = AVPlayerItem(url: url)
        let player = AVQueuePlayer()
        player.isMuted = true
        player.allowsExternalPlayback = false
        player.preventsDisplaySleepDuringVideoPlayback = false
        looper = AVPlayerLooper(player: player, templateItem: item)
        self.player = player
        playerLayer.player = player
        readyWatch = playerLayer.observe(\.isReadyForDisplay, options: [.new]) { [weak self] layer, _ in
            let ready = layer.isReadyForDisplay
            DispatchQueue.main.async { self?.onReady?(ready) }
        }
        statusWatch = looper?.observe(\.status, options: [.new]) { [weak self] looper, _ in
            guard looper.status == .failed else { return }
            DispatchQueue.main.async {
                guard let self, !self.triedFallback, let fallback else { return }
                self.triedFallback = true
                self.start(fallback, fallback: nil)
            }
        }
        if playing { player.play() }
    }

    func setPlaying(_ on: Bool) {
        guard on != playing else { return }
        playing = on
        if on { player?.play() } else { player?.pause() }
    }

    func stop() {
        readyWatch = nil
        statusWatch = nil
        looper?.disableLooping()
        looper = nil
        player?.pause()
        player = nil
        playerLayer.player = nil
        // Not during SwiftUI's own update, which is where load and teardown are called from.
        let onReady = onReady
        DispatchQueue.main.async { onReady?(false) }
    }
}
