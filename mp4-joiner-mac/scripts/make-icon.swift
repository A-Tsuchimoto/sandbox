// アプリアイコン（AppIcon.icns の元画像）を描く。build-app.sh から呼ばれる
//   swift scripts/make-icon.swift <出力 .iconset フォルダ>
import AppKit

let out = URL(fileURLWithPath: CommandLine.arguments[1])
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

func draw(_ px: Int) -> Data {
    let s = CGFloat(px)
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    // 背景（macOS の角丸スクエア）
    let inset = s * 0.1
    let bg = NSBezierPath(roundedRect: NSRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2),
                          xRadius: s * 0.18, yRadius: s * 0.18)
    NSGradient(starting: NSColor(red: 0.16, green: 0.55, blue: 0.98, alpha: 1),
               ending: NSColor(red: 0.04, green: 0.25, blue: 0.62, alpha: 1))!.draw(in: bg, angle: -90)

    // 3 本のクリップが 1 本につながるイメージ
    let colors: [NSColor] = [
        NSColor(red: 1.0, green: 0.62, blue: 0.25, alpha: 1),
        NSColor(red: 0.98, green: 0.85, blue: 0.3, alpha: 1),
        NSColor(red: 0.4, green: 0.85, blue: 0.55, alpha: 1),
    ]
    let w = s * 0.2, h = s * 0.3, gap = s * 0.03
    let x0 = (s - (w * 3 + gap * 2)) / 2
    let y = s * 0.42
    for (i, c) in colors.enumerated() {
        let r = NSRect(x: x0 + CGFloat(i) * (w + gap), y: y, width: w, height: h)
        c.setFill()
        NSBezierPath(roundedRect: r, xRadius: s * 0.025, yRadius: s * 0.025).fill()
        // フィルムの穴
        NSColor(white: 0, alpha: 0.25).setFill()
        for k in 0..<3 {
            let hole = NSRect(x: r.minX + w * (0.15 + CGFloat(k) * 0.27), y: r.maxY - h * 0.14, width: w * 0.16, height: h * 0.08)
            NSBezierPath(rect: hole).fill()
        }
    }
    // 下の 1 本の帯
    NSColor.white.setFill()
    NSBezierPath(roundedRect: NSRect(x: x0, y: s * 0.24, width: w * 3 + gap * 2, height: s * 0.09),
                 xRadius: s * 0.045, yRadius: s * 0.045).fill()

    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

for size in [16, 32, 128, 256, 512] {
    try! draw(size).write(to: out.appendingPathComponent("icon_\(size)x\(size).png"))
    try! draw(size * 2).write(to: out.appendingPathComponent("icon_\(size)x\(size)@2x.png"))
}
