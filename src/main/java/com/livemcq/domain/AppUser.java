package com.livemcq.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true)
    public String roll;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    public Role role;
    public Long classId;
    public String section;
    public String phone;
}
