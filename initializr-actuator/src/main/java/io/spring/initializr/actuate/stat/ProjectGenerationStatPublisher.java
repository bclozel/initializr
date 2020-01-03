/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.spring.initializr.actuate.stat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.initializr.web.project.ProjectRequestEvent;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;

import org.springframework.context.event.EventListener;

/**
 * Publish stats for each project generated to an Elastic index.
 *
 * @author Stephane Nicoll
 * @author Brian Clozel
 */
public class ProjectGenerationStatPublisher {

	private static final Log logger = LogFactory.getLog(ProjectGenerationStatPublisher.class);

	private final ProjectRequestDocumentFactory documentFactory;

	private final ObjectMapper objectMapper;

	private final StatsProperties.Elastic elasticProperties;

	private final BulkProcessor bulkProcessor;

	public ProjectGenerationStatPublisher(ProjectRequestDocumentFactory documentFactory,
			StatsProperties statsProperties, RestHighLevelClient restHighLevelClient) {
		this.documentFactory = documentFactory;
		this.objectMapper = createObjectMapper();
		this.elasticProperties = statsProperties.getElastic();
		this.bulkProcessor = BulkProcessor.builder((request, listener) -> {
			restHighLevelClient.bulkAsync(request, RequestOptions.DEFAULT, listener);
		}, new BulkListener()).setBulkActions(this.elasticProperties.getBatchSize())
				.setFlushInterval(TimeValue.timeValueSeconds(this.elasticProperties.getFlushInterval()))
				.setConcurrentRequests(1).setBackoffPolicy(BackoffPolicy
						.exponentialBackoff(TimeValue.timeValueMillis(50), this.elasticProperties.getMaxAttempts()))
				.build();
	}

	@EventListener
	public void handleEvent(ProjectRequestEvent event) {
		ProjectRequestDocument document = this.documentFactory.createDocument(event);
		logger.debug("Adding to the publishing queue: " + document);

		IndexRequest indexRequest = new IndexRequest(this.elasticProperties.getIndexName(),
				this.elasticProperties.getEntityName());
		indexRequest.source(toJson(document), XContentType.JSON);
		this.bulkProcessor.add(indexRequest);
	}

	protected BulkProcessor getBulkProcessor() {
		return this.bulkProcessor;
	}

	private String toJson(ProjectRequestDocument stats) {
		try {
			return this.objectMapper.writeValueAsString(stats);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Cannot convert to JSON", ex);
		}
	}

	private static ObjectMapper createObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
		return mapper;
	}

	class BulkListener implements BulkProcessor.Listener {

		@Override
		public void beforeBulk(long id, BulkRequest bulkRequest) {
			logger.debug(String.format("About to publish %s documents", bulkRequest.numberOfActions()));
		}

		@Override
		public void afterBulk(long id, BulkRequest bulkRequest, BulkResponse bulkResponse) {
			if (bulkResponse.hasFailures()) {
				for (BulkItemResponse item : bulkResponse.getItems()) {
					if (item.isFailed()) {
						logger.warn(String.format("Failed to publish stat %s to index %s", item.getId(),
								item.getFailureMessage()));
					}
				}
			}
		}

		@Override
		public void afterBulk(long id, BulkRequest bulkRequest, Throwable ex) {
			bulkRequest.requests().forEach(req -> {
				logger.warn(String.format("Failed to publish document: %s", req.toString()), ex);
			});
		}

	}

}