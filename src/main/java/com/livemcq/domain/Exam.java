package com.livemcq.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "exam")
public class Exam {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public String title;
    @Column(nullable = false)
    public Long classId;
    public int durationMin;
    public double perQ;
    public double neg;
    public boolean published;
    /** nullable: পুরনো সারির জন্য null = বন্ধ */
    public Boolean shuffle;
    /** পরীক্ষা দেওয়ার জানালা; null = সীমা নেই */
    public Instant startAt;
    public Instant endAt;
    public Instant createdAt;
}
