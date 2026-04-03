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
    private String baseUom; // PCS, BOX, KG

    private BigDecimal minStockLevel;

    @Builder.Default
    private boolean isDeleted = false;
}