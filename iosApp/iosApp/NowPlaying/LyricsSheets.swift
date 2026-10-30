import BitChordKit
import SwiftUI

/// The lyrics timing sheet: every synced line shifted against the clock, a tenth of a second at a
/// time, as the Compose player's LyricsOffsetSheet does. Positive shows the lyrics later.
struct LyricsTimingSheet: View {
    let offsetMs: Int
    private let player = NativePlayer.shared
    private static let range = -5000...5000
    private static let step = 100

    var body: some View {
        VStack(spacing: 18) {
            VStack(spacing: 4) {
                Text("Lyrics offset").font(.headline)
                Text("Positive values show lyrics later").font(.subheadline).foregroundStyle(.secondary)
            }
            Text(Self.format(offsetMs))
                .font(.system(size: 40, weight: .semibold).monospacedDigit())
                .contentTransition(.numericText())
            HStack(spacing: 14) {
                stepButton("minus", by: -Self.step).disabled(offsetMs <= Self.range.lowerBound)
                Slider(
                    value: Binding(
                        get: { Double(offsetMs) },
                        set: { set(Int(($0 / Double(Self.step)).rounded()) * Self.step) }
                    ),
                    in: Double(Self.range.lowerBound)...Double(Self.range.upperBound)
                )
                stepButton("plus", by: Self.step).disabled(offsetMs >= Self.range.upperBound)
            }
            Button("Reset") { set(0) }
                .disabled(offsetMs == 0)
        }
        .padding(24)
        .presentationDetents([.height(280)])
        .presentationDragIndicator(.visible)
    }

    private func stepButton(_ symbol: String, by delta: Int) -> some View {
        Button {
            PlayerHaptics.select()
            set(offsetMs + delta)
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .bold))
                .frame(width: 40, height: 40)
                .background(.white.opacity(0.12), in: Circle())
        }
        .buttonStyle(.plain)
    }

    private func set(_ ms: Int) { player.setLyricsOffset(ms: Int32(ms)) }

    static func format(_ ms: Int) -> String {
        let seconds = String(format: "%.1f s", abs(Double(ms)) / 1000)
        return ms > 0 ? "+" + seconds : ms < 0 ? "−" + seconds : seconds
    }
}

/// Where the lyrics come from: every provider in the listener's order with how its lookup for this
/// track went. One that already has the track switches at once; one that didn't can't be asked
/// again; and the sheet closes when the lyrics it was asked for arrive, as LyricsProviderSheet does.
struct LyricsProvidersSheet: View {
    @StateObject private var model = LyricsProvidersModel()
    @State private var requested: String?
    @Environment(\.dismiss) private var dismiss
    private let player = NativePlayer.shared

    var body: some View {
        NavigationStack {
            List(model.rows, id: \.key) { row in
                Button {
                    PlayerHaptics.select()
                    requested = row.key
                    player.selectLyricsProvider(key: row.key)
                } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.name).foregroundStyle(.primary)
                            Text(row.detail).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        Spacer()
                        status(row)
                    }
                }
                .disabled(row.state == 3 || row.state == 1)
            }
            .navigationTitle("Lyrics provider")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .onChange(of: model.rows.first { $0.selected }?.key) { current in
            if let requested, current == requested { dismiss() }
        }
    }

    @ViewBuilder private func status(_ row: LyricsProviderRow) -> some View {
        if row.selected {
            Image(systemName: "checkmark").foregroundStyle(.green)
        } else {
            switch row.state {
            case 1: ProgressView()
            case 2: Text("Found").font(.caption).foregroundStyle(.secondary)
            case 3: Text("Not found").font(.caption).foregroundStyle(.tertiary)
            default: EmptyView()
            }
        }
    }
}
