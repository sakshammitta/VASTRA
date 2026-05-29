package com.vastra.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Service
public class R2Service {

    @Autowired(required = false)
    private S3Client r2Client;

    @Autowired(required = false)
    private S3Presigner r2Presigner;

    @Value("${vastra.r2.bucket}")
    private String bucket;

    @Value("${vastra.r2.public-url:}")
    private String publicUrl;

    public String upload(MultipartFile file, String folder) throws IOException {
        if (r2Client == null) {
            return "https://placeholder.vastra.app/" + folder + "/" + UUID.randomUUID();
        }
        String key = folder + "/" + UUID.randomUUID() + "_" + file.getOriginalFilename();
        r2Client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(file.getContentType()).build(),
            RequestBody.fromBytes(file.getBytes())
        );
        return key;
    }

    public String getPresignedUrl(String key) {
        if (key == null) return null;
        if (r2Presigner == null) return "https://placeholder.vastra.app/" + key;
        if (!publicUrl.isBlank()) return publicUrl + "/" + key;
        var presign = r2Presigner.presignGetObject(GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1))
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .build());
        return presign.url().toString();
    }
}
