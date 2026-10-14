@preconcurrency import AVFoundation
import CoreMedia
import Foundation

/// ユーザーに見せるエラー
public enum JoinError: LocalizedError, Equatable {
    case noClips
    case noVideo(String)
    case unreadable(String, String)
    case outputIsInput
    case outputFolderMissing(String)
    case notEnoughSpace(need: Int64, free: Int64)
    case transitionCountMismatch
    case failed(String)
    case cancelled

    public var errorDescription: String? {
        switch self {
        case .noClips: return "動画が 1 本もありません。"
        case .noVideo(let name): return "映像トラックがありません: \(name)"
        case .unreadable(let name, let why): return "動画として読み込めません: \(name)\n\(why)"
        case .outputIsInput: return "保存先が入力ファイルと同じです。別の名前を指定してください。"
        case .outputFolderMissing(let dir): return "保存先フォルダがありません: \(dir)"
        case .notEnoughSpace(let need, let free):
            return String(format: "保存先の空き容量が足りません（必要 約%.1f GB / 空き %.1f GB）。",
                          Double(need) / 1e9, Double(free) / 1e9)
        case .transitionCountMismatch: return "つなぎ目の指定数がクリップ数と合いません。"
        case .failed(let why): return "書き出しに失敗しました:\n\(why)"
        case .cancelled: return "キャンセルしました。"
        }
    }
}

/// 1 本の動画の情報
public struct ClipInfo: Identifiable, Hashable, @unchecked Sendable {
    public var id: URL { url }
    public let url: URL
    public var name: String { url.lastPathComponent }
    public let fileSize: Int64
    public let duration: Double
    /// 回転を反映した表示サイズ
    public let displaySize: CGSize
    /// トラックに格納されている（回転前の）サイズと回転
    public let naturalSize: CGSize
    public let transform: CGAffineTransform
    public let frameRate: Double
    /// "avc1" / "hvc1" など
    public let videoCodec: String
    /// 映像のおおよそのビットレート (bps)
    public let videoBitRate: Double
    public let hasAudio: Bool
    public let creationDate: Date?
    /// この値が全クリップで同じなら再エンコードせずに連結できる
    public let formatSignature: String

    public init(url: URL, fileSize: Int64 = 0, duration: Double, displaySize: CGSize,
                naturalSize: CGSize? = nil, transform: CGAffineTransform = .identity,
                frameRate: Double = 30, videoCodec: String = "avc1", videoBitRate: Double = 0,
                hasAudio: Bool = true, creationDate: Date? = nil, formatSignature: String? = nil) {
        self.url = url
        self.fileSize = fileSize
        self.duration = duration
        self.displaySize = displaySize
        self.naturalSize = naturalSize ?? displaySize
        self.transform = transform
        self.frameRate = frameRate
        self.videoCodec = videoCodec
        self.videoBitRate = videoBitRate
        self.hasAudio = hasAudio
        self.creationDate = creationDate
        self.formatSignature = formatSignature
            ?? "\(videoCodec)|\(Int(displaySize.width))x\(Int(displaySize.height))|\(String(format: "%.2f", frameRate))|\(hasAudio)"
    }

    public static func == (a: ClipInfo, b: ClipInfo) -> Bool { a.url == b.url }
    public func hash(into h: inout Hasher) { h.combine(url) }

    /// ファイルを読んで情報を集める
    public static func load(_ url: URL) async throws -> ClipInfo {
        let name = url.lastPathComponent
        let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
        do {
            guard let video = try await asset.loadTracks(withMediaType: .video).first else {
                throw JoinError.noVideo(name)
            }
            let (natural, transform, fps, formats, rate, range) = try await video.load(
                .naturalSize, .preferredTransform, .nominalFrameRate, .formatDescriptions, .estimatedDataRate, .timeRange)

            var audioDesc = "none"
            let audio = try await asset.loadTracks(withMediaType: .audio).first
            if let audio {
                let afs = try await audio.load(.formatDescriptions)
                if let d = afs.first, let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(d)?.pointee {
                    audioDesc = "\(fourCC(asbd.mFormatID))/\(Int(asbd.mSampleRate))/\(asbd.mChannelsPerFrame)"
                } else {
                    audioDesc = "unknown"
                }
            }

            let codec = formats.first.map { fourCC(CMFormatDescriptionGetMediaSubType($0)) } ?? "?"
            let rect = CGRect(origin: .zero, size: natural).applying(transform)
            let display = CGSize(width: abs(rect.width).rounded(), height: abs(rect.height).rounded())

            var created: Date?
            if let item = try? await asset.load(.creationDate) {
                created = try? await item.load(.dateValue)
            }
            let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
            var duration = range.duration.seconds
            if !(duration > 0) { duration = try await asset.load(.duration).seconds }

            let t = transform
            let signature = [
                codec,
                "\(Int(natural.width))x\(Int(natural.height))",
                "\(t.a),\(t.b),\(t.c),\(t.d)",
                String(format: "%.2f", fps),
                audioDesc,
            ].joined(separator: "|")

            return ClipInfo(url: url, fileSize: size, duration: duration, displaySize: display,
                            naturalSize: natural, transform: transform, frameRate: Double(fps),
                            videoCodec: codec, videoBitRate: Double(rate), hasAudio: audio != nil,
                            creationDate: created, formatSignature: signature)
        } catch let e as JoinError {
            throw e
        } catch {
            throw JoinError.unreadable(name, error.localizedDescription)
        }
    }

    /// 一覧表示用のサムネイル
    public static func thumbnail(for url: URL, maxSize: CGSize = CGSize(width: 320, height: 180), at seconds: Double = 1) async -> CGImage? {
        let asset = AVURLAsset(url: url)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = maxSize
        gen.requestedTimeToleranceBefore = CMTime(seconds: 1, preferredTimescale: 600)
        gen.requestedTimeToleranceAfter = CMTime(seconds: 1, preferredTimescale: 600)
        return try? await gen.image(at: CMTime(seconds: seconds, preferredTimescale: 600)).image
    }
}

let videoExtensions: Set<String> = ["mp4", "mov", "m4v"]

/// ファイルはそのまま、フォルダは中の動画ファイル（直下のみ、名前順）に展開する
public func expandInputs(_ urls: [URL]) -> [URL] {
    var out: [URL] = []
    let fm = FileManager.default
    for url in urls {
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: url.path, isDirectory: &isDir) else { continue }
        if isDir.boolValue {
            let items = (try? fm.contentsOfDirectory(at: url, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])) ?? []
            out += items
                .filter { videoExtensions.contains($0.pathExtension.lowercased()) && !$0.lastPathComponent.hasPrefix("._") }
                .sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
        } else {
            out.append(url)
        }
    }
    return out
}

func fourCC(_ code: FourCharCode) -> String {
    let bytes = [24, 16, 8, 0].map { UInt8((code >> $0) & 0xFF) }
    let s = String(bytes: bytes, encoding: .ascii) ?? "?"
    return s.trimmingCharacters(in: .whitespaces)
}
