# Nghe Việt — bản thử Android 0.1

App nghe tiếng Anh qua mic điện thoại, dịch từng từ/cụm ngắn và tự đọc tiếng Việt qua tai nghe. Mục tiêu thiết bị: Samsung Galaxy S24 arm64 và Soundcore P30i. Đây là bản thử cần xác nhận trên máy thật; build thành công không chứng minh độ trễ, chất lượng dịch, định tuyến âm thanh hay thời lượng pin.

## Cài và dùng

1. Cài APK. Android có thể yêu cầu cho phép cài từ ứng dụng đang mở file.
2. Kết nối P30i. Trong bảng Đầu ra media của Samsung, chọn P30i.
3. Mở Nghe Việt → **Tải model dịch (Wi-Fi)**, chờ báo sẵn sàng.
4. Mở **Cài giọng Google TTS tiếng Việt**. Cài/cập nhật Speech Recognition & Synthesis của Google nếu chưa có. Chọn engine Google, tải dữ liệu giọng Tiếng Việt.
5. Bấm **Bắt đầu nghe**, cấp quyền mic, thiết bị lân cận và thông báo. Chờ báo đang nghe.
6. Đặt điện thoại nơi thu rõ tiếng người đối diện. Có thể khóa màn hình sau khi phiên đã bắt đầu.
7. Bấm **Dừng** trong thông báo hoặc quay về app. Để tiếp tục sau khi dừng, mở app và bấm Bắt đầu.

Chỉ cần Internet để tải model ML Kit / giọng Google lần đầu. Model Zipformer đã nằm trong APK. Không tự tải model trong phiên nghe.

## Kiểm tra bắt buộc trên S24

- Phát một đoạn tiếng Anh từ thiết bị khác ở âm lượng hội thoại trong ít nhất 2 phút; kiểm tra nhận từ và phát tiếng Việt.
- Bật chế độ máy bay, bật lại Bluetooth, tắt Wi-Fi; chạy lại để xác nhận cả ba bước offline.
- Khóa màn hình 15 phút rồi 30 phút; xem mic/đọc có tiếp tục, pin và nhiệt độ có chấp nhận được.
- Đang đọc, ngắt P30i: app phải dừng phiên, không tiếp tục đọc qua loa. Không coi kiểm tra thiết bị kết nối là bằng chứng đủ cho định tuyến thực tế.
- Thử cuộc gọi/ứng dụng khác giành âm thanh: app dừng và yêu cầu bắt đầu lại.
- Thử nói nhanh 5 phút: kiểm tra lượng từ chờ, từ bị nhận sai/lặp, độ trễ tăng dần. Đặt tốc độ đọc 1.7–2.0× tùy khả năng nghe.
- Nếu Samsung ngắt phiên: Cài đặt → Ứng dụng → Nghe Việt → Pin → Không hạn chế; kiểm tra mục Ứng dụng ngủ sâu.
- Phiên tự dừng sau 2 giờ để giới hạn wake lock; mở app bấm Bắt đầu để tiếp tục.

## Cấu hình

- ASR: `sherpa-onnx-streaming-zipformer-en-2023-06-26`, encoder/decoder/joiner INT8, sherpa-onnx 2.0.1 CPU 2 luồng, PCM mono 16 kHz.
- Dịch: Google ML Kit Translation 17.0.3, EN → VI.
- TTS: package `com.google.android.tts`, chỉ giọng `vi` có `isNetworkConnectionRequired == false` và không đánh dấu chưa cài; chọn giọng độ trễ thấp.
- App Kotlin native, Android 12+ (API 31), target API 35, ARM64.
- Foreground service `microphone|mediaPlayback`; bắt đầu từ Activity có quyền mic. Không tự bật mic khi khởi động máy hay sau khi bị hệ thống giết.
- Partial wake lock, notification Dừng, audio focus, noisy receiver và callback thiết bị.

## Dịch từng từ: hành vi và giới hạn

App không dùng ML Kit như mô hình dịch đồng thời; nó là API dịch văn bản. App tự chia kết quả ASR thành các đoạn ngắn bất biến và dịch từng đoạn. Không hứa chất lượng câu hay độ trễ 1 giây.

- Sau khoảng giây đầu, kết quả tạm được so với lần trước mỗi chu kỳ thu/giải mã. Từ đã xuất hiện ổn định trong hai lần quan sát có thể được chốt. Từ cuối được giữ thêm khoảng 450 ms; một số cụm động từ thông dụng được ghép hai từ khi có thể.
- Khi ASR sửa từ đã đọc, app không đọc lại vị trí đó. Điều này hạn chế lặp nhưng có thể bỏ sót/sai nghĩa khi nhận dạng thêm/xóa từ ở đầu câu. Đây là đánh đổi của chế độ nhanh, chưa có cơ chế sửa lời nói đã phát.
- Dịch từng từ dễ chọn nhầm nghĩa; không có bộ từ điển đảm bảo từ nào cũng được dịch đúng. Không có nhận dạng người nói: khi người dùng trả lời bằng tiếng Anh, app cũng có thể dịch lời của chính người dùng.
- Tốc độ TTS yêu cầu từ 1.0× đến 2.0×; giá trị thực tế tùy engine/giọng. Tăng dần theo số đoạn chờ. Nếu backlog vượt giới hạn 160 đoạn ở một bước, phiên dừng với thông báo; không âm thầm bỏ hoặc tóm tắt nội dung.
- Mic điện thoại được yêu cầu qua `setPreferredDevice` và kiểm tra đường thu khi hoạt động. TTS dùng đường media của Android, không trực tiếp ghim output vật lý. Cần chọn P30i ở đầu ra media và kiểm chứng trên máy thật; callback mất tai nghe/noisy dừng phát nhưng không bảo đảm tuyệt đối không có một khoảnh khắc âm thanh lọt ra loa do thời gian chuyển tuyến của hệ thống.
- Không có benchmark S24, chưa đo end-to-end latency hay mức tiêu thụ pin. Chỉ số trong UI gồm thời gian chốt đoạn đầu và thời gian dịch một đoạn; không phải độ trễ từ lời nói đến tai người nghe.

## Build

Cần JDK 17 hoặc 21, Android SDK 35 / build-tools 35.0.0, Internet để lấy dependencies.

```bash
python3 tools/fetch_assets.py
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. ZIP mã nguồn kèm script tải AAR và model từ upstream, xác minh SHA-256 theo DEPENDENCY_SHA256.json. Chạy script trước khi mở/build project. Bản APK đã có sẵn AAR và model ASR. Đường dẫn SDK dùng `ANDROID_HOME` hoặc `local.properties` riêng trên máy; không có đường dẫn môi trường xây dựng hard-code trong project.

## Nguồn và giấy phép

- sherpa-onnx 2.0.1 AAR: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1 (framework Apache-2.0; xem bản license kèm nguồn upstream).
- Model EN INT8: https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models ; tài liệu https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html ; nguồn huấn luyện https://huggingface.co/Zengwei/icefall-asr-librispeech-streaming-zipformer-2023-05-17 . Cần xác minh điều khoản model riêng trước khi phát hành thương mại; không suy giấy phép model từ giấy phép engine.
- ML Kit: https://developers.google.com/ml-kit/language/translation/android — dịch vụ/SDK và model thuộc điều khoản Google.
- Google TTS thuộc Google, người dùng cài riêng; không đóng gói model TTS của Google trong APK.
- Bản debug để thử cá nhân, chưa phải bản phát hành Play Store.

Không lưu audio hoặc bản dịch ra đĩa, không có analytics do app thêm vào. Giao diện giữ đoạn gần nhất trong RAM. Google SDK/engine là phụ thuộc bên thứ ba với điều khoản riêng.
