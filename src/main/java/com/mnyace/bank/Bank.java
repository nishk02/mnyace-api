package com.mnyace.bank;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "banks")
@Getter
@Setter
@NoArgsConstructor
public class Bank {
    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "color_light", nullable = false)
    private String colorLight;

    @Column(name = "color_dark", nullable = false)
    private String colorDark;

    @Column(name = "is_active", nullable = false, columnDefinition = "INTEGER")
    private boolean active = true;
}
