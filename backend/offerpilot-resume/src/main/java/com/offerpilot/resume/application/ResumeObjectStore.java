package com.offerpilot.resume.application;

public interface ResumeObjectStore {
    String bucket();
    void put(String key, byte[] bytes, String mime);
    byte[] get(String bucket, String key);
    void remove(String bucket, String key);
}
