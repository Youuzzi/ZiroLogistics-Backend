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

    @ManyToOne
    @JoinColumn(name = "warehouse_id")
    private WarehouseEntity warehouse;

    private String zoneName;
    private String rackNumber;

    @Column(unique = true, nullable = false)
    private String binCode;
}