import SwiftUI

@main
struct iosApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    var body: some View {
        VStack(spacing: 16) {
            Text("DC Cleaner")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("iPhone version")
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}
