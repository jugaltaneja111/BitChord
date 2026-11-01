import BitChordKit
import SwiftUI
import UIKit
import WidgetKit

/// The whole app is Compose (see iosKit); this shell only hosts it.
/// Answers iOS's "which ways may the screen turn": portrait on iPhone, except while Now Playing
/// shows a music video (see IosOrientation.kt); every way on iPad.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        UIInterfaceOrientationMask(rawValue: UInt(MainViewControllerKt.supportedInterfaceOrientationsMask()))
    }
}

@main
struct BitChordApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

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
        }
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
