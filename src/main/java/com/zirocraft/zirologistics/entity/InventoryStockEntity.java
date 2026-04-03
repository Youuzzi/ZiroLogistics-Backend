package com.zirocraft.zirologistics.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "inventory_stocks")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class InventoryStockEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private ItemEntity item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bin_id", nullable = false)
    private BinEntity bin;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private String status; // AVAILABLE, DAMAGED, RESERVED
}