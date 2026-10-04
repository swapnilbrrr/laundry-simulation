package com.smartlaundry.model;

public record CustomerSnapshot(int id, String thread, String stage, String resource, String status,
                               double arrivalSec, double totalSec) {
}
