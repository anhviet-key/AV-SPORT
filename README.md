# AV Sport APK Update

Repo này dùng để phát hành APK và manifest cập nhật cho TV Box.

File TV đọc khi bấm cập nhật:

```text
https://raw.githubusercontent.com/anhviet-key/AV-SPORT/main/app-update.json
```

Quy trình cập nhật lần sau:

1. Build APK mới.
2. Upload APK vào thư mục `releases/`, ví dụ `releases/av-sport-1.1.3.apk`.
3. Sửa `app-update.json`: tăng `version`, tăng `versionCode`, đổi `apkUrl` sang file APK mới.
4. Commit/push lên nhánh `main`.
5. Trên TV mở AV Sport > Cài đặt > Kiểm tra cập nhật APK.

Lưu ý: `versionCode` phải lớn hơn bản đang cài trên TV thì app mới báo có cập nhật.
