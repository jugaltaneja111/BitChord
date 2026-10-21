import BitChordKit
import SwiftUI
import UIKit

/// The whole app is Compose (see iosKit); this shell only hosts it.
@main
struct BitChordApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Compose lays itself out around the safe area.
                .ignoresSafeArea()
                // bitchord://party/CODE — a Listen Together invite.
                .onOpenURL { url in MainViewControllerKt.handleOpenUrl(url: url.absoluteString) }
        }
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
