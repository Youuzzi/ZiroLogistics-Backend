package com.zirocraft.zirologistics.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "bins")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class BinEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private WarehouseEntity warehouse;

    private String zoneName;
    private String rackNumber;

    @Column(unique = true, nullable = false)
    private String binCode;

    @Column(nullable = false)
    private BigDecimal maxWeightCapacity;

    @Column(nullable = false)
    private BigDecimal minWeightThreshold; // <--- Field yang tadi error/hilang

    @Column(nullable = false)
    private BigDecimal currentWeightOccupancy;

    @Builder.Default
    private boolean isDeleted = false;
}