@preconcurrency import AVFoundation
import CoreMedia
import Foundation

/// 連結処理 1 回分。別スレッドから cancel() できる。
///
/// * 高速コピー: AVMutableComposition に並べて Passthrough で書き出す（再エンコードなし）
/// * 再エンコード: Composition にフェード（不透明度・音量のランプ）と拡大縮小を指定し、
///   AVAssetReader → AVAssetWriter で書き出す。エンコードは Mac のハードウェアエンコーダが使われる
public final class Joiner: @unchecked Sendable {
    /// (0...1 の進捗, メッセージ)。任意のスレッドから呼ばれる
    public typealias ProgressHandler = (Double, String) -> Void

    private let lock = NSLock()
    private var _cancelled = false
    private var exportSession: AVAssetExportSession?

    public init() {}

    public var isCancelled: Bool {
        lock.lock(); defer { lock.unlock() }
        return _cancelled
    }

    public func cancel() {
        lock.lock()
        _cancelled = true
        let session = exportSession
        lock.unlock()
        session?.cancelExport()
    }

    /// キャンセル済みなら false
    private func setExportSession(_ session: AVAssetExportSession?) -> Bool {
        lock.lock(); defer { lock.unlock() }
        exportSession = session
        return !_cancelled
    }

    @discardableResult
    public func join(_ clips: [ClipInfo], to output: URL, options: JoinOptions,
                     progress: @escaping ProgressHandler = { _, _ in }) async throws -> JoinPlan {
        var plan = try makePlan(clips, options)
        let fm = FileManager.default
        let outDir = output.deletingLastPathComponent()
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: outDir.path, isDirectory: &isDir), isDir.boolValue else {
            throw JoinError.outputFolderMissing(outDir.path)
        }
        let outPath = output.resolvingSymlinksInPath().standardizedFileURL.path
        if clips.contains(where: { $0.url.resolvingSymlinksInPath().standardizedFileURL.path == outPath }) {
            throw JoinError.outputIsInput
        }
        if let free = try? outDir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage, free > 0, plan.estimatedBytes > free {
            throw JoinError.notEnoughSpace(need: plan.estimatedBytes, free: free)
        }

        // 書きかけのファイルは保存先と同じフォルダに隠しファイルで作り、完成したら置き換える
        let tmp = outDir.appendingPathComponent(".mp4join-\(UUID().uuidString).\(output.pathExtension.isEmpty ? "mp4" : output.pathExtension)")
        defer { try? fm.removeItem(at: tmp) }
        let throttled = Throttle(progress)

        if plan.mode == .passthrough {
            do {
                try await exportPassthrough(clips, plan: plan, to: tmp, progress: throttled)
            } catch JoinError.cancelled {
                throw JoinError.cancelled
            } catch {
                // まれに Passthrough できない組み合わせがあるので、再エンコードでやり直す
                try? fm.removeItem(at: tmp)
                var opts = options
                opts.forceReencode = true
                plan = try makePlan(clips, opts)
                throttled.send(0, "形式を揃えて書き出し直します…", force: true)
                try await reencode(clips, plan: plan, options: opts, to: tmp, progress: throttled)
            }
        } else {
            try await reencode(clips, plan: plan, options: options, to: tmp, progress: throttled)
        }

        if fm.fileExists(atPath: output.path) {
            _ = try fm.replaceItemAt(output, withItemAt: tmp)
        } else {
            try fm.moveItem(at: tmp, to: output)
        }
        throttled.send(1, "完了しました", force: true)
        return plan
    }

    // MARK: - Composition

    private struct Built {
        let composition: AVMutableComposition
        let videoTrack: AVMutableCompositionTrack
        let audioTrack: AVMutableCompositionTrack?
        let videoComposition: AVMutableVideoComposition
        let audioMix: AVMutableAudioMix?
    }

    private func build(_ clips: [ClipInfo], plan: JoinPlan) async throws -> Built {
        let comp = AVMutableComposition()
        guard let vTrack = comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw JoinError.failed("映像トラックを作成できません")
        }
        let aTrack = clips.contains(where: \.hasAudio)
            ? comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
            : nil
        let audioParams = aTrack.map { AVMutableAudioMixInputParameters(track: $0) }

        let render = plan.renderSize
        var instructions: [AVMutableVideoCompositionInstruction] = []
        var cursor = CMTime.zero

        for (i, clip) in clips.enumerated() {
            if isCancelled { throw JoinError.cancelled }
            let asset = AVURLAsset(url: clip.url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
            guard let srcV = try await asset.loadTracks(withMediaType: .video).first else {
                throw JoinError.noVideo(clip.name)
            }
            let vRange = try await srcV.load(.timeRange)
            do {
                try vTrack.insertTimeRange(vRange, of: srcV, at: cursor)
            } catch {
                throw JoinError.unreadable(clip.name, error.localizedDescription)
            }

            if let aTrack, let srcA = try await asset.loadTracks(withMediaType: .audio).first {
                let aRange = try await srcA.load(.timeRange).intersection(vRange)
                if aRange.duration > .zero {
                    let at = cursor + (aRange.start - vRange.start)
                    let end = aTrack.timeRange.end
                    if at > end {
                        // 音声のないクリップの区間は無音にする
                        aTrack.insertEmptyTimeRange(CMTimeRange(start: end, end: at))
                    }
                    try? aTrack.insertTimeRange(aRange, of: srcA, at: at)
                }
            }

            let dur = vRange.duration
            let fade = plan.fades[i]
            let instr = AVMutableVideoCompositionInstruction()
            instr.timeRange = CMTimeRange(start: cursor, duration: dur)
            instr.backgroundColor = CGColor(gray: 0, alpha: 1)
            let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: vTrack)
            layer.setTransform(fitTransform(natural: clip.naturalSize, preferred: clip.transform, into: render), at: cursor)
            if fade.fadeIn > 0 {
                let r = CMTimeRange(start: cursor, duration: seconds(fade.fadeIn))
                layer.setOpacityRamp(fromStartOpacity: 0, toEndOpacity: 1, timeRange: r)
                audioParams?.setVolumeRamp(fromStartVolume: 0, toEndVolume: 1, timeRange: r)
            }
            if fade.fadeOut > 0 {
                let d = seconds(fade.fadeOut)
                let r = CMTimeRange(start: cursor + dur - d, duration: d)
                layer.setOpacityRamp(fromStartOpacity: 1, toEndOpacity: 0, timeRange: r)
                audioParams?.setVolumeRamp(fromStartVolume: 1, toEndVolume: 0, timeRange: r)
            }
            instr.layerInstructions = [layer]
            instructions.append(instr)
            cursor = cursor + dur
        }

        let vc = AVMutableVideoComposition()
        vc.renderSize = render
        vc.frameDuration = frameDuration(plan.frameRate)
        vc.instructions = instructions

        var mix: AVMutableAudioMix?
        if let audioParams {
            mix = AVMutableAudioMix()
            mix!.inputParameters = [audioParams]
        }
        return Built(composition: comp, videoTrack: vTrack, audioTrack: aTrack, videoComposition: vc, audioMix: mix)
    }

    // MARK: - 高速コピー

    private func exportPassthrough(_ clips: [ClipInfo], plan: JoinPlan, to url: URL, progress: Throttle) async throws {
        progress.send(0, "\(clips.count) 本を高速連結中…", force: true)
        let built = try await build(clips, plan: plan)
        // 映像はそのまま使うので、回転情報は元のクリップのものを引き継ぐ
        built.videoTrack.preferredTransform = clips[0].transform
        guard let session = AVAssetExportSession(asset: built.composition, presetName: AVAssetExportPresetPassthrough) else {
            throw JoinError.failed("書き出しを開始できません")
        }
        session.outputURL = url
        session.outputFileType = url.pathExtension.lowercased() == "mov" ? .mov : .mp4
        session.shouldOptimizeForNetworkUse = true

        guard setExportSession(session) else { throw JoinError.cancelled }
        defer { _ = setExportSession(nil) }

        let total = plan.totalDuration
        let poller = Task {
            while !Task.isCancelled {
                let p = Double(session.progress)
                progress.send(min(p, 0.999), "高速連結中… \(formatTime(p * total)) / \(formatTime(total))")
                try? await Task.sleep(nanoseconds: 300_000_000)
            }
        }
        await session.export()
        poller.cancel()

        switch session.status {
        case .completed: return
        case .cancelled: throw JoinError.cancelled
        default:
            if isCancelled { throw JoinError.cancelled }
            throw JoinError.failed(session.error?.localizedDescription ?? "不明なエラー")
        }
    }

    // MARK: - 再エンコード

    private func reencode(_ clips: [ClipInfo], plan: JoinPlan, options: JoinOptions, to url: URL, progress: Throttle) async throws {
        progress.send(0, "準備中…", force: true)
        let built = try await build(clips, plan: plan)
        if isCancelled { throw JoinError.cancelled }

        let reader: AVAssetReader
        let writer: AVAssetWriter
        do {
            reader = try AVAssetReader(asset: built.composition)
            writer = try AVAssetWriter(outputURL: url, fileType: url.pathExtension.lowercased() == "mov" ? .mov : .mp4)
        } catch {
            throw JoinError.failed(error.localizedDescription)
        }
        writer.shouldOptimizeForNetworkUse = true

        let vOut = AVAssetReaderVideoCompositionOutput(
            videoTracks: [built.videoTrack],
            videoSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange])
        vOut.videoComposition = built.videoComposition
        vOut.alwaysCopiesSampleData = false
        reader.add(vOut)

        let w = Int(plan.renderSize.width), h = Int(plan.renderSize.height)
        var compression: [String: Any] = [
            AVVideoAverageBitRateKey: plan.videoBitRate,
            AVVideoMaxKeyFrameIntervalKey: max(1, Int((plan.frameRate * 2).rounded())),
            AVVideoExpectedSourceFrameRateKey: plan.frameRate,
        ]
        if options.codec == .h264 {
            compression[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel
        }
        let vIn = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: options.codec == .hevc ? AVVideoCodecType.hevc : AVVideoCodecType.h264,
            AVVideoWidthKey: w,
            AVVideoHeightKey: h,
            AVVideoCompressionPropertiesKey: compression,
        ])
        vIn.expectsMediaDataInRealTime = false
        writer.add(vIn)

        var aOut: AVAssetReaderAudioMixOutput?
        var aIn: AVAssetWriterInput?
        if let aTrack = built.audioTrack {
            let o = AVAssetReaderAudioMixOutput(audioTracks: [aTrack], audioSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: 48_000,
                AVNumberOfChannelsKey: 2,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
            ])
            o.audioMix = built.audioMix
            o.alwaysCopiesSampleData = false
            reader.add(o)
            let i = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 48_000,
                AVNumberOfChannelsKey: 2,
                AVEncoderBitRateKey: 192_000,
            ])
            i.expectsMediaDataInRealTime = false
            writer.add(i)
            aOut = o
            aIn = i
        }

        guard writer.startWriting() else {
            throw JoinError.failed(writer.error?.localizedDescription ?? "書き込みを開始できません")
        }
        guard reader.startReading() else {
            writer.cancelWriting()
            throw JoinError.failed(reader.error?.localizedDescription ?? "読み込みを開始できません")
        }
        writer.startSession(atSourceTime: .zero)

        let total = max(plan.totalDuration, 0.001)
        let starts = clipStarts(clips)
        let onVideo: (CMTime) -> Void = { t in
            let s = t.seconds
            let idx = max(0, (starts.lastIndex { $0 <= s } ?? 0))
            progress.send(min(s / total, 0.999),
                          "書き出し中 \(idx + 1)/\(clips.count): \(clips[idx].name)  \(formatTime(s)) / \(formatTime(total))")
        }

        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.pump(vIn, from: vOut, label: "video", onSample: onVideo) }
            if let aIn, let aOut {
                group.addTask { await self.pump(aIn, from: aOut, label: "audio", onSample: nil) }
            }
        }

        if isCancelled {
            reader.cancelReading()
            writer.cancelWriting()
            throw JoinError.cancelled
        }
        if reader.status == .failed {
            writer.cancelWriting()
            throw JoinError.failed(reader.error?.localizedDescription ?? "読み込みエラー")
        }
        if writer.status == .failed {
            throw JoinError.failed(writer.error?.localizedDescription ?? "書き込みエラー")
        }
        progress.send(0.999, "仕上げ中…", force: true)
        await writer.finishWriting()
        if writer.status != .completed {
            throw JoinError.failed(writer.error?.localizedDescription ?? "書き込みエラー")
        }
    }

    /// reader の出力を writer の入力へ流し込む
    private func pump(_ input: AVAssetWriterInput, from output: AVAssetReaderOutput, label: String,
                      onSample: ((CMTime) -> Void)?) async {
        let queue = DispatchQueue(label: "mp4join.\(label)")
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            let state = PumpState()
            input.requestMediaDataWhenReady(on: queue) {
                func finish() {
                    guard !state.finished else { return }
                    state.finished = true
                    input.markAsFinished()
                    cont.resume()
                }
                guard !state.finished else { return }
                while input.isReadyForMoreMediaData {
                    if self.isCancelled { return finish() }
                    guard let sample = output.copyNextSampleBuffer() else { return finish() }
                    onSample?(CMSampleBufferGetPresentationTimeStamp(sample))
                    if !input.append(sample) { return finish() }
                }
            }
        }
    }
}

/// pump のコールバックは同じシリアルキューで呼ばれるので、ロックなしで使える
private final class PumpState: @unchecked Sendable {
    var finished = false
}

// MARK: - helpers

/// 回転を反映し、縦横比を保ったまま render に収めて中央に置く（余白は黒）
func fitTransform(natural: CGSize, preferred: CGAffineTransform, into render: CGSize) -> CGAffineTransform {
    let rect = CGRect(origin: .zero, size: natural).applying(preferred)
    guard rect.width > 0, rect.height > 0 else { return preferred }
    var t = preferred.concatenating(CGAffineTransform(translationX: -rect.minX, y: -rect.minY))
    let s = min(render.width / rect.width, render.height / rect.height)
    t = t.concatenating(CGAffineTransform(scaleX: s, y: s))
    return t.concatenating(CGAffineTransform(translationX: (render.width - rect.width * s) / 2,
                                             y: (render.height - rect.height * s) / 2))
}

/// 29.97 などの NTSC 系は 1001/30000 のように正確に表す
func frameDuration(_ fps: Double) -> CMTime {
    guard fps > 0 else { return CMTime(value: 1, timescale: 30) }
    let r = fps.rounded()
    if abs(fps - r) < 0.01 { return CMTime(value: 1, timescale: CMTimeScale(r)) }
    let n = (fps * 1.001).rounded()
    if abs(n * 1000 / 1001 - fps) < 0.01 { return CMTime(value: 1001, timescale: CMTimeScale(n * 1000)) }
    return CMTime(seconds: 1 / fps, preferredTimescale: 600_000)
}

private func seconds(_ s: Double) -> CMTime {
    CMTime(seconds: s, preferredTimescale: 600)
}

private func clipStarts(_ clips: [ClipInfo]) -> [Double] {
    var acc = 0.0
    return clips.map { c in defer { acc += c.duration }; return acc }
}

public func formatTime(_ sec: Double) -> String {
    let s = Int(sec.isFinite ? sec.rounded() : 0)
    let h = s / 3600, m = s % 3600 / 60, r = s % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, r) : String(format: "%d:%02d", m, r)
}

/// 進捗通知を 0.1 秒に 1 回程度に間引く
final class Throttle {
    private let handler: Joiner.ProgressHandler
    private let lock = NSLock()
    private var last = Date.distantPast

    init(_ handler: @escaping Joiner.ProgressHandler) {
        self.handler = handler
    }

    func send(_ p: Double, _ message: String, force: Bool = false) {
        lock.lock()
        let now = Date()
        let go = force || now.timeIntervalSince(last) >= 0.1
        if go { last = now }
        lock.unlock()
        if go { handler(p, message) }
    }
}
