package com.loopone.loopinbe.domain.loop.ai.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.domain.loop.ai.dto.AiPayload;
import com.loopone.loopinbe.domain.loop.ai.dto.res.RecommendationsLoop;
import com.loopone.loopinbe.domain.loop.ai.service.AiProvider;
import com.loopone.loopinbe.domain.loop.loop.dto.req.LoopCreateRequest;
import com.loopone.loopinbe.domain.loop.loop.enums.RepeatType;
import com.loopone.loopinbe.global.exception.OpenAiRateLimitException;
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
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {OpenAiProvider.class, Gpt5Provider.class, GeminiProvider.class, AiProviderRetryTest.TestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AiProviderRetryTest {

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

    @DisplayName("5xx 오류가 계속되면 최초 호출과 재시도로 모델을 정확히 두 번 호출한다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"open-ai", "gpt-5", "gemini"})
    void retriesTransientFailureOnce(String name) {
        ProviderCase providerCase = providerCase(name);
        when(providerCase.chatModel().call(any(Prompt.class)))
                .thenThrow(new TransientAiException("500"));

        assertThatThrownBy(() -> providerCase.provider().callOpenAi(createPayload()))
                .isInstanceOf(RuntimeException.class);

        verify(providerCase.chatModel(), times(2)).call(any(Prompt.class));
    }

    @DisplayName("429 오류는 재시도하지 않고 모델을 정확히 한 번 호출한다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"open-ai", "gpt-5", "gemini"})
    void doesNotRetryRateLimitFailure(String name) {
        ProviderCase providerCase = providerCase(name);
        when(providerCase.chatModel().call(any(Prompt.class)))
                .thenThrow(new OpenAiRateLimitException("429", null, null));

        assertThatThrownBy(() -> providerCase.provider().callOpenAi(createPayload()))
                .isInstanceOf(RuntimeException.class);

        verify(providerCase.chatModel(), times(1)).call(any(Prompt.class));
    }

    @DisplayName("비일시적 4xx 오류는 재시도하지 않고 모델을 정확히 한 번 호출한다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"open-ai", "gpt-5", "gemini"})
    void doesNotRetryNonTransientFailure(String name) {
        ProviderCase providerCase = providerCase(name);
        when(providerCase.chatModel().call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("400"));

        assertThatThrownBy(() -> providerCase.provider().callOpenAi(createPayload()))
                .isInstanceOf(RuntimeException.class);

        verify(providerCase.chatModel(), times(1)).call(any(Prompt.class));
    }

    @DisplayName("첫 5xx 오류 후 재시도가 성공하면 두 번 호출하고 파싱한 응답을 반환한다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"open-ai", "gpt-5", "gemini"})
    void returnsParsedResponseWhenRetrySucceeds(String name) {
        ProviderCase providerCase = providerCase(name);
        when(providerCase.chatModel().call(any(Prompt.class)))
                .thenThrow(new TransientAiException("500"))
                .thenReturn(createChatResponse());

        assertThatCode(() -> {
            RecommendationsLoop result = providerCase.provider().callOpenAi(createPayload());
            assertThat(result).isEqualTo(createExpectedResponse());
        }).doesNotThrowAnyException();

        verify(providerCase.chatModel(), times(2)).call(any(Prompt.class));
    }

    @DisplayName("5xx 재시도가 소진된 요청 한 건은 서킷브레이커 실패를 정확히 한 번 기록한다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"open-ai", "gpt-5", "gemini"})
    void recordsOneCircuitBreakerFailureAfterRetryExhaustion(String name) {
        ProviderCase providerCase = providerCase(name);
        var circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        int failedCallsBefore = circuitBreaker.getMetrics().getNumberOfFailedCalls();
        when(providerCase.chatModel().call(any(Prompt.class)))
                .thenThrow(new TransientAiException("500"));

        assertThatThrownBy(() -> providerCase.provider().callOpenAi(createPayload()))
                .isInstanceOf(RuntimeException.class);

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls())
                .isEqualTo(failedCallsBefore + 1);
        verify(providerCase.chatModel(), times(2)).call(any(Prompt.class));
    }

    private ProviderCase providerCase(String name) {
        return switch (name) {
            case "open-ai" -> new ProviderCase(openAiProvider, openAiChatModel);
            case "gpt-5" -> new ProviderCase(gpt5Provider, gpt5ChatModel);
            case "gemini" -> new ProviderCase(geminiProvider, geminiChatModel);
            default -> throw new IllegalArgumentException("Unknown provider: " + name);
        };
    }

    private AiPayload createPayload() {
        return new AiPayload(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1L, "user-message-id", 1L, "hello", null, Instant.parse("2026-05-29T00:00:00Z"));
    }

    private ChatResponse createChatResponse() {
        String json = """
                {"title":"토익 루틴","recommendations":[{
                    "title":"집중 루틴","content":"c","scheduleType":"WEEKLY",
                    "specificDate":null,"daysOfWeek":["MONDAY"],
                    "startDate":"2026-05-01","endDate":"2026-05-31","checklists":["LC"]
                }]}
                """;
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    private RecommendationsLoop createExpectedResponse() {
        return new RecommendationsLoop("토익 루틴", null, List.of(
                new LoopCreateRequest("집중 루틴", "c", RepeatType.WEEKLY, null,
                        List.of(DayOfWeek.MONDAY), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                        List.of("LC"), null)));
    }

    private record ProviderCase(AiProvider provider, ChatModel chatModel) {
    }
}
