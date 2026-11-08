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

    @Column(nullable = false, precision = 38, scale = 3)
    private BigDecimal minStockLevel;

    // Weight in KG for one Base UOM unit; preserve sub-gram values where applicable.
    @Column(name = "weight_per_unit", nullable = false, precision = 38, scale = 6)
    private BigDecimal weightPerUnit;

    @Builder.Default
    private boolean isDeleted = false;
}