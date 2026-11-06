# ZiroLogistics – Backend API 🏭⚙️

> **Repository:** [github.com/Youuzzi/ZiroLogistics-Backend](https://github.com/Youuzzi/ZiroLogistics-Backend)
>
> Sistem manajemen gudang (WMS) skala industri — REST API backend yang dibangun dengan standar korporasi menggunakan Java Spring Boot dan MySQL.

---

## 🔗 Related Repository

| Repository | Link | Status |
|---|---|---|
| 🖥️ **Frontend** | [ZiroLogistics-Frontend](https://github.com/Youuzzi/ZiroLogistics-Frontend) | In Progress |

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

### 🏬 Warehouse & Bin Management
- **CRUD Warehouse** — Kelola data gudang dengan pagination
- **CRUD Bin** — Kelola lokasi penyimpanan (rak) di dalam gudang
- **Bin Capacity Control** — Setiap bin memiliki `maxWeightCapacity`, `minWeightThreshold`, dan `currentWeightOccupancy`
- **Soft Delete** — Data tidak dihapus permanen, hanya ditandai `isDeleted`

### 📦 Item Management
- **CRUD Item** — Kelola data barang/produk dengan SKU unik
- **Pagination & Sorting** — Endpoint item mendukung pagination untuk efisiensi memori

### 📥📤 Inventory Control
- **Inbound** — Proses penerimaan barang masuk ke bin spesifik dengan Idempotency Key
- **Outbound** — Proses pengeluaran barang dengan validasi stok real-time
- **Transfer** — Pindah stok antar bin dalam satu warehouse
- **Inventory Stock** — Pantau jumlah stok real-time per item per warehouse dengan pagination
- **Inventory Ledger** — Pencatatan historis seluruh pergerakan stok (inbound, outbound, transfer) dengan pagination

### 📊 Dashboard
- **Dashboard Summary** — Ringkasan total warehouse, item, total stok, dan pergerakan terkini

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
├── controller/      # Auth, Bin, Dashboard, Inbound, Inventory, Item, Outbound, Transfer, Warehouse
├── entity/          # JPA Entities + BaseEntity (Audit)
├── exception/       # Global Exception Handler
├── filter/          # JWT Request Filter
├── repository/      # Spring Data JPA Repositories
├── io/
│   ├── request/     # Request DTOs (Bin, Inbound, Item, Outbound, Transfer, Warehouse)
│   └── response/    # Response DTOs (Auth, Bin, Dashboard, Item, Ledger, Stock, Warehouse)
├── service/         # Business Logic Interface
│   └── impl/        # Service Implementation
└── util/            # JWT Utility
```

---

## 🚀 Cara Menjalankan

### Prerequisites
- Java 21
- MySQL 8.x
- Maven

### Setup

Buat database MySQL:

```sql
CREATE DATABASE ziro_logistics;
```

Konfigurasikan environment variables sebelum menjalankan aplikasi:

```powershell
$env:DB_URL="jdbc:mysql://localhost:3306/ziro_logistics"
$env:DB_USERNAME="your_username"
$env:DB_PASSWORD="your_password"
$env:JWT_SECRET_KEY="replace-with-a-secure-secret-at-least-32-characters"
```

Hibernate menggunakan default aman:
- `JPA_DDL_AUTO=validate` — memvalidasi schema dan tidak mengubahnya otomatis.
- `JPA_SHOW_SQL=false` — SQL tidak dicetak ke log aplikasi.

Untuk development lokal saja, jika memang perlu Hibernate membuat atau memperbarui tabel:

```powershell
$env:JPA_DDL_AUTO="update"
$env:JPA_SHOW_SQL="true"
```

Jangan gunakan `update` sebagai strategi deployment production. Siapkan dan kelola schema database production secara terkontrol sebelum menjalankan aplikasi dengan default `validate`.

Jalankan aplikasi:

```powershell
.\mvnw.cmd spring-boot:run
```

---

## 📌 Status Project

> ⚠️ **In Progress** — Backend sudah berjalan penuh. Frontend sedang dalam pengembangan aktif.

---

## 👨‍💻 Developer

**Yozi Heru Maulana** — [github.com/Youuzzi](https://github.com/Youuzzi)

*Zirocraft Studio*