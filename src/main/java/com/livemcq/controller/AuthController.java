package com.livemcq.controller;

import com.livemcq.repo.ClassRepo;
import com.livemcq.service.AuthService;
import com.livemcq.web.Dtos.*;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService auth;
    private final ClassRepo classes;

    public AuthController(AuthService auth, ClassRepo classes) {
        this.auth = auth;
        this.classes = classes;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/auth/login")
    public LoginRes login(@RequestBody LoginReq req) {
        return auth.login(req);
    }

    @PostMapping("/auth/register")
    public LoginRes register(@RequestBody RegisterReq req) {
        return auth.register(req);
    }

    /** রেজিস্ট্রেশন ফর্মে শ্রেণির তালিকা দেখানোর জন্য (লগইন ছাড়াই). */
    @GetMapping("/auth/classes")
    public List<ClassDto> publicClasses() {
        return classes.findAll().stream().map(c -> new ClassDto(c.id, c.name, c.sections)).toList();
    }
}
