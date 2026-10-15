import CoreGraphics
import Foundation

public enum VideoCodec: String, CaseIterable {
    case h264, hevc

    public var label: String {
        switch self {
        case .h264: return "H.264（互換性重視）"
        case .hevc: return "HEVC / H.265（ファイルが小さい）"
        }
    }
}

public enum Quality: String, CaseIterable {
    case high, standard, small

    public var label: String {
        switch self {
        case .high: return "高画質"
        case .standard: return "標準"
        case .small: return "軽量"
        }
    }

    /// H.264 の bits / pixel
    var bitsPerPixel: Double {
        switch self {
        case .high: return 0.12
        case .standard: return 0.08
        case .small: return 0.05
        }
    }
}

public struct JoinOptions {
    /// フェード秒数。0 ならフェードなし
    public var fade: Double = 0
    /// つなぎ目ごとのフェード有無（要素数 = クリップ数 - 1）。nil なら全部
    public var transitions: [Bool]?
    /// 動画全体の最初と最後もフェードする
    public var fadeEdges = false
    public var codec: VideoCodec = .h264
    public var quality: Quality = .standard
    public var forceReencode = false
    /// クリップごとに 180° 回転するか（要素数 = クリップ数）。nil なら回転なし
    public var rotations: [Bool]?

    public init(fade: Double = 0, transitions: [Bool]? = nil, fadeEdges: Bool = false,
                codec: VideoCodec = .h264, quality: Quality = .standard, forceReencode: Bool = false,
                rotations: [Bool]? = nil) {
        self.rotations = rotations
        self.fade = fade
        self.transitions = transitions
        self.fadeEdges = fadeEdges
        self.codec = codec
        self.quality = quality
        self.forceReencode = forceReencode
    }
}

public struct Fade: Equatable {
    public var fadeIn: Double
    public var fadeOut: Double

    public init(_ fadeIn: Double, _ fadeOut: Double) {
        self.fadeIn = fadeIn
        self.fadeOut = fadeOut
    }
}

public struct JoinPlan: Equatable {
    public enum Mode: Equatable {
        /// 再エンコードせずにそのまま繋ぐ（速い・劣化なし）
        case passthrough
        /// 同じ設定で書き出し直す（フェード・形式混在）
        case reencode
    }

    public var mode: Mode
    public var reason: String
    public var renderSize: CGSize
    public var frameRate: Double
    public var fades: [Fade]
    /// クリップごとの 180° 回転
    public var rotations: [Bool]
    public var totalDuration: Double
    public var videoBitRate: Int
    public var estimatedBytes: Int64
}

private func mostCommon<T: Hashable>(_ values: [T]) -> T {
    var counts: [T: Int] = [:]
    var order: [T] = []
    for v in values {
        if counts[v] == nil { order.append(v) }
        counts[v, default: 0] += 1
    }
    // 同数なら先に出てきた方
    var best = order[0]
    for v in order where counts[v]! > counts[best]! { best = v }
    return best
}

private struct SizeKey: Hashable {
    let w: Int, h: Int
}

public func makePlan(_ clips: [ClipInfo], _ opts: JoinOptions) throws -> JoinPlan {
    guard !clips.isEmpty else { throw JoinError.noClips }
    let n = clips.count
    var trans = opts.transitions ?? Array(repeating: true, count: n - 1)
    guard trans.count == n - 1 else { throw JoinError.transitionCountMismatch }
    let fade = max(0, opts.fade)
    if fade == 0 { trans = Array(repeating: false, count: n - 1) }
    let edges = fade > 0 && opts.fadeEdges
    let rotations = opts.rotations ?? Array(repeating: false, count: n)
    guard rotations.count == n else { throw JoinError.rotationCountMismatch }
    // 全部回転なら、映像の向き情報を書き換えるだけで済む（再エンコード不要）
    let mixedRotation = Set(rotations).count > 1

    let total = clips.reduce(0) { $0 + $1.duration }
    let anyFade = trans.contains(true) || edges
    let sameFormat = Set(clips.map(\.formatSignature)).count == 1

    if !anyFade && sameFormat && !mixedRotation && !opts.forceReencode {
        return JoinPlan(
            mode: .passthrough,
            reason: rotations[0]
                ? "全クリップを 180° 回転し、再エンコードせず高速に連結します（画質劣化なし）。"
                : "全クリップが同じ形式なので、再エンコードせず高速に連結します（画質劣化なし）。",
            renderSize: clips[0].displaySize,
            frameRate: clips[0].frameRate,
            fades: Array(repeating: Fade(0, 0), count: n),
            rotations: rotations,
            totalDuration: total,
            videoBitRate: 0,
            estimatedBytes: clips.reduce(0) { $0 + $1.fileSize })
    }

    let reason: String
    if anyFade {
        reason = "フェード処理のため再エンコードします。"
    } else if mixedRotation && sameFormat {
        reason = "一部のクリップだけ 180° 回転するため再エンコードします。"
    } else if opts.forceReencode {
        reason = "再エンコードが指定されています。"
    } else {
        reason = "解像度・フレームレート・コーデック等が異なるクリップがあるため、形式を揃えて再エンコードします。"
    }

    let size = mostCommon(clips.map { SizeKey(w: Int($0.displaySize.width), h: Int($0.displaySize.height)) })
    // エンコーダは偶数サイズが必要
    let render = CGSize(width: size.w - size.w % 2, height: size.h - size.h % 2)
    let fps = mostCommon(clips.map { $0.frameRate > 0 ? ($0.frameRate * 100).rounded() / 100 : 30 })

    var fades: [Fade] = []
    for (i, c) in clips.enumerated() {
        let fin = (i > 0 && trans[i - 1]) || (i == 0 && edges) ? fade : 0
        let fout = (i < n - 1 && trans[i]) || (i == n - 1 && edges) ? fade : 0
        // 短いクリップではフェードが重ならないよう縮める
        let limit = fin > 0 && fout > 0 ? c.duration / 2 : c.duration
        fades.append(Fade(min(fin, limit), min(fout, limit)))
    }

    let bitRate = targetBitRate(render, fps: fps, codec: opts.codec, quality: opts.quality, clips: clips)
    return JoinPlan(
        mode: .reencode,
        reason: reason,
        renderSize: render,
        frameRate: fps,
        fades: fades,
        rotations: rotations,
        totalDuration: total,
        videoBitRate: bitRate,
        estimatedBytes: Int64(total * Double(bitRate + 192_000) / 8))
}

func targetBitRate(_ size: CGSize, fps: Double, codec: VideoCodec, quality: Quality, clips: [ClipInfo]) -> Int {
    var rate = Double(size.width * size.height) * fps * quality.bitsPerPixel * (codec == .hevc ? 0.6 : 1)
    // 元動画より大きくしても画質は上がらないので頭打ち
    if let src = clips.map(\.videoBitRate).max(), src > 0 {
        rate = min(rate, src)
    }
    return Int(max(rate, 1_000_000))
}
