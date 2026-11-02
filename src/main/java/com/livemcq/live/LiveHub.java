package com.livemcq.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livemcq.core.Scope;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * সংযুক্ত সব WebSocket ক্লায়েন্টের তালিকা + ইভেন্ট পাঠানো.
 *
 * থ্রেড-নিরাপত্তা:
 *  - conns: ConcurrentHashMap, কারণ একসাথে অনেক থ্রেড সংযোগ যোগ/বাদ দেয়.
 *  - একই WebSocketSession-এ দুই থ্রেড একসাথে send করলে Spring ভেঙে পড়ে, তাই ConcurrentWebSocketSessionDecorator.
 *  - ইভেন্ট পাঠানো আলাদা একটি থ্রেডে (pusher): রিকোয়েস্ট থ্রেড ধীর ক্লায়েন্টের জন্য আটকায় না,
 *    আর একটাই থ্রেড বলে ইভেন্টের ক্রমও ঠিক থাকে.
 *
 * ইভেন্টে গোপন কিছু থাকে না — শুধু "কী বদলেছে" (ক্লায়েন্ট দরকার হলে REST থেকে নতুন ডেটা আনে).
 */
@Component
public class LiveHub {
    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);

    /** staff: অ্যাডমিন/শিক্ষক (scope = কোন শ্রেণির ইভেন্ট পাবে). ছাত্রের জন্য classId. */
    private record Conn(WebSocketSession session, boolean staff, Scope scope, Long classId) {}

    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final ObjectMapper om;
    private final ExecutorService pusher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ws-pusher");
        t.setDaemon(true);
        return t;
    });

    public LiveHub(ObjectMapper om) {
        this.om = om;
    }

    public void add(WebSocketSession raw, boolean staff, Scope scope, Long classId) {
        // ৫ সেকেন্ডের বেশি আটকে গেলে বা ৬৪ কেবি জমলে সংযোগ বাতিল
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(raw, 5000, 64 * 1024);
        conns.put(raw.getId(), new Conn(safe, staff, scope, classId));
        log.info("ws যুক্ত: {} (অনলাইন {})", raw.getId(), conns.size());
    }

    public void remove(String id) {
        if (conns.remove(id) != null) log.info("ws বিচ্ছিন্ন: {} (অনলাইন {})", id, conns.size());
    }

    public int online() {
        return conns.size();
    }

    /** শ্রেণির স্টাফ (অ্যাডমিন সবার, শিক্ষক নিজের শ্রেণির) — কে শুরু/জমা দিল জানার জন্য. */
    public void toStaff(Long classId, Map<String, Object> ev) {
        push(c -> c.staff() && c.scope().allows(classId), ev);
    }

    /** শ্রেণির স্টাফ + ওই শ্রেণির ছাত্ররা. */
    public void toClassAndStaff(Long classId, Map<String, Object> ev) {
        push(c -> c.staff() ? c.scope().allows(classId) : Objects.equals(c.classId(), classId), ev);
    }

    private void push(Predicate<Conn> to, Map<String, Object> ev) {
        final String json;
        try {
            json = om.writeValueAsString(ev);
        } catch (JsonProcessingException e) {
            log.warn("ইভেন্ট JSON হয়নি: {}", e.toString());
            return;
        }
        pusher.submit(() -> {
            TextMessage msg = new TextMessage(json);
            for (Conn c : conns.values()) {
                if (!to.test(c)) continue;
                try {
                    c.session().sendMessage(msg);
                } catch (Exception e) {
                    log.debug("ws পাঠানো গেল না, সংযোগ বাদ: {}", e.toString());
                    remove(c.session().getId());
                    try {
                        c.session().close();
                    } catch (Exception ignored) {
                        // বন্ধ করতে না পারলেও সমস্যা নেই
                    }
                }
            }
        });
    }

    /**
     * ডাটাবেস ট্রানজ্যাকশন সফলভাবে commit হওয়ার পরেই ইভেন্ট পাঠাতে চাই
     * (নইলে ক্লায়েন্ট রিফ্রেশ করে এখনো-না-সেভ-হওয়া ডেটা দেখতে পারে).
     * ট্রানজ্যাকশন না চললে সাথে সাথেই চালায়.
     */
    public static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    @PreDestroy
    void stop() {
        pusher.shutdownNow();
    }
}
