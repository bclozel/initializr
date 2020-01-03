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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import io.spring.initializr.generator.spring.test.InitializrMetadataTestBuilder;
import io.spring.initializr.metadata.InitializrMetadata;
import io.spring.initializr.web.project.ProjectGeneratedEvent;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.Customization;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.comparator.CustomComparator;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ProjectGenerationStatPublisher}.
 *
 * @author Stephane Nicoll
 * @author Brian Clozel
 */
class ProjectGenerationStatPublisherTests {

	private final InitializrMetadata metadata = InitializrMetadataTestBuilder.withDefaults()
			.addDependencyGroup("core", "security", "validation", "aop")
			.addDependencyGroup("web", "web", "data-rest", "jersey").addDependencyGroup("data", "data-jpa", "jdbc")
			.addDependencyGroup("database", "h2", "mysql").build();

	private ProjectGenerationStatPublisher statPublisher;

	private MockWebServer mockServer;

	@BeforeEach
	public void setUp() throws Exception {
		this.mockServer = new MockWebServer();
		this.mockServer.start();

		StatsProperties properties = new StatsProperties();
		properties.getElastic().setBatchSize(5);
		properties.getElastic().setFlushInterval(1);
		RestHighLevelClient restHighLevelClient = new RestHighLevelClient(
				RestClient.builder(HttpHost.create("http://localhost:" + this.mockServer.getPort())));
		ProjectRequestDocumentFactory documentFactory = new ProjectRequestDocumentFactory();
		this.statPublisher = new ProjectGenerationStatPublisher(documentFactory, properties, restHighLevelClient);

	}

	@AfterEach
	public void tearDown() throws Exception {
		this.mockServer.shutdown();
	}

	private void flushStats() {
		this.statPublisher.getBulkProcessor().flush();
	}

	@Test
	public void publishDocument() throws Exception {
		WebProjectRequest request = createProjectRequest();
		request.setGroupId("com.example.acme");
		request.setArtifactId("project");
		request.setType("maven-project");
		request.setBootVersion("2.1.1.RELEASE");
		request.setDependencies(Arrays.asList("web", "data-jpa"));
		request.setLanguage("java");
		request.getParameters().put("user-agent", "curl/1.2.4");
		request.getParameters().put("cf-connecting-ip", "10.0.0.42");
		request.getParameters().put("cf-ipcountry", "BE");

		this.mockServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody(readJson("stat/response-simple.json")));

		handleEvent(request);

		RecordedRequest recordedRequest = this.mockServer.takeRequest();
		assertBulkRequest(recordedRequest.getBody().readUtf8(), readJson("stat/request-simple.json"));
	}

	@Test
	public void publishDocumentWithNoClientInformation() throws Exception {
		ProjectRequest request = createProjectRequest();
		request.setGroupId("com.example.acme");
		request.setArtifactId("test");
		request.setType("gradle-project");
		request.setBootVersion("2.1.0.RELEASE");
		request.setDependencies(Arrays.asList("web", "data-jpa"));
		request.setLanguage("java");

		this.mockServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody(readJson("stat/response-simple.json")));

		handleEvent(request);

		RecordedRequest recordedRequest = this.mockServer.takeRequest();
		assertBulkRequest(recordedRequest.getBody().readUtf8(), readJson("stat/request-no-client.json"));
	}

	@Test
	public void publishDocumentWithInvalidType() throws Exception {
		ProjectRequest request = createProjectRequest();
		request.setGroupId("com.example.acme");
		request.setArtifactId("test");
		request.setType("not-a-type");
		request.setBootVersion("2.1.0.RELEASE");
		request.setDependencies(Arrays.asList("web", "data-jpa"));
		request.setLanguage("java");

		this.mockServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody(readJson("stat/response-simple.json")));

		handleEvent(request);

		RecordedRequest recordedRequest = this.mockServer.takeRequest();
		assertBulkRequest(recordedRequest.getBody().readUtf8(), readJson("stat/request-invalid-type.json"));
	}

	@Test
	public void publishDocumentWithInvalidLanguage() throws Exception {
		ProjectRequest request = createProjectRequest();
		request.setGroupId("com.example.acme");
		request.setArtifactId("test");
		request.setType("gradle-project");
		request.setBootVersion("2.1.0.RELEASE");
		request.setDependencies(Arrays.asList("web", "data-jpa"));
		request.setLanguage("c");

		this.mockServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody(readJson("stat/response-simple.json")));

		handleEvent(request);

		RecordedRequest recordedRequest = this.mockServer.takeRequest();
		assertBulkRequest(recordedRequest.getBody().readUtf8(), readJson("stat/request-invalid-language.json"));
	}

	@Test
	public void publishDocumentWithInvalidJavaVersion() throws Exception {
		ProjectRequest request = createProjectRequest();
		request.setGroupId("com.example.acme");
		request.setArtifactId("test");
		request.setType("gradle-project");
		request.setBootVersion("2.1.0.RELEASE");
		request.setDependencies(Arrays.asList("web", "data-jpa"));
		request.setLanguage("java");
		request.setJavaVersion("1.2");

		this.mockServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody(readJson("stat/response-simple.json")));

		handleEvent(request);

		RecordedRequest recordedRequest = this.mockServer.takeRequest();
		assertBulkRequest(recordedRequest.getBody().readUtf8(), readJson("stat/request-invalid-java-version.json"));
	}

	@Test
	public void publishDocumentWithInvalidDependencies() throws Exception {
		ProjectRequest request = createProjectRequest();
		request.setGroupId("com.example.acme");
		request.setArtifactId("test");
		request.setType("gradle-project");
		request.setBootVersion("2.1.0.RELEASE");
		request.setDependencies(Arrays.asList("invalid-2", "web", "invalid-1"));
		request.setLanguage("java");

		this.mockServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody(readJson("stat/response-simple.json")));

		handleEvent(request);

		RecordedRequest recordedRequest = this.mockServer.takeRequest();
		assertBulkRequest(recordedRequest.getBody().readUtf8(), readJson("stat/request-invalid-dependencies.json"));
	}

	@Test
	public void recoverFromError() throws Exception {
		ProjectRequest request = createProjectRequest();

		this.mockServer.enqueue(new MockResponse().setResponseCode(500));
		this.mockServer.enqueue(new MockResponse().setResponseCode(500));
		this.mockServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody(readJson("stat/response-simple.json")));

		handleEvent(request);

		this.mockServer.takeRequest();
		this.mockServer.takeRequest();
		this.mockServer.takeRequest();
		assertThat(this.mockServer.getRequestCount()).isEqualTo(3);
	}

	/*
	 * @Test public void fatalErrorOnlyLogs() { ProjectRequest request =
	 * createProjectRequest(); this.retryTemplate.setRetryPolicy(new SimpleRetryPolicy(2,
	 * Collections.singletonMap(Exception.class, true)));
	 *
	 * this.mockServer.expect(requestTo("http://example.com/elastic/initializr/request"))
	 * .andExpect(method(HttpMethod.POST))
	 * .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
	 *
	 * this.mockServer.expect(requestTo("http://example.com/elastic/initializr/request"))
	 * .andExpect(method(HttpMethod.POST))
	 * .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
	 *
	 * handleEvent(request); this.mockServer.verify(); }
	 */

	private WebProjectRequest createProjectRequest() {
		WebProjectRequest request = new WebProjectRequest();
		request.initialize(this.metadata);
		return request;
	}

	private void handleEvent(ProjectRequest request) {
		this.statPublisher.handleEvent(new ProjectGeneratedEvent(request, this.metadata));
	}

	private static String readJson(String location) {
		try {
			try (InputStream in = new ClassPathResource(location).getInputStream()) {
				return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Fail to read json from " + location, ex);
		}
	}

	private static void assertBulkRequest(String expected, String actual) {
		try {
			String[] expectedLines = expected.split("\\R");
			String[] acualLines = actual.split("\\R");
			for (int i = 0; i < expectedLines.length; i++) {
				JSONAssert.assertEquals(expectedLines[i], acualLines[i], new CustomComparator(JSONCompareMode.STRICT,
						Customization.customization("generationTimestamp", (o1, o2) -> true)));
			}
		}
		catch (JSONException ex) {
			throw new AssertionError("Failed to parse expected or actual JSON request content", ex);
		}
	}

}
