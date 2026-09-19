package com.offerpilot.resume.infrastructure;

import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.resume.application.ResumeObjectStore;
import io.minio.*;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;

@Component
public class MinioResumeObjectStore implements ResumeObjectStore {
    private final MinioClient client;
    private final String bucket;
    public MinioResumeObjectStore(OfferPilotProperties properties) {
        var storage = properties.storage();
        bucket = storage.bucket();
        client = MinioClient.builder().endpoint(storage.endpoint())
            .credentials(storage.accessKey(), storage.secretKey()).build();
        client.setTimeout(10_000, 30_000, 30_000);
    }
    public String bucket() { return bucket; }
    public void put(String key, byte[] bytes, String mime) {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                try { client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); }
                catch (Exception race) {
                    if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) throw race;
                }
            }
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(mime)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
        } catch (Exception ex) { throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "简历存储暂不可用，请确认 MinIO 已启动后重试"); }
    }
    public byte[] get(String bucket, String key) {
        try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] bytes = input.readNBytes(10 * 1024 * 1024 + 1);
            if (bytes.length > 10 * 1024 * 1024) throw new IllegalArgumentException();
            return bytes;
        } catch (Exception ex) { throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "无法读取简历原文件，请检查文件存储后重试"); }
    }
    public void remove(String bucket, String key) {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception ex) { throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE); }
    }
}
