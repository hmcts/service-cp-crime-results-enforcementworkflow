package uk.gov.hmcts.cp.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.taskmanager.persistence.entity.Job;

/**
 * cp-task-manager (feature 003, research R10). The library registers its own entity package for scanning, which
 * would replace the default scan of this service's entities, so both packages are listed here. Its schema is created
 * by this service's Flyway migrations (V1.006, V1.007; {@code taskmanager.schema.enabled=false}).
 */
@Configuration
@EntityScan(basePackageClasses = {HearingResultSubmissionEntity.class, Job.class})
public class TaskManagerConfig {
}
