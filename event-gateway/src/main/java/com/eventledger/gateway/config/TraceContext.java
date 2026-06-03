package com.eventledger.gateway.config;

public final class TraceContext {
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    private TraceContext() {}

    public static void set(String traceId) { TRACE.set(traceId); }
    public static String get() { return TRACE.get(); }
    public static void clear() { TRACE.remove(); }
}
