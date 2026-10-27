import AppIntents
import SwiftUI
import WidgetKit

// The home-screen and lock-screen widget: what BitChord is playing, its cover and its controls.
// The app writes the song into the App Group both are signed into (IosWidget.kt) and asks for a
// redraw; the buttons are App Intents the system runs in the app (PlaybackIntents.swift).

/// The App Group this widget shares with the app. Its name is set at signing, not here: AltStore
/// and SideStore write theirs into the Info.plist; a certificate's own profile grants groups of
/// its maker's naming. So every candidate is tried — Info.plist, then the groups the embedded
/// profile lists, in its order — and the first the system opens a folder for is the one: the
/// app walks the same list the same way (IosWidget.kt).
private func appGroup() -> String? {
    for group in groupCandidates() where
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) != nil {
        return group
    }
    return nil
}

private func groupCandidates() -> [String] {
    var candidates: [String] = []
    if let groups = Bundle.main.object(forInfoDictionaryKey: "ALTAppGroups") as? [String] {
        candidates += groups.filter { !$0.isEmpty }
    }
    candidates.append("group.com.music.bitchord")
    // The widget's own profile, else the app's two folders up (BitChord.app/PlugIns/x.appex).
    let here = Bundle.main.bundleURL
    let app = here.deletingLastPathComponent().deletingLastPathComponent()
    for url in [here, app].map({ $0.appendingPathComponent("embedded.mobileprovision") }) {
        let groups = profileGroups(url)
        if !groups.isEmpty {
            candidates += groups
            break
        }
    }
    return candidates
}

/// The application-groups a provisioning profile grants: the plist inside its signed envelope.
private func profileGroups(_ url: URL) -> [String] {
    guard let data = try? Data(contentsOf: url),
          let start = data.range(of: Data("<?xml".utf8)),
          let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
          let plist = try? PropertyListSerialization.propertyList(
              from: data.subdata(in: start.lowerBound..<end.upperBound), format: nil
          ) as? [String: Any],
          let entitlements = plist["Entitlements"] as? [String: Any],
          let groups = entitlements["com.apple.security.application-groups"] as? [String]
    else { return [] }
    return groups.filter { !$0.contains("*") }
}

struct NowPlayingEntry: TimelineEntry {
    let date: Date
    let hasSong: Bool
    let title: String
    let artist: String
    let playing: Bool
    let art: UIImage?

    static let sample = NowPlayingEntry(
        date: Date(), hasSong: true, title: "Song title", artist: "Artist", playing: true, art: nil
    )
}

struct NowPlayingProvider: TimelineProvider {
    func placeholder(in context: Context) -> NowPlayingEntry { .sample }

    func getSnapshot(in context: Context, completion: @escaping (NowPlayingEntry) -> Void) {
        let entry = read()
        completion(context.isPreview && !entry.hasSong ? .sample : entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NowPlayingEntry>) -> Void) {
        // Redrawn when the app says so, never on a clock.
        completion(Timeline(entries: [read()], policy: .never))
    }

    private func read() -> NowPlayingEntry {
        guard let group = appGroup(), let defaults = UserDefaults(suiteName: group) else {
            return NowPlayingEntry(date: Date(), hasSong: false, title: "", artist: "", playing: false, art: nil)
        }
        var art: UIImage?
        if defaults.bool(forKey: "hasArt"),
           let folder = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) {
            art = UIImage(contentsOfFile: folder.appendingPathComponent("widget-art.jpg").path)
        }
        return NowPlayingEntry(
            date: Date(),
            hasSong: defaults.bool(forKey: "hasSong"),
            title: defaults.string(forKey: "title") ?? "",
            artist: defaults.string(forKey: "artist") ?? "",
            playing: defaults.bool(forKey: "playing"),
            art: art
        )
    }
}

private struct Cover: View {
    let art: UIImage?

    var body: some View {
        if let art {
            Image(uiImage: art).resizable().aspectRatio(contentMode: .fill)
        } else {
            ZStack {
                LinearGradient(
                    colors: [Color(white: 0.22), Color(white: 0.1)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
                Image(systemName: "music.note")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.6))
            }
        }
    }
}

private struct Controls: View {
    let playing: Bool
    let full: Bool

    var body: some View {
        HStack(spacing: full ? 22 : 0) {
            if full {
                Button(intent: PreviousTrackIntent()) {
                    Image(systemName: "backward.fill").font(.system(size: 17, weight: .semibold))
                }
            }
            Button(intent: TogglePlaybackIntent()) {
                Image(systemName: playing ? "pause.fill" : "play.fill")
                    .font(.system(size: full ? 22 : 18, weight: .semibold))
                    .frame(width: 36, height: 36)
            }
            if full {
                Button(intent: NextTrackIntent()) {
                    Image(systemName: "forward.fill").font(.system(size: 17, weight: .semibold))
                }
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
    }
}

private struct SmallView: View {
    let entry: NowPlayingEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .top) {
                Cover(art: entry.art)
                    .frame(width: 58, height: 58)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                Spacer(minLength: 0)
                Controls(playing: entry.playing, full: false)
            }
            Spacer(minLength: 0)
            Text(entry.title).font(.system(size: 14, weight: .semibold)).lineLimit(2)
            Text(entry.artist).font(.system(size: 12)).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
        }
        .foregroundStyle(.white)
        .padding(14)
    }
}

private struct MediumView: View {
    let entry: NowPlayingEntry

    var body: some View {
        HStack(spacing: 14) {
            Cover(art: entry.art)
                .frame(width: 128, height: 128)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.title).font(.system(size: 16, weight: .semibold)).lineLimit(2)
                Text(entry.artist).font(.system(size: 13)).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                Spacer(minLength: 0)
                Controls(playing: entry.playing, full: true)
            }
            .foregroundStyle(.white)
            Spacer(minLength: 0)
        }
        .padding(14)
    }
}

private struct NothingPlayingView: View {
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "music.note").font(.system(size: 24, weight: .semibold))
            Text("Open BitChord").font(.system(size: 13, weight: .semibold))
        }
        .foregroundStyle(.white.opacity(0.8))
    }
}

struct NowPlayingView: View {
    @Environment(\.widgetFamily) private var family
    let entry: NowPlayingEntry

    var body: some View {
        content.containerBackground(for: .widget) { background }
    }

    @ViewBuilder
    private var content: some View {
        switch family {
        case .accessoryRectangular:
            if entry.hasSong {
                HStack(spacing: 6) {
                    Image(systemName: entry.playing ? "waveform" : "pause.fill")
                    VStack(alignment: .leading, spacing: 0) {
                        Text(entry.title).font(.headline).lineLimit(1)
                        Text(entry.artist).font(.caption).lineLimit(1)
                    }
                }
            } else {
                Text("BitChord").font(.headline)
            }
        case .accessoryCircular:
            Button(intent: TogglePlaybackIntent()) {
                Image(systemName: entry.playing ? "pause.fill" : "play.fill").font(.title2)
            }
            .buttonStyle(.plain)
        case .systemMedium:
            if entry.hasSong { MediumView(entry: entry) } else { NothingPlayingView() }
        default:
            if entry.hasSong { SmallView(entry: entry) } else { NothingPlayingView() }
        }
    }

    @ViewBuilder
    private var background: some View {
        switch family {
        case .accessoryRectangular, .accessoryCircular:
            AccessoryWidgetBackground()
        default:
            ZStack {
                Color(white: 0.08)
                if let art = entry.art {
                    // The cover, blurred and darkened behind everything, as the player's own backdrop.
                    Image(uiImage: art).resizable().aspectRatio(contentMode: .fill)
                        .blur(radius: 28).opacity(0.75)
                    Color.black.opacity(0.35)
                }
            }
        }
    }
}

struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "NowPlaying", provider: NowPlayingProvider()) { entry in
            NowPlayingView(entry: entry)
        }
        .configurationDisplayName("Now Playing")
        .description("What's playing in BitChord, with its controls.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryCircular])
        .contentMarginsDisabled()
    }
}

@main
struct BitChordWidgets: WidgetBundle {
    var body: some Widget {
        NowPlayingWidget()
    }
}
