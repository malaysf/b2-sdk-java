/*
 * Copyright 2017, Backblaze Inc. All Rights Reserved.
 * License https://www.backblaze.com/using_b2_code.html
 */
package com.backblaze.b2.client;

import com.backblaze.b2.client.contentHandlers.B2ContentSink;
import com.backblaze.b2.client.contentSources.B2ContentSource;
import com.backblaze.b2.client.contentSources.B2ContentTypes;
import com.backblaze.b2.client.exceptions.B2Exception;
import com.backblaze.b2.client.structures.B2AccountAuthorization;
import com.backblaze.b2.client.structures.B2AuthorizeAccountRequest;
import com.backblaze.b2.client.structures.B2Bucket;
import com.backblaze.b2.client.structures.B2BucketTypes;
import com.backblaze.b2.client.structures.B2CancelLargeFileRequest;
import com.backblaze.b2.client.structures.B2CancelLargeFileResponse;
import com.backblaze.b2.client.structures.B2CreateBucketRequest;
import com.backblaze.b2.client.structures.B2CreateBucketRequestReal;
import com.backblaze.b2.client.structures.B2DeleteBucketRequest;
import com.backblaze.b2.client.structures.B2DeleteBucketRequestReal;
import com.backblaze.b2.client.structures.B2DeleteFileVersionRequest;
import com.backblaze.b2.client.structures.B2DeleteFileVersionResponse;
import com.backblaze.b2.client.structures.B2DownloadAuthorization;
import com.backblaze.b2.client.structures.B2DownloadByIdRequest;
import com.backblaze.b2.client.structures.B2DownloadByNameRequest;
import com.backblaze.b2.client.structures.B2FileVersion;
import com.backblaze.b2.client.structures.B2FinishLargeFileRequest;
import com.backblaze.b2.client.structures.B2GetDownloadAuthorizationRequest;
import com.backblaze.b2.client.structures.B2GetFileInfoRequest;
import com.backblaze.b2.client.structures.B2GetUploadPartUrlRequest;
import com.backblaze.b2.client.structures.B2GetUploadUrlRequest;
import com.backblaze.b2.client.structures.B2HideFileRequest;
import com.backblaze.b2.client.structures.B2LifecycleRule;
import com.backblaze.b2.client.structures.B2ListBucketsRequest;
import com.backblaze.b2.client.structures.B2ListBucketsResponse;
import com.backblaze.b2.client.structures.B2ListFileNamesRequest;
import com.backblaze.b2.client.structures.B2ListFileNamesResponse;
import com.backblaze.b2.client.structures.B2ListFileVersionsRequest;
import com.backblaze.b2.client.structures.B2ListFileVersionsResponse;
import com.backblaze.b2.client.structures.B2ListPartsRequest;
import com.backblaze.b2.client.structures.B2ListPartsResponse;
import com.backblaze.b2.client.structures.B2ListUnfinishedLargeFilesRequest;
import com.backblaze.b2.client.structures.B2ListUnfinishedLargeFilesResponse;
import com.backblaze.b2.client.structures.B2Part;
import com.backblaze.b2.client.structures.B2StartLargeFileRequest;
import com.backblaze.b2.client.structures.B2UpdateBucketRequest;
import com.backblaze.b2.client.structures.B2UploadFileRequest;
import com.backblaze.b2.client.structures.B2UploadPartRequest;
import com.backblaze.b2.client.structures.B2UploadPartUrlResponse;
import com.backblaze.b2.client.structures.B2UploadUrlResponse;
import com.backblaze.b2.client.webApiClients.B2WebApiClient;
import com.backblaze.b2.util.B2ByteRange;
import com.backblaze.b2.util.B2Collections;
import com.backblaze.b2.util.B2ExecutorUtils;
import com.backblaze.b2.util.B2Preconditions;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.backblaze.b2.client.B2TestHelpers.bucketId;
import static com.backblaze.b2.client.B2TestHelpers.bucketName;
import static com.backblaze.b2.client.B2TestHelpers.fileId;
import static com.backblaze.b2.client.B2TestHelpers.fileName;
import static com.backblaze.b2.client.B2TestHelpers.makeAuth;
import static com.backblaze.b2.client.B2TestHelpers.makePart;
import static com.backblaze.b2.client.B2TestHelpers.makeSha1;
import static com.backblaze.b2.client.B2TestHelpers.makeVersion;
import static com.backblaze.b2.util.B2Collections.listOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 */
@SuppressWarnings("unchecked")
public class B2StorageClientImplTest {
    private static final B2AccountAuthorization ACCOUNT_AUTH = B2TestHelpers.makeAuth(1);
    private static final String ACCOUNT_ID = ACCOUNT_AUTH.getAccountId();
    private static final String APPLICATION_KEY = "applicationKey";
    private static final String USER_AGENT = "B2StorageClientImplTest/0.0.1";
    private static final String BUCKET_NAME = "bucket1";
    private static final String BUCKET_TYPE = B2BucketTypes.ALL_PUBLIC;
    private static final String FILE_PREFIX = "files/";
    private static final String LARGE_FILE_ID = fileId(2);

    private final B2StorageClientWebifier webifier = mock(B2StorageClientWebifier.class);
    private final B2ClientConfig config = B2ClientConfig
            .builder(ACCOUNT_ID, APPLICATION_KEY, USER_AGENT)
            .build();
    private final B2Sleeper sleeper = mock(B2Sleeper.class);
    private final BackoffRetryerWithCounter retryer = new BackoffRetryerWithCounter(sleeper);
    private final B2StorageClientImpl client = new B2StorageClientImpl(webifier, config, retryer);
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @After
    public void tearDown() throws Exception {
        B2ExecutorUtils.shutdownAndAwaitTermination(executor, 10, 10);
    }

    private static class BackoffRetryerWithCounter extends B2BackoffRetryer {
        private int callCount = 0;

        BackoffRetryerWithCounter(B2Sleeper sleeper) {
            super(sleeper);
        }

        @Override
        <T> T doRetry(B2AccountAuthorizationCache accountAuthCache,
                      Callable<T> callable) throws B2Exception {
            callCount++;
            return super.doRetry(accountAuthCache, callable);
        }

        @Override
        <T> T doRetry(B2AccountAuthorizationCache accountAuthCache,
                      RetryableCallable<T> callable) throws B2Exception {
            callCount++;
            return super.doRetry(accountAuthCache, callable);
        }

        void assertCallCountIs(int expectedCallCount) {
            assertEquals(expectedCallCount, callCount);
        }
    }

    @Before
    public void setup() throws B2Exception {
        {
            final B2AuthorizeAccountRequest request = new B2AuthorizeAccountRequest(ACCOUNT_ID, APPLICATION_KEY);
            when(webifier.authorizeAccount(request)).thenReturn(ACCOUNT_AUTH);
        }
    }


    @Test
    public void testCreateBucket_convenience() throws B2Exception {
        final B2Bucket bucket = new B2Bucket(
                "accountId",
                bucketId(1),
                BUCKET_NAME,
                BUCKET_TYPE,
                null,
                null,
                1);
        when(webifier.createBucket(anyObject(), anyObject())).thenReturn(bucket);

        final B2Bucket response = client.createBucket(BUCKET_NAME, BUCKET_TYPE);
        assertEquals(bucket, response);

        B2CreateBucketRequestReal expectedRequest = new B2CreateBucketRequestReal(
                ACCOUNT_ID,
                new B2CreateBucketRequest(
                        BUCKET_NAME,
                        BUCKET_TYPE,
                        null,
                        null
                )
        );
        verify(webifier, times(1)).createBucket(eq(ACCOUNT_AUTH), eq(expectedRequest));
        retryer.assertCallCountIs(2); // auth + createBucket.
     }

    @Test
    public void testCreateBucket() throws B2Exception {
        final Map<String,String> bucketInfo = B2Collections.mapOf(
                "one", "1",
                "two", "2"
        );
        final List<B2LifecycleRule> lifecycleRules = listOf(
               B2LifecycleRule.builder(FILE_PREFIX).build()
        );
        final B2CreateBucketRequest request = B2CreateBucketRequest
                .builder(BUCKET_NAME, BUCKET_TYPE)
                .setBucketInfo(bucketInfo)
                .setLifecycleRules(lifecycleRules)
                .build();
        final B2Bucket bucket = new B2Bucket(
                ACCOUNT_ID,
                bucketId(1),
                BUCKET_NAME,
                BUCKET_TYPE,
                bucketInfo,
                lifecycleRules,
                1);
        B2CreateBucketRequestReal expectedRequest = new B2CreateBucketRequestReal(ACCOUNT_ID, request);
        when(webifier.createBucket(ACCOUNT_AUTH, expectedRequest)).thenReturn(bucket);

        final B2Bucket response = client.createBucket(request);
        assertEquals(bucket, response);

        retryer.assertCallCountIs(2); // auth + createBucket.


        // for coverage
        //noinspection ResultOfMethodCallIgnored
        request.hashCode();
        //noinspection ResultOfMethodCallIgnored
        expectedRequest.hashCode();

        // for coverage
        assertEquals(bucket(1), bucket(1));
        //noinspection ResultOfMethodCallIgnored
        bucket.hashCode();
        assertEquals("B2Bucket(bucket1,allPublic,bucket1,2 infos,1 lifecycleRules,v1)", bucket.toString());
    }

    // test that the retryer is caching account authorizations.
    // note that i'm not testing this for every api call!
    @Test
    public void testUsesAccountAuthorizationCache() throws B2Exception {
        client.createBucket(BUCKET_NAME, BUCKET_TYPE);
        client.createBucket(BUCKET_NAME, BUCKET_TYPE);
        verify(webifier, times(1)).authorizeAccount(anyObject());
        verify(webifier, times(2)).createBucket(anyObject(), anyObject());
        retryer.assertCallCountIs(4); // 2*auth + 2*createBucket.

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        new B2AuthorizeAccountRequest(ACCOUNT_ID, APPLICATION_KEY).hashCode();
    }

    @Test
    public void testListBuckets() throws B2Exception {
        final B2ListBucketsRequest expectedRequest = B2ListBucketsRequest
                .builder(ACCOUNT_ID)
                .build();
        final B2ListBucketsResponse response = new B2ListBucketsResponse(
            listOf(bucket(1))
        );
        when(webifier.listBuckets(ACCOUNT_AUTH, expectedRequest)).thenReturn(response);

        assertEquals(response, client.listBuckets());
        assertEquals(response.getBuckets(), client.buckets());

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        expectedRequest.hashCode();
        //noinspection ResultOfMethodCallIgnored
        response.hashCode();
        assertEquals(response, new B2ListBucketsResponse(listOf(bucket(1))));
    }

    @Test
    public void testGetBucketByName() throws B2Exception {
        final B2ListBucketsRequest expectedRequest = B2ListBucketsRequest
                .builder(ACCOUNT_ID)
                .build();
        final B2ListBucketsResponse response = new B2ListBucketsResponse(
                listOf(bucket(1))
        );
        when(webifier.listBuckets(ACCOUNT_AUTH, expectedRequest)).thenReturn(response);

        assertEquals(bucket(1), client.getBucketOrNullByName(BUCKET_NAME));
        assertEquals(null, client.getBucketOrNullByName("noSuchBucket"));
    }

    @SuppressWarnings("SameParameterValue")
    private B2Bucket bucket(int i) {
        return new B2Bucket(
                ACCOUNT_ID,
                bucketId(i),
                BUCKET_NAME,
                BUCKET_TYPE,
                null,
                null,
                2);
    }

    @Test
    public void testCancelLargeFile() throws B2Exception {
        final B2CancelLargeFileRequest request = B2CancelLargeFileRequest.builder(LARGE_FILE_ID).build();
        client.cancelLargeFile(request);
        verify(webifier, times(1)).cancelLargeFile(anyObject(), eq(request));

        client.cancelLargeFile(LARGE_FILE_ID);
        verify(webifier, times(2)).cancelLargeFile(anyObject(), eq(request));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        request.hashCode();

        final B2CancelLargeFileResponse response = new B2CancelLargeFileResponse(fileId(1), bucketId(1), fileName(1));
        assertEquals(response, new B2CancelLargeFileResponse(fileId(1), bucketId(1), fileName(1)));
        //noinspection ResultOfMethodCallIgnored
        response.hashCode();
        assertEquals("B2FileVersion{fileId='" + fileId(1) + "', bucketId='" + bucketId(1) + "', fileName='" + fileName(1) + "'}", response.toString());
    }

    @Test
    public void testDeleteFileVersion() throws B2Exception {
        final B2DeleteFileVersionRequest request = B2DeleteFileVersionRequest.builder(fileName(1), fileId(1)).build();
        client.deleteFileVersion(request);
        verify(webifier, times(1)).deleteFileVersion(anyObject(), eq(request));

        final B2FileVersion fileVersion = makeVersion(1, 1);
        client.deleteFileVersion(fileVersion);
        verify(webifier, times(2)).deleteFileVersion(anyObject(), eq(request));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        request.hashCode();

        final B2DeleteFileVersionResponse response = new B2DeleteFileVersionResponse(fileId(1), fileName(1));
        assertEquals(response, new B2DeleteFileVersionResponse(fileId(1), fileName(1)));
        //noinspection ResultOfMethodCallIgnored
        response.hashCode();
        assertEquals("B2DeleteFileVersionResponse{fileId='" + fileId(1) + "', fileName='" + fileName(1) + "'}", response.toString());

    }

    @Test
    public void testGetDownloadAuthorization() throws B2Exception {
        final B2DownloadAuthorization downloadAuth = new B2DownloadAuthorization(bucketId(1), FILE_PREFIX, "downloadAuthToken");
        final B2GetDownloadAuthorizationRequest request = B2GetDownloadAuthorizationRequest.builder(bucketId(1), FILE_PREFIX, 100).build();
        when(webifier.getDownloadAuthorization(anyObject(), eq(request))).thenReturn(downloadAuth);

        assertEquals(downloadAuth, client.getDownloadAuthorization(request));
        verify(webifier, times(1)).getDownloadAuthorization(anyObject(), eq(request));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        request.hashCode();
        assertEquals(request, B2GetDownloadAuthorizationRequest.builder(bucketId(1), FILE_PREFIX, 100).build());
    }


    @Test
    public void testGetFileInfo() throws B2Exception {
        final B2FileVersion fileVersion = makeVersion(1, 2);
        final B2GetFileInfoRequest request = B2GetFileInfoRequest.builder(fileId(1)).build();
        when(webifier.getFileInfo(anyObject(), eq(request))).thenReturn(fileVersion);

        assertEquals(fileVersion, client.getFileInfo(request));
        verify(webifier, times(1)).getFileInfo(anyObject(), eq(request));

        assertEquals(fileVersion, client.getFileInfo(fileId(1)));
        verify(webifier, times(2)).getFileInfo(anyObject(), eq(request));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        request.hashCode();
    }

    @Test
    public void testHideFile() throws B2Exception {
        final B2FileVersion fileVersion = makeVersion(6, 2);
        final B2HideFileRequest request = B2HideFileRequest.builder(bucketId(1), fileName(2)).build();
        when(webifier.hideFile(anyObject(), eq(request))).thenReturn(fileVersion);

        assertEquals(fileVersion, client.hideFile(request));
        verify(webifier, times(1)).hideFile(anyObject(), eq(request));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        request.hashCode();
        assertEquals(request, B2HideFileRequest.builder(bucketId(1), fileName(2)).build());
    }


    @Test
    public void testUpdateBucket() throws B2Exception {
        final B2UpdateBucketRequest request = B2UpdateBucketRequest
                .builder(bucket(1))
                .setBucketInfo(B2Collections.mapOf())
                .setLifecycleRules(listOf())
                .setBucketType(B2BucketTypes.ALL_PUBLIC)
                .build();
        when(webifier.updateBucket(anyObject(), eq(request))).thenReturn(bucket(1));

        assertEquals(bucket(1), client.updateBucket(request));
        verify(webifier, times(1)).updateBucket(anyObject(), eq(request));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        request.hashCode();
        assertEquals(
                request,
                B2UpdateBucketRequest
                        .builder(bucket(1))
                        .setBucketInfo(B2Collections.mapOf())
                        .setLifecycleRules(listOf())
                        .setBucketType(B2BucketTypes.ALL_PUBLIC)
                        .build());
    }


    @Test
    public void testDeleteBucket() throws B2Exception {
        final B2Bucket bucket = bucket(1);
        final B2DeleteBucketRequest request = B2DeleteBucketRequest.builder(bucketId(1)).build();
        final B2DeleteBucketRequestReal realRequest = new B2DeleteBucketRequestReal(ACCOUNT_ID, bucketId(1));
        when(webifier.deleteBucket(anyObject(), eq(realRequest))).thenReturn(bucket);

        assertEquals(bucket, client.deleteBucket(request));
        verify(webifier, times(1)).deleteBucket(anyObject(), eq(realRequest));

        assertEquals(bucket, client.deleteBucket(bucketId(1)));
        verify(webifier, times(2)).deleteBucket(anyObject(), eq(realRequest));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        request.hashCode();
        //noinspection ResultOfMethodCallIgnored
        realRequest.hashCode();
        assertEquals(request, B2DeleteBucketRequest.builder(bucketId(1)).build());
    }

    @Test
    public void testListFileVersions() throws B2Exception {
        final B2ListFileVersionsRequest request = B2ListFileVersionsRequest.builder(bucketId(1)).build();
        final B2ListFileVersionsResponse response = new B2ListFileVersionsResponse(listOf(), null, null);
        when(webifier.listFileVersions(anyObject(), eq(request))).thenReturn(response);

        assertEquals(response, client.listFileVersions(request));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        response.hashCode();
        assertEquals(response, new B2ListFileVersionsResponse(listOf(), null, null));
    }

    @Test
    public void testFileVersions() throws B2Exception {
        final B2ListFileVersionsRequest request = B2ListFileVersionsRequest.builder(bucketId(1)).setMaxFileCount(1000).build();
        final B2FileVersion version1 = makeVersion(1, 1);
        final B2ListFileVersionsResponse response = new B2ListFileVersionsResponse(listOf(version1), null, null);
        when(webifier.listFileVersions(anyObject(), eq(request))).thenReturn(response);

        // with request object
        assertIterContents(client.fileVersions(request),
                version1
                );

        // convenience version.
        assertIterContents(client.fileVersions(bucketId(1)),
                version1
                );
    }

    @Test
    public void testFileNames() throws B2Exception {
        final B2ListFileNamesRequest request = B2ListFileNamesRequest.builder(bucketId(1)).setMaxFileCount(1000).build();
        final B2FileVersion version1 = makeVersion(1, 1);
        final B2ListFileNamesResponse response = new B2ListFileNamesResponse(listOf(version1), null);
        when(webifier.listFileNames(anyObject(), eq(request))).thenReturn(response);

        // with request object
        assertIterContents(client.fileNames(request),
                version1
                );

        // convenience version.
        assertIterContents(client.fileNames(bucketId(1)),
                version1
                );
    }

    @Test
    public void testUnfinishedLargeFiles() throws B2Exception {
        final B2ListUnfinishedLargeFilesRequest request = B2ListUnfinishedLargeFilesRequest.builder(bucketId(1)).setMaxFileCount(100).build();
        final B2FileVersion version1 = makeVersion(1, 1);
        final B2ListUnfinishedLargeFilesResponse response = new B2ListUnfinishedLargeFilesResponse(listOf(version1), null);
        when(webifier.listUnfinishedLargeFiles(anyObject(), eq(request))).thenReturn(response);

        // with request object
        assertIterContents(client.unfinishedLargeFiles(request),
                version1
                );

        // convenience version.
        assertIterContents(client.unfinishedLargeFiles(bucketId(1)),
                version1
                );
    }

    @Test
    public void testParts() throws B2Exception {
        final B2ListPartsRequest request = B2ListPartsRequest.builder(LARGE_FILE_ID).setMaxPartCount(100).build();
        final B2Part part = makePart(1);
        final B2ListPartsResponse response = new B2ListPartsResponse(listOf(part), null);
        when(webifier.listParts(anyObject(), eq(request))).thenReturn(response);

        // with request object
        assertIterContents(client.parts(request),
                part
                );

        // convenience version.
        assertIterContents(client.parts(LARGE_FILE_ID),
                part
                );
    }

    private <T> void assertIterContents(Iterable<T> iterable, T... expecteds) {
        int iExpected = 0;
        for (T actual : iterable) {
            B2Preconditions.checkState(iExpected < expecteds.length,
                    "more items in iterable than in expected? (expecteds.length=" + expecteds.length + ", iExpected=" + iExpected + ")");
            assertEquals(expecteds[iExpected], actual);
            iExpected++;
        }
        B2Preconditions.checkState(iExpected == expecteds.length,
                "different number of items in expected than in iterable?  (expecteds.length=" + expecteds.length + ", iExpected=" + iExpected + ")");
    }


    @Test
    public void testListFileNames() throws B2Exception {
        final B2ListFileNamesRequest request = B2ListFileNamesRequest.builder(bucketId(1)).build();
        final B2ListFileNamesResponse response = new B2ListFileNamesResponse(listOf(), null);
        when(webifier.listFileNames(anyObject(), eq(request))).thenReturn(response);

        assertEquals(response, client.listFileNames(request));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        response.hashCode();
        assertEquals(response, new B2ListFileNamesResponse(listOf(), null));
    }

    @Test
    public void testListUnfinishedLargeFiles() throws B2Exception {
        final B2ListUnfinishedLargeFilesRequest request = B2ListUnfinishedLargeFilesRequest.builder(bucketId(1)).build();
        final B2ListUnfinishedLargeFilesResponse response = new B2ListUnfinishedLargeFilesResponse(listOf(), null);
        when(webifier.listUnfinishedLargeFiles(anyObject(), eq(request))).thenReturn(response);

        assertEquals(response, client.listUnfinishedLargeFiles(request));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        response.hashCode();
        assertEquals(response, new B2ListUnfinishedLargeFilesResponse(listOf(), null));
    }

    @Test
    public void testListParts() throws B2Exception {
        final B2ListPartsRequest request = B2ListPartsRequest.builder(bucketId(1)).build();
        final B2ListPartsResponse response = new B2ListPartsResponse(listOf(), null);
        when(webifier.listParts(anyObject(), eq(request))).thenReturn(response);

        assertEquals(response, client.listParts(request));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        response.hashCode();
        assertEquals(response, new B2ListPartsResponse(listOf(), null));
    }

    @Test
    public void test_forCoverage() {
        new B2StorageClientImpl(webifier, config);
    }

    @Test
    public void testDownloadById() throws B2Exception {
        final B2ContentSink handler = (responseHeaders, in) -> {};
        B2DownloadByIdRequest request = B2DownloadByIdRequest
                .builder(LARGE_FILE_ID)
                .setRange(B2ByteRange.between(10,12))
                .build();
        assertEquals(B2ByteRange.between(10,12), request.getRange());
        client.downloadById(request, handler);

        verify(webifier, times(1)).downloadById(anyObject(), eq(request), eq(handler));

        // check the "convenience" form that takes a fileId instead of a request.
        client.downloadById(fileId(2), handler);
        B2DownloadByIdRequest request2 = B2DownloadByIdRequest
                .builder(fileId(2))
                .build();
        assertNull(request2.getRange());
        verify(webifier, times(1)).downloadById(anyObject(), eq(request2), eq(handler));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        request.hashCode();
    }

    @Test
    public void testDownloadByName() throws B2Exception {
        final B2ContentSink handler = (responseHeaders, in) -> {};
        B2DownloadByNameRequest request = B2DownloadByNameRequest
                .builder(bucketName(1), fileName(1))
                .setRange(B2ByteRange.startAt(17))
                .build();
        client.downloadByName(request, handler);

        verify(webifier, times(1)).downloadByName(anyObject(), eq(request), eq(handler));

        // check the "convenience" form that takes a bucketName & fileName instead of a request.
        client.downloadByName(bucketName(1), fileName(1), handler);
        B2DownloadByNameRequest request2 = B2DownloadByNameRequest
                .builder(bucketName(1), fileName(1))
                .build();
        verify(webifier, times(1)).downloadByName(anyObject(), eq(request2), eq(handler));

        // for coverage
        //noinspection ResultOfMethodCallIgnored
        request.hashCode();
        assertEquals(request,
                B2DownloadByNameRequest
                        .builder(bucketName(1), fileName(1))
                        .setRange(B2ByteRange.startAt(17))
                        .build());
    }

    @Test
    public void testSimpleBuilder() throws B2Exception {
        final B2StorageClientImpl impl = (B2StorageClientImpl) B2StorageClient.builder(ACCOUNT_ID, APPLICATION_KEY, USER_AGENT).build();
        assertTrue(impl.getConfig().getAccountAuthorizer() instanceof B2AccountAuthorizerSimpleImpl);
        assertNull(impl.getConfig().getMasterUrl());
        assertNull(impl.getConfig().getTestModeOrNull());
    }

    @Test
    public void testBuilderFromConfig() throws B2Exception {
        final B2StorageClientImpl impl = (B2StorageClientImpl) B2StorageClient.builder(config).build();
        assertTrue(config == impl.getConfig());
    }

    @Test
    public void testBuilderWithWebApiClient() throws B2Exception {
        final B2WebApiClient webApiClient = mock(B2WebApiClient.class);
        final B2StorageClientImpl client = (B2StorageClientImpl) B2StorageClient
                .builder(config)
                .setWebApiClient(webApiClient)
                .build();

        when(webApiClient.postJsonReturnJson(anyObject(), anyObject(), anyObject(), eq(B2AccountAuthorization.class)))
                .thenReturn(makeAuth(1));
        when(webApiClient.postJsonReturnJson(anyObject(), anyObject(), anyObject(), eq(B2ListBucketsResponse.class)))
                .thenReturn(new B2ListBucketsResponse(listOf(bucket(1))));

        client.listBuckets();

        verify(webApiClient, times(2)).postJsonReturnJson(anyObject(), anyObject(), anyObject(), anyObject());
    }

    @Test
    public void testSmallFileUploadDirectly() throws B2Exception, IOException {
        checkSmallFileUpload(false);
    }

    @Test
    public void testSmallFileUploadThroughSmartUpload() throws B2Exception, IOException {
        checkSmallFileUpload(true);
    }

    private void checkSmallFileUpload(boolean throughSmartApi) throws B2Exception, IOException {
        // arrange for an uploadUrl
        final B2GetUploadUrlRequest uploadUrlRequest = new B2GetUploadUrlRequest(bucketId(1));
        final B2UploadUrlResponse uploadUrl = new B2UploadUrlResponse(bucketId(1), "uploadUrl", "uploadAuthToken");
        when(webifier.getUploadUrl(anyObject(), eq(uploadUrlRequest))).thenReturn(uploadUrl);

        // make a content source that's small enough to be a small file.
        final long contentLen = (2 * ACCOUNT_AUTH.getRecommendedPartSize()) - 1; // the biggest it can be and still be a small file.
        final B2ContentSource contentSource = mock(B2ContentSource.class);
        when(contentSource.getContentLength()).thenReturn(contentLen);

        final B2UploadFileRequest request = B2UploadFileRequest
                .builder(bucketId(1), fileName(1), B2ContentTypes.TEXT_PLAIN, contentSource)
                .setCustomField("color", "blue")
                .build();

        if (throughSmartApi) {
            client.uploadFile(request, executor);
            verify(contentSource, times(1)).getContentLength();
        } else {
            client.uploadSmallFile(request);
        }

        verifyNoMoreInteractions(contentSource); // the webifier is a mock, so it doesn't do normal things

        verify(webifier, times(1)).uploadFile(eq(uploadUrl), eq(request));


        // for coverage
        //noinspection ResultOfMethodCallIgnored
        uploadUrlRequest.hashCode();
        //noinspection ResultOfMethodCallIgnored
        uploadUrl.hashCode();
        assertEquals(uploadUrl, new B2UploadUrlResponse(bucketId(1), "uploadUrl", "uploadAuthToken"));
    }

    @Test
    public void testUploadWithExceptionInGetContentLength() throws B2Exception, IOException {

        // arrange for an uploadUrl
        final B2GetUploadUrlRequest uploadUrlRequest = new B2GetUploadUrlRequest(bucketId(1));
        final B2UploadUrlResponse uploadUrl = new B2UploadUrlResponse(bucketId(1), "uploadUrl", "uploadAuthToken");
        when(webifier.getUploadUrl(anyObject(), eq(uploadUrlRequest))).thenReturn(uploadUrl);

        // make a content source that's small enough to be a small file.
        final B2ContentSource contentSource = mock(B2ContentSource.class);
        when(contentSource.getContentLength()).thenThrow(new IOException("testing!"));

        final B2UploadFileRequest request = B2UploadFileRequest
                .builder(bucketId(1), fileName(1), B2ContentTypes.TEXT_PLAIN, contentSource)
                .build();

        thrown.expect(B2Exception.class);
        thrown.expectMessage("failed to get contentLength from source: java.io.IOException: testing!");

        client.uploadFile(request, executor);
    }

    @Test
    public void testLargeFileUploadDirectly() throws B2Exception, IOException {
        checkLargeFileUpload(false);
    }

    @Test
    public void testLargeFileUploadThroughSmartUpload() throws B2Exception, IOException {
        checkLargeFileUpload(true);
    }

    private void checkLargeFileUpload(boolean throughSmartApi) throws IOException, B2Exception {
        // make a content source that's barely big enough to be a large file.
        final long contentLen = (2 * ACCOUNT_AUTH.getRecommendedPartSize());
        final B2ContentSource contentSource = mock(B2ContentSource.class);
        when(contentSource.getContentLength()).thenReturn(contentLen);

        final B2UploadFileRequest request = B2UploadFileRequest
                .builder(bucketId(1), fileName(1), B2ContentTypes.TEXT_PLAIN, contentSource)
                .build();

        // arrange to answer start_large_file
        final B2StartLargeFileRequest startLargeRequest = B2StartLargeFileRequest.buildFrom(request);
        final B2FileVersion largeFileVersion = makeVersion(1, 2);
        when(webifier.startLargeFile(anyObject(), eq(startLargeRequest))).thenReturn(largeFileVersion);

        // arrange to answer get_upload_part_url (which will be called several times, but it's ok to reuse the same value since it's all mocked!)
        final B2GetUploadPartUrlRequest partUrlRequest = new B2GetUploadPartUrlRequest(largeFileVersion.getFileId());
        final B2UploadPartUrlResponse partUrl = new B2UploadPartUrlResponse(largeFileVersion.getFileId(), "uploadPartUrl", "uploadPartAuthToken");
        when(webifier.getUploadPartUrl(anyObject(), eq(partUrlRequest))).thenReturn(partUrl);

        // arrange to answer upload_part (which will be called several times, but it's ok to reuse the same value since it's all mocked!)
        final B2Part part = makePart(1);
        when(webifier.uploadPart(anyObject(), anyObject())).thenReturn(part);

        // arrange to answer finish_large_file
        final B2FinishLargeFileRequest finishRequest = new B2FinishLargeFileRequest(largeFileVersion.getFileId(), listOf(B2TestHelpers.SAMPLE_SHA1));
        when(webifier.finishLargeFile(anyObject(), eq(finishRequest))).thenReturn(largeFileVersion);

        if (throughSmartApi) {
            client.uploadFile(request, executor);
        } else {
            client.uploadLargeFile(request, executor);
        }

        verify(contentSource, times(1)).getContentLength();
        verify(contentSource, times(2)).getSha1OrNull(); // once above while making the startLargeRequest for the mock & once for real
        verifyNoMoreInteractions(contentSource); // the webifier is a mock, so it doesn't do normal things

        verify(webifier, times(1)).startLargeFile(anyObject(), anyObject());
        // there's a very unlikely race condition where we might upload one part and then unget the url and upload another.  it's really unlikely, but it means there's a chance there might only be one call, not two...
        //verify(webifier, times(2)).getUploadPartUrl(anyObject(), anyObject());
        verify(webifier, times(2)).uploadPart(anyObject(), anyObject());
        verify(webifier, times(1)).finishLargeFile(anyObject(), anyObject());



        // for coverage
        //noinspection ResultOfMethodCallIgnored
        partUrlRequest.hashCode();
        //noinspection ResultOfMethodCallIgnored
        startLargeRequest.hashCode();
        //noinspection ResultOfMethodCallIgnored
        partUrl.hashCode();
        //noinspection ResultOfMethodCallIgnored
        finishRequest.hashCode();
        assertEquals(partUrl, new B2UploadPartUrlResponse(largeFileVersion.getFileId(), "uploadPartUrl", "uploadPartAuthToken"));
        final B2UploadPartRequest partRequest = B2UploadPartRequest.builder(1, contentSource).build();
        assertEquals(partRequest, B2UploadPartRequest.builder(1, contentSource).build());
        //noinspection ResultOfMethodCallIgnored
        partRequest.hashCode();

    }

    @Test
    public void testFinishUploadingLargeFile() throws B2Exception, IOException {
        final long contentLen = (3 * ACCOUNT_AUTH.getRecommendedPartSize() + 124);
        final B2ContentSource contentSource = mock(B2ContentSource.class);
        when(contentSource.getContentLength()).thenReturn(contentLen);

        final B2UploadFileRequest request = B2UploadFileRequest
                .builder(bucketId(1), fileName(1), B2ContentTypes.TEXT_PLAIN, contentSource)
                .build();

        final B2FileVersion largeFileVersion = new B2FileVersion(fileId(1),
                fileName(1),
                contentLen,
                B2ContentTypes.TEXT_PLAIN,
                null,
                B2Collections.mapOf(),
                "upload",
                System.currentTimeMillis());

        final String largeFileId = largeFileVersion.getFileId();

        // arrange to find that two parts -- the first and third -- have already been uploaded.
        final List<B2Part> alreadyUploadedParts = listOf(
                new B2Part(largeFileId, 1, 1041, makeSha1(1), 1111),
                new B2Part(largeFileId, 3, 1042, makeSha1(3), 3333)
        );
        final B2ListPartsResponse listPartsResponse = new B2ListPartsResponse(alreadyUploadedParts, null);
        when(webifier.listParts(anyObject(), anyObject())).thenReturn(listPartsResponse);

        // arrange to answer get_upload_part_url (which will be called several times, but it's ok to reuse the same value since it's all mocked!)
        final B2GetUploadPartUrlRequest partUrlRequest = new B2GetUploadPartUrlRequest(largeFileId);
        final B2UploadPartUrlResponse partUrl = new B2UploadPartUrlResponse(largeFileId, "uploadPartUrl", "uploadPartAuthToken");
        when(webifier.getUploadPartUrl(anyObject(), eq(partUrlRequest))).thenReturn(partUrl);

        // arrange to answer upload_part
        final B2Part part = makePart(2);
        when(webifier.uploadPart(anyObject(), anyObject())).thenReturn(part);

        // arrange to answer finish_large_file
        final B2FinishLargeFileRequest finishRequest = new B2FinishLargeFileRequest(largeFileId, listOf(B2TestHelpers.SAMPLE_SHA1));
        when(webifier.finishLargeFile(anyObject(), eq(finishRequest))).thenReturn(largeFileVersion);

        client.finishUploadingLargeFile(largeFileVersion, request, executor);

        verify(contentSource, times(1)).getContentLength();
        verify(contentSource, times(1)).getSha1OrNull();
        verifyNoMoreInteractions(contentSource); // the webifier is a mock, so it doesn't do normal things

        // we should be using the existing largeFile, not starting a new one.
        verify(webifier, never()).startLargeFile(anyObject(), anyObject());

        // we're only uploading 1 of the three, since two were already uploaded
        verify(webifier, times(1)).getUploadPartUrl(anyObject(), anyObject());
        verify(webifier, times(1)).uploadPart(anyObject(), anyObject());
        verify(webifier, times(1)).finishLargeFile(anyObject(), anyObject());
    }

    @Test
    public void testClose() {
        // i can't really
        client.close();

        final B2AccountAuthorizer authorizer = B2AccountAuthorizerSimpleImpl.builder(ACCOUNT_ID, APPLICATION_KEY).build();
        final B2ClientConfig config = mock(B2ClientConfig.class);
        when(config.getAccountAuthorizer()).thenReturn(authorizer);

        final B2StorageClientImpl client = new B2StorageClientImpl(webifier, config);

        // closing the client should close the config the first time.
        client.close();
        verify(config, times(1)).close();

        // closing the client should do nothing the second time.
        client.close();
        verify(config, times(1)).close();
    }

}