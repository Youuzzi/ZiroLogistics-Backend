package com.zirocraft.zirologistics.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Table(name = "warehouses")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class WarehouseEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String address;

    @Builder.Default // Agar nilai default isDeleted tidak di-ignore Lombok
    private boolean isDeleted = false;

    @OneToMany(mappedBy = "warehouse", cascade = CascadeType.ALL)
    private List<BinEntity> bins;
}