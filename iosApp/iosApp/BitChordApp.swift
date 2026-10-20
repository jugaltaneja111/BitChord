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
        }
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
