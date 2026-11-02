package com.livemcq.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * একই key (যেমন "পরীক্ষা+ছাত্র") এর কাজ একসাথে একটাই থ্রেড চালাবে; আলাদা key গুলো সমান্তরালে চলবে.
 * ডাবল-ক্লিক বা একসাথে দুটি রিকোয়েস্ট এলেও একই ছাত্রের attempt দুবার তৈরি/জমা হয় না.
 */
public final class KeyedLock {
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public <T> T with(String key, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
}
