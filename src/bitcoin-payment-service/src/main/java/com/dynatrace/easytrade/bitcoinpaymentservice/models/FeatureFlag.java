package com.dynatrace.easytrade.bitcoinpaymentservice.models;

public record FeatureFlag(String id, Boolean enabled, String name, String description, Boolean isModifiable,
                          String tag) {
}
