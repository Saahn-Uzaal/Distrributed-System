# Thiết kế tìm địa chỉ, bản đồ và định tuyến

## 1. Mục tiêu

Thay luồng nhập trực tiếp kinh độ/vĩ độ bằng luồng thân thiện hơn:

1. Hành khách nhập địa chỉ đón và địa chỉ đến tại Việt Nam.
2. Hành khách chủ động bấm **Tìm** hoặc Enter và chọn một kết quả cụ thể.
3. Hệ thống hiển thị hai điểm và tuyến đường bộ trên bản đồ.
4. Backend tính khoảng cách, thời gian và giá dự kiến từ tuyến đường.
5. Hành khách xem báo giá rồi mới quyết định đặt chuyến.
6. Tài xế xem được tên địa chỉ và cùng tuyến đường trước khi nhận chuyến và trong khi phục vụ.

Phạm vi này dành cho môi trường local/demo với lưu lượng nhỏ và chấp nhận phụ thuộc vào các dịch vụ công cộng miễn phí.

## 2. Dịch vụ và thư viện

- **Leaflet + React Leaflet** hiển thị bản đồ, marker và route geometry.
- **OpenStreetMap tiles** làm lớp bản đồ nền, kèm attribution bắt buộc.
- **Nominatim công cộng** tìm địa chỉ, giới hạn kết quả trong Việt Nam.
- **OSRM công cộng** tính tuyến đường ô tô, khoảng cách và thời gian dự kiến.

Frontend không gọi trực tiếp Nominatim hoặc OSRM. `trip-service` làm proxy/integration boundary để kiểm soát timeout, cache, rate limit, định dạng dữ liệu và tính toàn vẹn của báo giá. URL của Nominatim, OSRM và cấu hình timeout được cung cấp qua biến môi trường để có thể đổi provider hoặc tự host mà không sửa frontend.

### Ràng buộc Nominatim

- Không triển khai autocomplete theo từng phím.
- Chỉ gửi request khi người dùng bấm **Tìm** hoặc Enter.
- Tổng lưu lượng ra Nominatim không vượt quá 1 request/giây trên toàn ứng dụng.
- Gửi `User-Agent` nhận diện ứng dụng và tuân thủ yêu cầu attribution.
- Cache kết quả để không lặp lại cùng một truy vấn.

## 3. Kiến trúc và API

### 3.1. Tìm địa chỉ

`GET /api/locations/search?q=<query>`

- Chỉ dành cho người dùng đã đăng nhập.
- Chuẩn hóa và kiểm tra độ dài truy vấn.
- Gọi Nominatim với giới hạn quốc gia Việt Nam và tối đa 5 kết quả.
- Trả về mô hình nội bộ ổn định:

```json
{
  "results": [
    {
      "placeId": "nominatim:123",
      "displayName": "...",
      "latitude": 21.0345,
      "longitude": 105.8409
    }
  ]
}
```

Backend không chuyển nguyên response của Nominatim ra frontend.

### 3.2. Tạo báo giá

`POST /api/trips/quote`

Request chứa hai địa điểm đã chọn, gồm `placeId`, `displayName`, `latitude` và `longitude`. Backend:

1. Kiểm tra tọa độ hợp lệ và địa điểm thuộc kết quả tìm kiếm hợp lệ của phiên/người dùng.
2. Gọi OSRM để lấy tuyến đường ô tô.
3. Dùng khoảng cách tuyến đường do OSRM trả về để tính giá theo cấu hình fare hiện tại.
4. Lưu một báo giá có thời hạn 10 phút.
5. Trả về `quoteToken`, địa chỉ, khoảng cách, thời gian, giá, route geometry và thời điểm hết hạn.

Route geometry dùng GeoJSON LineString hoặc encoded polyline; lựa chọn cụ thể khi lập kế hoạch phải nhất quán giữa OSRM adapter, database và Leaflet. Không chấp nhận khoảng cách hay giá do frontend cung cấp.

### 3.3. Đặt chuyến

`POST /api/trips`

Request mới chỉ cần `quoteToken`; header `Idempotency-Key` vẫn bắt buộc. Trong cùng transaction, backend:

1. Khóa báo giá.
2. Kiểm tra báo giá thuộc hành khách hiện tại, còn hạn và chưa được dùng.
3. Kiểm tra hành khách chưa có chuyến active như hiện tại.
4. Tạo chuyến từ dữ liệu báo giá do server lưu.
5. Đánh dấu báo giá đã sử dụng.
6. Ghi trip event, saga và outbox `TripRequested` như luồng hiện tại.

Cơ chế idempotency hiện tại được giữ nguyên. Replay cùng key và cùng `quoteToken` trả lại response gốc; dùng cùng key với token khác trả `IDEMPOTENCY_MISMATCH`.

## 4. Mô hình dữ liệu

Tạo bảng `trip_quotes` với các trường chính:

- `id`/token ngẫu nhiên, khó đoán.
- `passenger_id`.
- Địa chỉ và tọa độ điểm đón/điểm đến.
- `route_distance_m`, `route_duration_s`, `route_geometry`.
- `fare_vnd`.
- `created_at`, `expires_at`, `used_at`.

Bảng `trips` bổ sung:

- `pickup_address`.
- `destination_address`.
- `route_distance_m` hoặc tái sử dụng `distance_m` với ý nghĩa mới được migration/documentation làm rõ.
- `route_duration_s`.
- `route_geometry`.

Các cột tọa độ hiện có được giữ lại. Matching tiếp tục dùng tọa độ điểm đón và vị trí tài xế; tên địa chỉ và geometry phục vụ hiển thị. Không chỉnh sửa migration cũ, chỉ thêm migration mới.

Event/command giữa Trip, Matching và Driver được mở rộng bằng địa chỉ, thời gian dự kiến và route geometry cần thiết để driver-service lưu offer độc lập. Phải tăng schema version theo quy ước event hiện tại và duy trì validation rõ ràng.

## 5. Trải nghiệm hành khách

Form đặt chuyến thay bốn ô tọa độ bằng hai bộ điều khiển địa chỉ:

- Ô **Điểm đón**, nút **Tìm**, danh sách tối đa 5 kết quả.
- Ô **Điểm đến**, nút **Tìm**, danh sách tối đa 5 kết quả.
- Bản đồ Leaflet mặc định tập trung tại Việt Nam.
- Marker khác nhau cho điểm đón và điểm đến.

Khi đã chọn đủ hai điểm, người dùng bấm **Xem tuyến đường và giá**. Sau khi API quote thành công, giao diện hiển thị:

- Tuyến đường trên bản đồ.
- Hai địa chỉ đã xác nhận.
- Khoảng cách đường bộ.
- Thời gian dự kiến.
- Giá dự kiến.
- Nút **Đặt chuyến**.

Sửa hoặc chọn lại một địa chỉ phải xóa ngay quote, geometry và `quoteToken` cũ. Nút đặt chuyến chỉ bật khi quote còn hiệu lực. Desktop ưu tiên bố cục form/thông tin và bản đồ hai cột; mobile xếp form và thông tin phía trên bản đồ.

## 6. Trải nghiệm tài xế

`OfferPanel` và `BusyTripPanel` hiển thị:

- Tên địa chỉ đón và địa chỉ đến thay cho tọa độ thô.
- Bản đồ với hai marker và tuyến đường đã chốt khi quote.
- Khoảng cách đường bộ, thời gian dự kiến và giá.
- Các hành động nhận, từ chối, bắt đầu và hoàn thành theo state machine hiện tại.

Tuyến hiển thị cho tài xế là snapshot đã lưu, không gọi lại OSRM và không tự thay đổi sau khi hành khách đặt chuyến.

## 7. Xử lý lỗi

### Tìm kiếm

- Truy vấn rỗng/quá ngắn: trả `INVALID_INPUT`.
- Không có kết quả: response thành công với danh sách rỗng; UI hiển thị thông báo phù hợp.
- Nominatim timeout, rate limit hoặc lỗi: trả lỗi upstream có correlation ID và cho phép người dùng thử lại thủ công.

### Định tuyến và báo giá

- OSRM không tìm được tuyến: không tạo quote.
- OSRM timeout hoặc lỗi: không tạo quote, xóa tuyến cũ trên UI và khóa đặt chuyến.
- Không fallback sang khoảng cách Haversine.
- Quote hết hạn, đã dùng hoặc không thuộc người dùng: từ chối đặt chuyến và yêu cầu tính lại.

Backend đặt timeout hữu hạn, không retry dồn dập. Log chỉ chứa metadata cần thiết và correlation ID, không ghi token đăng nhập hoặc dữ liệu bí mật.

## 8. Cache và giới hạn lưu lượng

- Cache tìm kiếm theo truy vấn đã chuẩn hóa và phạm vi Việt Nam.
- Dùng rate limiter dùng chung trong instance; cấu hình demo chạy một `trip-service` instance. Nếu scale nhiều instance, rate limit phải chuyển sang cơ chế chia sẻ trước khi cho phép tăng replica.
- Không gọi Nominatim định kỳ hoặc trong polling.
- Route quote chỉ được tạo theo thao tác rõ ràng của người dùng.
- Có thể dọn `trip_quotes` đã hết hạn bằng scheduled worker theo batch hữu hạn.

## 9. Kiểm thử

- Unit test cho validation, chuẩn hóa query, cache/rate limit, mapping response Nominatim/OSRM và fare theo khoảng cách tuyến đường.
- Integration test dùng mock HTTP server; CI không phụ thuộc Internet.
- Migration test cho `trip_quotes` và các cột route/address.
- Transaction/idempotency test cho `quoteToken`, replay, hết hạn, dùng hai lần và sai chủ sở hữu.
- Frontend test cho tìm/chọn địa chỉ, vẽ route, invalidation khi địa chỉ đổi, quote expiry và lỗi upstream.
- Driver UI test cho tên địa chỉ, bản đồ và geometry.
- E2E cho tìm địa chỉ → quote → đặt chuyến → tài xế xem/nhận chuyến.
- Chạy lại test concurrency, recovery, security và idempotency hiện có.

## 10. Tiêu chí hoàn thành

- Người dùng không cần nhập kinh độ/vĩ độ.
- Chỉ tìm địa chỉ tại Việt Nam và luôn yêu cầu thao tác **Tìm** rõ ràng.
- Giá được tính server-side từ khoảng cách tuyến đường OSRM.
- Không thể đặt chuyến nếu geocoding/routing thất bại hoặc quote không hợp lệ.
- Khách và tài xế nhìn thấy cùng địa chỉ, tuyến đường, khoảng cách, thời gian và giá.
- Attribution OpenStreetMap hiển thị đúng.
- Các test cũ và test mới đều vượt qua; CI không gọi dịch vụ bản đồ công cộng.

## 11. Ngoài phạm vi

- Autocomplete theo từng ký tự.
- Tự host Nominatim, OSRM hoặc tile server.
- Dẫn đường turn-by-turn hay theo dõi GPS thời gian thực.
- Tái định tuyến khi tài xế di chuyển.
- Phạm vi tìm kiếm ngoài Việt Nam.
