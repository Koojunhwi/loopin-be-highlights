package com.loopone.loopinbe.domain.loop.ai.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.domain.loop.ai.dto.AiPayload;
import com.loopone.loopinbe.domain.loop.ai.service.AiProvider;
import com.loopone.loopinbe.global.exception.AiResponseParseException;
import com.loopone.loopinbe.global.exception.OpenAiRateLimitException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// application.yml(공통)의 resilience4j ignore-exceptions가 실제 프로바이더 호출에 적용되는지 검증한다
@SpringBootTest(
        classes = {OpenAiProvider.class, Gpt5Provider.class, GeminiProvider.class, AiCircuitBreakerIgnoreTest.TestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AiCircuitBreakerIgnoreTest {

    @Configuration
    @EnableRetry
    @ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @MockitoBean(name = "openAiChatModel")
    private ChatModel openAiChatModel;

    @MockitoBean(name = "gpt5ChatModel")
    private ChatModel gpt5ChatModel;

    @MockitoBean(name = "geminiChatModel")
    private ChatModel geminiChatModel;

    @Autowired
    private OpenAiProvider openAiProvider;

    @Autowired
    private Gpt5Provider gpt5Provider;

    @Autowired
    private GeminiProvider geminiProvider;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetCircuitBreakers() {
        for (String name : List.of("open-ai", "gpt-5", "gemini")) {
            circuitBreakerRegistry.circuitBreaker(name).reset();
        }
    }

    @DisplayName("429가 연속돼도 서킷 실패로 집계하지 않는다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"open-ai", "gpt-5"})
    void rateLimitIsNotRecordedAsCircuitFailure(String name) {
        AiProvider provider = provider(name);
        when(chatModel(name).call(any(Prompt.class))).thenThrow(new OpenAiRateLimitException("429", null, null));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> provider.callOpenAi(createPayload())).isInstanceOf(RuntimeException.class);
        }

        CircuitBreaker.Metrics metrics = circuitBreakerRegistry.circuitBreaker(name).getMetrics();
        assertThat(metrics.getNumberOfFailedCalls()).isZero();
        assertThat(metrics.getNumberOfBufferedCalls()).isZero();
    }

    @DisplayName("응답 파싱 실패가 연속돼도 서킷 실패로 집계하지 않는다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"open-ai", "gpt-5", "gemini"})
    void parseFailureIsNotRecordedAsCircuitFailure(String name) {
        AiProvider provider = provider(name);
        when(chatModel(name).call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("JSON이 아닌 응답")))));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> provider.callOpenAi(createPayload())).isInstanceOf(RuntimeException.class);
        }

        CircuitBreaker.Metrics metrics = circuitBreakerRegistry.circuitBreaker(name).getMetrics();
        assertThat(metrics.getNumberOfFailedCalls()).isZero();
        assertThat(metrics.getNumberOfBufferedCalls()).isZero();
    }

    @DisplayName("대조군: 5xx는 제외 대상이 아니므로 서킷 실패로 집계한다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"open-ai", "gpt-5", "gemini"})
    void transientFailureIsStillRecordedAsCircuitFailure(String name) {
        AiProvider provider = provider(name);
        when(chatModel(name).call(any(Prompt.class))).thenThrow(new TransientAiException("500"));

        assertThatThrownBy(() -> provider.callOpenAi(createPayload())).isInstanceOf(RuntimeException.class);

        assertThat(circuitBreakerRegistry.circuitBreaker(name).getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @DisplayName("프로파일 yml의 서킷 임계값과 공통 yml의 ignore-exceptions가 함께 적용된다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dev", "prod"})
    void mergesProfileThresholdsWithCommonIgnoreExceptions(String profile) {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + profile)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(CircuitBreakerAutoConfiguration.class))
                .run(context -> {
                    CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
                    OpenAiRateLimitException rateLimit = new OpenAiRateLimitException("429", null, null);
                    AiResponseParseException parseFailure = new AiResponseParseException("bad json");
                    TransientAiException serverError = new TransientAiException("500");

                    CircuitBreakerConfig openAi = registry.circuitBreaker("open-ai").getCircuitBreakerConfig();
                    assertThat(openAi.getSlidingWindowSize()).isEqualTo(10);
                    assertThat(openAi.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(openAi.getIgnoreExceptionPredicate().test(rateLimit)).isTrue();
                    assertThat(openAi.getIgnoreExceptionPredicate().test(parseFailure)).isTrue();
                    assertThat(openAi.getIgnoreExceptionPredicate().test(serverError)).isFalse();

                    CircuitBreakerConfig gpt5 = registry.circuitBreaker("gpt-5").getCircuitBreakerConfig();
                    assertThat(gpt5.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(60));
                    assertThat(gpt5.getIgnoreExceptionPredicate().test(rateLimit)).isTrue();
                    assertThat(gpt5.getIgnoreExceptionPredicate().test(parseFailure)).isTrue();
                    assertThat(gpt5.getIgnoreExceptionPredicate().test(serverError)).isFalse();

                    CircuitBreakerConfig gemini = registry.circuitBreaker("gemini").getCircuitBreakerConfig();
                    assertThat(gemini.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(gemini.getIgnoreExceptionPredicate().test(parseFailure)).isTrue();
                    assertThat(gemini.getIgnoreExceptionPredicate().test(serverError)).isFalse();
                });
    }

    private AiProvider provider(String name) {
        return switch (name) {
            case "open-ai" -> openAiProvider;
            case "gpt-5" -> gpt5Provider;
            case "gemini" -> geminiProvider;
            default -> throw new IllegalArgumentException("Unknown provider: " + name);
        };
    }

    private ChatModel chatModel(String name) {
        return switch (name) {
            case "open-ai" -> openAiChatModel;
            case "gpt-5" -> gpt5ChatModel;
            case "gemini" -> geminiChatModel;
            default -> throw new IllegalArgumentException("Unknown provider: " + name);
        };
    }

    private AiPayload createPayload() {
        return new AiPayload(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1L, "user-message-id", 1L, "hello", null, Instant.parse("2026-05-29T00:00:00Z"));
    }
}
