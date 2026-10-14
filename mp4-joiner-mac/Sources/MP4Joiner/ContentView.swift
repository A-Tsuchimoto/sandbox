import JoinerCore
import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var selection = Set<URL>()
    @State private var dropTargeted = false

    var body: some View {
        VStack(spacing: 0) {
            clipList
                .disabled(model.isRunning)
            Divider()
            OptionsView()
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .disabled(model.isRunning)
            Divider()
            BottomBar()
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
        }
        .toolbar {
            ToolbarItemGroup {
                Button {
                    model.showOpenPanel()
                } label: {
                    Label("追加", systemImage: "plus")
                }
                .help("動画ファイルやフォルダを追加（Finder からドラッグ＆ドロップでも追加できます）")
                .disabled(model.isRunning)

                Menu {
                    ForEach(ClipOrder.allCases, id: \.self) { order in
                        Button(order.label) { model.sort(order) }
                    }
                    Divider()
                    Button("逆順") { model.reverse() }
                } label: {
                    Label("並べ替え", systemImage: "arrow.up.arrow.down")
                }
                .help("GoPro 順: ファイル番号 → 分割番号の順に並べます")
                .disabled(model.isRunning || model.items.isEmpty)

                Button(role: .destructive) {
                    model.removeAll()
                    selection.removeAll()
                } label: {
                    Label("全部外す", systemImage: "trash")
                }
                .help("リストを空にします（元の動画は消えません）")
                .disabled(model.isRunning || model.items.isEmpty)
            }
        }
    }

    private var clipList: some View {
        List(selection: $selection) {
            ForEach(Array(model.items.enumerated()), id: \.element.id) { index, item in
                ClipRow(index: index, item: item)
                    .contextMenu {
                        Button("上へ") { model.move(index, by: -1) }.disabled(index == 0)
                        Button("下へ") { model.move(index, by: 1) }.disabled(index == model.items.count - 1)
                        Divider()
                        Button("Finder で表示") { NSWorkspace.shared.activateFileViewerSelecting([item.id]) }
                        Button("リストから外す") { model.remove(selection.contains(item.id) ? selection : [item.id]) }
                    }
            }
            .onMove { model.move(from: $0, to: $1) }
            .onInsert(of: [.fileURL]) { index, providers in
                loadURLs(providers) { urls in Task { await model.add(urls, at: index) } }
            }
        }
        .onDeleteCommand {
            model.remove(selection)
            selection.removeAll()
        }
        .overlay {
            if model.items.isEmpty {
                EmptyState(targeted: dropTargeted)
                    .onDrop(of: [.fileURL], isTargeted: $dropTargeted) { providers in
                        loadURLs(providers) { urls in Task { await model.add(urls) } }
                        return true
                    }
            }
        }
    }
}

/// ドロップされたファイルの URL を取り出す
func loadURLs(_ providers: [NSItemProvider], completion: @escaping ([URL]) -> Void) {
    final class Box: @unchecked Sendable {
        let lock = NSLock()
        var urls: [URL?]
        init(_ n: Int) { urls = Array(repeating: nil, count: n) }
    }
    let box = Box(providers.count)
    let group = DispatchGroup()
    for (i, p) in providers.enumerated() {
        group.enter()
        _ = p.loadObject(ofClass: URL.self) { url, _ in
            box.lock.lock()
            box.urls[i] = url
            box.lock.unlock()
            group.leave()
        }
    }
    group.notify(queue: .main) { completion(box.urls.compactMap { $0 }) }
}

struct EmptyState: View {
    let targeted: Bool

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "film.stack")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("ここに動画やフォルダをドラッグ＆ドロップ")
                .font(.title3)
            Text("またはツールバーの「＋ 追加」から選んでください")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .background(targeted ? Color.accentColor.opacity(0.12) : Color.clear)
    }
}

struct ClipRow: View {
    @EnvironmentObject var model: AppModel
    let index: Int
    let item: AppModel.Item

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if index > 0 && model.fadeOn {
                transitionChip
            }
            HStack(spacing: 12) {
                Text("\(index + 1)")
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 30, alignment: .trailing)
                thumbnail
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.clip.name)
                        .font(.headline)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .help(item.clip.url.path)
                    Text(info)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                HStack(spacing: 2) {
                    Button { model.move(index, by: -1) } label: { Image(systemName: "chevron.up") }
                        .disabled(index == 0)
                        .help("上へ")
                    Button { model.move(index, by: 1) } label: { Image(systemName: "chevron.down") }
                        .disabled(index == model.items.count - 1)
                        .help("下へ")
                    Button { model.remove([item.id]) } label: { Image(systemName: "xmark") }
                        .help("リストから外す")
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 3)
    }

    private var transitionChip: some View {
        let on = model.transition(after: index - 1)
        let chapter = isSameRecording(model.items[index - 1].clip.name, item.clip.name)
        return Button {
            model.toggleTransition(after: index - 1)
        } label: {
            Label(on ? "前とのつなぎ目: フェード" : (chapter ? "前とのつなぎ目: そのまま（同じ撮影の続き）" : "前とのつなぎ目: そのまま"),
                  systemImage: on ? "circle.lefthalf.filled" : "arrow.right")
                .font(.caption)
                .padding(.horizontal, 10)
                .padding(.vertical, 2)
                .background(Capsule().fill(on ? Color.accentColor.opacity(0.18) : Color.secondary.opacity(0.12)))
        }
        .buttonStyle(.plain)
        .padding(.leading, 42)
        .help("クリックでフェードする / しないを切り替え")
    }

    private var thumbnail: some View {
        ZStack {
            Rectangle().fill(Color.black)
            if let image = item.thumbnail {
                Image(nsImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .frame(width: 112, height: 63)
        .clipShape(RoundedRectangle(cornerRadius: 5))
    }

    private var info: String {
        let c = item.clip
        var parts = [
            formatTime(c.duration),
            "\(Int(c.displaySize.width))×\(Int(c.displaySize.height))",
            String(format: "%.2ffps", c.frameRate).replacingOccurrences(of: ".00fps", with: "fps"),
            c.videoCodec.hasPrefix("hv") || c.videoCodec.hasPrefix("hev") ? "HEVC" : (c.videoCodec.hasPrefix("avc") ? "H.264" : c.videoCodec),
            ByteCountFormatter.string(fromByteCount: c.fileSize, countStyle: .file),
        ]
        if !c.hasAudio { parts.append("音声なし") }
        if let d = c.creationDate {
            parts.append(d.formatted(date: .numeric, time: .shortened))
        }
        return parts.joined(separator: " · ")
    }
}

struct OptionsView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 8) {
            GridRow {
                Text("フェード").foregroundStyle(.secondary)
                HStack(spacing: 12) {
                    Toggle("つなぎ目をフェードアウト → フェードイン", isOn: $model.fadeOn)
                    Slider(value: $model.fadeSeconds, in: 0.5...5, step: 0.5)
                        .frame(width: 150)
                        .disabled(!model.fadeOn)
                    Text(String(format: "%.1f 秒", model.fadeSeconds))
                        .monospacedDigit()
                        .foregroundStyle(model.fadeOn ? .primary : .secondary)
                }
            }
            GridRow {
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                HStack(spacing: 16) {
                    Toggle("GoPro が自動分割した同じ撮影のつなぎ目はフェードしない", isOn: $model.keepChapters)
                    Toggle("動画の最初と最後もフェード", isOn: $model.fadeEdges)
                }
                .disabled(!model.fadeOn)
            }
            GridRow {
                Text("画質").foregroundStyle(.secondary)
                HStack(spacing: 12) {
                    Picker("コーデック", selection: $model.codec) {
                        ForEach(VideoCodec.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    .labelsHidden()
                    .frame(width: 260)
                    Picker("画質", selection: $model.quality) {
                        ForEach(Quality.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    .labelsHidden()
                    .frame(width: 110)
                    Text("※ 再エンコード時のみ使います")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct BottomBar: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            summary
            switch model.job {
            case .running(let p, let message):
                VStack(alignment: .leading, spacing: 4) {
                    ProgressView(value: p)
                    HStack {
                        Text(message).lineLimit(1).truncationMode(.middle)
                        Spacer()
                        Text("\(Int(p * 100))%").monospacedDigit().foregroundStyle(.secondary)
                    }
                    .font(.callout)
                }
            case .failed(let message):
                Text(message)
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
            case .done(let url):
                Label("完了しました: \(url.path)", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .lineLimit(1)
                    .truncationMode(.middle)
            case .cancelled:
                Text("キャンセルしました。").foregroundStyle(.secondary)
            case .idle:
                EmptyView()
            }
            HStack {
                if model.isRunning {
                    Button("キャンセル") { model.cancel() }
                        .keyboardShortcut(.cancelAction)
                } else {
                    Button("連結して保存…") { model.chooseOutputAndStart() }
                        .keyboardShortcut(.defaultAction)
                        .controlSize(.large)
                        .disabled(model.items.isEmpty || model.isLoading)
                    if case .done = model.job {
                        Button("Finder で表示") { model.revealOutput() }
                    }
                }
                Spacer()
                if model.isLoading { ProgressView().controlSize(.small) }
                Text(model.loadMessage).foregroundStyle(.secondary).font(.callout)
            }
            if !model.loadErrors.isEmpty {
                Text(model.loadErrors.joined(separator: "\n"))
                    .font(.caption)
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
            }
        }
    }

    @ViewBuilder private var summary: some View {
        if let plan = model.plan {
            switch plan {
            case .success(let p):
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(model.items.count) 本 · 合計 \(formatTime(p.totalDuration)) · 出力 \(Int(p.renderSize.width))×\(Int(p.renderSize.height)) · 予想サイズ 約 \(ByteCountFormatter.string(fromByteCount: p.estimatedBytes, countStyle: .file))")
                        .font(.callout.weight(.semibold))
                    Label(p.reason, systemImage: p.mode == .passthrough ? "bolt.fill" : "film")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            case .failure(let e):
                Text(e.localizedDescription).foregroundStyle(.red)
            }
        } else {
            Text("動画を追加すると、連結方法と予想サイズを表示します。")
                .foregroundStyle(.secondary)
        }
    }
}
