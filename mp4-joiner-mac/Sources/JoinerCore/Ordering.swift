import Foundation

// GoPro のファイル名規則
//   HERO5 以前:  GOPR1234.MP4（1 本目）, GP011234.MP4, GP021234.MP4 …（分割の続き）
//   HERO6 以降:  GH011234.MP4, GH021234.MP4 …（GX=HEVC, GL=低解像度プロキシ）
// 名前順だと「分割番号 → ファイル番号」の順になってしまうので、
// 「ファイル番号 → 分割番号」で並べる。

/// (ファイル番号, 分割番号) を返す。GoPro 形式でなければ nil
public func goproID(_ name: String) -> (file: Int, chapter: Int)? {
    let c = Array(name.uppercased())
    func digits(_ from: Int, _ count: Int) -> Int? {
        guard c.count >= from + count else { return nil }
        let s = String(c[from..<from + count])
        return s.allSatisfy(\.isASCII) && s.allSatisfy(\.isNumber) ? Int(s) : nil
    }
    if c.count >= 9, String(c[0..<4]) == "GOPR", let file = digits(4, 4), c[8] == "." {
        return (file, 0)
    }
    if c.count >= 9, c[0] == "G", "HXPL".contains(c[1]),
       let chapter = digits(2, 2), let file = digits(4, 4), c[8] == "." {
        return (file, chapter)
    }
    return nil
}

/// GoPro が自動分割した同じ撮影の連続チャプターか（つなぎ目にフェード不要）
public func isSameRecording(_ prev: String, _ next: String) -> Bool {
    guard let a = goproID(prev), let b = goproID(next) else { return false }
    // GOPR1234 (0) → GP011234 (1)、GH011234 (1) → GH021234 (2)
    return a.file == b.file && b.chapter == a.chapter + 1
}

public enum SortOrder: String, CaseIterable {
    case gopro, date, name

    public var label: String {
        switch self {
        case .gopro: return "GoPro 順"
        case .date: return "撮影日時順"
        case .name: return "名前順"
        }
    }
}

func nameLess(_ a: String, _ b: String) -> Bool {
    a.localizedStandardCompare(b) == .orderedAscending
}

func goproLess(_ a: String, _ b: String) -> Bool {
    switch (goproID(a), goproID(b)) {
    case let (x?, y?): return x.file != y.file ? x.file < y.file : x.chapter < y.chapter
    case (_?, nil): return true
    case (nil, _?): return false
    case (nil, nil): return nameLess(a, b)
    }
}

public func sortClips(_ clips: [ClipInfo], by order: SortOrder) -> [ClipInfo] {
    switch order {
    case .gopro:
        return clips.sorted { goproLess($0.name, $1.name) }
    case .name:
        return clips.sorted { nameLess($0.name, $1.name) }
    case .date:
        // 撮影日時が無いものは末尾へ。同時刻は GoPro 順
        return clips.sorted { a, b in
            switch (a.creationDate, b.creationDate) {
            case let (x?, y?) where x != y: return x < y
            case (_?, nil): return true
            case (nil, _?): return false
            default: return goproLess(a.name, b.name)
            }
        }
    }
}
