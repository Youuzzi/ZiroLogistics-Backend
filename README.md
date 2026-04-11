# ZiroLogistics – Industrial Warehouse Management System 🏭

> Sistem manajemen gudang (WMS) skala industri yang dibangun dengan standar korporasi menggunakan Spring Boot dan MySQL.

---

## 🔗 Related Repository
- **Frontend:** *(In Progress)*

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 3.x |
| Security | Spring Security + JWT |
| ORM | Spring Data JPA (Hibernate) |
| Database | MySQL (Production), H2 (Testing) |
| Build Tool | Maven |
| API | RESTful API |

---

## ✨ Fitur Utama

### 🔐 Authentication & Authorization
- **JWT Authentication** — Stateless token-based authentication
- **Role-Based Access Control (RBAC)** — Pembatasan akses endpoint berdasarkan role

### 🏬 Warehouse Management
- CRUD Warehouse — Kelola data gudang
- CRUD Bin — Kelola lokasi penyimpanan di dalam gudang
- CRUD Item — Kelola data barang/produk

### 📦 Inventory Control
- **Inbound** — Proses penerimaan barang masuk dengan Idempotency Key
- **Outbound** — Proses pengeluaran barang dengan validasi stok
- **Inventory Ledger** — Pencatatan seluruh pergerakan stok secara historis
- **Stock Monitoring** — Pantau jumlah stok real-time per item per warehouse

### ⚡ Concurrency & Data Integrity
- **Pessimistic Write Locking** — Mencegah race condition dan anomali data stok saat transaksi bersamaan (High Concurrency)
- **Idempotency Key** — Menjamin satu request hanya diproses satu kali, mencegah duplikasi data akibat retry atau gangguan jaringan

### 🔍 Audit & Traceability
- **Audit Trail Otomatis** — Menggunakan JPA Auditing (`@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`) untuk mencatat jejak aktivitas di setiap perubahan data
- **Global Exception Handler** — Standarisasi format error response API dalam JSON

---

## 📁 Struktur Project

```
src/main/java/
├── config/          # Security, Web, Audit config
├── controller/      # REST Controllers (Auth, Bin, Inbound, Inventory, Item, Outbound, Warehouse)
├── entity/          # JPA Entities + BaseEntity (Audit)
├── exception/       # Global Exception Handler
├── filter/          # JWT Request Filter
├── repository/      # Spring Data JPA Repositories
├── request/         # Request DTOs
├── response/        # Response DTOs
├── service/         # Business Logic (Interface + Impl)
└── util/            # JWT Utility
```

---

## 🚀 Cara Menjalankan

### Prerequisites
- Java 21
- MySQL 8.x
- Maven

### Setup

```bash
# Clone repository
git clone https://github.com/Youuzzi/ZiroLogistics-Backend.git

# Buat database MySQL
CREATE DATABASE ziro_logistics;

# Konfigurasi application.properties
spring.datasource.url=jdbc:mysql://localhost:3306/ziro_logistics
spring.datasource.username=your_username
spring.datasource.password=your_password

# Jalankan aplikasi
mvn spring-boot:run
```

---

## 📌 Status Project

> ⚠️ **In Progress** — Sistem backend sudah berjalan. Frontend sedang dalam pengembangan.

---

## 👨‍💻 Developer

**Yozi Heru Maulana** — [github.com/Youuzzi](https://github.com/Youuzzi)

*Zirocraft Studio*