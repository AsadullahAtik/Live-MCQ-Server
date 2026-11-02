package com.livemcq.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "school_class")
public class SchoolClass {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public String name;
    /** কমা দিয়ে আলাদা, যেমন "ক,খ" */
    public String sections;
}
