package com.zirocraft.zirologistics.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "inventory_ledger")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class InventoryLedgerEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private ItemEntity item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bin_id", nullable = false)
    private BinEntity bin;

    private String transactionType; // INBOUND, OUTBOUND, TRANSFER
    private BigDecimal quantityChange;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;

    @Column(unique = true)
    private String requestId; // Idempotency Key

    private String referenceNo;
    private String userId;
}