package com.livemcq.domain;

import jakarta.persistence.*;

/** কোন শিক্ষক কোন শ্রেণির দায়িত্বে (অনেক-থেকে-অনেক). জোড়া অনন্য. */
@Entity
@Table(name = "teacher_class", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "class_id"}))
public class TeacherClass {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "user_id", nullable = false)
    public Long userId;
    @Column(name = "class_id", nullable = false)
    public Long classId;
}
