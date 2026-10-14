import AVFoundation
import XCTest
@testable import JoinerCore

/// 実際に動画を作って連結まで通すテスト
final class JoinTests: XCTestCase {
    var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("mp4join-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    func url(_ name: String) -> URL { dir.appendingPathComponent(name) }

    func clip(_ name: String, _ spec: Fixtures.Spec = .init()) async throws -> ClipInfo {
        try Fixtures.makeClip(url(name), spec)
        return try await ClipInfo.load(url(name))
    }

    func leftoverTempFiles() throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasPrefix(".mp4join-") }
    }

    func testLoadClipInfo() async throws {
        let c = try await clip("a.mp4", .init(seconds: 2, width: 320, height: 240))
        XCTAssertEqual(c.duration, 2, accuracy: 0.1)
        XCTAssertEqual(c.displaySize, CGSize(width: 320, height: 240))
        XCTAssertEqual(c.frameRate, 30, accuracy: 0.1)
        XCTAssertEqual(c.videoCodec, "avc1")
        XCTAssertTrue(c.hasAudio)
        XCTAssertGreaterThan(c.fileSize, 0)

        let silent = try await clip("b.mp4", .init(audio: false))
        XCTAssertFalse(silent.hasAudio)
        XCTAssertNotEqual(c.formatSignature, silent.formatSignature)
    }

    func testRotatedClipDisplaySize() async throws {
        let c = try await clip("portrait.mp4", .init(width: 320, height: 240, transform: CGAffineTransform(rotationAngle: .pi / 2)))
        XCTAssertEqual(c.displaySize, CGSize(width: 240, height: 320))
    }

    func testPassthroughSameFormat() async throws {
        var clips: [ClipInfo] = []
        for i in 0..<3 { clips.append(try await clip("c\(i).mp4", .init(seconds: 1.5))) }
        let out = url("out.mp4")
        let plan = try await Joiner().join(clips, to: out, options: JoinOptions())
        XCTAssertEqual(plan.mode, .passthrough)
        let joined = try await ClipInfo.load(out)
        XCTAssertEqual(joined.duration, 4.5, accuracy: 0.2)
        XCTAssertTrue(joined.hasAudio)
        XCTAssertEqual(try leftoverTempFiles(), [])
    }

    func testFadeReencode() async throws {
        var clips: [ClipInfo] = []
        for i in 0..<3 { clips.append(try await clip("c\(i).mp4", .init(seconds: 2, gray: 255))) }
        let out = url("out.mp4")
        let progress = ProgressLog()
        let plan = try await Joiner().join(clips, to: out, options: JoinOptions(fade: 0.5)) { p, _ in progress.add(p) }
        XCTAssertEqual(plan.mode, .reencode)
        XCTAssertEqual(plan.fades, [Fade(0, 0.5), Fade(0.5, 0.5), Fade(0.5, 0)])

        let joined = try await ClipInfo.load(out)
        XCTAssertEqual(joined.duration, 6, accuracy: 0.2)
        XCTAssertTrue(joined.hasAudio)
        XCTAssertEqual(progress.values.last, 1)
        XCTAssertEqual(progress.values, progress.values.sorted(), "進捗は単調増加")

        // つなぎ目（2 秒地点の直前）は暗く、クリップの中央は明るい
        let edge = try await Fixtures.luma(out, at: 1.98)
        let middle = try await Fixtures.luma(out, at: 3.0)
        XCTAssertLessThan(edge, 50)
        XCTAssertGreaterThan(middle, 200)
        // 最初はフェードしない設定
        let start = try await Fixtures.luma(out, at: 0.05)
        XCTAssertGreaterThan(start, 200)
    }

    func testMixedFormatsAndMissingAudio() async throws {
        let a = try await clip("a.mp4", .init(seconds: 1, width: 320, height: 240, fps: 30))
        let b = try await clip("b.mp4", .init(seconds: 1, width: 640, height: 360, fps: 25, audio: false))
        let c = try await clip("c.mp4", .init(seconds: 1, width: 320, height: 240, fps: 30))
        let out = url("out.mp4")
        let plan = try await Joiner().join([a, b, c], to: out, options: JoinOptions())
        XCTAssertEqual(plan.mode, .reencode)
        let joined = try await ClipInfo.load(out)
        XCTAssertEqual(joined.displaySize, CGSize(width: 320, height: 240))
        XCTAssertEqual(joined.frameRate, 30, accuracy: 0.5)
        XCTAssertTrue(joined.hasAudio)
        XCTAssertEqual(joined.duration, 3, accuracy: 0.2)
    }

    func testHEVCOutput() async throws {
        let a = try await clip("a.mp4", .init(seconds: 1))
        let b = try await clip("b.mp4", .init(seconds: 1))
        let out = url("out.mp4")
        try await Joiner().join([a, b], to: out, options: JoinOptions(fade: 0.3, codec: .hevc))
        let joined = try await ClipInfo.load(out)
        XCTAssertEqual(joined.videoCodec, "hvc1")
        XCTAssertEqual(joined.duration, 2, accuracy: 0.2)
    }

    func testFiftyClips() async throws {
        try Fixtures.makeClip(url("base.mp4"), .init(seconds: 0.5))
        var clips: [ClipInfo] = []
        for i in 0..<50 {
            let u = url("it's clip \(String(format: "%02d", i)).mp4")
            try FileManager.default.copyItem(at: url("base.mp4"), to: u)
            clips.append(try await ClipInfo.load(u))
        }
        let out = url("out.mp4")
        let plan = try await Joiner().join(clips, to: out, options: JoinOptions())
        XCTAssertEqual(plan.mode, .passthrough)
        let joined = try await ClipInfo.load(out)
        XCTAssertEqual(joined.duration, 25, accuracy: 1)
    }

    func testOverwriteExistingOutput() async throws {
        let a = try await clip("a.mp4", .init(seconds: 1))
        let out = url("out.mp4")
        try Data("old".utf8).write(to: out)
        try await Joiner().join([a, a], to: out, options: JoinOptions())
        let joined = try await ClipInfo.load(out)
        XCTAssertEqual(joined.duration, 2, accuracy: 0.2)
    }

    func testCancel() async throws {
        let a = try await clip("a.mp4", .init(seconds: 1))
        let b = try await clip("b.mp4", .init(seconds: 1))
        let joiner = Joiner()
        joiner.cancel()
        do {
            try await joiner.join([a, b], to: url("out.mp4"), options: JoinOptions(fade: 0.5))
            XCTFail("キャンセルされるはず")
        } catch JoinError.cancelled {
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: url("out.mp4").path))
        XCTAssertEqual(try leftoverTempFiles(), [])
    }

    func testOutputSameAsInputRejected() async throws {
        let a = try await clip("a.mp4")
        do {
            try await Joiner().join([a], to: a.url, options: JoinOptions())
            XCTFail("エラーになるはず")
        } catch JoinError.outputIsInput {
        }
    }

    func testExpandFolder() throws {
        try Fixtures.makeClip(url("GX020001.MP4"), .init(seconds: 0.3))
        try Fixtures.makeClip(url("GX010001.MP4"), .init(seconds: 0.3))
        try Data().write(to: url("notes.txt"))
        try Data().write(to: url("._GX010001.MP4"))  // macOS が外付けディスクに作るゴミファイル
        let names = expandInputs([dir]).map(\.lastPathComponent)
        XCTAssertEqual(names, ["GX010001.MP4", "GX020001.MP4"])
    }
}

final class ProgressLog: @unchecked Sendable {
    private let lock = NSLock()
    private var _values: [Double] = []
    var values: [Double] { lock.lock(); defer { lock.unlock() }; return _values }
    func add(_ v: Double) { lock.lock(); _values.append(v); lock.unlock() }
}

final class PlanTests: XCTestCase {
    func clip(_ name: String, _ duration: Double = 10, w: Int = 1920, h: Int = 1080, fps: Double = 30) -> ClipInfo {
        ClipInfo(url: URL(fileURLWithPath: "/x/\(name)"), fileSize: 1000, duration: duration,
                 displaySize: CGSize(width: w, height: h), frameRate: fps, videoBitRate: 50_000_000)
    }

    func testTransitionsAndEdges() throws {
        let plan = try makePlan([clip("a"), clip("b"), clip("c")],
                                JoinOptions(fade: 2, transitions: [false, true], fadeEdges: true))
        XCTAssertEqual(plan.fades, [Fade(2, 0), Fade(0, 2), Fade(2, 2)])
        XCTAssertEqual(plan.mode, .reencode)
    }

    func testShortClipFadeClamped() throws {
        let plan = try makePlan([clip("a"), clip("b", 1), clip("c")], JoinOptions(fade: 2))
        XCTAssertEqual(plan.fades[1], Fade(0.5, 0.5))
    }

    func testFadeZeroIsPassthrough() throws {
        let plan = try makePlan([clip("a"), clip("b")], JoinOptions(fade: 0, transitions: [true]))
        XCTAssertEqual(plan.mode, .passthrough)
    }

    func testMostCommonResolutionAndRate() throws {
        let plan = try makePlan([clip("a", w: 3840, h: 2160, fps: 60), clip("b"), clip("c")], JoinOptions())
        XCTAssertEqual(plan.mode, .reencode)
        XCTAssertEqual(plan.renderSize, CGSize(width: 1920, height: 1080))
        XCTAssertEqual(plan.frameRate, 30)
    }

    func testTieUsesFirstClip() throws {
        let plan = try makePlan([clip("a", w: 1280, h: 720), clip("b")], JoinOptions())
        XCTAssertEqual(plan.renderSize, CGSize(width: 1280, height: 720))
    }

    func testBitRateCappedBySource() throws {
        let src = ClipInfo(url: URL(fileURLWithPath: "/x/a"), duration: 10, displaySize: CGSize(width: 3840, height: 2160),
                           frameRate: 30, videoBitRate: 5_000_000)
        let plan = try makePlan([src, src], JoinOptions(fade: 1, quality: .high))
        XCTAssertEqual(plan.videoBitRate, 5_000_000)
    }

    func testTransitionCountMismatch() {
        XCTAssertThrowsError(try makePlan([clip("a"), clip("b")], JoinOptions(fade: 1, transitions: [])))
    }

    func testFitTransformLetterbox() {
        // 縦長 (240x320 表示) を 320x240 に収める → 高さ 240 に縮小して左右中央
        let t = fitTransform(natural: CGSize(width: 320, height: 240), preferred: CGAffineTransform(rotationAngle: .pi / 2),
                             into: CGSize(width: 320, height: 240))
        let r = CGRect(x: 0, y: 0, width: 320, height: 240).applying(t)
        XCTAssertEqual(r.minX, 70, accuracy: 0.5)
        XCTAssertEqual(r.width, 180, accuracy: 0.5)
        XCTAssertEqual(r.minY, 0, accuracy: 0.5)
        XCTAssertEqual(r.height, 240, accuracy: 0.5)
    }

    func testFrameDuration() {
        XCTAssertEqual(frameDuration(30), CMTime(value: 1, timescale: 30))
        XCTAssertEqual(frameDuration(29.97), CMTime(value: 1001, timescale: 30000))
        XCTAssertEqual(frameDuration(59.94), CMTime(value: 1001, timescale: 60000))
    }
}

final class OrderingTests: XCTestCase {
    func info(_ name: String, date: Date? = nil) -> ClipInfo {
        ClipInfo(url: URL(fileURLWithPath: "/x/\(name)"), duration: 1, displaySize: CGSize(width: 1, height: 1), creationDate: date)
    }

    func testGoProSort() {
        let names = ["GX020124.MP4", "GX010125.MP4", "GX010124.MP4", "GH010123.MP4", "GP010122.MP4", "GOPR0122.MP4", "IMG_0001.MP4"]
        let sorted = sortClips(names.map { info($0) }, by: .gopro).map(\.name)
        XCTAssertEqual(sorted, ["GOPR0122.MP4", "GP010122.MP4", "GH010123.MP4", "GX010124.MP4", "GX020124.MP4",
                                "GX010125.MP4", "IMG_0001.MP4"])
    }

    func testNameSortIsNatural() {
        let sorted = sortClips(["clip10.mp4", "clip2.mp4", "clip1.mp4"].map { info($0) }, by: .name).map(\.name)
        XCTAssertEqual(sorted, ["clip1.mp4", "clip2.mp4", "clip10.mp4"])
    }

    func testDateSort() {
        let t = Date(timeIntervalSince1970: 1_700_000_000)
        let clips = [info("b.mp4", date: t.addingTimeInterval(60)), info("none.mp4"), info("a.mp4", date: t)]
        XCTAssertEqual(sortClips(clips, by: .date).map(\.name), ["a.mp4", "b.mp4", "none.mp4"])
    }

    func testSameRecording() {
        XCTAssertTrue(isSameRecording("GX010124.MP4", "GX020124.MP4"))
        XCTAssertTrue(isSameRecording("GOPR0122.MP4", "GP010122.MP4"))
        XCTAssertFalse(isSameRecording("GX010124.MP4", "GX010125.MP4"))
        XCTAssertFalse(isSameRecording("GX020124.MP4", "GX010124.MP4"))
        XCTAssertFalse(isSameRecording("a.mp4", "b.mp4"))
    }
}
