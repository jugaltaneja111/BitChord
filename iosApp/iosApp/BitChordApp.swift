import BitChordKit
import SwiftUI
import UIKit
import WidgetKit

/// The app is Compose (see iosKit) apart from the player, which is SwiftUI (NowPlaying/): this
/// shell hosts the one and presents the other over it.
@main
struct BitChordApp: App {
    @StateObject private var player = NativePlayerPresenter()

    init() {
        // WidgetCenter is Swift-only: the Kotlin side asks through this when the song changes.
        IosWidgetKt.setWidgetReloader { WidgetCenter.shared.reloadAllTimelines() }
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Compose lays itself out around the safe area.
                .ignoresSafeArea()
                // bitchord://party/CODE — a Listen Together invite.
                .onOpenURL { url in MainViewControllerKt.handleOpenUrl(url: url.absoluteString) }
                .sheet(isPresented: $player.shown) {
                    NowPlayingView()
                        .presentationDragIndicator(.visible)
                }
        }
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
