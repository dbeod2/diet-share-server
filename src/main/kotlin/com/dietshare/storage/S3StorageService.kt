package com.dietshare.storage

import com.dietshare.common.AppProperties
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Clock
import java.time.Duration

/** S3-compatible storage. Works with Cloudflare R2, MinIO and AWS S3. */
class S3StorageService(props: AppProperties.Storage, private val clock: Clock) : StorageService {
    private val log = LoggerFactory.getLogger(javaClass)
    private val bucket = props.bucket
    private val uploadTtl = Duration.ofMinutes(props.uploadUrlMinutes)
    private val viewTtl = Duration.ofMinutes(props.viewUrlMinutes)

    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(props.accessKey, props.secretKey)
    )
    private val s3Config = S3Configuration.builder().pathStyleAccessEnabled(true).build()

    private val client: S3Client = S3Client.builder()
        .endpointOverride(URI.create(props.endpoint))
        .region(Region.of(props.region))
        .credentialsProvider(credentials)
        .serviceConfiguration(s3Config)
        // R2 does not support every checksum the newer SDKs send by default.
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build()

    private val presigner: S3Presigner = S3Presigner.builder()
        .endpointOverride(URI.create(props.endpoint))
        .region(Region.of(props.region))
        .credentialsProvider(credentials)
        .serviceConfiguration(s3Config)
        .build()

    override fun presignUpload(key: String, contentType: String): PresignedUpload {
        val request = PutObjectPresignRequest.builder()
            .signatureDuration(uploadTtl)
            .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build())
            .build()
        val url = presigner.presignPutObject(request).url().toString()
        return PresignedUpload(key, url, clock.instant().plus(uploadTtl))
    }

    override fun presignView(key: String): String {
        val request = GetObjectPresignRequest.builder()
            .signatureDuration(viewTtl)
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .build()
        return presigner.presignGetObject(request).url().toString()
    }

    override fun exists(key: String): Boolean =
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
            true
        } catch (e: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) false else throw e
        }

    override fun read(key: String): ByteArray =
        client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()

    override fun write(key: String, bytes: ByteArray, contentType: String) {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(bytes),
        )
    }

    override fun copy(fromKey: String, toKey: String) {
        client.copyObject(
            CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(fromKey)
                .destinationBucket(bucket).destinationKey(toKey)
                .build()
        )
    }

    override fun delete(keys: Collection<String>) {
        keys.forEach { key ->
            try {
                client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
            } catch (e: S3Exception) {
                log.warn("Failed to delete object {}", key, e)
            }
        }
    }
}
