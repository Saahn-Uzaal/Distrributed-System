# P01 — kết quả đo (5 lượt/hành khách)

    Máy đo (load generator k6 và toàn bộ stack chạy CHUNG một máy — số đo không tách được tải của k6):
    CPU: AMD Ryzen 9 5900HX with Radeon Graphics (16 logical)
    RAM: 31.9 GiB
    OS: Microsoft Windows 11 Home 10.0.26300
    Docker: server 29.8.1, Docker Desktop (containerized), VM 16 CPU, 16693227520 bytes RAM
    k6: grafana/k6:2.3.0; Nginx: 1 worker, 512 connections; PostgreSQL và RabbitMQ: một instance dùng chung
    Commit: 0c24f96
    Thời điểm: 2026-10-30T16:33:45Z

Latency ghi p50 / p95, đơn vị ms. `create→offered`: từ lúc gửi POST tới DRIVER_OFFERED; `create→accepted`: tới ACCEPTED (tài xế giả lập nhận ngay). Sai số poll tối đa 500 ms. Throughput tính trên cửa sổ đo, không gồm setup. `scarce` giữ tài xế 10 s mỗi chuyến nên throughput của nó bị chặn bởi số tài xế, không so trực tiếp với `enough`.

| Dataset | Hành khách | Tài xế | Giữ tài xế (s) | Trip tạo | Hoàn tất | NO_DRIVER | Tỷ lệ NO_DRIVER | POST 202 | create→offered | create→accepted | create→NO_DRIVER | Trip tạo/s | Hoàn tất/s | Lỗi kỹ thuật | Tỷ lệ lỗi KT | Offer mất | 409 nghiệp vụ | Heartbeat lỗi | Gán trùng |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| enough | 10 | 10 | 0 | 50 | 50 | 0 | 0.0% | 6 / 13 | 2022 / 8055 | 2038 / 8070 | – / – | 1.86 | 1.86 | 0 | 0.00% | 0 | 0 | 0 | 0 |
| enough | 30 | 30 | 0 | 150 | 150 | 0 | 0.0% | 6 / 18 | 3024 / 9103 | 3039 / 9118 | – / – | 3.49 | 3.49 | 0 | 0.00% | 0 | 0 | 0 | 0 |
| enough | 50 | 50 | 0 | 250 | 250 | 0 | 0.0% | 5 / 51 | 5536 / 10651 | 5550 / 10663 | – / – | 4.41 | 4.41 | 0 | 0.00% | 0 | 0 | 0 | 0 |
| scarce | 10 | 2 | 10 | 50 | 12 | 38 | 76.0% | 7 / 11 | 1519 / 13623 | 1534 / 13638 | 13114 / 14112 | 0.66 | 0.16 | 0 | 0.00% | 0 | 0 | 0 | 0 |
| scarce | 30 | 6 | 10 | 150 | 46 | 104 | 69.3% | 8 / 40 | 8570 / 13624 | 8591 / 13641 | 14129 / 15646 | 1.60 | 0.49 | 0 | 0.00% | 0 | 0 | 0 | 0 |
| scarce | 50 | 10 | 10 | 250 | 90 | 160 | 64.0% | 6 / 66 | 10580 / 15237 | 10596 / 15254 | 15635 / 18259 | 2.27 | 0.82 | 0 | 0.00% | 0 | 0 | 0 | 0 |
