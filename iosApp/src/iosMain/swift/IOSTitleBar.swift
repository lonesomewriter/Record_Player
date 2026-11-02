import SwiftUI

struct TitleBar: View {
    var title: String
    var subtitle: String? = nil
    @Environment(\.presentationMode) var presentationMode
    @Environment(\.colorScheme) var colorScheme
    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(title)
                    .font(.headline)
                    .foregroundColor(.primary)
                if let subtitle = subtitle {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
            Spacer()
            Button(action: { presentationMode.wrappedValue.dismiss() }) {
                Image(systemName: "xmark")
                    .font(.body)
                    .foregroundColor(.primary)
            }
        }
        .padding(.horizontal)
        .padding(.top, UIApplication.shared.windows.first?.safeAreaInsets.top ?? 0)
        .background(colorScheme == .dark ? Color.black : Color.white)
    }
}