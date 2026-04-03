package com.zirocraft.zirologistics.io.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
public class WarehouseResponse {
    private String publicId; // Pakai UUID, bukan ID Long
    private String code;
    private String name;
    private String address;
    private LocalDateTime createdAt;
}