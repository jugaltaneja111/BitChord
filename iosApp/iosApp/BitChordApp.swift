import BitChordKit
import SwiftUI
import UIKit
import WidgetKit

/// The app is Compose (see iosKit) apart from the player and the bars under the pages, which are
/// SwiftUI (NowPlaying/): this shell hosts the one and lays the others over it.
@main
struct BitChordApp: App {
    @StateObject private var player = NativePlayerPresenter()
    @State private var windowBottom: CGFloat = 0

    init() {
        // WidgetCenter is Swift-only: the Kotlin side asks through this when the song changes.
        IosWidgetKt.setWidgetReloader { WidgetCenter.shared.reloadAllTimelines() }
        // Before the Compose view is made, so its own mini player and tabs never show.
        NativePlayer.shared.useNativeBars()
    }

    var body: some Scene {
        WindowGroup {
            ZStack(alignment: .bottom) {
                ComposeView()
                    // Compose lays itself out around the safe area.
                    .ignoresSafeArea()
                    .background(GeometryReader { geometry in
                        Color.clear
                            .onAppear { windowBottom = geometry.frame(in: .global).maxY }
                            .onChange(of: geometry.frame(in: .global).maxY) { windowBottom = $0 }
                    })
                NativeBars(windowBottom: windowBottom)
                    // The keyboard covers the bars, as it did the Compose ones, rather than lifting them.
                    .ignoresSafeArea(.keyboard)
            }
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
