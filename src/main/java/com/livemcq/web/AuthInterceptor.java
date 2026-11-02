package com.livemcq.web;

import com.livemcq.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** প্রতিটি /api রিকোয়েস্টে JWT যাচাই করে; /api/admin/** শুধু অ্যাডমিনের জন্য. */
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtService jwt;

    public AuthInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if ("OPTIONS".equals(req.getMethod())) return true;
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw new ApiException(401, "আগে লগইন করুন");
        AuthUser u = jwt.parse(h.substring(7));
        String uri = req.getRequestURI();
        // /api/admin/** অ্যাডমিন ও শিক্ষকের; কোন শ্রেণির ওপর, তা service স্তরে (AccessService) দেখা হয়
        if (uri.startsWith("/api/admin/") && !u.isStaff()) {
            throw new ApiException(403, "শুধু অ্যাডমিন বা শিক্ষক এই কাজ করতে পারবেন");
        }
        // শিক্ষক ব্যবস্থাপনা শুধু অ্যাডমিনের
        if (uri.startsWith("/api/admin/teachers") && !u.isAdmin()) {
            throw new ApiException(403, "শুধু অ্যাডমিন এই কাজ করতে পারবেন");
        }
        req.setAttribute("auth", u);
        return true;
    }
}
