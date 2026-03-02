package com.foodbank.module.common.controller;

import com.foodbank.common.api.Result;
import com.foodbank.common.exception.BusinessException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@RestController
@RequestMapping("/common/file")
public class FileController {

    @Value("${minio.endpoint}")
    private String endpoint;
    @Value("${minio.access-key}")
    private String accessKey;
    @Value("${minio.secret-key}")
    private String secretKey;
    @Value("${minio.bucket-name}")
    private String bucketName;

    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        try {
            MinioClient minioClient = MinioClient.builder()
                    .endpoint(endpoint).credentials(accessKey, secretKey).build();

            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String ext = originalFilename.substring(originalFilename.lastIndexOf("."));
            String fileName = UUID.randomUUID().toString().replace("-", "") + ext;

            // 上传到 MinIO
            InputStream inputStream = file.getInputStream();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName).object(fileName)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());

            // 拼接可直接访问的公开 URL
            String fileUrl = endpoint + "/" + bucketName + "/" + fileName;
            return Result.success(fileUrl, "文件上传成功");
        } catch (Exception e) {
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }
}