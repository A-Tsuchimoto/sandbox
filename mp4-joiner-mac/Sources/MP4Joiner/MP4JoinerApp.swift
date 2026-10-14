import AppKit
import SwiftUI

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // .app にせず `swift run` で起動したときも前面に出す
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}

@main
struct MP4JoinerApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup("MP4 Joiner") {
            ContentView()
                .environmentObject(model)
                .frame(minWidth: 760, minHeight: 600)
        }
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("動画を追加…") { model.showOpenPanel() }
                    .keyboardShortcut("o")
                    .disabled(model.isRunning)
                Button("連結して保存…") { model.chooseOutputAndStart() }
                    .keyboardShortcut("s")
                    .disabled(model.items.isEmpty || model.isRunning)
            }
        }
    }
}
