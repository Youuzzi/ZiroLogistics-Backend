package com.zirocraft.zirologistics.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "items")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ItemEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private String baseUom;

    @Column(nullable = false)
    private BigDecimal minStockLevel;

    // --- WAJIB ADA INI AGAR BUILDER TIDAK ERROR ---
    @Column(name = "weight_per_unit", nullable = false)
    private BigDecimal weightPerUnit;

    @Builder.Default
    private boolean isDeleted = false;
}