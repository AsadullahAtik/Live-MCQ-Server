package com.livemcq.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** একজন ছাত্রের একটি পরীক্ষার চেষ্টা. (examId, userId) জোড়া অনন্য, তাই ডাটাবেসই দ্বিতীয়বার দেওয়া ঠেকায়. */
@Entity
@Table(name = "attempt", uniqueConstraints = @UniqueConstraint(columnNames = {"exam_id", "user_id"}))
public class Attempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "exam_id", nullable = false)
    public Long examId;
    @Column(name = "user_id", nullable = false)
    public Long userId;
    public Instant startedAt;
    public Instant deadline;
    public Instant submittedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    public AttemptStatus status;
    /** কমা দিয়ে উত্তর, যেমন "0,-1,2" (-১ = উত্তর দেয়নি) */
    @Column(length = 2000)
    public String answers;
    public int correct;
    public int wrong;
    public int blank;
    public double marks;
    public int timeSec;
    /** প্রশ্ন/অপশন এলোমেলো করার seed; null = এলোমেলো নয় */
    public Long shuffleSeed;
}
