package com.zirocraft.zirologistics.entity;

import jakarta.persistence.*;
import lombok.*;

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

    // Kita ganti namanya dari isDeleted menjadi deleted
    // biar Lombok Builder mendeteksi method .deleted()
    @Builder.Default
    private boolean deleted = false;
}