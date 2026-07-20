package org.lockard.xyztilecache.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.ExportJob;
import org.lockard.xyztilecache.model.ExportJobStatus;
import org.lockard.xyztilecache.model.ExportStatus;

class ExportServiceTest {

  private XyzConfiguration configuration;
  private ImportExportService importExportService;
  private MutableClock clock;
  private ExportService service;

  @BeforeEach
  void setUp() {
    configuration = new XyzConfiguration();
    configuration.setExportRetentionMinutes(60);
    importExportService = mock(ImportExportService.class);
    clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    service = new ExportService(importExportService, configuration, clock);
  }

  @Test
  void submit_returnsStatus_withExpiresAt() throws Exception {
    doAnswer(
            inv -> {
              OutputStream out = inv.getArgument(4);
              out.write(new byte[] {1, 2, 3});
              return null;
            })
        .when(importExportService)
        .streamExport(anyList(), any(), any(), any(), any(OutputStream.class));

    ExportJobStatus status = service.submit(java.util.List.of(), null, null, null, "alice");

    awaitStatus(status.id(), ExportStatus.DONE);
    ExportJob job = service.getJob(status.id()).orElseThrow();
    ExportJobStatus refreshed = service.statusFor(job);
    assertThat(refreshed.expiresAt()).isEqualTo(job.getCreatedAt().plus(Duration.ofMinutes(60)));
  }

  @Test
  void sweepExpired_removesDoneJobsPastRetention_andDeletesTempFile() throws Exception {
    doAnswer(
            inv -> {
              OutputStream out = inv.getArgument(4);
              out.write(new byte[] {1});
              return null;
            })
        .when(importExportService)
        .streamExport(anyList(), any(), any(), any(), any(OutputStream.class));

    ExportJobStatus status = service.submit(java.util.List.of(), null, null, null, "alice");
    awaitStatus(status.id(), ExportStatus.DONE);
    Path tempFile = service.getJob(status.id()).orElseThrow().getTempFile();
    assertThat(tempFile).exists();

    // Within retention window: sweep is a no-op
    clock.advance(Duration.ofMinutes(30));
    service.sweepExpired();
    assertThat(service.getJob(status.id())).isPresent();
    assertThat(tempFile).exists();

    // Past retention window: job and temp file removed
    clock.advance(Duration.ofMinutes(31));
    service.sweepExpired();
    assertThat(service.getJob(status.id())).isEmpty();
    assertThat(tempFile).doesNotExist();
  }

  @Test
  void sweepExpired_removesFailedJobsAfter24h() throws Exception {
    doAnswer(
            inv -> {
              throw new IOException("boom");
            })
        .when(importExportService)
        .streamExport(anyList(), any(), any(), any(), any(OutputStream.class));

    ExportJobStatus status = service.submit(java.util.List.of(), null, null, null, "alice");
    awaitStatus(status.id(), ExportStatus.FAILED);

    clock.advance(Duration.ofHours(23));
    service.sweepExpired();
    assertThat(service.getJob(status.id())).isPresent();

    clock.advance(Duration.ofHours(2));
    service.sweepExpired();
    assertThat(service.getJob(status.id())).isEmpty();
  }

  @Test
  void shutdown_deletesRemainingTempFiles() throws Exception {
    doAnswer(
            inv -> {
              OutputStream out = inv.getArgument(4);
              out.write(new byte[] {1});
              return null;
            })
        .when(importExportService)
        .streamExport(anyList(), any(), any(), any(), any(OutputStream.class));

    ExportJobStatus status = service.submit(java.util.List.of(), null, null, null, "alice");
    awaitStatus(status.id(), ExportStatus.DONE);
    Path tempFile = service.getJob(status.id()).orElseThrow().getTempFile();

    service.shutdown();

    assertThat(tempFile).doesNotExist();
    assertThat(service.getJob(status.id())).isEmpty();
  }

  private void awaitStatus(String jobId, ExportStatus expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000L;
    while (System.currentTimeMillis() < deadline) {
      ExportJob job = service.getJob(jobId).orElse(null);
      if (job != null && job.getStatus() == expected) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Job " + jobId + " did not reach " + expected + " within timeout");
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration delta) {
      now = now.plus(delta);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
