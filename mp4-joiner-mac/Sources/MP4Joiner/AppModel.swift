import AppKit
import JoinerCore
import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class AppModel: ObservableObject {
    struct Item: Identifiable, Equatable {
        let clip: ClipInfo
        var thumbnail: NSImage?
        var id: URL { clip.url }

        static func == (a: Item, b: Item) -> Bool { a.id == b.id && a.thumbnail === b.thumbnail }
    }

    enum JobState: Equatable {
        case idle
        case running(Double, String)
        case done(URL)
        case failed(String)
        case cancelled
    }

    @Published var items: [Item] = []
    @Published var fadeOn = false
    @Published var fadeSeconds = 1.5
    @Published var keepChapters = true
    @Published var fadeEdges = false
    @Published var codec: VideoCodec = .h264
    @Published var quality: Quality = .standard
    /// "前のパス|次のパス" → フェードするか（つなぎ目ごとの手動指定）
    @Published var overrides: [String: Bool] = [:]
    @Published var loadMessage = ""
    @Published var loadErrors: [String] = []
    @Published var isLoading = false
    @Published var job: JobState = .idle

    private var joiner: Joiner?

    var isRunning: Bool {
        if case .running = job { return true }
        return false
    }

    // MARK: - 追加

    func showOpenPanel() {
        let panel = NSOpenPanel()
        panel.message = "連結する動画、または動画の入ったフォルダ（例: GoPro の DCIM/100GOPRO）を選択"
        panel.prompt = "追加"
        panel.allowsMultipleSelection = true
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowedContentTypes = [.mpeg4Movie, .quickTimeMovie, .movie]
        guard panel.runModal() == .OK else { return }
        let urls = panel.urls
        Task { await add(urls) }
    }

    func add(_ urls: [URL], at index: Int? = nil) async {
        let have = Set(items.map(\.id))
        let paths = expandInputs(urls).filter { !have.contains($0) }
        guard !paths.isEmpty else {
            loadMessage = urls.isEmpty ? "" : "追加できる新しい動画がありません。"
            return
        }
        isLoading = true
        loadMessage = "\(paths.count) 本を読み込み中…"
        loadErrors = []

        // 並行して読み込み、指定した順番のまま追加する
        let results = await withTaskGroup(of: (Int, Result<ClipInfo, Error>).self) { group in
            for (i, url) in paths.enumerated() {
                group.addTask {
                    do { return (i, .success(try await ClipInfo.load(url))) } catch { return (i, .failure(error)) }
                }
            }
            var out = [(Int, Result<ClipInfo, Error>)]()
            for await r in group { out.append(r) }
            return out.sorted { $0.0 < $1.0 }.map(\.1)
        }

        var newItems: [Item] = []
        for r in results {
            switch r {
            case .success(let clip): newItems.append(Item(clip: clip))
            case .failure(let e): loadErrors.append(e.localizedDescription)
            }
        }
        let pos = min(index ?? items.count, items.count)
        items.insert(contentsOf: newItems, at: pos)
        isLoading = false
        loadMessage = newItems.isEmpty ? "" : "\(newItems.count) 本追加しました。"

        for item in newItems {
            Task {
                let duration = item.clip.duration
                guard let cg = await ClipInfo.thumbnail(for: item.clip.url, at: min(1, duration / 2)) else { return }
                let image = NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
                if let i = items.firstIndex(where: { $0.id == item.id }) {
                    items[i].thumbnail = image
                }
            }
        }
    }

    // MARK: - 並び

    func sort(_ order: SortOrder) {
        let sorted = sortClips(items.map(\.clip), by: order)
        let byURL = Dictionary(uniqueKeysWithValues: items.map { ($0.id, $0) })
        items = sorted.compactMap { byURL[$0.url] }
    }

    func reverse() { items.reverse() }

    func move(from source: IndexSet, to destination: Int) {
        items.move(fromOffsets: source, toOffset: destination)
    }

    func move(_ index: Int, by delta: Int) {
        let to = index + delta
        guard items.indices.contains(index), items.indices.contains(to) else { return }
        items.swapAt(index, to)
    }

    func remove(_ ids: Set<URL>) {
        items.removeAll { ids.contains($0.id) }
    }

    func removeAll() {
        items.removeAll()
        overrides.removeAll()
    }

    // MARK: - つなぎ目

    private func key(_ i: Int) -> String {
        items[i].id.path + "|" + items[i + 1].id.path
    }

    /// items[i] と items[i+1] の間をフェードするか
    func transition(after i: Int) -> Bool {
        if let v = overrides[key(i)] { return v }
        return !(keepChapters && isSameRecording(items[i].clip.name, items[i + 1].clip.name))
    }

    func toggleTransition(after i: Int) {
        overrides[key(i)] = !transition(after: i)
    }

    var options: JoinOptions {
        JoinOptions(
            fade: fadeOn ? fadeSeconds : 0,
            transitions: items.count > 1 ? (0..<items.count - 1).map { transition(after: $0) } : [],
            fadeEdges: fadeEdges,
            codec: codec,
            quality: quality)
    }

    var plan: Result<JoinPlan, Error>? {
        guard !items.isEmpty else { return nil }
        return Result { try makePlan(items.map(\.clip), options) }
    }

    var totalDuration: Double {
        items.reduce(0) { $0 + $1.clip.duration }
    }

    // MARK: - 実行

    func chooseOutputAndStart() {
        guard !items.isEmpty, !isRunning else { return }
        let panel = NSSavePanel()
        panel.message = "連結した動画の保存先"
        panel.prompt = "連結して保存"
        panel.allowedContentTypes = [.mpeg4Movie]
        panel.nameFieldStringValue = "joined.mp4"
        panel.directoryURL = items[0].clip.url.deletingLastPathComponent()
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        start(output: url)
    }

    func start(output: URL) {
        let clips = items.map(\.clip)
        let opts = options
        let joiner = Joiner()
        self.joiner = joiner
        job = .running(0, "準備中…")
        // 処理中にスリープしないようにする
        let activity = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiated, .idleSystemSleepDisabled], reason: "動画の連結")

        Task {
            defer { ProcessInfo.processInfo.endActivity(activity) }
            do {
                try await joiner.join(clips, to: output, options: opts) { p, msg in
                    Task { @MainActor [weak self] in
                        guard let self, self.isRunning else { return }
                        self.job = .running(p, msg)
                    }
                }
                job = .done(output)
                NSApp.requestUserAttention(.informationalRequest)
            } catch JoinError.cancelled {
                job = .cancelled
            } catch {
                job = .failed(error.localizedDescription)
            }
            self.joiner = nil
        }
    }

    func cancel() {
        joiner?.cancel()
    }

    func revealOutput() {
        if case .done(let url) = job {
            NSWorkspace.shared.activateFileViewerSelecting([url])
        }
    }
}
