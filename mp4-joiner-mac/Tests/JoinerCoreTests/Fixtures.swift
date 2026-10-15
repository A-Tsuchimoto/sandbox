import AVFoundation
import CoreMedia
import CoreVideo
import XCTest

/// テスト用の短い動画を AVAssetWriter で作る（単色の映像 + 440Hz の音）
enum Fixtures {
    struct Spec {
        var seconds: Double = 1
        var width = 320
        var height = 240
        var fps: Int32 = 30
        var gray: UInt8 = 255
        /// 指定すると下半分をこの明るさにする（上下の向きの確認用）
        var bottomGray: UInt8?
        var audio = true
        var transform: CGAffineTransform = .identity
    }

    static func makeClip(_ url: URL, _ spec: Spec = Spec()) throws {
        try? FileManager.default.removeItem(at: url)
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)

        let vIn = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: spec.width,
            AVVideoHeightKey: spec.height,
            AVVideoCompressionPropertiesKey: [AVVideoMaxKeyFrameIntervalKey: Int(spec.fps)],
        ])
        vIn.expectsMediaDataInRealTime = false
        vIn.transform = spec.transform
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: vIn, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: spec.width,
            kCVPixelBufferHeightKey as String: spec.height,
        ])
        writer.add(vIn)

        let sampleRate = 48_000
        var aIn: AVAssetWriterInput?
        var audioFormat: CMAudioFormatDescription?
        if spec.audio {
            var asbd = AudioStreamBasicDescription(
                mSampleRate: Double(sampleRate), mFormatID: kAudioFormatLinearPCM,
                mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
                mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4, mChannelsPerFrame: 2,
                mBitsPerChannel: 16, mReserved: 0)
            CMAudioFormatDescriptionCreate(allocator: nil, asbd: &asbd, layoutSize: 0, layout: nil,
                                           magicCookieSize: 0, magicCookie: nil, extensions: nil,
                                           formatDescriptionOut: &audioFormat)
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: sampleRate,
                AVNumberOfChannelsKey: 2,
                AVEncoderBitRateKey: 128_000,
            ], sourceFormatHint: audioFormat)
            input.expectsMediaDataInRealTime = false
            writer.add(input)
            aIn = input
        }

        guard writer.startWriting() else { throw writer.error ?? NSError(domain: "fixture", code: 1) }
        writer.startSession(atSourceTime: .zero)

        let frames = Int((spec.seconds * Double(spec.fps)).rounded())
        let totalSamples = Int(spec.seconds * Double(sampleRate))
        var writtenSamples = 0
        let chunk = 1024

        func appendFrame(_ f: Int) throws {
            var pb: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &pb)
            let buffer = pb!
            CVPixelBufferLockBaseAddress(buffer, [])
            let base = CVPixelBufferGetBaseAddress(buffer)!
            let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
            memset(base, Int32(spec.gray), rowBytes * spec.height)
            if let bottom = spec.bottomGray {
                // ピクセルバッファの 0 行目が画面の上
                memset(base + rowBytes * (spec.height / 2), Int32(bottom), rowBytes * (spec.height - spec.height / 2))
            }
            // アルファは不透明に
            let p = base.assumingMemoryBound(to: UInt8.self)
            for y in 0..<spec.height {
                for x in 0..<spec.width { p[y * rowBytes + x * 4 + 3] = 255 }
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            guard adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(f), timescale: spec.fps)) else {
                throw writer.error ?? NSError(domain: "fixture", code: 4)
            }
        }

        func appendAudioChunk(_ aIn: AVAssetWriterInput, _ audioFormat: CMAudioFormatDescription) throws {
            let n = min(chunk, totalSamples - writtenSamples)
            var pcm = [Int16](repeating: 0, count: n * 2)
            for i in 0..<n {
                let v = Int16(sin(2 * Double.pi * 440 * Double(writtenSamples + i) / Double(sampleRate)) * 8000)
                pcm[i * 2] = v
                pcm[i * 2 + 1] = v
            }
            var block: CMBlockBuffer?
            CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: n * 4,
                                               blockAllocator: nil, customBlockSource: nil, offsetToData: 0,
                                               dataLength: n * 4, flags: kCMBlockBufferAssureMemoryNowFlag,
                                               blockBufferOut: &block)
            pcm.withUnsafeBytes { raw in
                _ = CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block!,
                                                  offsetIntoDestination: 0, dataLength: n * 4)
            }
            var sb: CMSampleBuffer?
            CMAudioSampleBufferCreateReadyWithPacketDescriptions(
                allocator: nil, dataBuffer: block!, formatDescription: audioFormat, sampleCount: n,
                presentationTimeStamp: CMTime(value: CMTimeValue(writtenSamples), timescale: CMTimeScale(sampleRate)),
                packetDescriptions: nil, sampleBufferOut: &sb)
            guard aIn.append(sb!) else { throw writer.error ?? NSError(domain: "fixture", code: 3) }
            writtenSamples += n
        }

        // 受け付け可能な方から入れていく（片方だけ待つと writer が止まる）
        var frame = 0
        let deadline = Date().addingTimeInterval(30)
        while frame < frames || (aIn != nil && writtenSamples < totalSamples) {
            var progressed = false
            if frame < frames && vIn.isReadyForMoreMediaData {
                try appendFrame(frame)
                frame += 1
                // 書き終えたらすぐ終了を伝える（でないと writer がもう片方を待ち続ける）
                if frame == frames { vIn.markAsFinished() }
                progressed = true
            }
            if let aIn, let audioFormat, writtenSamples < totalSamples, aIn.isReadyForMoreMediaData {
                try appendAudioChunk(aIn, audioFormat)
                if writtenSamples >= totalSamples { aIn.markAsFinished() }
                progressed = true
            }
            if !progressed {
                if Date() > deadline {
                    throw NSError(domain: "fixture", code: 2, userInfo: [NSLocalizedDescriptionKey: "writer stalled"])
                }
                Thread.sleep(forTimeInterval: 0.001)
            }
        }

        writer.endSession(atSourceTime: CMTime(value: CMTimeValue(frames), timescale: spec.fps))
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        if writer.status != .completed { throw writer.error ?? NSError(domain: "fixture", code: 5) }
    }

    /// 指定時刻のフレームの平均の明るさ (0...255)
    static func luma(_ url: URL, at seconds: Double) async throws -> Int {
        let (top, bottom) = try await lumaHalves(url, at: seconds)
        return (top + bottom) / 2
    }

    /// 指定時刻のフレームの (上端付近, 下端付近) の明るさ。向き情報（回転）を反映した見た目で測る
    static func lumaHalves(_ url: URL, at seconds: Double) async throws -> (top: Int, bottom: Int) {
        let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        gen.appliesPreferredTrackTransform = true
        gen.requestedTimeToleranceBefore = .zero
        gen.requestedTimeToleranceAfter = .zero
        let image = try await gen.image(at: CMTime(seconds: seconds, preferredTimescale: 600)).image
        // 等倍のグレースケールに描いて、上端 1/4 と下端 1/4 の平均をとる（メモリ上の 0 行目が画像の上端）
        let w = image.width, h = image.height
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)!
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        let data = ctx.data!.assumingMemoryBound(to: UInt8.self)
        let stride = ctx.bytesPerRow
        func average(_ rows: Range<Int>) -> Int {
            var sum = 0
            for y in rows { for x in 0..<w { sum += Int(data[y * stride + x]) } }
            return sum / max(1, rows.count * w)
        }
        return (average(0..<h / 4), average(h - h / 4..<h))
    }
}
