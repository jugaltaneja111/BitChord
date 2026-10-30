import SwiftUI
import UIKit

/// Cover art for the native player: fetched once, kept in memory for the next time it's asked for,
/// and read from disk when it is a local file's own artwork (the bridge passes a path then).
@MainActor
final class ArtworkLoader: ObservableObject {
    @Published private(set) var image: UIImage?
    /// Nine colours from the cover, row by row, for the backdrop's mesh.
    @Published private(set) var palette: [Color] = []

    private static let cache = NSCache<NSString, UIImage>()
    private var current: String?
    private var task: Task<Void, Never>?

    func load(_ address: String?) {
        guard address != current else { return }
        current = address
        task?.cancel()
        guard let address else {
            image = nil
            palette = []
            return
        }
        if let cached = Self.cache.object(forKey: address as NSString) {
            show(cached)
            return
        }
        task = Task { [weak self] in
            let loaded = await Self.fetch(address)
            guard !Task.isCancelled, let self, self.current == address, let loaded else { return }
            Self.cache.setObject(loaded, forKey: address as NSString)
            self.show(loaded)
        }
    }

    private func show(_ loaded: UIImage) {
        image = loaded
        palette = Self.palette(of: loaded)
    }

    private static func fetch(_ address: String) async -> UIImage? {
        if address.hasPrefix("/") {
            return await Task.detached { UIImage(contentsOfFile: address) }.value
        }
        guard let url = URL(string: address),
              let (data, _) = try? await URLSession.shared.data(from: url) else { return nil }
        return await Task.detached { UIImage(data: data)?.preparingForDisplay() }.value
    }

    /// The cover shrunk to 3×3: each pixel is the average of its ninth, softened toward the middle
    /// so text stays readable over it.
    private static func palette(of image: UIImage) -> [Color] {
        guard let cg = image.cgImage else { return [] }
        let side = 3
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: side, height: side, bitsPerComponent: 8,
                bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.interpolationQuality = .medium
            context.draw(cg, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard drawn else { return [] }
        return (0..<(side * side)).map { i in
            let dim = 0.72
            return Color(
                red: Double(pixels[i * 4]) / 255 * dim,
                green: Double(pixels[i * 4 + 1]) / 255 * dim,
                blue: Double(pixels[i * 4 + 2]) / 255 * dim
            )
        }
    }
}

/// The player's backdrop: a still mesh of the cover's colours on iOS 18, the cover blurred before
/// that. Drawn once a track, never animated, so it costs nothing between track changes.
struct PlayerBackdrop: View {
    let image: UIImage?
    let palette: [Color]

    var body: some View {
        ZStack {
            Color.black
            if #available(iOS 18.0, *), palette.count == 9 {
                MeshGradient(
                    width: 3, height: 3,
                    points: [
                        [0, 0], [0.5, 0], [1, 0],
                        [0, 0.5], [0.6, 0.45], [1, 0.5],
                        [0, 1], [0.5, 1], [1, 1],
                    ],
                    colors: palette
                )
            } else if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .blur(radius: 60, opaque: true)
                    .overlay(Color.black.opacity(0.35))
            }
        }
        .animation(.easeInOut(duration: 0.6), value: palette)
        .ignoresSafeArea()
    }
}
