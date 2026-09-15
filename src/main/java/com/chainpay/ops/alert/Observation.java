package com.chainpay.ops.alert;

import java.util.Map;

/** 一轮里看到的一个部件：名字、状态（UP / DEGRADED / DOWN / UNKNOWN …）、健康端点给的细节。 */
public record Observation(String component, String status, Map<String, Object> details) {}
