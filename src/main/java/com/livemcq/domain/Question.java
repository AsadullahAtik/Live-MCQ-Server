package com.livemcq.domain;

import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "question")
public class Question {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Long examId;
    public int sortNo;
    @Column(length = 1000, nullable = false)
    public String text;
    @Column(length = 400) public String opt0;
    @Column(length = 400) public String opt1;
    @Column(length = 400) public String opt2;
    @Column(length = 400) public String opt3;
    /** সঠিক অপশনের সূচক (০-৩) */
    public int correct;

    public List<String> options() {
        return List.of(opt0, opt1, opt2, opt3);
    }
}
