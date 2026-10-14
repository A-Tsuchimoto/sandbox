// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "MP4Joiner",
    platforms: [.macOS(.v13)],
    targets: [
        // 連結処理（UI に依存しない）
        .target(name: "JoinerCore"),
        // Mac アプリ本体（SwiftUI）
        .executableTarget(name: "MP4Joiner", dependencies: ["JoinerCore"]),
        .testTarget(name: "JoinerCoreTests", dependencies: ["JoinerCore"]),
    ]
)
