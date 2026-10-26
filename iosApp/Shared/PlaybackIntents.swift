import AppIntents
#if BITCHORD_APP
import BitChordKit
#endif

// The widget's buttons. In both targets, so the widget can name them; as AudioPlaybackIntents the
// system runs them in the app (waking it in the background if it must), which is where the player
// is — the widget's own copy never performs.

@available(iOS 17.0, *)
struct TogglePlaybackIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Play or Pause"
    static var isDiscoverable: Bool = false

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if BITCHORD_APP
        IosWidgetKt.widgetTogglePlayPause()
        #endif
        return .result()
    }
}

@available(iOS 17.0, *)
struct NextTrackIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Next Track"
    static var isDiscoverable: Bool = false

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if BITCHORD_APP
        IosWidgetKt.widgetNext()
        #endif
        return .result()
    }
}

@available(iOS 17.0, *)
struct PreviousTrackIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Previous Track"
    static var isDiscoverable: Bool = false

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if BITCHORD_APP
        IosWidgetKt.widgetPrevious()
        #endif
        return .result()
    }
}
