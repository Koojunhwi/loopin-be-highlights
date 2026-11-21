package com.loopone.loopinbe.domain.loop.ai.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.domain.loop.ai.dto.AiPayload;
import com.loopone.loopinbe.domain.loop.ai.dto.res.RecommendationsLoop;
import com.loopone.loopinbe.domain.loop.loop.dto.req.LoopCreateRequest;
import com.loopone.loopinbe.domain.loop.loop.enums.RepeatType;
import com.loopone.loopinbe.global.exception.AiResponseParseException;
import com.loopone.loopinbe.global.exception.ReturnCode;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiProviderParseTest {

    @Test
    @DisplayName("JSON이 아닌 평문은 AI 응답 파싱 오류로 처리한다")
    void rejectsPlainText() {
        assertParseFailure("추천을 생성하지 못했습니다.");
    }

    @Test
    @DisplayName("잘린 JSON은 AI 응답 파싱 오류로 처리한다")
    void rejectsTruncatedJson() {
        assertParseFailure("{\"title\":\"t\",\"recommendations\":[");
    }

    @Test
    @DisplayName("추천 목록이 빈 배열이면 AI 응답 파싱 오류로 처리한다")
    void rejectsEmptyRecommendations() {
        assertParseFailure("{\"title\":\"t\",\"recommendations\":[]}");
    }

    @Test
    @DisplayName("추천 목록 필드가 없으면 AI 응답 파싱 오류로 처리한다")
    void rejectsMissingRecommendations() {
        assertParseFailure("{\"title\":\"t\"}");
    }

    @Test
    @DisplayName("추천 목록이 명시적 null이면 AI 응답 파싱 오류로 처리한다")
    void rejectsNullRecommendations() {
        assertParseFailure("{\"title\":\"t\",\"recommendations\":null}");
    }

    @Test
    @DisplayName("정상 JSON은 제목과 추천 내용을 파싱하여 반환한다")
    void parsesValidJson() {
        RecommendationsLoop result = createProvider().parse(createValidJson());

        assertThat(result).isEqualTo(createExpectedResponse());
    }

    @Test
    @DisplayName("정상 JSON을 감싼 json 코드펜스를 제거하고 파싱한다")
    void parsesJsonInsideCodeFence() {
        RecommendationsLoop result = createProvider().parse("```json\n" + createValidJson() + "```\n");

        assertThat(result).isEqualTo(createExpectedResponse());
    }

    @Test
    @DisplayName("정상 JSON 앞뒤의 잡텍스트를 제거하고 파싱한다")
    void parsesJsonWithSurroundingText() {
        RecommendationsLoop result = createProvider().parse("추천 결과입니다.\n" + createValidJson() + "\n완료했습니다.");

        assertThat(result).isEqualTo(createExpectedResponse());
    }

    @Nested
    @DisplayName("OpenAI 파싱 실패 재시도")
    @SpringBootTest(
            classes = {OpenAiProvider.class, AiProviderParseTest.TestConfig.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @ActiveProfiles("test")
    class RetryTests {

        @MockitoBean(name = "openAiChatModel")
        private ChatModel chatModel;

        @Autowired
        private OpenAiProvider openAiProvider;

        @Autowired
        private CircuitBreakerRegistry circuitBreakerRegistry;

        @BeforeEach
        void resetCircuitBreaker() {
            circuitBreakerRegistry.circuitBreaker("open-ai").reset();
        }

        @Test
        @DisplayName("잘못된 JSON이 두 번 연속 반환되면 모델을 정확히 두 번 호출하고 예외를 던진다")
        void throwsWhenBothResponsesAreInvalidJson() {
            ChatResponse bad = createChatResponse("{\"title\":\"t\",\"recommendations\":[");
            when(chatModel.call(any(Prompt.class))).thenReturn(bad, bad);

            assertThatThrownBy(() -> openAiProvider.callOpenAi(createPayload()))
                    .isInstanceOf(RuntimeException.class);

            verify(chatModel, times(2)).call(any(Prompt.class));
        }

        @Test
        @DisplayName("첫 JSON 파싱 실패 후 정상 응답을 받으면 모델을 정확히 두 번 호출하고 파싱 결과를 반환한다")
        void returnsParsedResponseWhenRetrySucceeds() {
            ChatResponse bad = createChatResponse("{\"title\":\"t\",\"recommendations\":[");
            ChatResponse good = createChatResponse(createValidJson());
            when(chatModel.call(any(Prompt.class))).thenReturn(bad, good);

            RecommendationsLoop result = openAiProvider.callOpenAi(createPayload());

            assertThat(result).isEqualTo(createExpectedResponse());
            verify(chatModel, times(2)).call(any(Prompt.class));
        }
    }

    @Configuration
    @EnableRetry
    @ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    private OpenAiProvider createProvider() {
        return new OpenAiProvider(new ObjectMapper().findAndRegisterModules(), mock(ChatModel.class));
    }

    private void assertParseFailure(String text) {
        OpenAiProvider provider = createProvider();

        assertThatThrownBy(() -> provider.parse(text))
                .isInstanceOfSatisfying(AiResponseParseException.class,
                        exception -> assertThat(exception.getReturnCode()).isEqualTo(ReturnCode.AI_RESPONSE_PARSE_ERROR));
    }

    private AiPayload createPayload() {
        return new AiPayload(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1L, "user-message-id", 1L, "hello", null, Instant.parse("2026-05-29T00:00:00Z"));
    }

    private ChatResponse createChatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private String createValidJson() {
        return """
                {"title":"토익 루틴","recommendations":[{
                    "title":"집중 루틴","content":"c","scheduleType":"WEEKLY",
                    "specificDate":null,"daysOfWeek":["MONDAY"],
                    "startDate":"2026-05-01","endDate":"2026-05-31","checklists":["LC"]
                }]}
                """;
    }

    private RecommendationsLoop createExpectedResponse() {
        return new RecommendationsLoop("토익 루틴", null, List.of(
                new LoopCreateRequest("집중 루틴", "c", RepeatType.WEEKLY, null,
                        List.of(DayOfWeek.MONDAY), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                        List.of("LC"), null)));
    }
}
