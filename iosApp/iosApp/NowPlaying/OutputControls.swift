import AVKit
import BitChordKit
import MediaPlayer
import SwiftUI

/// The system's own output picker (AirPlay, Bluetooth, the speaker): the button the Music app has.
struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = UIColor.white.withAlphaComponent(0.7)
        view.activeTintColor = .white
        view.prioritizesVideoDevices = false
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

/// The iPhone's volume as a slider: Apple's own, so it moves the real level and follows the side
/// buttons, and while it's on screen iOS leaves its volume overlay out.
struct SystemVolumeSlider: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView(frame: .zero)
        view.tintColor = .white
        return view
    }

    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}

/// The Compose Audio Pipeline dialog, hosted as it is on the Compose player.
struct AudioPipelineSheet: UIViewControllerRepresentable {
    let onDismiss: () -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = NativePlayer.shared.pipelineViewController(onDismiss: onDismiss)
        controller.view.backgroundColor = .clear
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// The Compose song menu, and the playlist pickers it leads to, drawn over the native player.
struct MenuSheet: UIViewControllerRepresentable {
    let onDismiss: () -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = NativePlayer.shared.menuViewController(onDismiss: onDismiss)
        controller.view.backgroundColor = .clear
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
